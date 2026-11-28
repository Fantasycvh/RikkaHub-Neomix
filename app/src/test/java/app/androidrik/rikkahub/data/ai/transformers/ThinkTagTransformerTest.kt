package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import android.content.ContextWrapper
import app.androidrik.ai.provider.Model
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.time.Instant

class ThinkTagTransformerTest {
    private val now = Instant.parse("2026-08-20T00:00:00Z")

    @Test
    fun `think tag at start should be converted to reasoning`() {
        val result = transform("<think>reason</think>answer")

        assertEquals("reason", result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("answer", result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(now, result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt)
    }

    @Test
    fun `think tag in visible answer should be preserved`() {
        val text = "The literal `<think>` tag should stay visible."

        assertEquals(listOf(UIMessagePart.Text(text)), transform(text).parts)
    }

    @Test
    fun `native reasoning should prevent fallback tag parsing`() {
        val nativeReasoning = UIMessagePart.Reasoning("native reasoning")
        val text = UIMessagePart.Text("answer mentions <think> without a closing tag")
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(nativeReasoning, text),
        )

        val result = listOf(message).transformThinkTags(now, generationFinished = true).single()

        assertEquals(listOf(nativeReasoning, text), result.parts)
    }

    @Test
    fun `later think tags should remain visible`() {
        val result = transform("<think>reason</think>answer <think>literal")

        assertEquals("reason", result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("answer <think>literal", result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `think tag in a later text part should be preserved`() {
        val parts = listOf(
            UIMessagePart.Text("visible answer"),
            UIMessagePart.Text("<think>literal"),
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = parts)

        val result = listOf(message).transformThinkTags(now, generationFinished = true).single()

        assertEquals(parts, result.parts)
    }

    @Test
    fun `unclosed prefix tag stays visible while streaming and when generation finishes`() {
        val message = UIMessage.assistant("<think>reason in progress")
        for (finished in listOf(false, true)) {
            val result = listOf(message).transformThinkTags(now, generationFinished = finished).single()
            assertEquals(message.parts, result.parts)
        }
    }

    @Test
    fun `closing tag arriving later separates reasoning from the answer`() {
        val message = UIMessage.assistant("<think>reason in progress")
        val visualResult = listOf(message).transformThinkTags(now, generationFinished = false)
        assertEquals(message.parts, visualResult.single().parts)
        val finishedResult = listOf(message.copy(parts = listOf(
            UIMessagePart.Text("<think>reason in progress</think>answer"),
        ))).transformThinkTags(now, generationFinished = true).single()
        assertEquals("reason in progress", finishedResult.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("answer", finishedResult.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(now, finishedResult.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt)
    }

    @Test
    fun `streamVisualTransforms only touches latest message and keeps historical references`() = runBlocking {
        val historical = UIMessage.assistant("earlier message without think")
        val latest = UIMessage.assistant("<think>streaming thought</think>answer")
        val messages = listOf(UIMessage.user("q1"), historical, UIMessage.user("q2"), latest)

        val result = messages.streamVisualTransforms(
            transformers = listOf(ThinkTagTransformer),
            context = ContextWrapper(null),
            model = Model(modelId = "test-model"),
            assistant = Assistant(),
            settings = Settings(),
        )

        assertSame(historical, result[1])
        assertEquals("streaming thought", result.last().parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning)
        assertEquals("answer", result.last().parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    private fun transform(text: String): UIMessage {
        val message = UIMessage.assistant(text)
        return listOf(message).transformThinkTags(now, generationFinished = true).single()
    }

    @Test
    fun `unclosed tag cannot swallow answer headings or code on finish`() {
        val text = "<think>unfinished thought\n\n# 正文\n\n```kotlin\nval x = 1\n```"
        assertEquals(listOf(UIMessagePart.Text(text)), transform(text).parts)
    }

    @Test
    fun `native reasoning is never guessed from its markdown or moved into the answer`() {
        val native = UIMessagePart.Reasoning("# A heading in provider reasoning\n\n下面是答案", finishedAt = now)
        val body = UIMessagePart.Text("正文里的 <think>literal</think> 保留。")
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(native, body))
        for (finished in listOf(false, true)) {
            val result = listOf(original).transformThinkTags(now, finished).single()
            assertEquals(original.parts, result.parts)
            assertSame(native, result.parts.first())
        }
    }
}
