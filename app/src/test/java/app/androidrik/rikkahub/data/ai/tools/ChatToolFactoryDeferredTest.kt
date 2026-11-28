package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.DEFERRED_TOOL_SEARCH_TOOL_NAME
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.deferredToolProtocol
import app.androidrik.ai.ui.DeferredToolSearchResultMetadata
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.ui.toMetadata
import app.androidrik.rikkahub.data.ai.ToolOutputCompressor
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolFactoryDeferredTest {

    private fun tool(name: String) = Tool(
        name = name,
        description = "Tool $name",
        execute = { emptyList() },
    )

    @Test
    fun `policy marks default scopes deferred and appends search_tools on supported DeepSeek Responses`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
        )
        val model = Model(modelId = "deepseek-flash", abilities = listOf(ModelAbility.TOOL))
        val segments = listOf(
            ToolSegment(ToolSource.Memory, listOf(tool("memory_tool"))),
            ToolSegment(ToolSource.Workspace, listOf(tool("workspace_read_file"))),
            ToolSegment(ToolSource.McpServer("github"), listOf(tool("create_issue"))),
            ToolSegment(ToolSource.Plugin("feishu"), listOf(tool("send_msg"))),
            ToolSegment(ToolSource.Skills, listOf(tool("use_skill"))),
        )

        val result = applyNativeDeferredToolPolicy(
            segments = segments,
            enabled = true,
            protocol = model.deferredToolProtocol(provider),
            scopes = DEFAULT_DEFERRED_TOOL_SCOPES,
            explicitlyAllowedToolNames = emptySet(),
            providerType = provider,
            json = Json,
        )

        val byName = result.flatMap { it.tools }.associateBy { it.name }
        assertFalse(byName.getValue("memory_tool").deferLoading)
        assertFalse(byName.getValue("workspace_read_file").deferLoading)
        assertTrue(byName.getValue("create_issue").deferLoading)
        assertTrue(byName.getValue("send_msg").deferLoading)
        assertTrue(byName.getValue("use_skill").deferLoading)
        assertTrue(byName.getValue(DEFERRED_TOOL_SEARCH_TOOL_NAME).isDeferredToolSearch)
        assertFalse(byName.getValue(DEFERRED_TOOL_SEARCH_TOOL_NAME).deferLoading)
    }

    @Test
    fun `policy falls back to unchanged eager tools when web tools or unsupported protocol is used`() {
        val provider = ProviderSetting.OpenAI(
            baseUrl = "https://api.deepseek.com/v1",
            useResponseApi = true,
        )
        val webToolModel = Model(
            modelId = "deepseek-flash",
            abilities = listOf(ModelAbility.TOOL),
            tools = setOf(BuiltInTools.DeepSeekWebSearch),
        )
        val segments = listOf(
            ToolSegment(ToolSource.McpServer("github"), listOf(tool("create_issue"))),
        )

        val result = applyNativeDeferredToolPolicy(
            segments = segments,
            enabled = true,
            protocol = webToolModel.deferredToolProtocol(provider),
            scopes = DEFAULT_DEFERRED_TOOL_SCOPES,
            explicitlyAllowedToolNames = emptySet(),
            providerType = provider,
            json = Json,
        )

        val tools = result.flatMap { it.tools }
        assertEquals(listOf("create_issue"), tools.map { it.name })
        assertFalse(tools.single().deferLoading)
    }

    @Test
    fun `subagent envelope and catalog exclude search_tools`() {
        val segments = listOf(
            ToolSegment(ToolSource.Search, listOf(tool("search_web"))),
            ToolSegment(ToolSource.DeferredSearch, listOf(tool(DEFERRED_TOOL_SEARCH_TOOL_NAME))),
        )
        assertEquals(listOf("search_web"), runToolCatalog(segments))
        assertEquals(
            setOf("search_web"),
            resolveSubagentEnvelope(segments, allowedTools = setOf("search_web", DEFERRED_TOOL_SEARCH_TOOL_NAME)),
        )
    }

    @Test
    fun `tool output compressor preserves search_tools result verbatim with metadata`() {
        val searchToolPart = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = DEFERRED_TOOL_SEARCH_TOOL_NAME,
            input = """{"query":"weather"}""",
        )
        val output = listOf(
            UIMessagePart.Text(
                text = "Loaded 1 tool(s): get_weather",
                metadata = DeferredToolSearchResultMetadata(loadedToolNames = listOf("get_weather")).toMetadata(),
            )
        )

        // Even under a tiny maxChars budget, search_tools metadata must survive intact
        val compressed = ToolOutputCompressor.compressSuccess(
            context = null,
            tool = searchToolPart,
            output = output,
            maxChars = 10,
            globalCapChars = 10,
            workspaceBound = false,
            hasShellAccess = false,
        )
        assertEquals(ToolOutputCompressor.Method.VERBATIM, compressed.method)
        val text = compressed.parts.single() as UIMessagePart.Text
        assertEquals(listOf("get_weather"), text.metadataAs<DeferredToolSearchResultMetadata>()?.loadedToolNames)
    }
}
