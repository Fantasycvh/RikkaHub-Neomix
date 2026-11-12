package app.androidrik.rikkahub.data.model

import app.androidrik.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * 重生成的纯计算部分（从 `ChatService` 抽出以便单测）。
 *
 * 设计见 `docs/design/design-regenerate-scope.md`。要点：
 * - scope 与消息角色**无关**（用户编辑后既可能想"只修正这一段"、也可能想"从这里重做"）；
 * - 目标节点（新回复归属的节点）= **用户消息 → 它的回复（下一节点）**、
 *   **助手消息 → 自身**；两者的共同点是"基线不含目标节点"，故新回复总是作为该节点的
 *   **候选**追加（与 [mergeGenerationMessages] 的位置兜底规则一致）。
 *
 * 树形分支 P0（design-conversation-tree-branching.md §六.2）：节点寻址一律经
 * [pathNodes]（路径相对）而非裸 messageNodes——P0 两者相同，P1 列表含分支后
 * 只有路径相对寻址仍然正确。下标运算作用于**线性路径**，树形化后依旧成立。
 */

/** 目标节点下标（路径相对）：用户消息重做其回复（下一节点），助手消息重做自身。 */
fun regenerateReplyNodeIndex(nodeIndex: Int, isUserMessage: Boolean): Int =
    if (isUserMessage) nodeIndex + 1 else nodeIndex

/** 一次截断的计划（scope B 用）。 */
data class TruncationPlan(
    /** 被移除的尾部节点。 */
    val removedNodes: List<MessageNode>,
    /** 截断后的节点数（撤销时 `take` 到此长度；锚点缺失时的回退）。 */
    val sizeAfterTruncation: Int,
    /**
     * 截断锚点 = 保留的最后一个节点 id（树形分支 P0 新增）。
     * 撤销还原优先按它定位——「回到截断前」的语义锚在节点身份上，不锚在数量上。
     */
    val anchorNodeId: Uuid? = null,
)

/**
 * 被点击消息的节点下标（**路径相对**；找不到返回 null）。
 *
 * 用 message **id** 而非对象相等定位——`UIMessage` 经流式合并/可视化变换后对象可能不是同一个实例。
 */
fun Conversation.nodeIndexOfMessage(messageId: Uuid): Int? =
    pathNodes().indexOfFirst { node -> node.messages.any { it.id == messageId } }
        .takeIf { it >= 0 }

/** 被点击消息**之后**的节点数（scope B 会移除这些；也是"将移除 N 条"）。 */
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
 * scope B 的截断计划：截断到「被点击的消息」为止（保留它本身）、移除其后全部节点。
 *
 * 无尾部时返回 null（无破坏性，无需确认/撤销）。
 */
fun Conversation.truncationPlan(messageId: Uuid): TruncationPlan? {
    val path = pathNodes()
    val index = nodeIndexOfMessage(messageId) ?: return null
    val removed = path.drop(index + 1)
    if (removed.isEmpty()) return null
    return TruncationPlan(
        removedNodes = removed,
        sizeAfterTruncation = index + 1,
        anchorNodeId = path[index].id,
    )
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
 * 撤销截断：回到「锚点节点为止」的前缀，再拼回被移除的尾巴。
 *
 * 为什么用锚点 id 而非纯数量：截断后生成可能已产出新回复（甚至 #1812 半截草稿），
 * 而**撤销的语义是「回到截断前」**，故这些新内容一并撤掉是正确行为。锚点定位在
 * 节点身份上，比「take 到某长度」更抗中间态漂移（锚点找不到时回退旧的数量语义）。
 *
 * 注：P1 起 scope B 改为非破坏分支，本函数随撤销机制一并移除（设计稿 §四.3）。
 */
fun restoreAfterTruncation(
    currentNodes: List<MessageNode>,
    plan: TruncationPlan,
): List<MessageNode> {
    val anchorIndex = plan.anchorNodeId?.let { anchorId ->
        currentNodes.indexOfFirst { it.id == anchorId }
    } ?: -1
    val keep = if (anchorIndex >= 0) anchorIndex + 1 else plan.sizeAfterTruncation
    return currentNodes.take(keep) + plan.removedNodes
}