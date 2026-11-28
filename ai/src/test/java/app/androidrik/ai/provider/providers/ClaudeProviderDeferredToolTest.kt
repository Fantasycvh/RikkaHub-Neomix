package app.androidrik.ai.provider.providers

import app.androidrik.ai.core.DEFERRED_TOOL_SEARCH_TOOL_NAME
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.DeferredToolSearchResultMetadata
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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

class ClaudeProviderDeferredToolTest {

    private val provider = ClaudeProvider(OkHttpClient())

    private fun testTool(
        name: String,
        deferLoading: Boolean = false,
        isDeferredToolSearch: Boolean = false,
    ) = Tool(
        name = name,
        description = "Tool $name",
        parameters = { InputSchema.Obj(properties = buildJsonObject { put("q", buildJsonObject { put("type", "string") }) }) },
        deferLoading = deferLoading,
        isDeferredToolSearch = isDeferredToolSearch,
        execute = { emptyList() },
    )

    @Test
    fun `orders eager tools first, marks deferred tools, and puts cache_control only on last eager tool`() {
        val setting = ProviderSetting.Claude(
            baseUrl = "https://api.deepseek.com/anthropic/v1",
            promptCaching = true,
        )
        val params = TextGenerationParams(
            model = Model(modelId = "deepseek-flash", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(
                testTool("mcp_heavy", deferLoading = true),
                testTool(DEFERRED_TOOL_SEARCH_TOOL_NAME, isDeferredToolSearch = true),
                testTool("plugin_tool", deferLoading = true),
                testTool("memory_tool"),
            ),
        )

        val request = provider.buildClaudeRequestBody(setting, listOf(UIMessage.user("hi")), params)
        val tools = request["tools"]!!.jsonArray
        assertEquals(
            listOf(DEFERRED_TOOL_SEARCH_TOOL_NAME, "memory_tool", "mcp_heavy", "plugin_tool"),
            tools.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )

        // Eager tools: no defer_loading; last eager tool carries cache_control
        assertNull(tools[0].jsonObject["defer_loading"])
        assertNull(tools[0].jsonObject["cache_control"])
        assertNull(tools[1].jsonObject["defer_loading"])
        assertNotNull(tools[1].jsonObject["cache_control"])

        // Deferred tools: defer_loading=true and NEVER cache_control
        assertEquals(true, tools[2].jsonObject["defer_loading"]?.jsonPrimitive?.booleanOrNull)
        assertNull(tools[2].jsonObject["cache_control"])
        assertEquals(true, tools[3].jsonObject["defer_loading"]?.jsonPrimitive?.booleanOrNull)
        assertNull(tools[3].jsonObject["cache_control"])
    }

    @Test
    fun `official Anthropic Claude 4_8 also supports defer_loading without beta headers`() {
        val setting = ProviderSetting.Claude(
            baseUrl = "https://api.anthropic.com/v1",
            promptCaching = true,
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-opus-4-8", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(
                testTool(DEFERRED_TOOL_SEARCH_TOOL_NAME, isDeferredToolSearch = true),
                testTool("get_weather", deferLoading = true),
            ),
        )

        val request = provider.buildClaudeRequestBody(setting, listOf(UIMessage.user("weather?")), params)
        val tools = request["tools"]!!.jsonArray
        assertNotNull(tools[0].jsonObject["cache_control"])
        assertEquals(true, tools[1].jsonObject["defer_loading"]?.jsonPrimitive?.booleanOrNull)
        assertNull(tools[1].jsonObject["cache_control"])
    }

    @Test
    fun `tool_result replays tool_reference blocks and filters removed tools`() {
        val setting = ProviderSetting.Claude(
            baseUrl = "https://api.deepseek.com/anthropic/v1",
        )
        val params = TextGenerationParams(
            model = Model(modelId = "deepseek-flash", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(
                testTool(DEFERRED_TOOL_SEARCH_TOOL_NAME, isDeferredToolSearch = true),
                testTool("get_weather", deferLoading = true),
                // "removed_mcp_tool" intentionally absent from current params.tools
            ),
        )
        val history = listOf(
            UIMessage.user("weather in Hangzhou"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "toolu_1",
                        toolName = DEFERRED_TOOL_SEARCH_TOOL_NAME,
                        input = """{"query":"weather"}""",
                        output = listOf(
                            UIMessagePart.Text(
                                text = "Loaded: get_weather, removed_mcp_tool",
                                metadata = DeferredToolSearchResultMetadata(
                                    loadedToolNames = listOf("get_weather", "removed_mcp_tool", "get_weather"),
                                ).toMetadata(),
                            )
                        ),
                    )
                ),
            ),
        )

        val request = provider.buildClaudeRequestBody(setting, history, params)
        val messages = request["messages"]!!.jsonArray
        val toolResultMsg = messages.last().jsonObject
        assertEquals("user", toolResultMsg["role"]!!.jsonPrimitive.content)
        val toolResultBlock = toolResultMsg["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", toolResultBlock["type"]!!.jsonPrimitive.content)
        val refBlocks = toolResultBlock["content"]!!.jsonArray
        assertEquals(1, refBlocks.size)
        assertEquals("tool_reference", refBlocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", refBlocks[0].jsonObject["tool_name"]!!.jsonPrimitive.content)
        assertNull(refBlocks[0].jsonObject["name"])
    }

    @Test
    fun `unsupported model degrades defer_loading and tool_reference to plain text`() {
        val setting = ProviderSetting.Claude(
            baseUrl = "https://api.deepseek.com/anthropic/v1",
            promptCaching = true,
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
        val history = listOf(
            UIMessage.user("weather"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "toolu_1",
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
                    )
                ),
            ),
        )

        val request = provider.buildClaudeRequestBody(setting, history, params)
        val tools = request["tools"]!!.jsonArray
        assertFalse(tools.any { it.jsonObject["defer_loading"] != null })
        assertNotNull(tools.last().jsonObject["cache_control"])

        val toolResultContent = request["messages"]!!.jsonArray.last().jsonObject["content"]!!
            .jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals("text", toolResultContent[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Loaded: get_weather", toolResultContent[0].jsonObject["text"]!!.jsonPrimitive.content)
    }
}
