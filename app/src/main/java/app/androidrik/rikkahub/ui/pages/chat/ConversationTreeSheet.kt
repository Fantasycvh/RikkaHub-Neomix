package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import app.androidrik.rikkahub.data.model.NodeTreeInfo
import app.androidrik.rikkahub.data.model.healMissingParents
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
 * 稀疏会话用 [sparseTree]（SQL 侧抽取的轻量树信息）渲染，打开面板不再全量载入正文；
 * 这类行没有正文，分叉/剪枝入口隐藏（需先加载该节点）。
 *
 * 宿主纪律：行表在组合期一次性展开成 (row, depth)，但用 LazyColumn 只组合可见行
 * （数千节点一次性组合会 ANR）；面板内无 TextField，不涉 IME 宿主坑族。
 */
@Composable
fun ConversationTreeSheet(
    conversation: Conversation,
    sparseTree: List<NodeTreeInfo>,
    onDismissRequest: () -> Unit,
    onSwitchToNode: (Uuid) -> Unit,
    onForkBranchAt: (UIMessage) -> Unit,
    onDeleteBranch: (MessageNode) -> Unit,
    switching: Boolean = false,
) {
    val maxSheetHeight = LocalWindowInfo.current.containerDpSize.height * 0.7f
    val displayRows = remember(conversation.messageNodes, conversation.isSparse, sparseTree) {
        if (conversation.isSparse) {
            sparseTree.map {
                TreeRowData(it.id, it.parentId, it.role, it.preview, it.candidateCount, node = null)
            }
        } else {
            conversation.messageNodes.healMissingParents().map { node ->
                val message = node.currentMessage
                TreeRowData(
                    id = node.id,
                    parentId = node.parentId,
                    role = message.role.name,
                    preview = message.toText().trim(),
                    candidateCount = node.messages.size,
                    node = node,
                )
            }
        }
    }
    val byId = remember(displayRows) { displayRows.associateBy { it.id } }
    val childrenByParent = remember(displayRows) { displayRows.groupBy { it.parentId } }
    val rows = remember(childrenByParent) {
        buildList {
            val visited = mutableSetOf<Uuid>()
            fun dfs(parentId: Uuid?, depth: Int) {
                val children = childrenByParent[parentId].orEmpty()
                // 只有真分叉才增加层级：长对话单链不缩进，避免 59 层把内容挤出屏幕
                val childDepth = if (children.size > 1) depth + 1 else depth
                children.forEach { row ->
                    if (!visited.add(row.id)) return@forEach
                    add(row to depth)
                    dfs(row.id, childDepth)
                }
            }
            dfs(null, 0)
        }
    }
    val pathIds = remember(conversation.activeLeafNodeId, byId) {
        buildSet {
            var current = conversation.activeLeafNodeId?.let { byId[it] }
            while (current != null && add(current.id)) current = current.parentId?.let { byId[it] }
        }
    }
    val subtreeMessageCounts = remember(displayRows) { subtreeCandidateCounts(displayRows) }

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
            if (switching) {
                // 分支切换中（稀疏会话可能触发全量补齐，约 0.5 s）：留在面板里给反馈
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.chat_tree_switching),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (rows.isEmpty()) {
                // 稀疏会话的首帧：树信息查询还没回来（全量会话不会走到这里）
                if (conversation.isSparse) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(24.dp)
                            .size(24.dp),
                    )
                }
            } else {
                // 长会话（数千节点）用 Lazy 只组合可见行：Column+verticalScroll 会一次性组合
                // 全部行（每行含两个 IconButton），7000 节点实测 ANR。面板内无 TextField，不涉 IME 宿主坑。
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(rows.size, key = { rows[it].first.id }) { index ->
                        val (row, depth) = rows[index]
                        TreeRow(
                            row = row,
                            depth = depth,
                            onPath = row.id in pathIds,
                            isLeaf = conversation.activeLeafNodeId == row.id,
                            subtreeMessageCount = subtreeMessageCounts[row.id] ?: row.candidateCount,
                            onSwitch = { if (!switching) onSwitchToNode(row.id) },
                            onFork = row.node?.let { node -> { if (!switching) onForkBranchAt(node.currentMessage) } },
                            onDelete = row.node?.let { node -> { if (!switching) onDeleteBranch(node) } },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 树行数据。`node` 非空表示正文已加载（分叉/剪枝可用）；稀疏树信息构造的行没有正文。
 */
private data class TreeRowData(
    val id: Uuid,
    val parentId: Uuid?,
    val role: String?,
    val preview: String,
    val candidateCount: Int,
    val node: MessageNode?,
)

/** 子树消息数：自底向上一次累加（行按创建序，子节点必在父节点之后），避免逐节点遍历子树。 */
private fun subtreeCandidateCounts(rows: List<TreeRowData>): Map<Uuid, Int> {
    val counts = HashMap<Uuid, Int>(rows.size)
    rows.asReversed().forEach { row ->
        val subtree = (counts[row.id] ?: 0) + row.candidateCount
        counts[row.id] = subtree
        row.parentId?.let { counts[it] = (counts[it] ?: 0) + subtree }
    }
    return counts
}

@Composable
private fun TreeRow(
    row: TreeRowData,
    depth: Int,
    onPath: Boolean,
    isLeaf: Boolean,
    subtreeMessageCount: Int,
    onSwitch: () -> Unit,
    onFork: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
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
            .padding(start = treeIndentDp(depth).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                when (row.role) {
                    MessageRole.USER.name -> R.string.agent_run_role_user
                    MessageRole.ASSISTANT.name -> R.string.agent_run_role_assistant
                    MessageRole.SYSTEM.name -> R.string.agent_run_role_system
                    MessageRole.TOOL.name -> R.string.agent_run_role_tool
                    // 稀疏行：选中消息首段非文本等情况下 SQL 抽不到角色
                    else -> R.string.agent_run_role_tool
                }
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (isLeaf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 34.dp),
        )
        Text(
            text = row.preview.ifBlank { "[...]" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.candidateCount > 1) {
            Text(
                text = stringResource(R.string.chat_tree_candidates_count, row.candidateCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (subtreeMessageCount > row.candidateCount) {
            Text(
                text = stringResource(R.string.chat_tree_subtree_count, subtreeMessageCount - row.candidateCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
        if (onFork != null) {
            IconButton(onClick = onFork, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = HugeIcons.Time02,
                    contentDescription = stringResource(R.string.chat_tree_fork_here),
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onDelete != null) {
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
}

/**
 * 树行缩进：前 6 层每层 16dp，更深层每层仅 3dp 且最多再算 18 层，
 * 总缩进 ≤ 8 + 96 + 54 = 158dp，避免深层分叉把行内容挤出屏幕。
 */
private fun treeIndentDp(depth: Int): Int {
    val full = depth.coerceAtMost(6)
    val deep = (depth - 6).coerceIn(0, 18)
    return 8 + full * 16 + deep * 3
}
