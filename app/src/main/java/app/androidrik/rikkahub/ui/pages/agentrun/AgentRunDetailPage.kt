package app.androidrik.rikkahub.ui.pages.agentrun

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.ui.components.nav.BackButton
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.components.richtext.MarkdownBlock
import app.androidrik.rikkahub.ui.theme.CustomColors
import app.androidrik.rikkahub.utils.plus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.Stop
import org.koin.androidx.compose.koinViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DetailTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatDetailTime(ts: Long?): String =
    ts?.let {
        DetailTimeFormatter.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    } ?: "—"

@Composable
fun AgentRunDetailPage(
    runId: String,
    vm: AgentRunDetailVM = koinViewModel(),
) {
    val run by vm.observeRun(runId).collectAsStateWithLifecycle(null)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var parentConversationId by remember { mutableStateOf<String?>(null) }
    var parentExists by remember { mutableStateOf(false) }

    // 父会话存在性只在 run 变化时查一次（无外键的纯列 ⇒ 悬空是正常情形，必须判）
    LaunchedEffect(run?.parentConversationId) {
        val parent = run?.parentConversationId
        parentConversationId = parent
        parentExists = parent != null && vm.parentConversationExists(parent)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = run?.profileName?.ifBlank { null }
                            ?: stringResource(R.string.agent_runs_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    // 取消（仅运行中）与删除（随时）
                    if (run?.status == AgentRunEntity.STATUS_RUNNING ||
                        run?.status == AgentRunEntity.STATUS_PENDING ||
                        run?.status == AgentRunEntity.STATUS_AWAITING_APPROVAL
                    ) {
                        IconButton(
                            onClick = {
                                run?.id?.let { vm.cancel(kotlin.uuid.Uuid.parse(it)) }
                            },
                        ) {
                            Icon(
                                HugeIcons.Stop,
                                contentDescription = stringResource(R.string.agent_run_cancel),
                            )
                        }
                    }
                    run?.let { r ->
                        IconButton(onClick = { vm.delete(kotlin.uuid.Uuid.parse(r.id)) }) {
                            Icon(
                                HugeIcons.Delete02,
                                contentDescription = stringResource(R.string.agent_run_delete),
                            )
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val current = run
        if (current == null) {
            // 已删除或尚未加载
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.agent_runs_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding + PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    RunHeader(
                        run = current,
                        // 只有"确有父会话"时才给入口（对话内派发 + 会话还在）
                        onOpenConversation = parentConversationId
                            ?.takeIf { parentExists }
                            ?.let { parent -> { navController.navigate(Screen.Chat(parent)) } },
                    )
                }
                item {
                    errorLabel(current.error)?.let {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CustomColors.cardColorsOnSurfaceContainer,
                        ) {
                            Text(
                                text = it,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                val messages = decodeTranscript(current.transcript)
                val pendingApprovals = if (current.status == AgentRunEntity.STATUS_AWAITING_APPROVAL) {
                    messages.flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }
                        .filter { it.isPending }
                } else {
                    emptyList()
                }
                if (pendingApprovals.isNotEmpty()) {
                    item {
                        ApprovalCard(
                            tools = pendingApprovals,
                            onDecide = { toolCallId, approved ->
                                if (!vm.resolveApproval(toolCallId, approved)) {
                                    // 无人在等 = 已超时或进程重启（run 其实已经没了）
                                    toaster.show(context.getString(R.string.agent_run_approval_expired))
                                }
                            },
                        )
                    }
                }
                itemsIndexed(messages) { _, message ->
                    TranscriptMessageCard(message)
                }
            }
        }
    }
}

/**
 * 等待批准卡片（设计 §十一）：逐条列出入参 + 批准/拒绝。
 *
 * **为什么是逐条而不是"全部批准"**：一批里往往混着不同风险的动作（读一个文件 + 跑一段 shell），
 * 而"全部批准"会把它们绑成一个决定。通知栏那两个按钮是"批量"（小屏没法逐条），
 * 详情页这张卡才是精确控制的地方——两者落在同一条队列上。
 */
@Composable
private fun ApprovalCard(
    tools: List<UIMessagePart.Tool>,
    onDecide: (toolCallId: String, approved: Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.agent_run_approval_title),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            Text(
                text = stringResource(R.string.agent_run_approval_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tools.forEach { tool ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = tool.toolName,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = tool.input.ifBlank { "{}" },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onDecide(tool.toolCallId, true) }) {
                            Text(stringResource(R.string.subagent_approval_approve))
                        }
                        TextButton(onClick = { onDecide(tool.toolCallId, false) }) {
                            Text(
                                text = stringResource(R.string.subagent_approval_deny),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 解码轨迹 JSON；坏数据（旧版本/手工改动）降级为空列表而非崩溃。 */
private fun decodeTranscript(json: String): List<UIMessage> =
    runCatching {
        app.androidrik.rikkahub.utils.JsonInstant.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<UIMessage>()),
            json,
        )
    }.getOrDefault(emptyList())

@Composable
private fun RunHeader(run: AgentRunEntity, onOpenConversation: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = statusLabel(run.status),
                    style = MaterialTheme.typography.titleMedium,
                    color = when (run.status) {
                        AgentRunEntity.STATUS_RUNNING,
                        AgentRunEntity.STATUS_AWAITING_APPROVAL,
                            -> MaterialTheme.colorScheme.primary
                        AgentRunEntity.STATUS_COMPLETED -> MaterialTheme.colorScheme.tertiary
                        AgentRunEntity.STATUS_FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = formatDetailTime(run.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            run.resultSummary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.agent_run_field_result),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = listOfNotNull(
                    run.modelName,
                    formatDetailTime(run.startedAt),
                    formatDetailTime(run.finishedAt),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 反向入口：run → 派发它的对话。子代理的中间过程不进父上下文，账本是唯一线索，
            // 所以这条链在两个方向都要能走（对话里的工具卡已有「查看运行」）。
            onOpenConversation?.let { open ->
                TextButton(onClick = open, contentPadding = PaddingValues(0.dp)) {
                    Icon(HugeIcons.Message02, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.agent_run_back_to_conversation))
                }
            }
        }
    }
}

@Composable
private fun TranscriptMessageCard(message: UIMessage) {
    val roleLabel = when (message.role) {
        MessageRole.USER -> stringResource(R.string.agent_run_role_user)
        MessageRole.ASSISTANT -> stringResource(R.string.agent_run_role_assistant)
        MessageRole.SYSTEM -> stringResource(R.string.agent_run_role_system)
        else -> stringResource(R.string.agent_run_role_tool)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = roleLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> MarkdownBlock(part.text)
                    is UIMessagePart.Tool -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "⚙ ${part.toolName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (part.isExecuted) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }
}
