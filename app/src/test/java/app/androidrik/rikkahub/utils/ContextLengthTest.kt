package app.androidrik.rikkahub.utils

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContextLengthTest {

    // ===== parseContextLengthInput =====

    @Test
    fun `parses k and m suffixes and plain numbers`() {
        assertEquals(256_000, parseContextLengthInput("256k"))
        assertEquals(1_000_000, parseContextLengthInput("1M"))
        assertEquals(2_500_000, parseContextLengthInput("2.5m"))
        assertEquals(262_144, parseContextLengthInput("262144"))
        assertEquals(128_000, parseContextLengthInput("128 k"))
    }

    @Test
    fun `rejects invalid input`() {
        assertNull(parseContextLengthInput(""))
        assertNull(parseContextLengthInput("abc"))
        assertNull(parseContextLengthInput("0"))
        assertNull(parseContextLengthInput("-5k"))
        assertNull(parseContextLengthInput("100000001"))
    }

    // ===== formatContextLength =====

    @Test
    fun `formats compact token counts`() {
        assertEquals("1M", formatContextLength(1_000_000))
        assertEquals("2.5M", formatContextLength(2_500_000))
        assertEquals("256K", formatContextLength(256_000))
        assertEquals("999", formatContextLength(999))
        assertEquals("", formatContextLength(null))
    }

    // ===== effectiveContextLength =====

    @Test
    fun `effective context length falls back to default`() {
        assertEquals(DEFAULT_CONTEXT_LENGTH, Model(modelId = "m").effectiveContextLength())
        assertEquals(128_000, Model(modelId = "m", contextLength = 128_000).effectiveContextLength())
    }

    // ===== estimateTokenCount =====

    @Test
    fun `estimates cjk heavier than latin`() {
        val latin = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("abcd")))
        val cjk = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("你好世界")))
        // 4 拉丁字符 -> ~1 token；4 CJK 字符 -> ~2.67 token
        assertEquals(1, estimateTokenCount(listOf(latin)))
        assertEquals(2, estimateTokenCount(listOf(cjk)))
    }

    @Test
    fun `includes tool output in estimate`() {
        val tool = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "workspace_read_file",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("file content here")),
                )
            ),
        )
        // "file content here" = 18 个非 CJK 字符 -> 4 token
        assertEquals(4, estimateTokenCount(listOf(tool)))
    }

    @Test
    fun `empty messages estimate zero`() {
        assertEquals(0, estimateTokenCount(emptyList()))
    }

    // ===== estimateWindowTokens =====

    private fun conversationWith(messages: List<UIMessage>) = Conversation(
        id = kotlin.uuid.Uuid.random(),
        assistantId = kotlin.uuid.Uuid.random(),
        messageNodes = messages.map { MessageNode(messages = listOf(it)) },
    )

    @Test
    fun `uses real usage when present`() {
        val last = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("reply")),
            usage = TokenUsage(promptTokens = 12_345, completionTokens = 100, totalTokens = 12_445),
        )
        val conversation = conversationWith(
            listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hi"))),
                last,
            )
        )
        assertEquals(12_345, conversation.estimateWindowTokens(null))
    }

    @Test
    fun `falls back to local estimate without usage`() {
        val conversation = conversationWith(
            listOf(
                UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello world"))),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi"))),
            )
        )
        // 11 + 2 个非 CJK 字符 -> 3 token
        assertEquals(3, conversation.estimateWindowTokens(null))
    }
}
