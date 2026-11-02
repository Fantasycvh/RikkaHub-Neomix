package app.androidrik.rikkahub.ui.pages.taskcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.db.dao.AgentRunDAO
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.data.task.ConversationTaskSnapshot
import app.androidrik.rikkahub.data.task.ConversationTaskTree
import app.androidrik.rikkahub.data.task.TaskCard
import app.androidrik.rikkahub.data.task.TaskCenter
import app.androidrik.rikkahub.data.task.TaskKey
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationTasksSheet(conversationId: Uuid, onDismiss: () -> Unit) {
    val dao = koinInject<AgentRunDAO>()
    val workspace = koinInject<WorkspaceRepository>()
    val chat = koinInject<ChatService>()
    val center = koinInject<TaskCenter>()
    val flow = remember(conversationId, dao, workspace) {
        ConversationTaskTree(dao, workspace).observe(conversationId.toString()) { chat.getTurnOwner(conversationId) }
    }
    val snapshot by flow.collectAsStateWithLifecycle(ConversationTaskSnapshot())
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    fun open(runId: String) { onDismiss(); nav.navigate(Screen.AgentRunDetail(runId)) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        ConversationTasksContent(snapshot, onOpen = ::open,
            onCancel = { key -> scope.launch {
                if (center.cancel(key) != app.androidrik.rikkahub.data.task.TaskCancelResult.REQUESTED)
                    toaster.show("任务已结束或不可取消")
            } },
            onDecision = { request, approved ->
                if (!SubagentApprovalQueue.resolve(request, approved)) toaster.show("审批已失效，请刷新任务")
            })
    }
}

@Composable
fun ConversationTasksContent(
    snapshot: ConversationTaskSnapshot,
    onOpen: (String) -> Unit,
    onCancel: (TaskKey) -> Unit,
    onDecision: (SubagentApprovalQueue.Request, Boolean) -> Unit,
) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "heading") { Text("本会话任务", style = MaterialTheme.typography.titleLarge) }
            if (snapshot.runs.isEmpty() && snapshot.commands.isEmpty()) item(key = "empty") {
                Text("当前没有关联任务", modifier = Modifier.padding(vertical = 16.dp))
            }
            items(snapshot.runs, key = { it.task.key.wire }) { branch ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TaskRow(branch.task, onOpen = { onOpen((branch.task.key as TaskKey.Agent).runId) },
                            onCancel = { onCancel(branch.task.key) })
                        if (branch.tools.isNotEmpty()) Text("工具：${branch.tools.joinToString(" → ")}",
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        branch.approvals.forEach { request ->
                            Column {
                                Text("待批准：${request.tool.toolName}", style = MaterialTheme.typography.labelLarge)
                                Text(request.tool.input.take(1000), style = MaterialTheme.typography.bodySmall,
                                    maxLines = 6, overflow = TextOverflow.Ellipsis)
                                Row {
                                    TextButton(onClick = {
                                        onDecision(request, true)
                                    }) { Text("批准") }
                                    TextButton(onClick = {
                                        onDecision(request, false)
                                    }) { Text("拒绝") }
                                }
                            }
                        }
                        branch.commands.forEach { command ->
                            Column(Modifier.padding(start = 16.dp)) {
                                TaskRow(command, onCancel = { onCancel(command.key) })
                            }
                        }
                    }
                }
            }
            items(snapshot.commands, key = { it.key.wire }) { command ->
                TaskRow(command, onCancel = { onCancel(command.key) })
            }
            item(key = "footer") { Text("任务结束后不会自动续跑主助手", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 16.dp)) }
        }
}

@Composable
private fun TaskRow(task: TaskCard, onOpen: (() -> Unit)? = null, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        task.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Row {
            Text(taskStateLabel(task.state), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            if (onOpen != null) TextButton(onClick = onOpen) { Text("详情") }
            if (task.canCancel) TextButton(onClick = onCancel) { Text("停止") }
        }
    }
}
