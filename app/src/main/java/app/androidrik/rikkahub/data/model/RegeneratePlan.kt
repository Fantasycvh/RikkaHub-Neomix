package app.androidrik.rikkahub.data.model

import app.androidrik.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * 重生成的纯计算部分（从 `ChatService` 抽出以便单测）。
 *
 * 设计见 `docs/design-regenerate-scope.md`。要点：
 * - scope 与消息角色**无关**（用户编辑后既可能想"只修正这一段"、也可能想"从这里重做"）；
 * - 目标节点（新回复归属的节点）= **用户消息 → 它的回复（下一节点）**、
 *   **助手消息 → 自身**；两者的共同点是"基线不含目标节点"，故新回复总是作为该节点的
 *   **候选**追加（与 `updateCurrentMessages` 的索引写回一致）。
 */

/** 目标节点下标：用户消息重做其回复（下一节点），助手消息重做自身。 */
fun regenerateReplyNodeIndex(nodeIndex: Int, isUserMessage: Boolean): Int =
    if (isUserMessage) nodeIndex + 1 else nodeIndex

/** 一次截断的计划（scope B 用）。 */
data class TruncationPlan(
    /** 被移除的尾部节点。 */
    val removedNodes: List<MessageNode>,
    /** 截断后的节点数（撤销时 `take` 到此长度）。 */
    val sizeAfterTruncation: Int,
)

/**
 * 被点击消息的节点下标（找不到返回 null）。
 *
 * 用 message **id** 而非对象相等定位——`UIMessage` 经流式合并/可视化变换后对象可能不是同一个实例。
 */
fun Conversation.nodeIndexOfMessage(messageId: Uuid): Int? =
    messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
        .takeIf { it >= 0 }

/** 被点击消息**之后**的节点数（scope B 会移除这些；也是"将移除 N 条"）。 */
fun Conversation.followingCount(messageId: Uuid): Int {
    val index = nodeIndexOfMessage(messageId) ?: return 0
    return (messageNodes.size - index - 1).coerceAtLeast(0)
}

/** 被点击消息之后各节点**当前选中**的消息（「导出后继续」时导出的内容）。 */
fun Conversation.followingMessages(messageId: Uuid): List<UIMessage> {
    val index = nodeIndexOfMessage(messageId) ?: return emptyList()
    return messageNodes.drop(index + 1).map { it.currentMessage }
}

/**
 * scope B 的截断计划：截断到「被点击的消息」为止（保留它本身）、移除其后全部节点。
 *
 * 无尾部时返回 null（无破坏性，无需确认/撤销）。
 */
fun Conversation.truncationPlan(messageId: Uuid): TruncationPlan? {
    val index = nodeIndexOfMessage(messageId) ?: return null
    val removed = messageNodes.drop(index + 1)
    if (removed.isEmpty()) return null
    return TruncationPlan(removedNodes = removed, sizeAfterTruncation = index + 1)
}

/**
 * 生成基线范围：`0..<(目标节点)`（不含目标节点本身 → 新回复成为它的候选）。
 *
 * 目标节点已在末尾或越界（尚无回复）时返回 **null**：调用方走全量基线，
 * 从而允许自动压缩，与旧的「重生成最后一条用户消息」行为一致。
 */
fun regenerateBaselineRange(replyNodeIndex: Int, nodeCount: Int): ClosedRange<Int>? =
    if (replyNodeIndex in 0..<nodeCount) 0..<(replyNodeIndex) else null

/**
 * 撤销截断：`take(sizeAfterTruncation)` 去掉「截断后新产生的节点」，再拼回被移除的尾巴。
 *
 * 为什么用 `take` 而非直接追加：截断后生成可能已产出新回复（甚至 #1812 半截草稿），
 * 而**撤销的语义是「回到截断前」**，故这些新内容一并撤掉是正确行为。
 */
fun restoreAfterTruncation(
    currentNodes: List<MessageNode>,
    plan: TruncationPlan,
): List<MessageNode> =
    currentNodes.take(plan.sizeAfterTruncation) + plan.removedNodes
