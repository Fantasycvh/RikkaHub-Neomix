package app.androidrik.rikkahub.utils

import app.androidrik.rikkahub.data.ai.PromptSegment
import app.androidrik.rikkahub.data.ai.PromptSegmentKind

/**
 * 上下文容量面板的派生数据（纯计算，便于单测）。
 *
 * 口径见 `docs/roadmap.md` 2026-10-02「上下文容量面板」条目：
 * - **组成明细用差值法**：总占用可能来自服务端 usage，而分段只有本地估算 —— 逐项独立求和
 *   一定加不平。故 `系统与记忆 = 总占用 − Σ(单列项)`，让「各项相加 = 总占用」**结构性成立**。
 * - **轮数预测**：分母 = 问答对，分子只取历史正文，每轮取中位数，样本不足则不预测。
 */
data class ContextCapacity(
    val usedTokens: Int,
    val windowTokens: Int,
    val remainingTokens: Int,
    val fraction: Float,
    /** 是否达到共用的警戒阈值（[CONTEXT_USAGE_WARNING_RATIO]）——标题旁状态点与输入栏共用这一处判断。 */
    val isWarning: Boolean,
    /**
     * 是否用了**本地兜底**的总占用（本地已知总量 > 服务端锚点）。
     *
     * 首次回复前拿不到 usage，而本仓的本地占用估算**只数消息**，不含「每轮都发」的固定开销
     * （系统提示、注入、工具 schema）——于是出现「合计 0 而明细里有 4.3K 工具 schema」这种
     * 自相矛盾。面板因此取两者较大者，并把这个事实显式标出来（首次回复后 usage 一到即校准）。
     */
    val usesLocalFloor: Boolean,
    /** 有效历史消息（[PromptSegmentKind.CHAT_HISTORY] 合计）。 */
    val historyTokens: Int,
    val toolSchemaTokens: Int,
    val toolResultTokens: Int,
    val checkpointTokens: Int,
    /** 系统设定与记忆 = 总占用 − 单列项合计（差值法，见类注释）。 */
    val systemMemoryTokens: Int,
    /** 本次发送的历史条数（压缩检查点计 1 条）；与 [totalMessageCount] 对比可见裁剪量。 */
    val sentHistoryCount: Int,
    val totalMessageCount: Int,
    /** 每轮 token 的中位数（抗离群）；无历史时为 null。 */
    val medianTokensPerRound: Int?,
    /** 还能容纳约多少轮；样本不足（< [MIN_ROUNDS_FOR_ESTIMATE]）时为 null。 */
    val estimatedRounds: Int?,
    val roundSampleCount: Int,
) {
    /** 单列项（工具 schema / 工具输出 / 压缩检查点）合计——差值法的减数之一。 */
    val itemizedTokens: Int get() = toolSchemaTokens + toolResultTokens + checkpointTokens
}

/** 轮数预测的最小样本轮数：更少时给出的「平均每轮」会被单条长消息带偏。 */
const val MIN_ROUNDS_FOR_ESTIMATE = 3

/**
 * 由「占用 + 拼装分段」算出面板要显示的一切。
 *
 * ⚠️ **新增单列行时必须把减数加进 [itemizedSum]**（全仓唯一一处），否则面板立刻自相矛盾
 * （各项相加 ≠ 总占用）。
 */
fun computeContextCapacity(
    usedTokens: Int,
    windowTokens: Int,
    segments: List<PromptSegment>,
    sentHistoryCount: Int,
    totalMessageCount: Int,
    /** 本地只读组装出的总 token（[app.androidrik.rikkahub.data.ai.PromptInspection.totalTokens]）。 */
    localTotalTokens: Int = 0,
): ContextCapacity {
    val history = segments.filter { it.kind == PromptSegmentKind.CHAT_HISTORY }
    val historyTokens = history.sumOf { it.tokens }
    val toolSchemaTokens = segments.sumOf { if (it.kind == PromptSegmentKind.TOOL_SCHEMA) it.tokens else 0 }
    val toolResultTokens = segments.sumOf { if (it.kind == PromptSegmentKind.TOOL_RESULT) it.tokens else 0 }
    val checkpointTokens = segments.sumOf { if (it.kind == PromptSegmentKind.COMPRESSION_CHECKPOINT) it.tokens else 0 }

    val itemizedSum = historyTokens + toolSchemaTokens + toolResultTokens + checkpointTokens
    // 总占用取「服务端锚点」与「本地已知总量」的较大者：usage 缺失（首次回复前）时，
    // 本地口径才知道系统提示/注入/工具 schema 这些固定开销，否则明细会超过合计。
    val effectiveUsed = maxOf(usedTokens, localTotalTokens)
    val systemMemoryTokens = (effectiveUsed - itemizedSum).coerceAtLeast(0)

    val fraction = if (windowTokens > 0) (effectiveUsed.toFloat() / windowTokens).coerceIn(0f, 1f) else 0f
    val perRound = perRoundTokens(history)
    val median = medianOrNull(perRound)
    val remainingTokens = (windowTokens - effectiveUsed).coerceAtLeast(0)

    return ContextCapacity(
        usedTokens = effectiveUsed,
        usesLocalFloor = effectiveUsed > usedTokens,
        windowTokens = windowTokens,
        remainingTokens = remainingTokens,
        fraction = fraction,
        isWarning = windowTokens > 0 && fraction >= CONTEXT_USAGE_WARNING_RATIO,
        historyTokens = historyTokens,
        toolSchemaTokens = toolSchemaTokens,
        toolResultTokens = toolResultTokens,
        checkpointTokens = checkpointTokens,
        systemMemoryTokens = systemMemoryTokens,
        sentHistoryCount = sentHistoryCount,
        totalMessageCount = totalMessageCount,
        medianTokensPerRound = median,
        estimatedRounds = if (median != null && median > 0 && perRound.size >= MIN_ROUNDS_FOR_ESTIMATE) {
            remainingTokens / median
        } else {
            null
        },
        roundSampleCount = perRound.size,
    )
}

/**
 * 按 **user 消息**切轮（每条 user 消息起一轮，工具循环不另算一轮），返回每轮 token。
 *
 * 只统计历史正文段：工具输出（[PromptSegmentKind.TOOL_RESULT]）波动极大、工具 schema 是顶层
 * 字段、压缩检查点是一次性开销，都不属于「每轮都会涨的量」，混进来会把预测带偏。
 */
internal fun perRoundTokens(history: List<PromptSegment>): List<Int> {
    val rounds = mutableListOf<Int>()
    var current: Int? = null
    for (segment in history) {
        // 防御式过滤：调用方若把完整分段列表传进来，非历史段也不能计入「每轮」
        if (segment.kind != PromptSegmentKind.CHAT_HISTORY) continue
        if (segment.role == ROLE_USER) {
            current?.let { rounds += it }
            current = segment.tokens
        } else {
            current = (current ?: 0) + segment.tokens
        }
    }
    current?.let { rounds += it }
    return rounds
}

/** 中位数（偶数个取中间两个的平均）——抗离群：一条 30K 的长粘贴不该代表「每轮」。 */
internal fun medianOrNull(values: List<Int>): Int? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

private const val ROLE_USER = "user"
