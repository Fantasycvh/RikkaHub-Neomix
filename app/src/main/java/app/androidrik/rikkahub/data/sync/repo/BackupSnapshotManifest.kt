package app.androidrik.rikkahub.data.sync.repo

import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupManifest
import app.androidrik.rikkahub.data.sync.backupFolderSpecFor
import app.androidrik.rikkahub.data.sync.BackupRestoreKind
import app.androidrik.rikkahub.data.sync.WorkspaceBackupHelper
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

internal const val REPO_VERSION = 1
internal const val METADATA_LIMIT = 32L * 1024 * 1024
internal const val ENTRY_LIMIT = 100_000
internal const val RESERVE_BYTES = 64L * 1024 * 1024

/** 对象级并行度（哈希/stat/压缩/下载/上传）：独立对象可并发，低端机也不至于打满 IO。 */
internal const val PARALLEL_OBJECTS = 4

/** 快照格式 v1：整库作为 `rikka_hub.db` 文件对象（P1）。 */
internal const val SNAPSHOT_FORMAT_V1 = 1

/** 快照格式 v2：数据库拆成逻辑对象（P2），不再携带整库文件。 */
internal const val SNAPSHOT_FORMAT_V2 = 2

/** 快照格式 v3：小对象可进 `v3/packs/<sha>.zip`（内容寻址打包），清单按 pack 指针复用。 */
internal const val SNAPSHOT_FORMAT_V3 = 3
internal const val SNAPSHOT_FORMAT_VERSION = SNAPSHOT_FORMAT_V3

@Serializable
internal data class BackupRepositoryInfo(val version: Int = REPO_VERSION, val repoId: String, val createdAt: String)

@Serializable
internal enum class SnapshotDatabaseState { NOT_SELECTED, ABSENT, PRESENT }

internal interface SnapshotObjectRef {
    val sha256: String
    val size: Long

    /** v3：该对象所在 pack 的 SHA-256（null = 独立对象）。 */
    val pack: String?

    /** 独立对象的仓库路径（`v3/objects/<aa>/<sha>`）。 */
    val storageKey: String get() = objectKeyFor(sha256)

    /** pack 的仓库路径（`v3/packs/<sha>.zip`）；未打包时为 null。 */
    val packKey: String? get() = pack?.let(::packKeyFor)

    /** 该引用在仓库里的实际路径（pack 或独立对象），用于存在性检查/错误信息。 */
    val key: String get() = packKey ?: storageKey
}

@Serializable
internal data class SnapshotFileEntry(
    val path: String,
    override val sha256: String,
    override val size: Long,
    override val pack: String? = null,
) : SnapshotObjectRef

/**
 * P2 数据库逻辑对象引用。`objectKey` 是稳定逻辑键（`conversation:<uuid>` / `table:<name>`），
 * 仓库里仍按内容 sha256 去重存储：会话没变就不重新上传。
 */
@Serializable
internal data class SnapshotDbObject(
    val objectKey: String,
    override val sha256: String,
    override val size: Long,
    override val pack: String? = null,
) : SnapshotObjectRef

/** A complete view: references never depend on a previous manifest. */
@Serializable
internal data class BackupSnapshotManifest(
    val formatVersion: Int = SNAPSHOT_FORMAT_VERSION,
    val snapshotId: String,
    val repoId: String,
    val createdAt: String,
    val deviceId: String,
    val appVersionCode: String,
    val dbVersion: Int,
    val items: List<BackupItem>,
    val databaseState: SnapshotDatabaseState,
    val files: List<SnapshotFileEntry>,
    val dbObjects: List<SnapshotDbObject> = emptyList(),
) {
    val manifestKey get() = "v3/snapshots/$snapshotId.json.gz"
    val completionKey get() = "v3/snapshots/$snapshotId.complete.json"
    val logicalBytes get() = files.sumOf { it.size } + dbObjects.sumOf { it.size }
    val objects: List<SnapshotObjectRef> get() = files + dbObjects

    /** 本快照引用到的 pack（去重）；GC 可达性与恢复下载都用它。 */
    val packs: Set<String> get() = objects.mapNotNull { it.pack }.toSet()
}

@Serializable
internal data class SnapshotCompletion(
    val version: Int = REPO_VERSION,
    val snapshotId: String,
    val repoId: String,
    val manifestSha256: String,
    val manifestBytes: Long,
)

/** 拆包改写清单时的「旧清单备份」目录；崩溃后由 GC 的 repair 按完成标记回滚。 */
internal const val REWRITE_DIR = "v3/rewrite"

internal fun snapshotRewriteKey(snapshotId: String) = "$REWRITE_DIR/$snapshotId.json.gz"

