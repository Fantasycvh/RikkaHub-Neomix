package app.androidrik.rikkahub.data.sync.webdav

import app.androidrik.rikkahub.data.sync.BackupArchiveEngine
import app.androidrik.rikkahub.data.sync.BackupInspection
import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.BackupPhase
import app.androidrik.rikkahub.data.sync.BackupProgress
import app.androidrik.rikkahub.data.sync.RestoreOutcome
import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.data.files.SkillPaths
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.WebDavConfig
import app.androidrik.rikkahub.data.datastore.migration.SettingsJsonMigrator
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.utils.fileSizeToString
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "WebDavSync"

class WebDavSync(
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    private val appDatabase: AppDatabase,
) {
    /** 打包/恢复委托给共享引擎（与 S3Sync 共用同一实现） */
    private val archiveEngine = BackupArchiveEngine(settingsStore, json, context, appDatabase)

    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig, onProgress: (BackupProgress) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val file = prepareBackupFile(config, onProgress)
            val client = getClient(config)

            // Ensure the backup directory exists
            client.ensureCollectionExists().getOrThrow()

            // Upload the backup file
            client.put(
                path = file.name,
                file = file,
                contentType = "application/zip",
                onProgress = { sent, total ->
                    onProgress(BackupProgress(BackupPhase.UPLOADING, sent, total ?: file.length()))
                },
            ).getOrThrow()

            Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")

            // Clean up temp file
            file.delete()
        }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        resources
            .filter { !it.isCollection && it.displayName.startsWith("backup_") && it.displayName.endsWith(".zip") }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem, onProgress: (BackupProgress) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val client = getClient(config)
            val backupFile = File(context.cacheDir, item.displayName)

            try {
                // Download backup file directly to file to avoid OOM
                Log.i(TAG, "restore: Downloading ${item.displayName}")
                client.downloadToFile(item.displayName, backupFile) { downloaded, total ->
                    onProgress(BackupProgress(BackupPhase.DOWNLOADING, downloaded, total ?: item.size))
                }.getOrThrow()

                Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")

                // Restore from backup file
                restoreFromBackupFile(backupFile, config, onProgress)
            } finally {
                // Clean up temp file
                if (backupFile.exists()) {
                    backupFile.delete()
                    Log.i(TAG, "restore: Cleaned up temporary backup file")
                }
            }
        }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(
        file: File,
        config: WebDavConfig,
        onProgress: (BackupProgress) -> Unit = {},
    ): RestoreOutcome = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromLocalFile: Starting restore from ${file.absolutePath}")

        if (!file.exists()) {
            throw Exception("Backup file does not exist")
        }

        if (!file.canRead()) {
            throw Exception("Cannot read backup file")
        }

        try {
            val outcome = restoreFromBackupFile(file, config, onProgress)
            Log.i(TAG, "restoreFromLocalFile: Restore completed successfully")
            outcome
        } catch (e: Exception) {
            Log.e(TAG, "restoreFromLocalFile: Failed to restore from local file", e)
            throw Exception("Restore failed: ${e.message}")
        }
    }

    /** 恢复前巡检（design-backup-manifest-and-safety §3.5）：读 manifest + 中央目录，不全量解压。 */
    suspend fun inspectBackupFile(file: File): BackupInspection = withContext(Dispatchers.IO) {
        if (!file.exists()) throw Exception("Backup file does not exist")
        if (!file.canRead()) throw Exception("Cannot read backup file")
        archiveEngine.inspectBackup(file)
    }

    suspend fun prepareBackupFile(config: WebDavConfig, onProgress: (BackupProgress) -> Unit = {}): File =
        archiveEngine.prepareBackupFile(settingsStore.settingsFlow.value.backupItems, onProgress)

    private suspend fun restoreFromBackupFile(
        backupFile: File,
        config: WebDavConfig,
        onProgress: (BackupProgress) -> Unit,
    ): RestoreOutcome =
        archiveEngine.restoreFromBackupFile(backupFile, settingsStore.settingsFlow.value.backupItems, onProgress)

                }

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
