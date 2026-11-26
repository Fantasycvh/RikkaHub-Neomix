package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.compression.CompressionProgress
import app.androidrik.rikkahub.data.ai.tools.local.TodoEntry
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.pathNodes
import app.androidrik.rikkahub.ui.components.message.PlanBar
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import app.androidrik.rikkahub.ui.theme.LocalMotionTokens
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import kotlin.uuid.Uuid

internal sealed interface ChatStatusDockItem {
    data class PendingInteraction(val count: Int, val targetNodeId: Uuid) : ChatStatusDockItem
    data class Compressing(val progress: CompressionProgress) : ChatStatusDockItem
    data class Plan(val todos: List<TodoEntry>) : ChatStatusDockItem
}

internal data class ChatStatusDockModel(
    val items: List<ChatStatusDockItem>,
) {
    val primary: ChatStatusDockItem? get() = items.firstOrNull()
    val secondary: List<ChatStatusDockItem> get() = if (items.size > 1) items.drop(1) else emptyList()
    val hiddenCount: Int get() = secondary.size
}

/** 扫描当前激活路径上等待用户确认/回答的交互数与最近节点 id。 */
internal fun findPendingInteractionSummary(conversation: Conversation): Pair<Int, Uuid?> {
    var count = 0
    var targetNodeId: Uuid? = null
    for (node in conversation.pathNodes()) {
        val message = node.messages.getOrNull(node.selectIndex) ?: continue
        val pendingInMessage = message.parts.count { it is UIMessagePart.Tool && it.isPending }
        if (pendingInMessage > 0) {
            count += pendingInMessage
            targetNodeId = node.id
        }
    }
    return count to targetNodeId
}

/**
 * 输入栏上方多状态条归约（参考 dock_stack 模式）：
 * 按「待确认交互 > 后台压缩 > Todo 计划」排序；多条并存时默认折叠次要条，防止挤占聊天视口。
 */
internal fun resolveChatStatusDock(
    pendingInteractionCount: Int,
    pendingTargetNodeId: Uuid?,
    compressionProgress: CompressionProgress?,
    todos: List<TodoEntry>,
): ChatStatusDockModel {
    val items = buildList {
        if (pendingInteractionCount > 0 && pendingTargetNodeId != null) {
            add(ChatStatusDockItem.PendingInteraction(pendingInteractionCount, pendingTargetNodeId))
        }
        if (compressionProgress != null) {
            add(ChatStatusDockItem.Compressing(compressionProgress))
        }
        if (todos.isNotEmpty()) {
            add(ChatStatusDockItem.Plan(todos))
        }
    }
    return ChatStatusDockModel(items = items)
}

@Composable
internal fun ChatStatusDock(
    model: ChatStatusDockModel,
    onJumpToPending: (Uuid) -> Unit,
    onCancelCompression: () -> Unit,
    renderCompressionBar: @Composable (CompressionProgress, () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = model.primary ?: return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val motion = LocalMotionTokens.current

    // 次要项归零时自动复位展开态，避免下次多条出现时意外全展开
    LaunchedEffect(model.hiddenCount) {
        if (model.hiddenCount == 0 && expanded) {
            expanded = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (model.hiddenCount > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.chat_status_dock_collapse)
                        } else {
                            stringResource(R.string.chat_status_dock_more, model.hiddenCount)
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(motion.collapseMs)) + fadeIn(tween(motion.quickMs)),
                exit = shrinkVertically(tween(motion.collapseMs)) + fadeOut(tween(motion.quickMs)),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    model.secondary.forEach { item ->
                        ChatStatusDockEntry(
                            item = item,
                            onJumpToPending = onJumpToPending,
                            onCancelCompression = onCancelCompression,
                            renderCompressionBar = renderCompressionBar,
                        )
                    }
                }
            }
        }
        ChatStatusDockEntry(
            item = primary,
            onJumpToPending = onJumpToPending,
            onCancelCompression = onCancelCompression,
            renderCompressionBar = renderCompressionBar,
        )
    }
}

@Composable
private fun ChatStatusDockEntry(
    item: ChatStatusDockItem,
    onJumpToPending: (Uuid) -> Unit,
    onCancelCompression: () -> Unit,
    renderCompressionBar: @Composable (CompressionProgress, () -> Unit) -> Unit,
) {
    when (item) {
        is ChatStatusDockItem.PendingInteraction -> PendingInteractionBar(
            count = item.count,
            onView = { onJumpToPending(item.targetNodeId) },
        )
        is ChatStatusDockItem.Compressing -> renderCompressionBar(item.progress, onCancelCompression)
        is ChatStatusDockItem.Plan -> PlanBar(todos = item.todos)
    }
}

@Composable
private fun PendingInteractionBar(
    count: Int,
    onView: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Icon(
                imageVector = HugeIcons.BubbleChatQuestion,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.chat_status_dock_pending_title, count),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onView,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_status_dock_pending_view),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
