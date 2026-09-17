package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1906 引用快照的采集与序列化。
 *
 * 重点在**边界**：只采集文本、不递归、截断不切代理对。这些性质决定了引用卡片显示什么
 * 以及发送时送给模型什么，静默变化不会被 UI 暴露出来。
 */
class MessageQuoteTest {

    private fun message(
        role: MessageRole = MessageRole.ASSISTANT,
        vararg parts: UIMessagePart,
    ) = UIMessage(role = role, parts = parts.toList())

    @Test
    fun `collects text parts only`() {
        val quote = message(
            MessageRole.USER,
            UIMessagePart.Text("hello"),
            UIMessagePart.Reasoning("chain of thought"),
            UIMessagePart.Image("file:///a.png"),
            UIMessagePart.Text("world"),
        ).toQuotedMessage()
        assertEquals("hello\n\nworld", quote?.content)
        assertEquals(MessageRole.USER, quote?.role)
    }

    @Test
    fun `reasoning and tool output never leak into the snapshot`() {
        val quote = message(
            MessageRole.ASSISTANT,
            UIMessagePart.Reasoning("secret plan"),
            UIMessagePart.Tool(
                toolCallId = "c1",
                toolName = "search",
                input = "{}",
                output = listOf(UIMessagePart.Text("tool output")),
            ),
        ).toQuotedMessage()
        // 整条消息没有 Text part ⇒ 不可引用（菜单里不显示「引用」）
        assertNull(quote)
    }

    @Test
    fun `blank text is not quotable`() {
        assertNull(message(MessageRole.USER, UIMessagePart.Text("   \n  ")).toQuotedMessage())
    }

    @Test
    fun `snapshot does not include nested quotes`() {
        val inner = UIMessageAnnotation.QuotedMessage(
            messageId = kotlin.uuid.Uuid.random(),
            role = MessageRole.USER,
            content = "inner quoted text",
        )
        val outer = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("outer")),
            annotations = listOf(inner),
        ).toQuotedMessage()
        // 快照只取本方文本：引用里的引用不会带进来（避免无限套娃）
        assertEquals("outer", outer?.content)
    }

    @Test
    fun `snapshot is a frozen copy that ignores later edits`() {
        val source = message(MessageRole.ASSISTANT, UIMessagePart.Text("original"))
        val quote = source.toQuotedMessage()!!
        // 源消息之后被改写：已采下的引用仍是当时那份文本（引用块不应随源漂移）
        source.copy(parts = listOf(UIMessagePart.Text("edited")))
        assertEquals("original", quote.content)
    }

    @Test
    fun `long text is truncated to the limit`() {
        val quote = message(MessageRole.USER, UIMessagePart.Text("x".repeat(20_000))).toQuotedMessage()
        assertEquals(DEFAULT_QUOTE_MAX_CHARS, quote!!.content.length)
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        // emoji 是代理对（2 个 UTF-16 单元）；上限落在对中间时必须退一格，
        // 否则快照尾是一个孤立高代理——显示与 JSON 序列化都会变成无效字符。
        val text = "a".repeat(7) + "\uD83D\uDE00" + "b".repeat(100)
        val quote = message(MessageRole.USER, UIMessagePart.Text(text)).toQuotedMessage(maxChars = 8)
        val content = quote!!.content
        assertTrue("must not end with a lone high surrogate", !content.last().isHighSurrogate())
        assertEquals(7, content.length)
        assertEquals("a".repeat(7), content)
    }

    @Test
    fun `keeps complete surrogate pair when it fits`() {
        val text = "a".repeat(7) + "\uD83D\uDE00"
        assertEquals(text, message(MessageRole.USER, UIMessagePart.Text(text)).toQuotedMessage(maxChars = 9)!!.content)
    }

    @Test
    fun `round trips through json with the quoted_message discriminator`() {
        val quote = message(MessageRole.USER, UIMessagePart.Text("quoted body")).toQuotedMessage()!!
        val encoded = Json.encodeToString(UIMessageAnnotation.serializer(), quote)
        assertTrue(encoded.contains("quoted_message"))
        val decoded = Json.decodeFromString(UIMessageAnnotation.serializer(), encoded)
        assertEquals(quote, decoded)
    }

    @Test
    fun `legacy annotation json still decodes after the new subtype is added`() {
        val json = """{"type":"url_citation","title":"t","url":"https://example.com"}"""
        val decoded = Json.decodeFromString(UIMessageAnnotation.serializer(), json)
        assertTrue(decoded is UIMessageAnnotation.UrlCitation)
    }

    @Test
    fun `prompt block carries the author and the snapshot text`() {
        val quote = UIMessageAnnotation.QuotedMessage(
            messageId = kotlin.uuid.Uuid.random(),
            role = MessageRole.ASSISTANT,
            content = "quoted line",
            createdAt = LocalDateTime(2026, 9, 17, 12, 0),
        )
        val block = quote.toPromptBlock()
        assertTrue(block.contains("""author="assistant""""))
        assertTrue(block.contains("quoted line"))
        assertTrue(block.startsWith("<quoted_message"))
    }

    @Test
    fun `prompt block keeps multi-line content verbatim`() {
        // trimIndent() 会把插值正文一起算进缩进基准：正文里有一行顶格时，
        // 标签行会残留模板缩进、正文缩进也可能被吃掉。这里钉住显式拼接的行为。
        val quote = UIMessageAnnotation.QuotedMessage(
            messageId = kotlin.uuid.Uuid.random(),
            role = MessageRole.USER,
            content = "first\nsecond\n  indented",
        )
        assertEquals(
            "<quoted_message author=\"user\">\nfirst\nsecond\n  indented\n</quoted_message>",
            quote.toPromptBlock(),
        )
    }
}
