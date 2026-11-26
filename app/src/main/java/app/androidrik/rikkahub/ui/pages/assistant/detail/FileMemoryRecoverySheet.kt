package app.androidrik.rikkahub.ui.pages.assistant.detail

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.db.entity.MemoryFileEntity
import app.androidrik.rikkahub.data.db.entity.MemoryFileVersionEntity
import app.androidrik.rikkahub.data.repository.MemoryFileRepository
import app.androidrik.rikkahub.ui.context.LocalToaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.Date

private data class RecoverySelection(val version: MemoryFileVersionEntity, val expectedRevision: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileMemoryRecoverySheet(
    storeId: String,
    files: List<MemoryFileEntity>,
    repository: MemoryFileRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    var trashMode by remember { mutableStateOf(true) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<RecoverySelection?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    val currentFile = files.firstOrNull { it.identity == historyId }
    val history = remember(repository, storeId, trashMode, historyId) {
        if (trashMode) repository.observeTrash(storeId)
        else historyId?.let { repository.observeHistory(storeId, it) } ?: flowOf(emptyList())
    }
    val records by key(history) {
        history.collectAsStateWithLifecycle(initialValue = emptyList())
    }

    fun runRecovery(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        errorRes = null
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (selection == null) toaster.show(context.getString(failure.memoryFileErrorRes()))
                else errorRes = failure.memoryFileErrorRes()
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.memory_recovery_title),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = trashMode, enabled = !busy, onClick = { trashMode = true },
                    label = { Text(stringResource(R.string.memory_recovery_tab_trash)) },
                )
                FilterChip(
                    selected = !trashMode, enabled = !busy, onClick = { trashMode = false },
                    label = { Text(stringResource(R.string.memory_recovery_tab_history)) },
                )
            }
            val pickFile = !trashMode && currentFile == null
            LazyColumn(Modifier.heightIn(max = 540.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "intro") {
                    Text(
                        stringResource(R.string.memory_file_recovery_description),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!trashMode && historyId != null) {
                        TextButton(onClick = { historyId = null }) {
                            Text(stringResource(R.string.memory_recovery_back_to_list))
                        }
                        currentFile?.let { Text(it.path, Modifier.padding(horizontal = 16.dp)) }
                    }
                    val emptyText = when {
                        pickFile && files.isEmpty() -> R.string.assistant_page_memory_file_empty
                        !pickFile && records.isEmpty() && trashMode -> R.string.memory_recovery_trash_empty
                        !pickFile && records.isEmpty() -> R.string.memory_recovery_history_empty
                        else -> null
                    }
                    emptyText?.let { Text(stringResource(it), Modifier.padding(16.dp)) }
                }
                if (pickFile) {
                    items(files, key = { it.identity }) { file ->
                        ListItem(modifier = Modifier.clickable(enabled = !busy) { historyId = file.identity }) {
                            Text(file.path)
                        }
                    }
                } else {
                    items(records, key = { it.id }) { row ->
                        ListItem(
                            modifier = Modifier.clickable(enabled = !busy) {
                                val expected = currentFile?.revision.takeUnless { trashMode }
                                val fromTrash = trashMode
                                runRecovery {
                                    val version = repository.readVersion(storeId, row.id)
                                    if (version != null && version.inTrash == fromTrash) {
                                        selection = RecoverySelection(version, expected)
                                    }
                                }
                            },
                            supportingContent = {
                                val date = Date(row.recordedAt)
                                Text("${DateFormat.getDateFormat(context).format(date)} · ${DateFormat.getTimeFormat(context).format(date)}")
                            },
                        ) { Text(row.path) }
                    }
                }
            }
        }
    }

    selection?.let { selected ->
        val version = selected.version
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { if (!busy) confirmDelete = false },
                title = { Text(stringResource(R.string.memory_file_purge_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.memory_file_purge_description, version.path))
                        errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        runRecovery {
                            repository.permanentlyDelete(storeId, version.id)
                            confirmDelete = false
                            selection = null
                        }
                    }) { Text(stringResource(R.string.memory_file_purge_action)) }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { confirmDelete = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { if (!busy) selection = null },
                title = { Text(version.path) },
                text = {
                    Column(
                        Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        errorRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                        if (version.anchorId != null) Text(stringResource(R.string.assistant_page_memory_anchor))
                        Text(version.content)
                        if (version.inTrash) {
                            TextButton(enabled = !busy, onClick = { confirmDelete = true }) {
                                Text(stringResource(R.string.memory_file_purge_action))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy && (version.inTrash || selected.expectedRevision != null),
                        onClick = {
                            runRecovery {
                                if (version.inTrash) repository.restoreTrash(storeId, version.id)
                                else repository.restoreVersion(storeId, version.id, requireNotNull(selected.expectedRevision))
                                selection = null
                                toaster.show(context.getString(R.string.memory_recovery_restored_toast))
                            }
                        },
                    ) { Text(stringResource(R.string.backup_page_restore_confirm_action)) }
                },
                dismissButton = {
                    TextButton(enabled = !busy, onClick = { selection = null }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    }
}
