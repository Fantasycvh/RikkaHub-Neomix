package app.androidrik.rikkahub.data.ai.compression

import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.utils.estimateTokenCount

/** 压缩摘要目标 token（注入提示词的 {target_tokens}） */
const val COMPRESS_TARGET_TOKENS: Int = 2000

/**
 * 保留窗口占模型窗口的比例（对齐 deepseek-harness compaction-basic 的 retainRatio 语义）。
 *
 * **注意**：harness 默认 0.16，但那是为编码 agent 调优的；聊天/角色扮演场景保留比过低会
 * 把有意义的对话内容摘要掉，故 fork 取更保守的 0.4（design §3.2 建议 0.3~0.4 起）。
 */
const val COMPRESS_RETAIN_RATIO: Double = 0.4

/** 单块最大消息数（超长时递归二分并行摘要） */
const val COMPRESS_MAX_MESSAGES_PER_CHUNK: Int = 256

/** 保留窗口最少消息数（低于此值不再切分，避免把整个窗口压掉） */
const val COMPRESS_MIN_KEEP_MESSAGES: Int = 2

data class CompressionPlan(
    /** 待压缩的早期节点（按原顺序） */
    val toCompress: List<MessageNode>,
    /** 保留为原文的尾部节点（按原顺序） */
    val toKeep: List<MessageNode>,
) {
    val shouldCompress: Boolean get() = toCompress.isNotEmpty()
}

/**
 * 计算压缩切分点：从尾部向前按 token 累计，预算 = [budgetTokens]，至少保留 [minKeep] 条。
 *
 * 对齐 harness 的 token 驱动保留策略（而非按条数），预算不足时至少留 minKeep 条，
 * 防止把整个窗口都压进摘要。不修改入参。
 */
fun estimateCompressionPlan(
    nodes: List<MessageNode>,
    budgetTokens: Int,
    minKeep: Int = COMPRESS_MIN_KEEP_MESSAGES,
): CompressionPlan {
    if (nodes.isEmpty()) return CompressionPlan(emptyList(), emptyList())
    var keepCount = 0
    var keptTokens = 0
    for (node in nodes.asReversed()) {
        val nodeTokens = estimateTokenCount(listOf(node.currentMessage))
        if (keepCount >= minKeep && keptTokens + nodeTokens > budgetTokens) break
        keptTokens += nodeTokens
        keepCount++
    }
    // 全部保留时无待压缩内容
    if (keepCount >= nodes.size) return CompressionPlan(emptyList(), nodes)
    return CompressionPlan(
        toCompress = nodes.take(nodes.size - keepCount),
        toKeep = nodes.takeLast(keepCount),
    )
}

/** 按 [maxPerChunk] 递归二分分块（超长输入并行摘要用）；不超过上限时单块返回 */
fun splitIntoChunks(
    messages: List<app.androidrik.ai.ui.UIMessage>,
    maxPerChunk: Int = COMPRESS_MAX_MESSAGES_PER_CHUNK,
): List<List<app.androidrik.ai.ui.UIMessage>> {
    if (messages.size <= maxPerChunk) return listOf(messages)
    val mid = messages.size / 2
    return splitIntoChunks(messages.subList(0, mid), maxPerChunk) +
        splitIntoChunks(messages.subList(mid, messages.size), maxPerChunk)
}
