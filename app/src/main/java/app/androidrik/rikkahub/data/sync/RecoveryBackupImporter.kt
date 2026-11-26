package app.androidrik.rikkahub.data.sync

import android.content.Context
import android.util.Log
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.RecoverySettings
import app.androidrik.rikkahub.data.files.FileFolders
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

private const val TAG = "RecoveryImport"

/**
 * 安全模式离线备份导入（对齐上游 `2d93676e4` / `#2361`，适配本仓无 Koin/Room 启动期架构）：
 *
 * 在 `crashRecoveryPending == true`（常规启动暂停、Koin/Room 均未启动）下：
 * 1. 校验备份 zip（拒绝救援包，必须含 `settings.json` 或 `rikka_hub.db`，通过 manifest 双 SHA-256）；
 * 2. 尽力写一份安全备份到 `filesDir/safety_backup/`（失败不阻断恢复）；
 * 3. 若此前有未消费的 `PendingRestoreStore` 残留则清理后重新 stage 数据库家族；
 * 4. 恢复附件/工作区文件并通过 [RecoverySettings.writeRestored] 覆盖设置；
 * 5. 调用方随后清掉崩溃标记并重启进程，由下次启动的 `PendingRestoreStore.applyIfPending` 原子换库。
 */
internal enum class RecoveryArchiveCheck {
    VALID,
    RESCUE_ARCHIVE,
    INVALID_ARCHIVE,
}

internal fun checkRecoveryBackupZip(backupFile: File): RecoveryArchiveCheck = ZipFile(backupFile).use { zip ->
    if (zip.getEntry(RESCUE_REPORT_ENTRY) != null) return@use RecoveryArchiveCheck.RESCUE_ARCHIVE
    val hasPayload = zip.getEntry("settings.json") != null || zip.getEntry("rikka_hub.db") != null
    if (hasPayload) RecoveryArchiveCheck.VALID else RecoveryArchiveCheck.INVALID_ARCHIVE
}

internal class RecoveryBackupImporter(private val context: Context) {

    suspend fun importArchive(backupFile: File): RestoreOutcome = withContext(Dispatchers.IO) {
        when (checkRecoveryBackupZip(backupFile)) {
            RecoveryArchiveCheck.RESCUE_ARCHIVE -> error(context.getString(R.string.safe_mode_rescue_manual_only))
            RecoveryArchiveCheck.INVALID_ARCHIVE -> throw IllegalArgumentException(
                context.getString(R.string.safe_mode_import_invalid_archive)
            )
            RecoveryArchiveCheck.VALID -> Unit
        }

        // 用户在安全模式显式确认导入新备份：清掉可能残留的未应用 pending，避免卡死在二次 stage 检查
        if (PendingRestoreStore.hasPending(context.filesDir)) {
            PendingRestoreStore.clearPending(context.filesDir)
        }

        val recoverySettings = RecoverySettings(context)
        restoreBackupArchive(
            context = context,
            json = JsonInstant,
            backupFile = backupFile,
            items = BackupItem.entries,
            mode = RestoreMode.OVERWRITE,
            scopeOverride = null,
            createSafetyBackup = { writeSafetyBackupBestEffort() },
            updateSettings = { transform ->
                val current = recoverySettings.readForRestoreOrDefault()
                recoverySettings.writeRestored(transform(current))
            },
            requirePayload = true,
        )
    }

    private suspend fun writeSafetyBackupBestEffort() {
        try {
            val archive = RecoveryBackupExporter(context).prepare(rescue = false)
            try {
                val dir = File(context.filesDir, FileFolders.SAFETY_BACKUP).apply { mkdirs() }
                val target = File(dir, "safety_${archive.file.name}")
                val part = File.createTempFile("safety-", ".part", dir)
                try {
                    archive.file.copyTo(part, overwrite = true)
                    check(part.renameTo(target)) { "Could not publish safety backup" }
                } finally {
                    part.delete()
                }
                dir.listFiles { f -> f.name.startsWith("safety_") && f.extension == "zip" }
                    ?.sortedByDescending { it.name }
                    ?.drop(3)
                    ?.forEach { it.delete() }
            } finally {
                archive.file.delete()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "writeSafetyBackupBestEffort failed; continuing safe-mode import", failure)
        }
    }
}
