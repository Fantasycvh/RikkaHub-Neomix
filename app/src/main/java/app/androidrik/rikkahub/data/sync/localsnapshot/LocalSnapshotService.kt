package app.androidrik.rikkahub.data.sync.localsnapshot

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.sync.BackupArchiveEngine
import app.androidrik.rikkahub.data.sync.BackupProgress
import app.androidrik.rikkahub.data.sync.BackupRunCoordinator
import app.androidrik.rikkahub.data.sync.BackupRunKind
import app.androidrik.rikkahub.data.sync.PendingRestoreStore
import app.androidrik.rikkahub.data.sync.encodeBackupSettings
import app.androidrik.rikkahub.data.sync.repo.AndroidBackupDocumentAccess
import app.androidrik.rikkahub.data.sync.sha256Hex
import app.androidrik.rikkahub.utils.SecretCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime

internal class LocalSnapshotPermissionException : IllegalStateException("Backup folder access has expired")

internal class LocalSnapshotService(
    private val app: Application,
    private val appScope: AppScope,
    private val settings: SettingsStore,
    private val archiveEngine: BackupArchiveEngine,
    private val coordinator: BackupRunCoordinator,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val resolver get() = app.contentResolver
    val state = settings.localSnapshotState

    suspend fun setPassword(password: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(password == null || password.length in 12..256 && password.isNotBlank())
            val encrypted = password?.let(SecretCipher::encrypt)
            settings.update { it.copy(localSnapshotConfig = it.localSnapshotConfig.copy(encryptedPassword = encrypted)) }
            val config = settings.settingsFlow.value.localSnapshotConfig
            settings.saveLocalSnapshotState(LocalSnapshotState(treeUri = config.treeUri))
        }
    }

    suspend fun isEncryptedArchive(file: File): Boolean = withContext(Dispatchers.IO) { EncryptedBackupZip.isWrapped(file) }

    suspend fun stageImportedArchive(uri: Uri): File = stageArchive("local_snapshot_import_") { target ->
        requireNotNull(resolver.openInputStream(uri)).buffered().use { input ->
            target.outputStream().buffered().use { output -> EncryptedBackupZip.copy(input, output) }
        }
    }

    suspend fun unlockArchive(file: File, password: String): File = stageArchive("local_snapshot_unlock_") { target ->
        val key = password.toCharArray()
        try { EncryptedBackupZip.unwrap(file, target, key) } finally { key.fill('\u0000') }
    }

    suspend fun attach(uri: Uri, flags: Int) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(DocumentsContract.isTreeUri(uri) && uri.authority != "${app.packageName}.documents") {
                "Select a folder outside the app workspace"
            }
            val required = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            require(flags and required == required) { "The backup folder requires read and write access" }
            resolver.takePersistableUriPermission(uri, required)
            directory(uri.toString()).list()
            settings.update { it.copy(localSnapshotConfig = it.localSnapshotConfig.copy(treeUri = uri.toString())) }
            settings.saveLocalSnapshotState(LocalSnapshotState(treeUri = uri.toString()))
        }
    }

    suspend fun detach() = withContext(Dispatchers.IO) {
        mutex.withLock {
            settings.update { it.copy(localSnapshotConfig = it.localSnapshotConfig.copy(treeUri = null, enabled = false)) }
            settings.saveLocalSnapshotState(LocalSnapshotState())
            // Persisted grants belong to the app and may also be used by the incremental repository.
        }
    }

    suspend fun list(treeUri: String): List<LocalSnapshot> = withContext(Dispatchers.IO) {
        mutex.withLock { directory(treeUri).list() }
    }

    suspend fun delete(treeUri: String, snapshot: LocalSnapshot) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = directory(treeUri)
            val snapshots = directory.list()
            val current = snapshots.firstOrNull { it.id == snapshot.id && it.name == snapshot.name } ?: return@withLock
            check(current != LocalSnapshotRetentionPolicy.newestNonEmpty(snapshots)) { "The newest backup is protected" }
            directory.delete(current)
        }
    }

    suspend fun stageForRestore(treeUri: String, snapshot: LocalSnapshot): File = stageArchive("local_snapshot_restore_") { target ->
        mutex.withLock {
            val directory = directory(treeUri)
            val current = directory.list().first { it.id == snapshot.id && it.name == snapshot.name }
            directory.stage(current, target)
        }
    }

    private suspend fun stageArchive(prefix: String, write: suspend (File) -> Unit): File {
        var file: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val target = File.createTempFile(prefix, ".zip", app.cacheDir)
                file = target
                write(target)
                target
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { file?.delete() }
            throw failure
        }
    }

    suspend fun runNow() = coordinator.await(BackupRunKind.LOCAL_SNAPSHOT) { report ->
        save(automatic = false, report)
    }

    fun registerSchedule() {
        val earliest = SystemClock.elapsedRealtime() + 8_000
        var lastCheck: Long? = null
        var scheduled: Job? = null
        fun schedule(wait: Long) {
            if (scheduled?.isActive == true) return
            scheduled = appScope.launch(Dispatchers.Main) {
                val nextCheck = lastCheck?.plus(60_000) ?: earliest
                delay(maxOf(wait, nextCheck - SystemClock.elapsedRealtime()))
                settings.settingsFlow.first { !it.init }
                while (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    lastCheck = SystemClock.elapsedRealtime()
                    if (checkAutomatically()) break
                    coordinator.state.first { it == null }
                }
            }
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = schedule(3_000)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        schedule(8_000)
    }

    private suspend fun checkAutomatically(): Boolean {
        val shouldStart = withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = settings.settingsFlow.value
                val config = current.localSnapshotConfig
                val treeUri = config.treeUri ?: return@withLock false
                val previous = state.first().forTree(treeUri)
                if (!previous.isDue(config, System.currentTimeMillis()) || PendingRestoreStore.hasPending(app.filesDir)) {
                    return@withLock false
                }
                val fingerprint = settingsFingerprint(current)
                if (fingerprint != null && fingerprint == previous.fingerprint && previous.status != LocalSnapshotStatus.CLEANUP_FAILED) {
                    val newest = runCatching { LocalSnapshotRetentionPolicy.newestNonEmpty(directory(treeUri).list()) }.getOrNull()
                    currentCoroutineContext().ensureActive()
                    if (previous.isUnchanged(fingerprint, newest)) {
                        settings.saveLocalSnapshotState(previous.copy(status = LocalSnapshotStatus.UNCHANGED))
                        return@withLock false
                    }
                }
                true
            }
        }
        if (!shouldStart) return true
        return try {
            coordinator.awaitIfIdle(BackupRunKind.LOCAL_SNAPSHOT) { report -> save(automatic = true, report) }
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            true
        } catch (_: Exception) {
            true // The coordinator reports the error, and save records the retry time.
        }
    }

    private suspend fun save(automatic: Boolean, report: (BackupProgress) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = settings.settingsFlow.value
            val config = current.localSnapshotConfig
            val treeUri = if (automatic) config.treeUri ?: return@withLock else requireNotNull(config.treeUri)
            val previous = state.first().forTree(treeUri)
            if (automatic && (!previous.isDue(config, System.currentTimeMillis()) || PendingRestoreStore.hasPending(app.filesDir))) {
                return@withLock
            }
            try {
                check(!PendingRestoreStore.hasPending(app.filesDir)) { "Restart to finish the pending restore" }
                val directory = directory(treeUri)
                val archive = archiveEngine.prepareBackupFile(current.backupItems, current.backupScope, report)
                val name: String
                val fingerprint: String?
                val bytes: Long
                val now = LocalDateTime.now()
                var wrapper: File? = null
                try {
                    fingerprint = if (current.backupItems.isEmpty()) {
                        archiveEngine.inspectBackup(archive).manifest?.settingsSha256
                    } else null
                    val published = config.encryptedPassword?.let { encrypted ->
                        val target = File.createTempFile("local_snapshot_encrypted_", ".zip", app.cacheDir)
                        wrapper = target
                        val password = SecretCipher.decrypt(encrypted).toCharArray()
                        try { EncryptedBackupZip.wrap(archive, target, password) } finally { password.fill('\u0000') }
                        target
                    } ?: archive
                    bytes = published.length()
                    name = directory.publish(published, now, report)
                } finally {
                    wrapper?.delete()
                    archive.delete()
                }
                // The archive has been published. Finish local bookkeeping even if the notification is cancelled.
                withContext(NonCancellable) {
                    val cleanup = runCatching {
                        LocalSnapshotRetentionPolicy.deletions(directory.list(), now, config.keepRecent)
                            .filter { it.name != name }
                            .forEach(directory::delete)
                    }
                    settings.saveLocalSnapshotState(
                        LocalSnapshotState(
                            treeUri = treeUri,
                            lastSuccessAt = System.currentTimeMillis(),
                            lastArchiveName = name,
                            lastArchiveBytes = bytes,
                            fingerprint = fingerprint,
                            status = if (cleanup.isSuccess) LocalSnapshotStatus.SAVED else LocalSnapshotStatus.CLEANUP_FAILED,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val reason = if (failure is SecurityException || failure is LocalSnapshotPermissionException) {
                    LocalSnapshotStatus.PERMISSION_REQUIRED
                } else LocalSnapshotStatus.FAILED
                settings.saveLocalSnapshotState(previous.failed(System.currentTimeMillis(), reason))
                throw failure
            }
        }
    }

    private fun directory(treeUri: String): LocalSnapshotDirectory {
        val uri = Uri.parse(treeUri)
        if (resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission && it.isWritePermission }) {
            throw LocalSnapshotPermissionException()
        }
        val access = AndroidBackupDocumentAccess(resolver, uri)
        return LocalSnapshotDirectory(access, access::rename)
    }

    private fun settingsFingerprint(current: Settings): String? {
        // The archive always includes settings. Other sections have writers outside the settings store.
        if (current.backupItems.isNotEmpty()) return null
        return sha256Hex(encodeBackupSettings(json, current, current.backupScope).toByteArray())
    }

    private fun LocalSnapshotState.forTree(treeUri: String): LocalSnapshotState =
        if (this.treeUri == treeUri) this else LocalSnapshotState(treeUri = treeUri)
}
