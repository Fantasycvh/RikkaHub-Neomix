package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole

/**
 * #1906：把一条历史消息采集为可引用的快照。
 *
 * 三条刻意的取舍：
 * - **只采集 [UIMessagePart.Text]**：思考内容与工具调用/结果不进引用——它们随上下文窗口变化，
 *   引用它们既无意义也会让引用块难以阅读。
 * - **不递归**：被引用消息自带的引用不会带进新引用（避免无限套娃与文本膨胀）。
 * - **快照固定**：返回的是文本副本，源消息后续编辑/重新生成不会改变它。
 *
 * @param maxChars 快照上限（UTF-16 单元数）。超长消息只引用开头部分，避免一条引用把
 *   请求撑爆；截断点若落在代理对中间会回退一个单元，防止产出半个 emoji（无效字符）。
 * @return 无可引用文本时返回 null（调用方据此不显示引用入口）
 */
fun UIMessage.toQuotedMessage(maxChars: Int = DEFAULT_QUOTE_MAX_CHARS): UIMessageAnnotation.QuotedMessage? {
    val text = parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n\n") { it.text }
        .trim()
    if (text.isEmpty()) return null
    return UIMessageAnnotation.QuotedMessage(
        messageId = id,
        role = role,
        content = text.takeSafely(maxChars),
        createdAt = createdAt,
    )
}

/** 引用快照上限：足够承载常见段落，又不会让单条引用吃掉可观上下文。 */
const val DEFAULT_QUOTE_MAX_CHARS = 8_000

/**
 * 安全截断：不切断 UTF-16 代理对。
 *
 * `String.take` 按 Char 计数，恰好切在 emoji/增补平面字符的中间时会留下**孤立的高代理**——
 * 它在显示与序列化时都是无效字符（JSON 里会变成问号或乱码）。回退一个单元即得完整字符。
 */
internal fun String.takeSafely(maxChars: Int): String {    if (length <= maxChars) return this
    if (maxChars <= 0) return ""
    val cut = maxChars
    val lastKept = this[cut - 1]
    // 高代理收尾 ⇒ 该对的后半被切掉了，退回一个单元
    return if (lastKept.isHighSurrogate()) substring(0, cut - 1) else substring(0, cut)
}

/**
 * 引用块的发送侧展开：把引用快照转成模型可读的文本前缀。
 *
 * 刻意**不用** `trimIndent()` 包装：它的缩进基准会把插值进来的 [content] 一起算进去，
 * 多行引用里只要有一行顶格，`<quoted_message>` 那行就会残留模板缩进（内容本身也可能被吃掉缩进）。
 * 这里显式拼接，正文原样保留。
 */
fun UIMessageAnnotation.QuotedMessage.toPromptBlock(): String {
    val author = when (role) {
        MessageRole.USER -> "user"
        MessageRole.ASSISTANT -> "assistant"
        MessageRole.SYSTEM -> "system"
        MessageRole.TOOL -> "tool"
    }
    return "<quoted_message author=\"$author\">\n$content\n</quoted_message>"
}
