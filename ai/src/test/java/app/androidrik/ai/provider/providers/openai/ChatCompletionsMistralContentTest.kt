package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.util.KeyRoulette
import app.androidrik.ai.util.json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Mistral 开启推理时 content 是块列表
 * https://docs.mistral.ai/capabilities/reasoning
 */
class ChatCompletionsMistralContentTest {
    @Test
    fun `stream should keep first text chunk in thinking-to-answer transition frame`() {
        val decoder = ChatCompletionsStreamDecoder()
        val lines = listOf(
            // 思考中：content 是只有 thinking 块的列表
            """{"id":"r1","choices":[{"index":0,"delta":{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"17 * 23"}]}]},"finish_reason":null}]}""",
            """{"id":"r1","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":" = 391"}]}]},"finish_reason":null}]}""",
            // 过渡帧：收尾的 thinking 块 + 回答的第一个 text 块
            """{"id":"r1","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"."}],"closed":true},{"type":"text","text":"The answer"}]},"finish_reason":null}]}""",
            // 回答中：content 是普通字符串
            """{"id":"r1","choices":[{"index":0,"delta":{"content":" is 391."},"finish_reason":null}]}""",
            """{"id":"r1","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}""",
        )
        val chunks = lines.flatMap { decoder.accept(SseEvent(data = it)).chunks }

        assertEquals(
            "17 * 23 = 391.",
            chunks.filterIsInstance<StreamChunk.ReasoningDelta>().joinToString("") { it.text },
        )
        assertEquals(
            "The answer is 391.",
            chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text },
        )
    }

    @Test
    fun `non streaming response should parse both thinking and text chunks`() {
        val message = json.parseToJsonElement(
            """{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"think "},{"type":"text","text":"more"}]},{"type":"text","text":"Answer"}]}"""
        ).jsonObject

        val parsed = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default()).parseMessage(message)

        assertEquals(
            "think more",
            parsed.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning,
        )
        assertEquals("Answer", parsed.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `string content should still parse as text`() {
        val parsed = parseChatCompletionsContent(json.parseToJsonElement("\"Hi\""))
        assertEquals(ChatCompletionsContent("Hi", null), parsed)
    }

    @Test
    fun `mistral replay should put history reasoning into content thinking chunk`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "17 * 23 = 391"),
                UIMessagePart.Text("The answer is 391."),
            ),
        )

        val built = invokeBuildMessages(listOf(assistant), mistralThinkingReplay = true).single().jsonObject

        assertFalse("Mistral 不接受 reasoning_content", "reasoning_content" in built)
        val content = built["content"]!!.jsonArray
        val thinking = content[0].jsonObject
        assertEquals("thinking", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(
            "17 * 23 = 391",
            thinking["thinking"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("text", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("The answer is 391.", content[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `default replay should keep reasoning_content field`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking"),
                UIMessagePart.Text("answer"),
            ),
        )

        val built = invokeBuildMessages(listOf(assistant)).single().jsonObject

        assertEquals("thinking", built["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("answer", built["content"]!!.jsonPrimitive.content)
    }

    private fun invokeBuildMessages(
        messages: List<UIMessage>,
        mistralThinkingReplay: Boolean = false,
    ): JsonArray {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            List::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(
            api,
            messages,
            true,
            listOf(Modality.TEXT, Modality.IMAGE),
            false,
            mistralThinkingReplay,
        ) as JsonArray
    }
}
