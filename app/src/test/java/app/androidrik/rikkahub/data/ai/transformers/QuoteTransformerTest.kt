package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * #1906 引用在**请求侧**的展开（`QuoteTransformer`）。
 *
 * 三件事必须钉住：
 * 1. 展开只发生在请求副本上——落库消息的 parts 不变（否则引用前缀会被写进历史与 FTS）；
 * 2. 幂等——工具多步循环会对同一批消息反复 transform，不能累积出多份引用前缀；
 * 3. 自足——只用注解里的快照，不依赖被引用消息仍在同一批消息里（它可能已被压缩截断）。
 */
class QuoteTransformerTest {

    private fun quote(
        content: String = "previous answer",
        role: MessageRole = MessageRole.ASSISTANT,
        messageId: Uuid = Uuid.random(),
    ) = UIMessageAnnotation.QuotedMessage(messageId = messageId, role = role, content = content)

    private fun userMessage(
        text: String = "follow up",
        annotations: List<UIMessageAnnotation> = emptyList(),
    ) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)), annotations = annotations)

    @Test
    fun `expands quote into a text prefix before the user text`() {
        val expanded = expandQuotes(listOf(userMessage(annotations = listOf(quote()))))
        val texts = expanded.single().parts.filterIsInstance<UIMessagePart.Text>().map { it.text }
        assertEquals(2, texts.size)
        assertTrue(texts[0].startsWith("<quoted_message"))
        assertTrue(texts[0].contains("previous answer"))
        assertEquals("follow up", texts[1])
    }

    @Test
    fun `drops the quote annotation from the request copy`() {
        val expanded = expandQuotes(listOf(userMessage(annotations = listOf(quote()))))
        assertTrue(expanded.single().annotations.none { it is UIMessageAnnotation.QuotedMessage })
    }

    @Test
    fun `is idempotent across repeated transforms`() {
        // 工具多步循环里同一批消息会被反复 transform（PromptInjectionTransformer 亦然）
        var messages = listOf(userMessage(annotations = listOf(quote())))
        messages = expandQuotes(messages)
        val once = messages
        messages = expandQuotes(messages)
        messages = expandQuotes(messages)
        assertEquals(once, messages)
        assertEquals(
            1,
            messages.single().parts.filterIsInstance<UIMessagePart.Text>()
                .count { it.text.startsWith("<quoted_message") },
        )
    }

    @Test
    fun `does not consult the source message so it survives context trimming`() {
        // 引用源不在本批消息里（已被压缩检查点/limitContext 截断）——展开仍应完整
        val expanded = expandQuotes(listOf(userMessage(annotations = listOf(quote("trimmed away")))))
        assertTrue(expanded.single().parts.first().toString().contains("trimmed away"))
    }

    @Test
    fun `messages without quotes pass through untouched`() {
        val plain = listOf(userMessage(), UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi"))))
        assertEquals(plain, expandQuotes(plain))
    }

    @Test
    fun `other annotations are preserved`() {
        val partial = UIMessageAnnotation.PartialFailure
        val expanded = expandQuotes(listOf(userMessage(annotations = listOf(partial, quote()))))
        assertEquals(listOf(partial), expanded.single().annotations)
    }

    @Test
    fun `multiple quotes merge into a single prefix`() {
        val expanded = expandQuotes(
            listOf(userMessage(annotations = listOf(quote("first"), quote("second"))))
        )
        val prefix = expanded.single().parts.filterIsInstance<UIMessagePart.Text>().first().text
        assertTrue(prefix.contains("first"))
        assertTrue(prefix.contains("second"))
        assertEquals(2, expanded.single().parts.size)
    }
}
