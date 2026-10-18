package app.androidrik.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.repository.MemoryHistoryRecord
import app.androidrik.rikkahub.data.repository.MemoryTrashRecord
import app.androidrik.rikkahub.ui.context.LocalToaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
        title = { Text("记忆恢复") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row {
                    TextButton(onClick = { trashMode = true }) { Text("回收站") }
                    TextButton(onClick = { trashMode = false }) { Text("修改历史") }
                }
                Text(
                    "恢复保留原 ID，并生成新版本。旧提案不会因此重新生效。" +
                        "回收站不参与注入或检索；修改历史每条最多保留 20 个事件。",
                    style = MaterialTheme.typography.bodySmall,
                )
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (trashMode) {
                    if (trash.isEmpty() && loadError == null) Text("回收站为空")
                    trash.forEach { record ->
                        Text("${record.snapshot.publicId} · ${record.tombstone.reason}")
                        Text(record.snapshot.content, maxLines = 4)
                        TextButton(enabled = !busy, onClick = { pickedTrash = record }) {
                            Text("查看并恢复")
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
                        TextButton(onClick = { selectedMemory = null }) { Text("返回记忆列表") }
                        Text("当前快照 v${selectedMemory?.revision}；若已变化，恢复会被拒绝。")
                        if (history.isEmpty() && loadError == null) Text("暂无保留历史")
                        history.forEach { record ->
                            Text("v${record.snapshot.revision} · ${record.event.operation} · ${record.event.actor}")
                            Text(record.snapshot.content, maxLines = 4)
                            TextButton(enabled = !busy, onClick = { pickedHistory = record }) {
                                Text("查看并恢复此版本")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } },
    )

    val historyChoice = pickedHistory
    val trashChoice = pickedTrash
    if (historyChoice != null || trashChoice != null) {
        val snapshot = historyChoice?.snapshot ?: requireNotNull(trashChoice).snapshot
        AlertDialog(
            onDismissRequest = { if (!busy) { pickedHistory = null; pickedTrash = null } },
            title = { Text("确认恢复") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(snapshot.content)
                    Text("主题：${snapshot.description}")
                    Text("重要度：${snapshot.importance} · 固化：${snapshot.isAnchor}")
                    Text("这会作为一次新修改保存；不会回退当前读取计数。")
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
                            toaster.show("已恢复为新版本")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            toaster.show("无法恢复：${failure.message}")
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "恢复中…" else "确认恢复") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pickedHistory = null; pickedTrash = null }) { Text("取消") }
            },
        )
    }
}
