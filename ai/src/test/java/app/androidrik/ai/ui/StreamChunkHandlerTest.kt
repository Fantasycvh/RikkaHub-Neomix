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
    fun `image snapshot without start should honor the chunk mime type`() {
        // #2311 同族（fork 6b901177e）：快照可能不经 ImageStart 冷启动；decoder 给了
        // output_format 时按真值拼前缀，jpeg 不再被误标成 png。未知 mime 仍落回 png。
        var messages = listOf(UIMessage.user("draw an image"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(
            messages,
            StreamChunk.ImageSnapshot("image-1", "final", mimeType = "image/jpeg"),
        )
        messages = handler.handle(messages, StreamChunk.ImageEnd("image-1"))

        val jpeg = messages.last().parts.single() as UIMessagePart.Image
        assertEquals("data:image/jpeg;base64,final", jpeg.url)

        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-2", "final"))
        val defaulted = messages.last().parts.last() as UIMessagePart.Image
        assertEquals("data:image/png;base64,final", defaulted.url)
    }

    @Test
    fun `image snapshot mime should not downgrade the mime chosen by image start`() {
        // 快照未带 mime（null）时保留部件已有前缀（ImageStart 可能是 webp）；
        // 显式 mime（decoder 知道 output_format）才是权威，可以覆盖。
        var messages = listOf(UIMessage.user("draw an image"))
        val handler = StreamChunkHandler(model)

        messages = handler.handle(messages, StreamChunk.ImageStart("image-1", mimeType = "image/webp"))
        messages = handler.handle(messages, StreamChunk.ImageSnapshot("image-1", "partial"))
        val partial = messages.last().parts.single() as UIMessagePart.Image
        assertEquals("data:image/webp;base64,partial", partial.url)

        messages = handler.handle(
            messages,
            StreamChunk.ImageSnapshot("image-1", "final", mimeType = "image/jpeg"),
        )
        val final = messages.last().parts.single() as UIMessagePart.Image
        assertEquals("data:image/jpeg;base64,final", final.url)
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

    @Test
    fun `batched accept and flush should match per-chunk handle across text reasoning tool and image`() {
        val chunks = listOf(
            StreamChunk.ReasoningStart("r1"),
            StreamChunk.ReasoningDelta("r1", "step 1 "),
            StreamChunk.ReasoningDelta("r1", "step 2"),
            StreamChunk.ReasoningEnd("r1"),
            StreamChunk.TextStart("t1"),
            StreamChunk.TextDelta("t1", "Hello "),
            StreamChunk.TextDelta("t1", "world"),
            StreamChunk.ToolCallStart("c1", toolName = "read_"),
            StreamChunk.ToolCallDelta("c1", toolNameDelta = "file", inputDelta = "{\"path\":"),
            StreamChunk.ToolCallDelta("c1", inputDelta = "\"a.kt\"}"),
            StreamChunk.ToolCallEnd("c1"),
            StreamChunk.ImageStart("i1", mimeType = "image/png"),
            StreamChunk.ImageDelta("i1", "abc"),
            StreamChunk.ImageDelta("i1", "def"),
            StreamChunk.ImageEnd("i1"),
            StreamChunk.Usage(TokenUsage(promptTokens = 5, completionTokens = 10)),
        )

        val eagerHandler = StreamChunkHandler(model)
        var eagerMessages = listOf(UIMessage.user("go"))
        chunks.forEach { eagerMessages = eagerHandler.handle(eagerMessages, it) }

        val batchHandler = StreamChunkHandler(model)
        var batchMessages = listOf(UIMessage.user("go"))
        chunks.forEach { batchHandler.accept(batchMessages, it) }
        assertTrue(batchHandler.hasBufferedChanges)
        assertTrue(batchHandler.activeContentChars > 0)
        batchMessages = batchHandler.flush(batchMessages)
        assertFalse(batchHandler.hasBufferedChanges)

        val eagerAssistant = eagerMessages.last()
        val batchAssistant = batchMessages.last()
        assertEquals(eagerAssistant.toText(), batchAssistant.toText())
        assertEquals(
            eagerAssistant.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning },
            batchAssistant.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning },
        )
        assertEquals(
            eagerAssistant.parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolName to it.input },
            batchAssistant.parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolName to it.input },
        )
        assertEquals(
            eagerAssistant.parts.filterIsInstance<UIMessagePart.Image>().map { it.url },
            batchAssistant.parts.filterIsInstance<UIMessagePart.Image>().map { it.url },
        )
        assertEquals(eagerAssistant.usage, batchAssistant.usage)
    }

    @Test
    fun `accept should preserve external modifications between flushes`() {
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("hi"))

        handler.accept(messages, StreamChunk.TextStart("t1"))
        handler.accept(messages, StreamChunk.TextDelta("t1", "first "))
        messages = handler.flush(messages)

        // 模拟外部给末条消息挂载 usage / memoryContext
        val externalUsage = TokenUsage(promptTokens = 42, completionTokens = 7)
        messages = messages.dropLast(1) + messages.last().copy(usage = externalUsage)

        handler.accept(messages, StreamChunk.TextDelta("t1", "second"))
        messages = handler.flush(messages)

        assertEquals("first second", messages.last().toText())
        assertEquals(externalUsage, messages.last().usage)
    }

    @Test
    fun `finish chunk should flush buffered deltas and reset handler buffers`() {
        val handler = StreamChunkHandler(model)
        var messages = listOf(UIMessage.user("hi"))

        handler.accept(messages, StreamChunk.TextStart("t1"))
        handler.accept(messages, StreamChunk.TextDelta("t1", "done"))
        handler.accept(messages, StreamChunk.Finish(finishReason = "stop"))
        messages = handler.flush(messages)

        assertTrue(handler.finished)
        assertFalse(handler.hasBufferedChanges)
        assertEquals("done", messages.last().toText())
        assertEquals("stop", messages.last().finishReason)
        assertEquals(4, handler.activeContentChars)
    }
}
