package app.androidrik.rikkahub.data.model

import app.androidrik.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * 重生成/分支操作的纯计算部分（从 `ChatService` 抽出以便单测）。
 *
 * 设计见 `docs/design/design-regenerate-scope.md` 与
 * `docs/design/in-progress/design-conversation-tree-branching.md`。要点：
 * - scope 与消息角色**无关**（用户编辑后既可能想"只修正这一段"、也可能想"从这里重做"）；
 * - 目标节点（新回复归属的节点）= **用户消息 → 它的回复（下一节点）**、
 *   **助手消息 → 自身**；两者的共同点是"基线不含目标节点"。
 *
 * 树形分支 P1：寻址一律经 [pathNodes]（激活路径）；下标运算作用于线性路径，树形化后依旧成立。
 * scope B 改为**非破坏兄弟分支**：不再截断删除，激活叶子收回到分支点
 * （= 路径上目标节点的前一个），旧回复与其下游留在旧兄弟子树（§四.2/§四.3）。
 * 撤销机制随之移除（无数据丢失，撤销失去存在理由）。
 */

/** 目标节点下标（路径相对）：用户消息重做其回复（下一节点），助手消息重做自身。 */
fun regenerateReplyNodeIndex(nodeIndex: Int, isUserMessage: Boolean): Int =
    if (isUserMessage) nodeIndex + 1 else nodeIndex

/**
 * 被点击消息的节点下标（**路径相对**；找不到返回 null）。
 *
 * 用 message **id** 而非对象相等定位——`UIMessage` 经流式合并/可视化变换后对象可能不是同一个实例。
 */
fun Conversation.nodeIndexOfMessage(messageId: Uuid): Int? =
    pathNodes().indexOfFirst { node -> node.messages.any { it.id == messageId } }
        .takeIf { it >= 0 }

/** 被点击消息**之后**的节点数（激活路径上的下游；也是"将开新时间线"的规模提示）。 */
fun Conversation.followingCount(messageId: Uuid): Int {
    val index = nodeIndexOfMessage(messageId) ?: return 0
    return (pathNodes().size - index - 1).coerceAtLeast(0)
}

/** 被点击消息之后各节点**当前选中**的消息（「导出后继续」时导出的内容）。 */
fun Conversation.followingMessages(messageId: Uuid): List<UIMessage> {
    val index = nodeIndexOfMessage(messageId) ?: return emptyList()
    return pathNodes().drop(index + 1).map { it.currentMessage }
}

/**
 * 生成基线范围（路径相对）：`0..<(目标节点)`（不含目标节点本身 → 新回复成为它的候选）。
 *
 * 目标节点已在末尾或越界（尚无回复）时返回 **null**：调用方走全量基线，
 * 从而允许自动压缩，与旧的「重生成最后一条用户消息」行为一致。
 */
fun regenerateBaselineRange(replyNodeIndex: Int, nodeCount: Int): ClosedRange<Int>? =
    if (replyNodeIndex in 0..<nodeCount) 0..<(replyNodeIndex) else null

/**
 * **剪枝**：移除 [targetNodeId] 节点及其全部后代（破坏性，调用方负责确认；§四.2 删除语义）。
 *
 * 激活叶子落在被剪子树内时收回：优先父节点（路径=root→父），父也没有（剪的是根且叶子在其内）
 * 则落到剩余森林的「最近更新叶子」。目标不存在返回 null（无操作）。
 * 不触碰文件——树内剪枝天然文件安全（文件清理只在会话级删除，§一.3 事实 2）。
 */
fun Conversation.pruneSubtree(targetNodeId: Uuid): Conversation? {
    val target = messageNodes.firstOrNull { it.id == targetNodeId } ?: return null
    val prunedIds = messageNodes.subtreeIdsOf(targetNodeId)
    val remaining = messageNodes.filterNot { it.id in prunedIds }
    val newLeaf = when {
        activeLeafNodeId == null || activeLeafNodeId !in prunedIds -> activeLeafNodeId
        else -> target.parentId ?: remaining.latestLeafId()
    }
    return copy(messageNodes = remaining, activeLeafNodeId = newLeaf)
}
