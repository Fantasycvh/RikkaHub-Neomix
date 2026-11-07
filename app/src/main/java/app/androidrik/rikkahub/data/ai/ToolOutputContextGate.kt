package app.androidrik.rikkahub.data.ai

// 上下文闸 A：per-call 动态限幅的收缩档（design-tool-context-gate §4）。
// 窗口占用 ≥65% 收到 16K、≥80% 收到 8K；基础档维持 32K 不变。
private const val TOOL_OUTPUT_MID_CHARS = 16 * 1024
private const val TOOL_OUTPUT_MIN_CHARS = 8 * 1024

/** 基础档限幅（handler 的截断逻辑共用同一上限）。 */
internal const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024

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
 * 上下文闸 B 的硬限信号：卸载后仍超 90%。由 [GenerationHandler.enforceContextBudget] 抛出，调用方转成优雅停止。
 */
internal class ContextGateHardStop(
    val usedTokens: Int,
    val windowTokens: Int,
) : IllegalStateException("Context window budget exceeded after offloading old tool outputs: $usedTokens / $windowTokens")
