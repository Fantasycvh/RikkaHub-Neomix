package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.healMissingParents
import app.androidrik.rikkahub.data.model.pathNodes
import app.androidrik.rikkahub.data.model.subtreeIdsOf
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Time02
import kotlin.uuid.Uuid

/**
 * 会话树视图（树形分支 P2，设计稿 §7.3）：ModalBottomSheet 缩进树形列表。
 *
 * 节点行 = 角色标签 + 文本摘要 + 徽标（候选数 / 子树规模）；点击 = 切到该子树时间线；
 * 行内操作 = 树内分叉（[onForkBranchAt]）/ 剪枝（[onDeleteBranch]，确认弹窗由页面层负责）。
 * 激活路径上的行高亮，当前叶子用主色标出。
 *
 * 宿主纪律：Column + verticalScroll，节点树在组合期一次性展开成 (node, depth) 行表
 * （节点集小、且与 LazyList 的 TextField 宿主坑族无关）。
 */
@Composable
fun ConversationTreeSheet(
    conversation: Conversation,
    onDismissRequest: () -> Unit,
    onSwitchToNode: (Uuid) -> Unit,
    onForkBranchAt: (UIMessage) -> Unit,
    onDeleteBranch: (MessageNode) -> Unit,
) {
    val maxSheetHeight = LocalWindowInfo.current.containerDpSize.height * 0.7f
    val nodes = remember(conversation.messageNodes) {
        conversation.messageNodes.healMissingParents()
    }
    val childrenByParent = remember(nodes) { nodes.groupBy { it.parentId } }
    val rows = remember(childrenByParent) {
        buildList {
            val visited = mutableSetOf<Uuid>()
            fun dfs(parentId: Uuid?, depth: Int) {
                childrenByParent[parentId]?.forEach { node ->
                    if (!visited.add(node.id)) return@forEach
                    add(node to depth)
                    dfs(node.id, depth + 1)
                }
            }
            dfs(null, 0)
        }
    }
    val pathIds = remember(conversation.activeLeafNodeId, nodes) {
        conversation.pathNodes().map { it.id }.toSet()
    }
    val subtreeMessageCounts = remember(nodes) {
        nodes.associate { node ->
            node.id to nodes.subtreeIdsOf(node.id).sumOf { id ->
                nodes.first { it.id == id }.messages.size
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismissRequest, contentWindowInsets = { WindowInsets(0) }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.chat_page_tree_view),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEach { (node, depth) ->
                    TreeRow(
                        node = node,
                        depth = depth,
                        onPath = node.id in pathIds,
                        isLeaf = conversation.activeLeafNodeId == node.id,
                        subtreeMessageCount = subtreeMessageCounts[node.id] ?: node.messages.size,
                        onSwitch = { onSwitchToNode(node.id) },
                        onFork = { onForkBranchAt(node.currentMessage) },
                        onDelete = { onDeleteBranch(node) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TreeRow(
    node: MessageNode,
    depth: Int,
    onPath: Boolean,
    isLeaf: Boolean,
    subtreeMessageCount: Int,
    onSwitch: () -> Unit,
    onFork: () -> Unit,
    onDelete: () -> Unit,
) {
    val message = node.currentMessage
    val rowBackground = if (onPath) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(rowBackground)
            .clickable(onClick = onSwitch)
            .padding(start = (8 + depth * 16).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                when (node.role) {
                    MessageRole.USER -> R.string.agent_run_role_user
                    MessageRole.ASSISTANT -> R.string.agent_run_role_assistant
                    MessageRole.SYSTEM -> R.string.agent_run_role_system
                    else -> R.string.agent_run_role_tool
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (isLeaf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 34.dp),
        )
        Text(
            text = message.toText().trim().ifBlank { "[...]" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (node.messages.size > 1) {
            Text(
                text = stringResource(R.string.chat_tree_candidates_count, node.messages.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (subtreeMessageCount > node.messages.size) {
            Text(
                text = stringResource(R.string.chat_tree_subtree_count, subtreeMessageCount - node.messages.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onFork, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = HugeIcons.Time02,
                contentDescription = stringResource(R.string.chat_tree_fork_here),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = HugeIcons.Delete01,
                contentDescription = stringResource(R.string.delete),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
