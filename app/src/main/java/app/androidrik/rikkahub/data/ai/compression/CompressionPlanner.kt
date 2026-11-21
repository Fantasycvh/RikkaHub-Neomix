package app.androidrik.rikkahub.data.ai.compression

import app.androidrik.ai.ui.alignCutIndex
import app.androidrik.ai.ui.hasUnfinishedToolCalls
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

/** 自动压缩触发阈值默认值：占模型窗口的百分比（对齐 deepseek-harness 的 thresholdRatio 0.8） */
const val DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT: Int = 80

/** 自动压缩保留比例默认值（百分比，与 [COMPRESS_RETAIN_RATIO] 的 0.4 一致） */
const val DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT: Int = 40

/** 自动压缩摘要目标 token 默认值 */
const val DEFAULT_AUTO_COMPRESS_TARGET_TOKENS: Int = 2000

/** 阈值百分比钳制范围：低于 50% 会在刚压缩完又立刻触发（抖动），高于 95% 已接近溢出 */
val AUTO_COMPRESS_THRESHOLD_PERCENT_RANGE: IntRange = 50..95

/** 保留比例钳制范围：过低保不住近期细节，过高则压缩收益不足 */
val AUTO_COMPRESS_RETAIN_PERCENT_RANGE: IntRange = 10..80

/** 单块最大消息数（超长时递归二分并行摘要） */
const val COMPRESS_MAX_MESSAGES_PER_CHUNK: Int = 256

/**
 * 最低压缩进度闸（OpenHands `minimum_progress` 同域，设计稿 §2.1）：安全切点回退/轮对齐后，
 * 可压缩前缀 token 占全窗比例低于此值 ⇒ 判「无可压缩」，省一次注定净亏的摘要调用。
 * 0 = 关闸。
 */
const val COMPRESS_MIN_PROGRESS_RATIO: Double = 0.1

/** 保留窗口最少消息数（低于此值不再切分，避免把整个窗口压掉） */
const val COMPRESS_MIN_KEEP_MESSAGES: Int = 2

/**
 * 「保留最近消息数」的输入范围：0 = 不额外指定，只按 token 预算切分（默认下限仍是
 * [COMPRESS_MIN_KEEP_MESSAGES] 条）；上限 999 只是输入框防误触，超出窗口时自然压无可压。
 */
val COMPRESS_KEEP_RECENT_MESSAGES_RANGE: IntRange = 0..999

/**
 * 把用户可选的「保留最近 N 条」合并成实际的 [estimateCompressionPlan] 下限：
 * 0（或不填）回退默认 [COMPRESS_MIN_KEEP_MESSAGES]；负数按 0 处理；
 * 大于 0 时该数值就是**下限**——token 预算仍可能保留更多，但不会少于它。
 */
fun effectiveMinKeep(keepRecentMessages: Int): Int =
    maxOf(COMPRESS_MIN_KEEP_MESSAGES, keepRecentMessages.coerceAtLeast(0))

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
 * 防止把整个窗口都压进摘要。[minKeep] 由调用方按用户「保留最近 N 条」选项换算
 * （见 [effectiveMinKeep]）：它是**下限**，token 预算充足时仍会多留。
 *
 * 候选切点再过**共享安全切点判定**（[alignCutIndex]，设计稿 §2.1）：
 * - 遗留 `ToolCall`/`ToolResult` 配对骑线 ⇒ 回退到调用所在消息（现代 `Tool` part 自含 call+output，
 *   不构成骑线）；
 * - 轮边界对齐（D143）⇒ 切点优先落在真实 USER 轮起点，避免「窗口以助手消息开头」。
 * 回退只会多保留（minKeep 下限仍满足）；回退到 0 = 无合法切点 ⇒ 空计划。
 *
 * 末尾再过**进度闸**（[COMPRESS_MIN_PROGRESS_RATIO]）：可压缩前缀 token 占比不足一成 ≈
 * 一次摘要调用净亏，判「无可压缩」。节省以 token 估算衡量，条数比例仅作参考。
 * 不修改入参；相同路径、内容和预算得到相同切点（确定性 + 前缀稳定性）。
 */
