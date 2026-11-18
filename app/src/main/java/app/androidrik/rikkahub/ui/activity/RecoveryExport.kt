package app.androidrik.rikkahub.ui.activity

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.RikkaHubApp
import app.androidrik.rikkahub.StartupState
import app.androidrik.rikkahub.data.DataOperation
import app.androidrik.rikkahub.data.DataOperationLock
import app.androidrik.rikkahub.data.sync.RecoveryArchive
import app.androidrik.rikkahub.data.sync.RecoveryBackupExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class RecoveryExportViewModel(private val app: Application) : AndroidViewModel(app) {
    enum class Stage { IDLE, PREPARING, READY_TO_PICK, PICKING, SAVING }

    var stage by mutableStateOf(Stage.IDLE)
        private set
    val busy get() = stage != Stage.IDLE
    var canRescue by mutableStateOf(false)
        private set
    var message by mutableStateOf<Int?>(null)
        private set
    var omissions by mutableStateOf(0)
        private set
    private var pending: RecoveryArchive? = null
    private var rescue = false

    fun prepare(rescue: Boolean) {
        if (busy) return
        this.rescue = rescue
        message = null
        omissions = 0
        stage = Stage.PREPARING
        viewModelScope.launch {
            var unclaimed: RecoveryArchive? = null
            try {
                DataOperationLock.runWaiting(DataOperation.BACKUP) {
                    withContext(Dispatchers.IO) {
                        val runtime = app as RikkaHubApp
                        check(runtime.crashRecoveryPending || runtime.startupState.value is StartupState.Failed)
                        unclaimed = RecoveryBackupExporter(app).prepare(rescue)
                    }
                }
                pending = checkNotNull(unclaimed)
                unclaimed = null
                stage = Stage.READY_TO_PICK
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                preparationFailed(failure)
            } catch (failure: OutOfMemoryError) {
                preparationFailed(failure)
            } finally {
                unclaimed?.let { withContext(NonCancellable + Dispatchers.IO) { it.file.delete() } }
                if (stage == Stage.PREPARING) stage = Stage.IDLE
            }
        }
    }

    fun beginPicker(): String {
        stage = Stage.PICKING
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        return if (rescue) "rikkahub_rescue_$timestamp.zip" else "rikkahub_backup_$timestamp.zip"
    }

    fun pickerUnavailable() {
        save(null)
        message = R.string.safe_mode_export_write_failed
    }

    fun save(uri: Uri?) {
        val archive = pending ?: return
        pending = null
        stage = Stage.SAVING
        viewModelScope.launch {
            try {
                if (uri == null) return@launch
                withContext(Dispatchers.IO) {
                    val resolver = app.contentResolver
                    try {
                        val output = resolver.openOutputStream(uri) ?: throw IOException("Cannot open destination")
                        output.use { out ->
                            archive.file.inputStream().use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        // CreateDocument gave us a newly created file. Some providers disallow deletion.
                        runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                        throw failure
                    }
                }
                omissions = archive.omissions
                message = if (rescue) R.string.safe_mode_rescue_success else R.string.safe_mode_export_backup_success
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.w("RecoveryExport", "Save failed: ${failure.javaClass.simpleName}")
                message = R.string.safe_mode_export_write_failed
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { archive.file.delete() }
                stage = Stage.IDLE
            }
        }
    }

    private fun preparationFailed(failure: Throwable) {
        Log.w("RecoveryExport", "Archive preparation failed: ${failure.javaClass.simpleName}")
        canRescue = true
        message = if (rescue) R.string.safe_mode_rescue_failed else R.string.safe_mode_export_try_rescue
    }

    override fun onCleared() {
        pending?.file?.delete()
    }
}

@Composable
internal fun RecoveryExportActions(model: RecoveryExportViewModel, enabled: Boolean = true) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), model::save)
    LaunchedEffect(model.stage) {
        if (model.stage == RecoveryExportViewModel.Stage.READY_TO_PICK) {
            try {
                picker.launch(model.beginPicker())
            } catch (_: RuntimeException) {
                model.pickerUnavailable()
            }
        }
    }
    OutlinedButton(
        onClick = { model.prepare(rescue = false) },
        enabled = enabled && !model.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (model.busy) R.string.backup_page_exporting else R.string.safe_mode_export_backup))
    }
    if (model.canRescue) {
        OutlinedButton(
            onClick = { model.prepare(rescue = true) },
            enabled = enabled && !model.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.safe_mode_export_rescue))
        }
    }
    model.message?.let { id ->
        Text(
            text = if (model.omissions > 0) stringResource(R.string.safe_mode_rescue_incomplete, model.omissions)
            else stringResource(id),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
