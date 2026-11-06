package app.androidrik.rikkahub.data.sync.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import app.androidrik.rikkahub.BuildConfig
import app.androidrik.rikkahub.data.DataOperation
import app.androidrik.rikkahub.data.DataOperationLock
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.sync.BackupArchiveEngine
import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupProgress
import app.androidrik.rikkahub.data.sync.RestoreOutcome
import app.androidrik.rikkahub.data.sync.WorkspaceBackupHelper
import app.androidrik.rikkahub.data.sync.s3.S3Client
import app.androidrik.rikkahub.data.sync.webdav.WebDavClient
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.util.UUID

internal enum class BackupDestination { LOCAL, WEBDAV, S3 }

/** 增量快照保留份数上限（按设备、按 createdAt 保留最新 N 份；0 = 不限）。 */
internal const val DEFAULT_SNAPSHOT_RETENTION = 10

/** 本机增量备份仓库设置（与 Settings 分开存：不改变已提交设置 schema，读写都在服务层）。 */
@Serializable
internal data class SnapshotRetentionConfig(val keep: Int = DEFAULT_SNAPSHOT_RETENTION)

@Serializable
internal data class LocalBackupTarget(val treeUri: String, val repoId: String?, val name: String, val canWrite: Boolean = true)

/** Shared singleton: serializes repository jobs without holding a data lock across the confirmation UI. */
class IncrementalBackupService(
    private val context: Context,
    private val settings: SettingsStore,
    private val database: AppDatabase,
    private val httpClient: HttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val cache = File(context.cacheDir, "incremental_backup")
    private val stateDir = File(context.filesDir, FileFolders.BACKUP_INDEX)
    private val targetFile get() = File(stateDir, "local-target.json")
    private val retentionFile get() = File(stateDir, "snapshot-retention.json")
    private var cacheInitialized = false

    /** 当前应用 DB schema 版本；UI 用于提示「旧版本快照将按当前结构恢复」。 */
    internal val currentSchemaVersion: Int get() = AppDatabase.SCHEMA_VERSION

    internal fun snapshotRetention(): Int = if (retentionFile.exists()) {
        runCatching { json.decodeFromString<SnapshotRetentionConfig>(retentionFile.readText()).keep }
            .getOrDefault(DEFAULT_SNAPSHOT_RETENTION)
    } else DEFAULT_SNAPSHOT_RETENTION

    internal fun setSnapshotRetention(keep: Int) {
        val normalized = if (keep <= 0) 0 else keep.coerceIn(1, 100)
        check(stateDir.isDirectory || stateDir.mkdirs())
        val atomic = AtomicFile(retentionFile)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(SnapshotRetentionConfig(normalized)).toByteArray())
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream); throw failure
        }
    }

    /** Only once per singleton lifetime, before any live plan exists. Interrupted previous-process tasks are disposable. */
    private fun engine(onProgress: (BackupProgress) -> Unit = {}): IncrementalBackupEngine {
        if (!cacheInitialized) {
            check(cache.isDirectory || cache.mkdirs())
            cache.listFiles()?.forEach { check(it.deleteRecursively()) { "Cannot clean interrupted backup task" } }
            cacheInitialized = true
        }
        return IncrementalBackupEngine(cache, progress = onProgress)
    }

    internal fun localTarget(): LocalBackupTarget? {
        if (!targetFile.exists()) return null
        val target = json.decodeFromString<LocalBackupTarget>(AtomicFile(targetFile).readFully().decodeToString())
        val writable = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == target.treeUri && it.isWritePermission }
        return target.copy(canWrite = writable)
    }

    private fun saveTarget(target: LocalBackupTarget) {
        check(stateDir.isDirectory || stateDir.mkdirs())
        val atomic = AtomicFile(targetFile)
        val stream = atomic.startWrite()
        try { stream.write(json.encodeToString(target).toByteArray()); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
    }

    internal suspend fun attachLocal(uri: Uri, grantedFlags: Int): LocalBackupTarget = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(uri.authority != "${context.packageName}.documents") { "不能将备份仓库放入本应用的工作区，否则会重复备份仓库自身" }
            val resolver = context.contentResolver
            val store = SafBackupObjectStore(AndroidBackupDocumentAccess(resolver, uri), uri.toString())
            val repo = engine().inspectRepository(store)
            require(repo != null || store.listChildren("").isEmpty()) { "Select an empty backup folder or an existing repository" }
            val flags = grantedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "Read permission was not granted" }
            resolver.takePersistableUriPermission(uri, flags)
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: "备份文件夹"
            LocalBackupTarget(uri.toString(), repo?.repoId, name, flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0).also(::saveTarget)
        }
    }

    internal suspend fun detachLocal() = withContext(Dispatchers.IO) { mutex.withLock { AtomicFile(targetFile).delete() } }

    private suspend fun store(destination: BackupDestination): BackupObjectStore = when (destination) {
        BackupDestination.LOCAL -> {
            val target = requireNotNull(localTarget()) { "请先选择备份文件夹" }
            val uri = Uri.parse(target.treeUri)
            require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
                "文件夹授权已失效，请重新选择文件夹"
            }
            SafBackupObjectStore(AndroidBackupDocumentAccess(context.contentResolver, uri), target.treeUri).also { store ->
                val info = engine().inspectRepository(store)
                require(target.repoId == null || info?.repoId == target.repoId) { "备份仓库已被移动或替换，请重新关联" }
            }
        }
        BackupDestination.WEBDAV -> settings.settingsFlow.value.webDavConfig.let {
            WebDavBackupObjectStore(WebDavClient(it, httpClient), "webdav:${it.url}:${it.path}:${it.username}")
        }
        BackupDestination.S3 -> settings.settingsFlow.value.s3Config.let {
            S3BackupObjectStore(S3Client(it, httpClient), "s3:${it.endpoint}:${it.bucket}:${it.pathStyle}")
        }
    }

    private fun deviceId(): String {
        check(stateDir.isDirectory || stateDir.mkdirs())
        val file = File(stateDir, "device-id")
        if (file.exists()) return file.readText().also { require(validUuid(it)) }
        val id = UUID.randomUUID().toString()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(id.toByteArray()); atomic.finishWrite(stream) }
        catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
        return id
    }

    internal suspend fun list(destination: BackupDestination): List<BackupSnapshotManifest> = withContext(Dispatchers.IO) {
        mutex.withLock { engine().list(store(destination)) }
    }

    internal suspend fun plan(destination: BackupDestination, onProgress: (BackupProgress) -> Unit): BackupSnapshotPlan {
        var captured: BackupSnapshotPlan? = null
        try { return withContext(Dispatchers.IO) {
        mutex.withLock {
            DataOperationLock.runWaiting(DataOperation.BACKUP) {
                val protocol = engine(onProgress)
                val targetStore = store(destination)
                if (destination == BackupDestination.LOCAL) {
                    val uri = Uri.parse(requireNotNull(localTarget()).treeUri)
                    require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }) {
                        "文件夹只有读取权限，请重新选择并授予写入权限"
                    }
                }
                check(cache.isDirectory || cache.mkdirs())
                val sourceDir = File(cache, "capture-${UUID.randomUUID()}").also { check(it.mkdir()) }
                try {
                    val current = settings.settingsFlow.value
                    val inputs = collectSnapshotFiles(context.filesDir, WorkspaceBackupHelper.workspacesDir(context), current.backupItems).toMutableList()
                    val settingsFile = File(sourceDir, "settings.json").apply { writeText(json.encodeToString(current)) }
                    inputs += SnapshotInput("settings.json", settingsFile)
                    val dbObjects: List<SnapshotDbObjectInput>
                    val state: SnapshotDatabaseState
                    if (BackupItem.DATABASE !in current.backupItems) {
                        dbObjects = emptyList()
                        state = SnapshotDatabaseState.NOT_SELECTED
                    } else if (!context.getDatabasePath(AppDatabase.NAME).exists()) {
                        dbObjects = emptyList()
                        state = SnapshotDatabaseState.ABSENT
                    } else {
                        val snapshotFile = File(sourceDir, "database.db")
                        val size = context.getDatabasePath(AppDatabase.NAME).length()
                        check(context.cacheDir.usableSpace >= size * 4 + RESERVE_BYTES) { "Not enough space for database snapshot" }
                        // Unlike legacy ZIP export, incremental snapshots NEVER fall back to copying a live DB.
                        database.openHelper.writableDatabase.execSQL("VACUUM main INTO ?", arrayOf(snapshotFile.absolutePath))
                        check(snapshotFile.isFile && snapshotFile.length() > 0) { "Consistent database snapshot failed" }
                        // P2：整库不直接上传，改为从同一一致性快照导出按会话/按表的逻辑对象。
                        val staging = File(sourceDir, "db-objects").also { check(it.mkdir()) }
                        dbObjects = DatabaseObjectCapture.capture(snapshotFile, staging)
                        snapshotFile.delete()
                        state = SnapshotDatabaseState.PRESENT
                    }
                    protocol.plan(targetStore, inputs, current.backupItems,
                        state, deviceId(), AppDatabase.SCHEMA_VERSION, BuildConfig.VERSION_CODE, dbObjects).also { captured = it }
                } finally { sourceDir.deleteRecursively() }
            }
        }
        } } catch (failure: Throwable) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { captured?.close() }
            throw failure
        }
    }

    internal suspend fun commit(plan: BackupSnapshotPlan, onProgress: (BackupProgress) -> Unit): BackupSnapshotManifest = withContext(Dispatchers.IO) {
        mutex.withLock {
            DataOperationLock.runWaiting(DataOperation.BACKUP) {
                val engine = engine(onProgress)
                val result = engine.commit(plan)
                localTarget()?.takeIf { it.treeUri == plan.store.destinationId }?.let { saveTarget(it.copy(repoId = result.repoId)) }
                // 保留策略：提交成功后按「本设备最新 N 份」清理旧快照清单；对象留给手动 GC。
                runCatching { engine.pruneSnapshots(plan.store, deviceId(), snapshotRetention()) }
                    .onFailure { Log.w("IncrementalBackup", "prune snapshots failed", it) }
                result
            }
        }
    }

    /** 手动按保留策略清理旧快照（不动对象）。 */
    internal suspend fun prune(destination: BackupDestination): SnapshotPruneResult = withContext(Dispatchers.IO) {
        mutex.withLock { engine().pruneSnapshots(store(destination), deviceId(), snapshotRetention()) }
    }

    /** 手动回收未引用对象/pack；跨设备仓库执行全仓可达性分析，失败即停止（见引擎实现）。 */
    internal suspend fun garbageCollect(destination: BackupDestination, onProgress: (BackupProgress) -> Unit = {}): ObjectGcResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            DataOperationLock.runWaiting(DataOperation.STORAGE_CLEANUP) {
                engine(onProgress).garbageCollect(store(destination), deviceId())
            }
        }
    }

    internal suspend fun restore(destination: BackupDestination, snapshot: BackupSnapshotManifest, onProgress: (BackupProgress) -> Unit): RestoreOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val warnings = ArrayList<String>()
            val zip = DataOperationLock.runWaiting(DataOperation.RESTORE) {
                engine(onProgress).assemble(store(destination), snapshot, AppDatabase.SCHEMA_VERSION) { objects, dir ->
                    // P2：以当前库的 VACUUM 副本为底，把快照对象整表替换进去；未覆盖表（FTS/本机进度）保留。
                    // 跨 schema：快照 dbVersion 可以比当前旧，列交集/新增列默认值由 DatabaseObjectMerge 兜底。
                    mergeSnapshotDatabase(snapshot, objects, dir, warnings)
                }
            }
            try {
                val outcome = BackupArchiveEngine(settings, json, context, database).restoreFromBackupFile(zip, snapshot.items, onProgress)
                // 逻辑对象快照不含 FTS；换库后由启动期按当前数据重建搜索索引。
                if (snapshot.dbObjects.isNotEmpty()) FtsRebuildPending.mark(context.filesDir)
                if (warnings.isNotEmpty()) Log.i("IncrementalBackup", "restore cross-schema warnings=${warnings.size}")
                outcome
            } finally { zip.parentFile?.deleteRecursively() }
        }
    }

    /**
     * 把快照导出成完整 ZIP（与恢复时组装的 ZIP 相同：settings + 文件 + 合并后的数据库 + manifest）。
     *
     * 供归档或交给「完整 ZIP 导入」入口使用；生成与写入都在任务生命周期内完成，
     * 中途离开页面/取消不会留下半份目标文件或临时 ZIP。旧版 App 只有在数据库版本兼容时才能导入。
     */
    internal suspend fun exportSnapshot(
        destination: BackupDestination,
        snapshot: BackupSnapshotManifest,
        target: Uri,
        onProgress: (BackupProgress) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            DataOperationLock.runWaiting(DataOperation.BACKUP) {
                val warnings = ArrayList<String>()
                val zip = engine(onProgress).assemble(store(destination), snapshot, AppDatabase.SCHEMA_VERSION) { objects, dir ->
                    mergeSnapshotDatabase(snapshot, objects, dir, warnings)
                }
                try {
                    val output = requireNotNull(context.contentResolver.openOutputStream(target)) { "无法写入所选文件" }
                    output.use { out -> FileInputStream(zip).use { it.copyTo(out) } }
                    if (warnings.isNotEmpty()) Log.i("IncrementalBackup", "snapshot export cross-schema warnings=${warnings.size}")
                } finally {
                    withContext(kotlinx.coroutines.NonCancellable) { zip.parentFile?.deleteRecursively() }
                }
            }
        }
    }

    /** 用快照的逻辑数据库对象覆盖**当前库副本**中被覆盖的表，返回可写入恢复/导出 ZIP 的数据库文件。 */
    private suspend fun mergeSnapshotDatabase(
        snapshot: BackupSnapshotManifest,
        objects: List<DecodedDbObject>,
        dir: File,
        warnings: MutableList<String>,
    ): File {
        val base = File(dir, "restore-base.db")
        try {
            database.openHelper.writableDatabase.execSQL("VACUUM main INTO ?", arrayOf(base.absolutePath))
            check(base.isFile && base.length() > 0) { "Failed to copy the current database for restore" }
            DatabaseObjectMerge.merge(
                baseDb = base,
                objects = objects.map { it.objectKey to it.raw },
                currentDbVersion = AppDatabase.SCHEMA_VERSION,
                snapshotDbVersion = snapshot.dbVersion,
            ) { warning ->
                warnings += warning
                Log.w("IncrementalBackup", "restore merge: $warning")
            }
            return base
        } catch (failure: Throwable) {
            base.delete()
            throw failure
        }
    }

    internal suspend fun delete(destination: BackupDestination, snapshot: BackupSnapshotManifest) = withContext(Dispatchers.IO) {
        mutex.withLock { engine().deleteSnapshot(store(destination), snapshot) }
    }
}