fun estimateCompressionPlan(
    nodes: List<MessageNode>,
    budgetTokens: Int,
    minKeep: Int = COMPRESS_MIN_KEEP_MESSAGES,
    /** 最低压缩进度（可压缩前缀 token / 全窗 token）；0 = 关闸（测试与特殊场景用） */
    minProgressRatio: Double = COMPRESS_MIN_PROGRESS_RATIO,
): CompressionPlan {
    if (nodes.isEmpty()) return CompressionPlan(emptyList(), emptyList())
    val nodeTokens = nodes.map { estimateTokenCount(listOf(it.currentMessage)) }
    val totalTokens = nodeTokens.sum()
    var keepCount = 0
    var keptTokens = 0
    for (index in nodes.indices.reversed()) {
        if (keepCount >= minKeep && keptTokens + nodeTokens[index] > budgetTokens) break
        keptTokens += nodeTokens[index]
        keepCount++
    }
    // 全部保留时无待压缩内容
    if (keepCount >= nodes.size) return CompressionPlan(emptyList(), nodes)
    // 安全切点：遗留配对骑线回退 + 轮边界对齐（只回退不前进，minKeep 下限仍满足）
    val candidateStart = nodes.size - keepCount
    val alignedStart = nodes.map { it.currentMessage }.alignCutIndex(candidateStart)
    if (alignedStart <= 0) return CompressionPlan(emptyList(), nodes)
    // 进度闸：对齐后可压缩前缀不足最低进度 ⇒ 无变化（省一次注定净亏的摘要调用）
    if (minProgressRatio > 0 && totalTokens > 0) {
        val keptAfterAlign = nodeTokens.subList(alignedStart, nodes.size).sum()
        if (totalTokens - keptAfterAlign < totalTokens * minProgressRatio) {
            return CompressionPlan(emptyList(), nodes)
        }
    }
    return CompressionPlan(
        toCompress = nodes.take(alignedStart),
        toKeep = nodes.drop(alignedStart),
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


/**
 * 自动压缩触发判断：占用达到模型窗口的 [thresholdPercent]% 时触发。
 *
 * - window/used 非正时返回 false（无窗口信息或空会话，不触发）
 * - 阈值钳制在 [AUTO_COMPRESS_THRESHOLD_PERCENT_RANGE]，避免误配导致抖动或永不触发
 * - 使用 >=（达到阈值即触发），与 harness 的 thresholdRatio 语义一致
 */
fun shouldAutoCompress(
    usedTokens: Int,
    windowTokens: Int,
    thresholdPercent: Int = DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
): Boolean {
    if (usedTokens <= 0 || windowTokens <= 0) return false
    val threshold = thresholdPercent.coerceIn(AUTO_COMPRESS_THRESHOLD_PERCENT_RANGE)
    return usedTokens.toLong() * 100 >= windowTokens.toLong() * threshold
}

/**
 * 中途压缩的切分点：**压到窗口末尾为止**（含当前这条还没写完的回复），但**进行中的工具批
 * 整批留在原文侧**（设计稿 §2.1）。
 *
 * 判据：中途压缩的触发原因就是「这一批工具的输出把窗口撑爆了」，而那些输出就在当前回复里。
 * 普通压缩的 token 驱动保留策略（[estimateCompressionPlan]）至少留 [COMPRESS_MIN_KEEP_MESSAGES]
 * 条尾部节点原文——用在中途等于把刚撑爆的那段原样留着，压了也白压。故中途压缩不保留任何
 * **已完成**的尾部节点：检查点边界 = 窗口最后一条节点，之后由调用方新开一条回复继续。
 *
 * 例外：首个含**未收束调用**（[hasUnfinishedToolCalls]：待审批 / 执行中 / 结果未写回）的节点起
 * 整批保留——这类调用进摘要会让下一步模型失去调用现场。正常触发点（整批结果回填后）窗口里
 * 没有未收束调用 ⇒ 与旧「压全窗」行为一致。窗口起点就带未收束调用 ⇒ 无可压缩（空计划）。
 *
 * 遗留 `ToolCall`/`ToolResult` 配对骑线时同样回退（[alignCutIndex]，`preferUserTurn = false`——
 * 中途压缩保的是任务连续性，kept 尾就是当前轮，不做轮边界对齐）。
 */
fun midTurnCompressionPlan(nodes: List<MessageNode>): CompressionPlan {
    if (nodes.isEmpty()) return CompressionPlan(emptyList(), emptyList())
    val candidateStart = nodes.indexOfFirst { it.currentMessage.hasUnfinishedToolCalls() }
        .takeIf { it >= 0 } ?: nodes.size
    if (candidateStart <= 0) return CompressionPlan(emptyList(), nodes)
    val alignedStart = nodes.map { it.currentMessage }.alignCutIndex(candidateStart, preferUserTurn = false)
    if (alignedStart <= 0) return CompressionPlan(emptyList(), nodes)
    return CompressionPlan(toCompress = nodes.take(alignedStart), toKeep = nodes.drop(alignedStart))
}

/** 中途压缩的三种决策（[decideMidTurnCompression] 的输出）。 */
enum class MidTurnCompressionDecision {
    /** 达到阈值：可以压。 */
    Compress,

    /** 不压（未达阈值 / 同一条回复已试过 / 本轮已判定无意义）。 */
    Skip,

    /** 本轮不再压缩：压缩后的第一次占用检查又超阈值，说明摘要 + 系统提示词自身就装不下。 */
    GiveUp,
}

/**
 * 中途压缩判定（纯函数，便于单测）。
 *
 * @param stepsSinceCompression 压缩后第几次占用检查（1 = 第一次）；null = 本轮尚未压缩过
 *
 * 判据（上游 `bcc649abc` 的同款语义、本仓落点）：调用方（`ChatService.handleMessageComplete`
 * 的中途压缩闭包）只负责取数与写库，判定全在这里。
 * 判定顺序（与上游 `bcc649abc` 的 pause 判据逐条对齐，**阈值必须先判**）：
 * 1. **同一条回复只试一次**（[replyAlreadyTried]）：压缩失败后还要接着原回复写，若不拦，
 *    下一批工具执行完又会再发一次压缩请求——每批都白烧一次摘要 token，且永远压不成功；
 *    以及本轮已判定无意义（[compressionUseless]）时直接跳过；
 * 2. 未达阈值 ⇒ [MidTurnCompressionDecision.Skip]。**这一步必须在防抖之前**：压缩成功后
 *    占用本来就会掉到阈值以下，若先判防抖会把它误判成「摘要自身超窗」而整轮放弃压缩；
 * 3. **防抖**（[stepsSinceCompression] 为 1 = 压缩后的第一次占用检查）：此时仍达阈值
 *    ⇒ [MidTurnCompressionDecision.GiveUp]。摘要 + 系统提示词自身已接近窗口，再压只会把摘要
 *    越压越薄，本轮该交给优雅停止/硬限收尾。调用方用「压缩后还没检查过」的布尔给出 1，
 *    不用步数相减——生成器压缩后会把步数归零（新回复重新计数），绝对差会变成负数；
 * 4. 其余按常规阈值（[shouldAutoCompress]，与轮前自动压缩同口径）⇒ Compress。
 */
fun decideMidTurnCompression(
    usedTokens: Int,
    windowTokens: Int,
    thresholdPercent: Int,
    replyAlreadyTried: Boolean,
    compressionUseless: Boolean,
    stepsSinceCompression: Int?,
): MidTurnCompressionDecision = when {
    compressionUseless || replyAlreadyTried -> MidTurnCompressionDecision.Skip
    !shouldAutoCompress(usedTokens, windowTokens, thresholdPercent) -> MidTurnCompressionDecision.Skip
    stepsSinceCompression != null && stepsSinceCompression <= 1 -> MidTurnCompressionDecision.GiveUp
    else -> MidTurnCompressionDecision.Compress
}
