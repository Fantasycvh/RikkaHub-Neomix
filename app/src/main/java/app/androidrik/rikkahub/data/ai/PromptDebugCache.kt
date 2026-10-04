package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.utils.estimateTokenCount

/**
 * 前缀分叉诊断的数据层（借鉴华灯 `PromptDebugCache`，见 peer-apps-benchmark §八.1）。
 *
 * 缓存每次生成**实际发送**给模型的完整消息列表（按会话隔离，保留上一次与本次）：
 * 前缀缓存从第一个 token 起连续匹配，缓存长度停在某个值不再增长 ⇒ 该位置存在
 * 每轮变化的内容。对比上次与本次请求、定位第一条分叉消息并给出可命中前缀的
 * token 估算，把「缓存为什么断」变成可观察事实。
 *
 * 与拼装预览（PromptInspector，单次请求逐段归因）互补：本层做**跨请求差分**。
 * 调试用内存缓存，进程被杀即失效，无需持久化。
 */
object PromptDebugCache {
    private data class DebugEntry(
        val current: List<UIMessage>,
        val previous: List<UIMessage>?,
    )

    /** 每条 entry 保存两份完整请求（含工具输出），不设上限会随会话数无限增长 */
    private const val MAX_ENTRIES = 8

    // LinkedHashMap accessOrder + synchronized 实现 LRU；并发调用仅来自生成与诊断页
    private val cache = object : LinkedHashMap<String, DebugEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DebugEntry>): Boolean =
            size > MAX_ENTRIES
    }

    /** 记录一次实际请求（工具循环的每一步都算一次请求；分叉诊断对步进同样成立）。 */
    @Synchronized
    fun record(key: String, messages: List<UIMessage>) {
        if (messages.isEmpty()) return
        val old = cache[key]
        cache[key] = DebugEntry(messages, old?.current)
    }

    /** 返回 (本次, 上一次)；上一次为 null 表示该会话只记录到一次请求。 */
    @Synchronized
    fun latest(key: String): Pair<List<UIMessage>, List<UIMessage>?>? =
        cache[key]?.let { it.current to it.previous }
}

/** 诊断结果：前缀一致段 + 分叉点信息。 */
data class PrefixDivergence(
    /** 两条请求从头一致的消息条数（这些内容可命中前缀缓存） */
    val commonPrefixMessages: Int,
    /** [commonPrefixMessages] 的 token 估算（视图打开时计算，调用方自担线程） */
    val commonPrefixTokens: Int,
    val previousSize: Int,
    val currentSize: Int,
    /** 本次请求中第一条不一致消息的预览（role + 首行）；null = 两条请求完全一致 */
    val divergedMessagePreview: String?,
)

/** 诊断：定位两条请求的第一条分叉消息。纯函数，token 估算由回调注入（便于单测）。 */
fun diagnosePrefixDivergence(
    previous: List<UIMessage>,
    current: List<UIMessage>,
    tokenEstimator: (List<UIMessage>) -> Int = ::estimateTokenCount,
): PrefixDivergence {
    val n = minOf(previous.size, current.size)
    var i = 0
    while (i < n && messageKey(previous[i]) == messageKey(current[i])) i++
    return PrefixDivergence(
        commonPrefixMessages = i,
        commonPrefixTokens = if (i == 0) 0 else tokenEstimator(previous.take(i)),
        previousSize = previous.size,
        currentSize = current.size,
        divergedMessagePreview = current.getOrNull(i)?.let { msg ->
            val firstLine = msg.parts.firstNotNullOfOrNull { part ->
                when (part) {
                    is app.androidrik.ai.ui.UIMessagePart.Text -> part.text.lineSequence().firstOrNull { it.isNotBlank() }
                    is app.androidrik.ai.ui.UIMessagePart.Tool -> "[工具输出] ${part.toolName}"
                    is app.androidrik.ai.ui.UIMessagePart.Reasoning -> "[思考]"
                    else -> null
                }
            }.orEmpty()
            "${roleLabel(msg.role)}：${firstLine.take(60)}"
        },
    )
}

private fun roleLabel(role: MessageRole): String = when (role) {
    MessageRole.SYSTEM -> "系统"
    MessageRole.USER -> "用户"
    MessageRole.ASSISTANT -> "助手"
    MessageRole.TOOL -> "工具"
}

/** 消息的稳定内容签名（role + parts 全量序列化；诊断用途，不追求可读）。 */
private fun messageKey(message: UIMessage): String =
    message.role.name + ":" + message.parts.joinToString("|") { it.toString() }
