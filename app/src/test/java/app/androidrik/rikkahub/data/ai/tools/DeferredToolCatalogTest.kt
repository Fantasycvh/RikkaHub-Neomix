package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.DEFERRED_TOOL_SEARCH_TOOL_NAME
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.DeferredToolProtocol
import app.androidrik.ai.ui.DeferredToolSearchResultMetadata
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredToolCatalogTest {

    private fun dummyTool(
        name: String,
        description: String = "Description for $name",
        paramNames: List<String> = emptyList(),
    ) = Tool(
        name = name,
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    paramNames.forEach { p -> put(p, buildJsonObject { put("type", "string") }) }
                },
            )
        },
        execute = { emptyList() },
    )

    @Test
    fun `workspace, memory, search, subagent, and core local tools are never deferred`() {
        val allScopes = DeferredToolScope.entries.toSet()
        assertFalse(shouldDeferTool(ToolSource.Workspace, dummyTool("workspace_read_file"), 1000, allScopes))
        assertFalse(shouldDeferTool(ToolSource.Memory, dummyTool("memory_tool"), 1000, allScopes))
        assertFalse(shouldDeferTool(ToolSource.Search, dummyTool("search_web"), 1000, allScopes))
        assertFalse(shouldDeferTool(ToolSource.Subagent, dummyTool("dispatch_subagent"), 1000, allScopes))
        CORE_EAGER_LOCAL_TOOL_NAMES.forEach { coreName ->
            assertFalse(shouldDeferTool(ToolSource.Local, dummyTool(coreName), 1000, allScopes))
        }
    }

    @Test
    fun `default scopes defer MCP_ALL, PLUGINS, SKILLS but keep optional local eager unless enabled`() {
        val scopes = DEFAULT_DEFERRED_TOOL_SCOPES
        assertTrue(shouldDeferTool(ToolSource.McpServer("github"), dummyTool("create_issue"), 50, scopes))
        assertTrue(shouldDeferTool(ToolSource.Plugin("p1"), dummyTool("send_note"), 50, scopes))
        assertTrue(shouldDeferTool(ToolSource.Skills, dummyTool("use_skill"), 50, scopes))
        assertTrue(shouldDeferTool(ToolSource.SkillInstall, dummyTool("install_skill"), 50, scopes))

        assertFalse(shouldDeferTool(ToolSource.Local, dummyTool("calendar_query"), 50, scopes))
        assertFalse(shouldDeferTool(ToolSource.Image, dummyTool("generate_image"), 50, scopes))

        val withOptional = scopes + DeferredToolScope.OPTIONAL_LOCAL
        assertTrue(shouldDeferTool(ToolSource.Local, dummyTool("calendar_query"), 50, withOptional))
        assertTrue(shouldDeferTool(ToolSource.Image, dummyTool("generate_image"), 50, withOptional))
    }

    @Test
    fun `MCP_HEAVY only defers MCP tools exceeding threshold while explicit allowlist promotes back`() {
        val scopes = setOf(DeferredToolScope.MCP_HEAVY)
        assertFalse(shouldDeferTool(ToolSource.McpServer("s"), dummyTool("small"), 200, scopes))
        assertTrue(shouldDeferTool(ToolSource.McpServer("s"), dummyTool("heavy"), 900, scopes))
        assertFalse(
            shouldDeferTool(
                source = ToolSource.McpServer("s"),
                tool = dummyTool("heavy"),
                schemaTokens = 900,
                scopes = scopes,
                explicitlyAllowedToolNames = setOf("heavy"),
            )
        )
    }

    @Test
    fun `catalog description is deterministic regardless of input entry order`() {
        val e1 = DeferredToolEntry(ToolSource.Plugin("b"), dummyTool("z_tool"))
        val e2 = DeferredToolEntry(ToolSource.McpServer("a"), dummyTool("create_issue"))
        val e3 = DeferredToolEntry(ToolSource.McpServer("a"), dummyTool("add_comment"))

        val descA = buildDeferredCatalogDescription(listOf(e1, e2, e3))
        val descB = buildDeferredCatalogDescription(listOf(e3, e1, e2))
        assertEquals(descA, descB)
        assertTrue(descA.contains("[mcp:a] add_comment, create_issue"))
        assertTrue(descA.contains("[plugin:b] z_tool"))
    }

    @Test
    fun `search ranks exact names first, supports keywords and wildcard, and clamps limit`() {
        val entries = listOf(
            DeferredToolEntry(ToolSource.McpServer("github"), dummyTool("create_issue", "Open a GitHub issue", listOf("repo", "title"))),
            DeferredToolEntry(ToolSource.McpServer("github"), dummyTool("list_issues", "List repository issues")),
            DeferredToolEntry(ToolSource.Local, dummyTool("calendar_query", "查询日程安排", listOf("date"))),
            DeferredToolEntry(ToolSource.Plugin("mail"), dummyTool("send_email", "Send an email message")),
        )

        val byExactNames = searchDeferredTools(entries, names = listOf("send_email"), query = "issue", limit = 2)
        assertEquals("send_email", byExactNames.first().tool.name)
        assertEquals(2, byExactNames.size)

        val byKeyword = searchDeferredTools(entries, query = "repo title")
        assertEquals("create_issue", byKeyword.first().tool.name)

        val byCjk = searchDeferredTools(entries, query = "日程")
        assertEquals(listOf("calendar_query"), byCjk.map { it.tool.name })

        val wildcard = searchDeferredTools(entries, query = "*", limit = 20)
        assertEquals(4, wildcard.size)

        val clampedMin = searchDeferredTools(entries, query = "*", limit = 0)
        assertEquals(1, clampedMin.size)
    }

    @Test
    fun `search_tools execution emits DeferredToolSearchResultMetadata`() = runBlocking {
        val tool = createDeferredToolSearchTool(
            listOf(
                DeferredToolEntry(ToolSource.McpServer("weather"), dummyTool("get_weather", "Get city weather")),
                DeferredToolEntry(ToolSource.Plugin("notes"), dummyTool("save_note", "Save a markdown note")),
            )
        )
        assertEquals(DEFERRED_TOOL_SEARCH_TOOL_NAME, tool.name)
        assertTrue(tool.isDeferredToolSearch)
        assertFalse(tool.deferLoading)

        val output = tool.execute(
            buildJsonObject {
                put("query", "weather")
                put("names", buildJsonArray { })
                put("limit", 5)
            }
        )
        val textPart = output.single() as UIMessagePart.Text
        assertEquals(listOf("get_weather"), textPart.metadataAs<DeferredToolSearchResultMetadata>()?.loadedToolNames)
    }

    @Test
    fun `applyNativeDeferredToolPolicy injects search_tools only when deferred tools exist`() {
        val eagerOnly = listOf(
            ToolSegment(ToolSource.Workspace, listOf(dummyTool("workspace_read_file"))),
        )
        val unchanged = applyNativeDeferredToolPolicy(
            segments = eagerOnly,
            enabled = true,
            protocol = DeferredToolProtocol.RESPONSES_TOOL_SEARCH,
            scopes = DEFAULT_DEFERRED_TOOL_SCOPES,
            explicitlyAllowedToolNames = emptySet(),
            providerType = null,
            json = Json,
        )
        assertEquals(1, unchanged.size)
        assertTrue(unchanged.flatMap { it.tools }.none { it.isDeferredToolSearch })
    }
}
