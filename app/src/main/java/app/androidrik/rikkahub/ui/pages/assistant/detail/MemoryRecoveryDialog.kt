package app.androidrik.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import java.time.Instant
import java.time.ZoneId
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.repository.MemoryHistoryRecord
import app.androidrik.rikkahub.data.repository.MemoryTrashRecord
import app.androidrik.rikkahub.ui.context.LocalToaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun MemorySnapshotMetadata(snapshot: MemoryEntity) {
    val metadata = memorySnapshotPresentation(snapshot)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(memoryKindLabel(metadata.kind), style = MaterialTheme.typography.labelMedium)
        if (metadata.kind == MemoryKind.TASK) {
            Text(
                stringResource(when (requireNotNull(metadata.taskStatus)) {
                    MemoryTaskStatus.OPEN -> R.string.assistant_page_memory_task_open
                    MemoryTaskStatus.DONE -> R.string.assistant_page_memory_task_done
                    MemoryTaskStatus.DROPPED -> R.string.assistant_page_memory_task_dropped
                }),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                if (metadata.dueAt != null) {
                    stringResource(
                        R.string.assistant_page_memory_task_due,
                        Instant.ofEpochMilli(metadata.dueAt).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                    )
                } else stringResource(R.string.assistant_page_memory_task_without_due),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Additive recovery entry; the existing editor, suggestion details and approval flow stay intact. */
@Composable
internal fun MemoryRecoveryDialog(
    storeId: String,
    memories: List<AssistantMemory>,
    vm: AssistantDetailVM,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var trashMode by remember { mutableStateOf(true) }
    var selectedMemory by remember { mutableStateOf<AssistantMemory?>(null) }
    var history by remember { mutableStateOf<List<MemoryHistoryRecord>>(emptyList()) }
    var trash by remember { mutableStateOf<List<MemoryTrashRecord>>(emptyList()) }
    var pickedHistory by remember { mutableStateOf<MemoryHistoryRecord?>(null) }
    var pickedTrash by remember { mutableStateOf<MemoryTrashRecord?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(storeId, selectedMemory?.id, refresh) {
        history = emptyList()
        trash = emptyList()
        loadError = null
        try {
            trash = vm.memoryTrash(storeId)
            selectedMemory?.let { history = vm.memoryHistory(storeId, it.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: "Unable to load recovery records"
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.memory_recovery_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row {
                    TextButton(onClick = { trashMode = true }) { Text(stringResource(R.string.memory_recovery_tab_trash)) }
                    TextButton(onClick = { trashMode = false }) { Text(stringResource(R.string.memory_recovery_tab_history)) }
                }
                Text(
                    stringResource(R.string.memory_recovery_description),
                    style = MaterialTheme.typography.bodySmall,
                )
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (trashMode) {
                    if (trash.isEmpty() && loadError == null) Text(stringResource(R.string.memory_recovery_trash_empty))
                    trash.forEach { record ->
                        Text("${record.snapshot.publicId} · ${record.tombstone.reason}")
                        MemorySnapshotMetadata(record.snapshot)
                        Text(record.snapshot.content, maxLines = 4)
                        TextButton(enabled = !busy, onClick = { pickedTrash = record }) {
                            Text(stringResource(R.string.memory_recovery_view_and_restore))
                        }
                    }
                } else {
                    if (selectedMemory == null) {
                        memories.forEach { memory ->
                            TextButton(onClick = { selectedMemory = memory }) {
                                Text("${memory.id} · ${memory.description.ifBlank { memory.content.take(50) }}")
                            }
                        }
                    } else {
                        TextButton(onClick = { selectedMemory = null }) { Text(stringResource(R.string.memory_recovery_back_to_list)) }
                        Text(stringResource(R.string.memory_recovery_snapshot_revision, requireNotNull(selectedMemory).revision))
                        if (history.isEmpty() && loadError == null) Text(stringResource(R.string.memory_recovery_history_empty))
                        history.forEach { record ->
                            Text("v${record.snapshot.revision} · ${record.event.operation} · ${record.event.actor}")
                            MemorySnapshotMetadata(record.snapshot)
                            Text(record.snapshot.content, maxLines = 4)
                            TextButton(enabled = !busy, onClick = { pickedHistory = record }) {
                                Text(stringResource(R.string.memory_recovery_view_restore_this))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )

    val historyChoice = pickedHistory
    val trashChoice = pickedTrash
    if (historyChoice != null || trashChoice != null) {
        val snapshot = historyChoice?.snapshot ?: requireNotNull(trashChoice).snapshot
        AlertDialog(
            onDismissRequest = { if (!busy) { pickedHistory = null; pickedTrash = null } },
            title = { Text(stringResource(R.string.backup_page_restore_confirm_action)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    MemorySnapshotMetadata(snapshot)
                    Text(snapshot.content)
                    Text(stringResource(R.string.memory_recovery_topic, snapshot.description))
                    Text(stringResource(R.string.memory_recovery_importance_anchor, snapshot.importance, snapshot.isAnchor))
                    Text(stringResource(R.string.memory_recovery_confirm_note))
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val target = selectedMemory
                    busy = true
                    scope.launch {
                        try {
                            if (historyChoice != null) {
                                val restored = vm.restoreMemoryRevision(
                                    storeId, requireNotNull(target), historyChoice.snapshot.revision,
                                )
                                selectedMemory = restored
                            } else {
                                val tombstone = requireNotNull(trashChoice).tombstone
                                vm.restoreTrashedMemory(storeId, tombstone.memoryId, tombstone.revision)
                            }
                            pickedHistory = null
                            pickedTrash = null
                            refresh++
                            toaster.show(context.getString(R.string.memory_recovery_restored_toast))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            toaster.show(context.getString(R.string.memory_recovery_restore_failed, failure.message))
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) stringResource(R.string.memory_recovery_restoring) else stringResource(R.string.backup_page_restore_confirm_action)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pickedHistory = null; pickedTrash = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}
