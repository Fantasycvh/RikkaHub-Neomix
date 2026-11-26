package app.androidrik.rikkahub.ui.activity

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedButton as OutlinedButton
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.RikkaHubApp
import app.androidrik.rikkahub.data.DataOperation
import app.androidrik.rikkahub.data.DataOperationLock
import app.androidrik.rikkahub.data.sync.RecoveryBackupImporter
import app.androidrik.rikkahub.ui.pages.backup.components.BackupDialog
import app.androidrik.rikkahub.utils.CrashHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "RecoveryImport"

internal class RecoveryImportViewModel(private val app: Application) : AndroidViewModel(app) {
    var importing by mutableStateOf(false)
        private set
    var restoreStaged by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun importFromUri(uri: Uri?) {
        if (uri == null || importing || restoreStaged) return
        importing = true
        errorMessage = null
        viewModelScope.launch {
            var tempArchive: File? = null
            try {
                DataOperationLock.runWaiting(DataOperation.RESTORE) {
                    val runtime = app as RikkaHubApp
                    check(runtime.crashRecoveryPending) { "App is not in safe mode" }
                    val copied = withContext(Dispatchers.IO) {
                        File.createTempFile("safe_mode_restore_", ".zip", app.cacheDir).also { file ->
                            try {
                                val input = app.contentResolver.openInputStream(uri)
                                    ?: error("Cannot open the selected file")
                                input.use { src ->
                                    file.outputStream().use { dst -> src.copyTo(dst) }
                                }
                            } catch (e: Throwable) {
                                file.delete()
                                throw e
                            }
                        }
                    }
                    tempArchive = copied
                    RecoveryBackupImporter(app).importArchive(copied)
                }
                // 恢复已暂存且设置已落盘：清掉崩溃标记，使随后重启进入正常启动并执行原子换库
                CrashHandler.clearCrashed(app)
                restoreStaged = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Log.e(TAG, "Safe-mode backup import failed", failure)
                errorMessage = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
            } finally {
                tempArchive?.let { file ->
                    withContext(NonCancellable + Dispatchers.IO) { file.delete() }
                }
                importing = false
            }
        }
    }
}

@Composable
internal fun RecoveryImportActions(
    model: RecoveryImportViewModel,
    enabled: Boolean = true,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = model::importFromUri,
    )

    OutlinedButton(
        onClick = { showConfirm = true },
        enabled = enabled && !model.importing && !model.restoreStaged,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (model.importing) R.string.backup_page_importing else R.string.safe_mode_import_backup
            )
        )
    }

    model.errorMessage?.let { error ->
        Text(
            text = stringResource(R.string.safe_mode_import_backup_failed, error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.backup_page_local_backup_import)) },
            text = { Text(stringResource(R.string.safe_mode_import_overwrite_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        runCatching { picker.launch(arrayOf("application/zip", "application/octet-stream")) }
                            .onFailure { Log.w(TAG, "Failed to open document picker", it) }
                    }
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.safe_mode_cancel))
                }
            },
        )
    }

    if (model.restoreStaged) {
        BackupDialog()
    }
}
