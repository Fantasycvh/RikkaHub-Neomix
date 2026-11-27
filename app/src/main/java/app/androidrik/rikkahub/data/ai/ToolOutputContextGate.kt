package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.utils.estimateTokens

// 上下文闸 A：per-call 动态限幅的收缩档（design-tool-context-gate §4）。
// 窗口占用 ≥65% 收到 16K、≥80% 收到 8K；基础档维持 32K 不变。
private const val TOOL_OUTPUT_MID_CHARS = 16 * 1024
private const val TOOL_OUTPUT_MIN_CHARS = 8 * 1024

/** 基础档限幅（handler 的截断逻辑共用同一上限）。 */
internal const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024

/**
 * 协议信封固定开销（A4）：`{"role":"tool","tool_call_id":"...","name":"...","content":"..."}`
 * 在短工具结果上占比可达 10–25%，纳入载荷计量防偏乐观。
 */
internal const val TOOL_WIRE_ENVELOPE_OVERHEAD_TOKENS = 48

/**
 * 唯一容量来源（design-tool-output-budget A5）：所有工具输出预算/预留/截断共用此只读函数，
 * 避免各模块各自推导 reserve 导致边界漂移。
 */
internal fun effectiveInputCapacity(
    windowTokens: Int,
    reserveTokens: Int = MIN_REPLY_TOKENS,
): Int = (windowTokens.coerceAtLeast(0) - reserveTokens.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * 结构预检（design-tool-output-budget A6）：在调用 BPE tokenizer 之前 O(1) 判断「必然超」，
 * 防止对数十 MB 原始输出跑精确分词引发 CPU/内存尖峰。
 */
internal fun definitelyExceedsTokenLimit(text: String, tokenLimit: Int): Boolean {
    if (tokenLimit <= 0) return text.isNotEmpty()
    // 保守上界：字符数 ≥ limit × 4（ASCII 约 4 字符/token，CJK 更密 ~1.5 字符/token）⇒ 必然超限
    return text.length.toLong() >= tokenLimit.toLong() * 4L
}

/** 最终到达模型的完整工具结果载荷计量（A4 + §8 B9 HTTP 体字节口径）。 */
internal data class WirePayloadMeasure(
    /** 估算发送 token（含协议信封与 JSON 包装开销） */
    val wireTokens: Int,
    /** 估算 HTTP 请求体字节数（UTF-8 编码 + 信封字节） */
    val wireBytes: Long,
    /** 是否因触发结构预检（A6）而跳过精确 BPE 分词 */
    val fastPathExceeded: Boolean,
)

/**
 * 载荷计量唯一入口（design-tool-output-budget A4 + B9）：
 * 同时产出「发送 token（含信封）」与「HTTP 体字节」双口径，供预算结清与观测对照使用。
 */
internal fun measureWirePayload(
    toolName: String,
    toolCallId: String,
    output: List<UIMessagePart>,
    tokenLimitHint: Int? = null,
): WirePayloadMeasure {
    val envelopeBytes = 64L + toolName.length + toolCallId.length
    var totalTextChars = 0L
    var totalBytes = envelopeBytes
    val texts = mutableListOf<String>()

    for (part in output) {
        when (part) {
            is UIMessagePart.Text -> {
                texts += part.text
                totalTextChars += part.text.length
                totalBytes += part.text.toByteArray(Charsets.UTF_8).size
            }
            is UIMessagePart.Image -> totalBytes += part.url.length.toLong() + 64L
            is UIMessagePart.Audio -> totalBytes += part.url.length.toLong() + 64L
            is UIMessagePart.Video -> totalBytes += part.url.length.toLong() + 64L
            is UIMessagePart.Document -> totalBytes += part.url.length.toLong() + part.fileName.length.toLong() + 64L
            else -> totalBytes += 64L
        }
    }

    val fastExceeded = tokenLimitHint != null &&
        tokenLimitHint > 0 &&
        totalTextChars >= tokenLimitHint.toLong() * 4L

    val contentTokens = if (fastExceeded) {
        (totalTextChars / 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else {
        texts.sumOf { estimateTokens(it) }
    }
    val wireTokens = contentTokens + TOOL_WIRE_ENVELOPE_OVERHEAD_TOKENS
    return WirePayloadMeasure(
        wireTokens = wireTokens,
        wireBytes = totalBytes,
        fastPathExceeded = fastExceeded,
    )
}

/** 上下文闸 A 的纯函数核（边界测试直接打这个，不用拉起整条生成流）。 */
internal fun toolOutputBudgetFor(estimatedTokens: Int, window: Int): Int {
    val ratio = estimatedTokens.toFloat() / window.coerceAtLeast(1)
    return when {
        ratio >= 0.80f -> TOOL_OUTPUT_MIN_CHARS
        ratio >= 0.65f -> TOOL_OUTPUT_MID_CHARS
        else -> MAX_TOOL_OUTPUT_CHARS
    }
}

/**
 * 上下文闸 B 的硬限信号：卸载后仍超 90%。由 [enforceContextBudget] 抛出，调用方转成优雅停止。
 */
internal class ContextGateHardStop(
    val usedTokens: Int,
    val windowTokens: Int,
) : IllegalStateException("Context window budget exceeded after offloading old tool outputs: $usedTokens / $windowTokens")
