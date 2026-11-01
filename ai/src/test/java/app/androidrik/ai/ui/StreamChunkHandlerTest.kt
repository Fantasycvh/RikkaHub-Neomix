package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.TextGenerationResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StreamChunkHandler] 的聚合语义回归测试（移植自上游 #1648
 * `ai/src/test/java/me/rerere/ai/ui/StreamChunkHandlerTest.kt`）。
 *
 * 上游与本仓的 `UIMessage`/`UIMessagePart` 字段基本一致，仅包名不同，故整体移植；
 * 非流式路径用本仓的 `handleTextGenerationResult` 覆盖（上游同名 helper）。
 */
class StreamChunkHandlerTest {
    private val model = Model(modelId = "test-model")

    @Test
    fun `text lifecycle should create and update assistant message`() {
        var messages = listOf(UIMessage.user("hello"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "hel"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "lo"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-1"))
        messages = handler.handle(messages, StreamChunk.Finish(finishReason = "stop"))

        assertEquals(2, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages.last().role)
        assertEquals("hello", messages.last().toText())
        assertEquals(model.id, messages.last().modelId)
        assertNotNull(messages.last().finishedAt)
    }

    @Test
    fun `reasoning tool and usage events should preserve semantic order`() {
        var messages = listOf(UIMessage.user("use a tool"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ReasoningStart("reasoning-1"))
        messages = handler.handle(messages, StreamChunk.ReasoningDelta("reasoning-1", "think"))
        messages = handler.handle(messages, StreamChunk.ReasoningEnd("reasoning-1"))
        messages = handler.handle(messages, StreamChunk.ToolCallStart("call-1"))
        messages = handler.handle(
            messages,
            StreamChunk.ToolCallDelta(
                id = "call-1",
                toolNameDelta = "search",
                inputDelta = "{\"q\":\"test\"}",
            ),
        )
        messages = handler.handle(messages, StreamChunk.ToolCallEnd("call-1"))
        messages = handler.handle(
            messages,
            StreamChunk.Usage(TokenUsage(promptTokens = 10, completionTokens = 5)),
        )

        val assistant = messages.last()
        val reasoning = assistant.parts[0] as UIMessagePart.Reasoning
        val tool = assistant.parts[1] as UIMessagePart.Tool
        assertEquals("think", reasoning.reasoning)
        assertNotNull(reasoning.finishedAt)
        assertEquals("search", tool.toolName)
        assertEquals("{\"q\":\"test\"}", tool.input)
        assertEquals(15, assistant.usage?.totalTokens)
    }

    @Test
    fun `interleaved text chunks should be merged by id`() {
        var messages = listOf(UIMessage.user("hello"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.TextStart("text-1"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "A"))
        messages = handler.handle(messages, StreamChunk.TextStart("text-2"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-2", "B"))
        messages = handler.handle(messages, StreamChunk.TextDelta("text-1", "C"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-2"))
        messages = handler.handle(messages, StreamChunk.TextEnd("text-1"))

        val textParts = messages.last().parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(listOf("AC", "B"), textParts.map { it.text })
    }

    @Test
    fun `image snapshots should replace previous image data`() {
        var messages = listOf(UIMessage.user("draw an image"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ImageStart("image-1"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "partial-1"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "partial-2"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "final"))
        messages = handler.handle(messages, StreamChunk.ImageEnd("image-1"))

        val image = messages.last().parts.single() as UIMessagePart.Image
        assertEquals("data:image/png;base64,final", image.url)
    }

    @Test
    fun `non streaming result should keep image data url intact`() {
        val messages = listOf(UIMessage.user("draw an image"))
        val result = TextGenerationResult(
            id = "resp-1",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Image("data:image/png;base64,first"),
                    UIMessagePart.Image("data:image/jpeg;base64,second"),
                ),
            ),
        )

        val images = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Image>()

        assertEquals(
            listOf("data:image/png;base64,first", "data:image/jpeg;base64,second"),
            images.map { it.url },
        )
    }

    @Test
    fun `non streaming result appended to assistant turn should not merge images`() {
        val messages = listOf(
            UIMessage.user("draw an image"),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("sure"))),
        )
        val result = TextGenerationResult(
            id = "resp-1",
            model = model.modelId,
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Image("data:image/png;base64,first"),
                    UIMessagePart.Image("data:image/jpeg;base64,second"),
                ),
            ),
        )

        val images = messages.handleTextGenerationResult(result, model)
            .last().parts.filterIsInstance<UIMessagePart.Image>()

        assertEquals(
            listOf("data:image/png;base64,first", "data:image/jpeg;base64,second"),
            images.map { it.url },
        )
    }

    @Test
    fun `server tool lifecycle should merge streamed input result and metadata`() {
        var messages = listOf(UIMessage.user("search"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(
            messages,
            StreamChunk.ServerToolStart(
                id = "srv-1",
                toolName = "web_search",
                metadata = buildJsonObject { put("call", "raw") },
            ),
        )
        messages = handler.handle(messages, StreamChunk.ServerToolInputDelta("srv-1", "{\"query\":"))
        messages = handler.handle(messages, StreamChunk.ServerToolInputDelta("srv-1", "\"Kotlin\"}"))
        messages = handler.handle(messages, StreamChunk.ServerToolInputEnd("srv-1"))
        messages = handler.handle(
            messages,
            StreamChunk.ServerToolEnd(
                id = "srv-1",
                output = buildJsonObject { put("result", "docs") },
                status = ServerToolStatus.COMPLETED,
                metadata = buildJsonObject { put("result", "raw") },
            ),
        )

        val tool = messages.last().parts.single() as UIMessagePart.ServerTool
        assertEquals("web_search", tool.toolName)
        assertEquals("Kotlin", tool.input?.jsonObject?.get("query")?.jsonPrimitive?.content)
        assertEquals("docs", tool.output?.jsonObject?.get("result")?.jsonPrimitive?.content)
        assertEquals(ServerToolStatus.COMPLETED, tool.status)
        assertEquals("raw", tool.metadata?.get("call")?.jsonPrimitive?.content)
        assertEquals("raw", tool.metadata?.get("result")?.jsonPrimitive?.content)
    }

    @Test
    fun `tool call output should merge into existing tool idempotently`() {
        // 本仓 #2181：服务端已执行函数工具的结果以 ToolCallOutput 送达（上游事件集没有该通道）。
        // Responses API 会在 added/done 各带一份**完整**结果 ⇒ 必须幂等去重，否则输出翻倍。
        var messages = listOf(UIMessage.user("use a tool"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call-1", toolName = "search_web"))
        messages = handler.handle(messages, StreamChunk.ToolCallDelta("call-1", inputDelta = "{\"q\":\"k\"}"))
        messages = handler.handle(messages, StreamChunk.ToolCallOutput("call-1", "sunny"))
        messages = handler.handle(messages, StreamChunk.ToolCallOutput("call-1", "sunny"))

        val tool = messages.last().parts.single() as UIMessagePart.Tool
        assertEquals("call-1", tool.toolCallId)
        assertEquals("search_web", tool.toolName)
        assertEquals("{\"q\":\"k\"}", tool.input)
        assertEquals(listOf("sunny"), tool.output.filterIsInstance<UIMessagePart.Text>().map { it.text })
        // 有 output ⇒ 已执行，客户端不会再跑一遍（避免重复副作用）
        assertTrue(tool.isExecuted)
    }

    @Test
    fun `tool call output without start creates an executed tool part`() {
        // 容忍网关只下发 function_call_output、没有 function_call start 的形状
        var messages = listOf(UIMessage.user("use a tool"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallOutput("call-2", "server result"))

        val tool = messages.last().parts.single() as UIMessagePart.Tool
        assertEquals("call-2", tool.toolCallId)
        assertEquals("server result", tool.output.filterIsInstance<UIMessagePart.Text>().single().text)
        assertTrue(tool.isExecuted)
    }

    @Test
    fun `tool call output for a different id should not touch existing tool`() {
        var messages = listOf(UIMessage.user("use a tool"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ToolCallStart("call-1", toolName = "search_web"))
        messages = handler.handle(messages, StreamChunk.ToolCallOutput("call-9", "other"))

        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(listOf("call-1", "call-9"), tools.map { it.toolCallId })
        assertFalse(tools.first { it.toolCallId == "call-1" }.isExecuted)
    }
}
