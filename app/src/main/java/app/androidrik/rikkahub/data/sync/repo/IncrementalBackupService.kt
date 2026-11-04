package app.androidrik.rikkahub.data.sync.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
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
import java.util.UUID

internal enum class BackupDestination { LOCAL, WEBDAV, S3 }

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
    private var cacheInitialized = false

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
                    val state = if (BackupItem.DATABASE !in current.backupItems) SnapshotDatabaseState.NOT_SELECTED
                    else if (!context.getDatabasePath(AppDatabase.NAME).exists()) SnapshotDatabaseState.ABSENT
                    else {
                        val snapshot = File(sourceDir, "database.db")
                        val size = context.getDatabasePath(AppDatabase.NAME).length()
                        check(context.cacheDir.usableSpace >= size * 3 + RESERVE_BYTES) { "Not enough space for database snapshot" }
                        // Unlike legacy ZIP export, incremental snapshots NEVER fall back to copying a live DB.
                        database.openHelper.writableDatabase.execSQL("VACUUM main INTO ?", arrayOf(snapshot.absolutePath))
                        check(snapshot.isFile && snapshot.length() > 0) { "Consistent database snapshot failed" }
                        inputs += SnapshotInput("rikka_hub.db", snapshot)
                        SnapshotDatabaseState.PRESENT
                    }
                    protocol.plan(targetStore, inputs, current.backupItems,
                        state, deviceId(), AppDatabase.SCHEMA_VERSION, BuildConfig.VERSION_CODE).also { captured = it }
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
                val result = engine(onProgress).commit(plan)
                localTarget()?.takeIf { it.treeUri == plan.store.destinationId }?.let { saveTarget(it.copy(repoId = result.repoId)) }
                result
            }
        }
    }

    internal suspend fun restore(destination: BackupDestination, snapshot: BackupSnapshotManifest, onProgress: (BackupProgress) -> Unit): RestoreOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            val zip = DataOperationLock.runWaiting(DataOperation.RESTORE) {
                engine(onProgress).assemble(store(destination), snapshot, AppDatabase.SCHEMA_VERSION)
            }
            try {
                BackupArchiveEngine(settings, json, context, database).restoreFromBackupFile(zip, snapshot.items, onProgress)
            } finally { zip.parentFile?.deleteRecursively() }
        }
    }

    internal suspend fun delete(destination: BackupDestination, snapshot: BackupSnapshotManifest) = withContext(Dispatchers.IO) {
        mutex.withLock { engine().deleteSnapshot(store(destination), snapshot) }
    }
}
