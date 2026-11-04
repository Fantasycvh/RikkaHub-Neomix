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

@Serializable
internal data class BackupRepositoryInfo(val version: Int = REPO_VERSION, val repoId: String, val createdAt: String)

@Serializable
internal enum class SnapshotDatabaseState { NOT_SELECTED, ABSENT, PRESENT }

@Serializable
internal data class SnapshotFileEntry(val path: String, val sha256: String, val size: Long) {
    val key: String get() = "v3/objects/${sha256.take(2)}/$sha256"
}

/** A complete view: references never depend on a previous manifest. */
@Serializable
internal data class BackupSnapshotManifest(
    val formatVersion: Int = REPO_VERSION,
    val snapshotId: String,
    val repoId: String,
    val createdAt: String,
    val deviceId: String,
    val appVersionCode: String,
    val dbVersion: Int,
    val items: List<BackupItem>,
    val databaseState: SnapshotDatabaseState,
    val files: List<SnapshotFileEntry>,
) {
    val manifestKey get() = "v3/snapshots/$snapshotId.json.gz"
    val completionKey get() = "v3/snapshots/$snapshotId.complete.json"
    val logicalBytes get() = files.sumOf { it.size }
}

@Serializable
internal data class SnapshotCompletion(
    val version: Int = REPO_VERSION,
    val snapshotId: String,
    val repoId: String,
    val manifestSha256: String,
    val manifestBytes: Long,
)

internal fun validUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
internal fun validHash(value: String) = value.matches(Regex("[a-f0-9]{64}"))
internal fun validateKey(key: String) {
    require(key.isNotEmpty() && !key.contains('\\') && !key.contains('\u0000')) { "Invalid repository path" }
    require(key.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Invalid repository path" }
}

internal fun validateSnapshot(snapshot: BackupSnapshotManifest) {
    if (snapshot.formatVersion != REPO_VERSION) throw UnsupportedOperationException("Unsupported snapshot version; update the app")
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
    require(("rikka_hub.db" in seen) == (snapshot.databaseState == SnapshotDatabaseState.PRESENT)) { "Missing database" }
    require((BackupItem.DATABASE in snapshot.items) == (snapshot.databaseState != SnapshotDatabaseState.NOT_SELECTED)) { "Invalid database state" }
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