internal fun validUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun validHash(value: String) = value.matches(Regex("[a-f0-9]{64}"))
internal fun validateKey(key: String) {
    require(key.isNotEmpty() && !key.contains('\\') && !key.contains('\u0000')) { "Invalid repository path" }
    require(key.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid repository path" }
}

internal fun validateSnapshot(snapshot: BackupSnapshotManifest) {
    if (snapshot.formatVersion !in SNAPSHOT_FORMAT_V1..SNAPSHOT_FORMAT_V3) {
        throw UnsupportedOperationException("Unsupported snapshot version; update the app")
    }
    require(validUuid(snapshot.snapshotId) && validUuid(snapshot.repoId) && validUuid(snapshot.deviceId)) { "Invalid snapshot identity" }
    java.time.Instant.parse(snapshot.createdAt)
    require(snapshot.dbVersion > 0 && snapshot.items.distinct() == snapshot.items) { "Invalid backup scope" }
    require(snapshot.files.size <= ENTRY_LIMIT) { "Too many backup entries" }
    val seen = HashSet<String>()
    var total = 0L
    snapshot.files.forEach { entry ->
        validateKey(entry.path)
        require(entry.path.length <= 4096 && seen.add(entry.path)) { "Duplicate or excessive backup path" }
        require(validHash(entry.sha256) && entry.size in 0..BackupManifest.MAX_ENTRY_BYTES) { "Invalid object metadata" }
        validatePackedRef(snapshot.formatVersion, entry)
        total = Math.addExact(total, entry.size)
        require(total <= BackupManifest.MAX_TOTAL_BYTES) { "Backup exceeds restore size limit" }
        when (entry.path) {
            "settings.json" -> require(entry.size <= METADATA_LIMIT) { "Settings exceed size limit" }
            "rikka_hub.db" -> require(snapshot.databaseState == SnapshotDatabaseState.PRESENT)
            else -> if (entry.path.startsWith(WorkspaceBackupHelper.ZIP_PREFIX)) {
                require(BackupItem.WORKSPACE_FILES in snapshot.items)
                val parts = entry.path.split('/')
                require(parts.size >= 3 && WorkspaceBackupHelper.isValidRoot(parts[1])) { "Invalid workspace path" }
            } else {
                require(BackupItem.FILES in snapshot.items && backupFolderSpecFor(entry.path) != null) { "Unknown backup folder" }
                val spec = requireNotNull(backupFolderSpecFor(entry.path))
                require(spec.recursive || entry.path.split('/').size == 2) { "Unexpected nested backup path" }
                if (spec.restoreKind == BackupRestoreKind.SKILLS) require(entry.path.split('/').size >= 3 &&
                    entry.path.split('/')[1].isNotBlank()) { "Invalid skill backup path" }
            }
        }
    }
    seen.forEach { path ->
        var ancestor = path.substringBeforeLast('/', "")
        while (ancestor.isNotEmpty()) {
            require(ancestor !in seen) { "File/directory path conflict" }
            ancestor = ancestor.substringBeforeLast('/', "")
        }
    }
    require("settings.json" in seen) { "Missing settings" }
    val databaseSelected = snapshot.databaseState != SnapshotDatabaseState.NOT_SELECTED
    require((BackupItem.DATABASE in snapshot.items) == databaseSelected) { "Invalid database state" }
    when (snapshot.formatVersion) {
        SNAPSHOT_FORMAT_V1 -> {
            require(snapshot.dbObjects.isEmpty()) { "Legacy snapshot must not contain database objects" }
            require(("rikka_hub.db" in seen) == (snapshot.databaseState == SnapshotDatabaseState.PRESENT)) { "Missing database" }
        }
        else -> {
            // v2：数据库不再以整库文件出现，而是完整逻辑对象视图（含空表对象），保证恢复时可整体替换。
            require("rikka_hub.db" !in seen) { "Snapshot database must be captured as logical objects" }
            require(snapshot.dbObjects.size <= ENTRY_LIMIT) { "Too many database objects" }
            val objectKeys = HashSet<String>()
            snapshot.dbObjects.forEach { obj ->
                validateDbObjectKey(obj.objectKey)
                require(objectKeys.add(obj.objectKey)) { "Duplicate database object" }
                require(validHash(obj.sha256) && obj.size in 0..BackupManifest.MAX_ENTRY_BYTES) { "Invalid database object metadata" }
                validatePackedRef(snapshot.formatVersion, obj)
                if (dbObjectKind(obj.objectKey) == "table") {
                    require(!isConversationObjectTable(dbObjectValue(obj.objectKey))) {
                        "Conversation tables must be captured per conversation"
                    }
                }
                total = Math.addExact(total, obj.size)
                require(total <= BackupManifest.MAX_TOTAL_BYTES) { "Backup exceeds restore size limit" }
            }
            val expectsObjects = snapshot.databaseState == SnapshotDatabaseState.PRESENT
            require(expectsObjects == snapshot.dbObjects.isNotEmpty()) { "Missing database objects" }
        }
    }
}

/** v3 pack 引用校验：老格式不得出现 pack；新格式的 pack 名必须是内容 SHA，且条目大小在阈值内。 */
private fun validatePackedRef(formatVersion: Int, ref: SnapshotObjectRef) {
    val pack = ref.pack ?: return
    require(formatVersion >= SNAPSHOT_FORMAT_V3) { "Packed objects require snapshot format v3" }
    require(validHash(pack)) { "Invalid pack checksum" }
    require(ref.size in 0 until PACK_ENTRY_LIMIT) { "Packed object exceeds the pack entry limit" }
}

internal fun fileSha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().use { input ->
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/** Enforces budgets while reading, including unknown-length and compressed streams. */
internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long, tick: (Long) -> Unit = {}): Long {
    val buffer = ByteArray(256 * 1024)
    var count = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        count += n
        require(count <= limit) { "Backup stream exceeds its size budget" }
        tick(n.toLong())
        output.write(buffer, 0, n)
    }
    return count
}
