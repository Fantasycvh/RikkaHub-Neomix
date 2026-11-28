package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.DEFERRED_TOOL_SEARCH_TOOL_NAME
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.DeferredToolSearchCallMetadata
import app.androidrik.ai.ui.DeferredToolSearchResultMetadata
import app.androidrik.ai.ui.ServerToolProtocol
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.StreamChunkHandler
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.ui.toMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseApiDeferredToolTest {

    private val api = ResponseAPI(OkHttpClient())

    private fun testTool(
        name: String,
        deferLoading: Boolean = false,
        isDeferredToolSearch: Boolean = false,
    ) = Tool(
        name = name,
        description = "Tool $name",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject { put("type", "string") })
                },
                required = listOf("query"),
            )
        },
        deferLoading = deferLoading,
        isDeferredToolSearch = isDeferredToolSearch,
        execute = { emptyList() },
    )

    @Test
    fun `isOpenAIServerToolCall excludes client tool_search_call`() {
        assertFalse(isOpenAIServerToolCall("tool_search_call"))
        assertTrue(isOpenAIServerToolCall("web_search_call"))
    }

    @Test
    fun `buildRequestBody omits deferred tools and emits tool_search client declaration`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
        )
        val params = TextGenerationParams(
            model = Model(modelId = "deepseek-flash", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(
                testTool("memory_tool"),
                testTool(DEFERRED_TOOL_SEARCH_TOOL_NAME, isDeferredToolSearch = true),
                testTool("get_weather", deferLoading = true),
            ),
        )

        val body = api.buildRequestBody(provider, listOf(UIMessage.user("weather?")), params, stream = false)
        val tools = body["tools"]!!.jsonArray
        assertEquals(2, tools.size)

        assertEquals("function", tools[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("memory_tool", tools[0].jsonObject["name"]!!.jsonPrimitive.content)

        val searchTool = tools[1].jsonObject
        assertEquals("tool_search", searchTool["type"]!!.jsonPrimitive.content)
        assertEquals("client", searchTool["execution"]!!.jsonPrimitive.content)
        assertNull(searchTool["name"])
        assertNull(searchTool["strict"])
        assertNotNull(searchTool["parameters"])
    }

    @Test
    fun `stream decoder decodes client tool_search_call with object arguments into UIMessagePart_Tool`() {
        val decoder = ResponseApiStreamDecoder()
        val handler = StreamChunkHandler()
        var messages = listOf(UIMessage.user("What is the weather?"))

        val added = decoder.accept(
            SseEvent(
                event = "response.output_item.added",
                data = """{"type":"response.output_item.added","output_index":0,"item":{"type":"tool_search_call","id":"tsc_1","call_id":"call_1","execution":"client","status":"in_progress"}}""",
            )
        )
        val done = decoder.accept(
            SseEvent(
                event = "response.output_item.done",
                data = """{"type":"response.output_item.done","output_index":0,"item":{"type":"tool_search_call","id":"tsc_1","call_id":"call_1","execution":"client","status":"completed","arguments":{"query":"weather"}}}""",
            )
        )
        val completed = decoder.accept(
            SseEvent(
                event = "response.completed",
                data = """{"type":"response.completed","response":{"id":"resp_1","status":"completed","model":"deepseek-flash"}}""",
            )
        )

        (added.chunks + done.chunks + completed.chunks).forEach { chunk ->
            messages = handler.handle(messages, chunk)
        }

        val toolPart = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("call_1", toolPart.toolCallId)
        assertEquals(DEFERRED_TOOL_SEARCH_TOOL_NAME, toolPart.toolName)
        assertEquals("""{"query":"weather"}""", toolPart.input)
        val meta = toolPart.metadataAs<DeferredToolSearchCallMetadata>()
        assertEquals(ServerToolProtocol.OPENAI_RESPONSES, meta?.protocol)
        assertEquals("client", meta?.execution)
        assertEquals("tsc_1", meta?.itemId)
        assertTrue(messages.last().parts.none { it is UIMessagePart.ServerTool })
    }

    @Test
    fun `stream decoder keeps server execution tool_search_call as ServerTool`() {
        val decoder = ResponseApiStreamDecoder()
        val added = decoder.accept(
            SseEvent(
                event = "response.output_item.added",
                data = """{"type":"response.output_item.added","output_index":0,"item":{"type":"tool_search_call","id":"tsc_srv","execution":"server","status":"in_progress"}}""",
            )
        )
        assertTrue(added.chunks.single() is StreamChunk.ServerToolStart)
    }

    @Test
    fun `parseResponseOutput handles non-streaming client tool_search_call`() {
        val responseJson = buildJsonObject {
            put("id", "resp_1")
            put("status", "completed")
            put(
                "output",
                kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tool_search_call")
                        put("id", "tsc_1")
                        put("call_id", "call_1")
                        put("execution", "client")
                        put("status", "completed")
                        put("arguments", buildJsonObject { put("query", "weather") })
                    })
                }
            )
        }

        val result = api.parseResponseOutput(responseJson)
        val tool = result.message.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("call_1", tool.toolCallId)
        assertEquals(DEFERRED_TOOL_SEARCH_TOOL_NAME, tool.toolName)
        assertEquals("""{"query":"weather"}""", tool.input)
    }

    @Test
    fun `replays tool_search_call and tool_search_output with full function schemas and reasoning backfill`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
        )
        val params = TextGenerationParams(
            model = Model(
                modelId = "deepseek-flash",
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ),
            reasoningLevel = ReasoningLevel.HIGH,
            tools = listOf(
                testTool(DEFERRED_TOOL_SEARCH_TOOL_NAME, isDeferredToolSearch = true),
                testTool("get_weather", deferLoading = true),
            ),
        )
        val history = listOf(
            UIMessage.user("weather in Hangzhou"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = DEFERRED_TOOL_SEARCH_TOOL_NAME,
                        input = """{"query":"weather"}""",
                        output = listOf(
                            UIMessagePart.Text(
                                text = "Loaded: get_weather",
                                metadata = DeferredToolSearchResultMetadata(
                                    loadedToolNames = listOf("get_weather"),
                                ).toMetadata(),
                            )
                        ),
                        metadata = DeferredToolSearchCallMetadata(
                            protocol = ServerToolProtocol.OPENAI_RESPONSES,
                            execution = "client",
                        ).toMetadata(),
                    )
                ),
            ),
        )

        val body = api.buildRequestBody(provider, history, params, stream = false)
        val input = body["input"]!!.jsonArray
        // user + reasoning backfill + tool_search_call + tool_search_output
        assertEquals("reasoning", input[1].jsonObject["type"]!!.jsonPrimitive.content)

        val searchCall = input[2].jsonObject
        assertEquals("tool_search_call", searchCall["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", searchCall["call_id"]!!.jsonPrimitive.content)
        assertEquals("client", searchCall["execution"]!!.jsonPrimitive.content)
        assertTrue(searchCall["arguments"] is JsonObject)
        assertEquals("weather", searchCall["arguments"]!!.jsonObject["query"]!!.jsonPrimitive.content)

        val searchOutput = input[3].jsonObject
        assertEquals("tool_search_output", searchOutput["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", searchOutput["call_id"]!!.jsonPrimitive.content)
        assertEquals("client", searchOutput["execution"]!!.jsonPrimitive.content)
        val loadedTools = searchOutput["tools"]!!.jsonArray
        assertEquals(1, loadedTools.size)
        assertEquals("function", loadedTools[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", loadedTools[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(false, loadedTools[0].jsonObject["strict"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `cross-provider downgrade emits function_call and function_call_output when deferred is unsupported`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
        )
        val params = TextGenerationParams(
            model = Model(
                modelId = "deepseek-v4-pro",
                abilities = listOf(ModelAbility.TOOL),
            ),
            tools = listOf(
                testTool(DEFERRED_TOOL_SEARCH_TOOL_NAME, isDeferredToolSearch = true),
                testTool("get_weather", deferLoading = true),
            ),
        )
        val history = listOf(
            UIMessage.user("weather"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call_1",
                        toolName = DEFERRED_TOOL_SEARCH_TOOL_NAME,
                        input = """{"query":"weather"}""",
                        output = listOf(
                            UIMessagePart.Text(
                                text = "Loaded: get_weather",
                                metadata = DeferredToolSearchResultMetadata(
                                    loadedToolNames = listOf("get_weather"),
                                ).toMetadata(),
                            )
                        ),
                        metadata = DeferredToolSearchCallMetadata(
                            protocol = ServerToolProtocol.OPENAI_RESPONSES,
                            execution = "client",
                        ).toMetadata(),
                    )
                ),
            ),
        )

        val body = api.buildRequestBody(provider, history, params, stream = false)
        // tools[] keeps all tools as function declarations on unsupported models
        assertEquals(2, body["tools"]!!.jsonArray.size)
        val input = body["input"]!!.jsonArray
        // DeepSeek host keeps reasoning backfill item before function_call
        assertEquals("reasoning", input[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_call", input[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[3].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `web tools on deepseek-flash suppress tool_search declaration`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
        )
        val params = TextGenerationParams(
            model = Model(
                modelId = "deepseek-flash",
                abilities = listOf(ModelAbility.TOOL),
                tools = setOf(BuiltInTools.DeepSeekWebSearch),
            ),
            tools = listOf(
                testTool(DEFERRED_TOOL_SEARCH_TOOL_NAME, isDeferredToolSearch = true),
                testTool("get_weather", deferLoading = true),
            ),
        )

        val body = api.buildRequestBody(provider, listOf(UIMessage.user("hi")), params, stream = false)
        val tools = body["tools"]!!.jsonArray
        assertTrue(tools.none { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_search" })
        assertTrue(tools.any { it.jsonObject["type"]?.jsonPrimitive?.content == "deepseek_web_search" })
    }
}
