package app.androidrik.rikkahub.utils

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation

// 模型上下文长度的默认兜底值（未设置时的 256K）
const val DEFAULT_CONTEXT_LENGTH: Int = 256 * 1024

// 上下文占用警告阈值：占用达到模型窗口的 80% 时进度条变红（对齐 deepseek-harness compaction-basic
// 的触发阈值；此处仅作 UI 警告色，不触发任何压缩动作）
const val CONTEXT_USAGE_WARNING_RATIO: Float = 0.8f

// 获取模型上下文长度：contextLength 优先，未设置时返回默认 256K
fun Model.effectiveContextLength(): Int = contextLength ?: DEFAULT_CONTEXT_LENGTH

// 解析 "256k"/"1M"/"2.5m"/"262144" 等格式为 token 数；非法返回 null
fun parseContextLengthInput(text: String): Int? {
    val normalized = text.trim().lowercase().replace(" ", "")
    if (normalized.isEmpty()) return null
    return when {
        normalized.endsWith("m") -> ((normalized.dropLast(1).toDoubleOrNull() ?: return null) * 1_000_000).toLong()
        normalized.endsWith("k") -> ((normalized.dropLast(1).toDoubleOrNull() ?: return null) * 1_000).toLong()
        else -> normalized.toLongOrNull() ?: return null
    }.takeIf { it > 0 && it <= 100_000_000 }?.toInt()
}

// 格式化上下文长度：1_000_000 -> "1M"，256_000 -> "256K"，null -> 空串（UI 用 placeholder 提示默认值）
fun formatContextLength(tokens: Int?): String = when {
    tokens == null -> ""
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000_000 -> "%.1fM".format(tokens / 1_000_000.0)
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    tokens >= 1_000 -> "%.1fK".format(tokens / 1_000.0)
    else -> tokens.toString()
}

/**
 * 会话发送窗口的 token 占用估算（「已使用上下文」显示与将来的自动压缩触发判断共用）。
 *
 * 真实 usage 优先：最后一条 assistant 消息的 `usage.promptTokens` 恰是「上一次实际发给模型的
 * 完整上下文 token 数」，比任何本地估算都准。fork 暂无压缩检查点，该值直接可信；
 * 无 usage 时回退本地估算（[estimateTokenCount]）。
 */
fun Conversation.estimateWindowTokens(model: Model?): Int {
    val lastAssistant = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val usageTokens = lastAssistant?.usage?.promptTokens ?: 0
    return if (usageTokens > 0 && lastAssistant != null) {
        usageTokens
    } else {
        estimateTokenCount(currentMessages)
    }
}

/**
 * 本地估算消息 token 数：CJK 字符按 1.5 字符/token，其余按 4 字符/token（粗略，用于无 usage 时的占用判断）。
 * 工具输出纳入估算（fork 工具输出上限 32KB，不纳入会低估 agent 对话占用）。
 */
fun estimateTokenCount(messages: List<UIMessage>): Int {
    var cjk = 0
    var other = 0
    for (message in messages) {
        for (part in message.parts) {
            val text = when (part) {
                is UIMessagePart.Text -> part.text
                is UIMessagePart.Tool -> part.output.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                else -> continue
            }
            for (ch in text) {
                if (ch in '\u4e00'..'\u9fff' || ch in '\u3040'..'\u30ff' || ch in '\uac00'..'\ud7af') {
                    cjk++
                } else {
                    other++
                }
            }
        }
    }
    return (cjk / 1.5 + other / 4.0).toInt().coerceAtLeast(0)
}
