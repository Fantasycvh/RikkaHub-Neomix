package app.androidrik.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.SubagentPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 对话内子代理派发工具 + 信封解析（设计 §十）。
 *
 * 钉住四组契约：
 * ① [resolveSubagentEnvelope] 的三种取值语义——尤其 `null` 只覆盖**能力开关控制的来源**，
 *    不能把 MCP / 技能安装这类无条件装配的来源放进来（那是静默扩权）；
 * ② **禁嵌套**：无论怎么配，信封里永远没有 `dispatch_subagent` 自己；
 * ③ 工具的入参解析与错误文本（未知名字要列出可选值，模型才能自己纠正）；
 * ④ 结果 JSON：成功带全文摘要 + run_id（指针），失败带原因，忙时如实说"忙"。
 */
class SubagentToolsTest {

    private fun tool(name: String) = Tool(name = name, description = "", execute = { emptyList() })

    private fun segment(source: String, vararg names: String) =
        ToolSegment(source = source, tools = names.map { tool(it) })

    private val segments = listOf(
        segment("search", "search_web", "scrape_web"),
        segment("local", "get_time_info"),
        segment("workspace", "workspace_read_file"),
        segment("skill-install", "install_skill"),
        segment("mcp:calendar", "mcp_calendar_create_event"),
        segment("subagent", SUBAGENT_DISPATCH_TOOL_NAME),
    )

    private fun preset(
        name: String = "research",
        allowedTools: Set<String>? = null,
    ) = SubagentPreset(name = name, allowedTools = allowedTools)

    /**
     * 假派发器。
     *
     * 不用 lambda SAM：`SubagentDispatcher` 的方法带 suspend，直接写 lambda 依赖 SAM 转换，
     * 换成显式对象更稳（也让"被测契约是几个参数"在测试里看得见）。
     */
    private fun dispatcher(
        fn: suspend (SubagentPreset, String, Long) -> SubagentRunResult?,
    ) = object : SubagentDispatcher {
        override suspend fun dispatchAndAwait(
            preset: SubagentPreset,
            goal: String,
            timeoutMillis: Long,
        ): SubagentRunResult? = fn(preset, goal, timeoutMillis)
    }

    private fun args(name: String, goal: String = "summarize this"): JsonObject = buildJsonObject {
        put("name", name)
        put("goal", goal)
    }

    // ---- 信封解析 ----

    @Test
    fun `null envelope follows only the capability-switched sources`() {
        val envelope = resolveSubagentEnvelope(segments, allowedTools = null)

        assertEquals(setOf("search_web", "scrape_web", "workspace_read_file"), envelope)
        // 无条件装配的来源不得顺带进来（这是本函数最关键的取舍）
        assertFalse("get_time_info" in envelope)
        assertFalse("install_skill" in envelope)
        assertFalse("mcp_calendar_create_event" in envelope)
    }

    @Test
    fun `empty envelope denies every tool`() {
        assertEquals(emptySet<String>(), resolveSubagentEnvelope(segments, allowedTools = emptySet()))
    }

    @Test
    fun `explicit envelope intersects what is actually assembled`() {
        val envelope = resolveSubagentEnvelope(
            segments,
            allowedTools = setOf("mcp_calendar_create_event", "search_web", "not_assembled"),
        )

        // 点名可以放进其它来源，但"没装配出来的"不会凭空出现
        assertEquals(setOf("mcp_calendar_create_event", "search_web"), envelope)
    }

    @Test
    fun `envelope never contains the dispatch tool itself`() {
        // 禁嵌套：即使有人把它写进预设，也进不了信封（第二层是 runner 的串行闸）
        listOf<Set<String>?>(null, emptySet(), setOf(SUBAGENT_DISPATCH_TOOL_NAME)).forEach { allowed ->
            assertFalse(
                "allowedTools=$allowed leaked the dispatch tool",
                SUBAGENT_DISPATCH_TOOL_NAME in resolveSubagentEnvelope(segments, allowed),
            )
        }
    }

    @Test
    fun `run tool catalog drops the dispatch tool and sorts`() {
        val catalog = runToolCatalog(segments)

        assertFalse(SUBAGENT_DISPATCH_TOOL_NAME in catalog)
        assertEquals(catalog.sorted(), catalog)
        assertTrue("mcp_calendar_create_event" in catalog)
    }

    // ---- 工具装配 ----

    @Test
    fun `no presets means no tool`() {
        assertTrue(createSubagentTools(emptyList(), dispatcher = dispatcher { _, _, _ -> null }).isEmpty())
    }

    @Test
    fun `schema requires name and goal and lists the dispatch names`() {
        val tool = createSubagentTools(
            presets = listOf(preset("research"), preset("digest")),
            dispatcher = dispatcher { _, _, _ -> null },
        ).single()

        assertEquals(SUBAGENT_DISPATCH_TOOL_NAME, tool.name)
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("name", "goal"), schema.required)
        // schema 是 {"type":"string","description":"…"}，取 description 里的可选名单
        val nameDescription = schema.properties["name"]!!
            .jsonObject["description"]!!.jsonPrimitive.content
        assertTrue(nameDescription.contains("research"))
        assertTrue(nameDescription.contains("digest"))
    }

    @Test
    fun `catalog block lists every preset with its capabilities`() {
        val block = subagentCatalogBlock(
            listOf(
                preset("research").copy(description = "finds sources", enableWebSearch = true),
                preset("review").copy(description = "critiques", allowedTools = emptySet()),
            )
        )

        assertTrue(block.contains("<name>research</name>"))
        assertTrue(block.contains("<description>finds sources</description>"))
        assertTrue(block.contains("web search"))
        assertTrue(block.contains("<name>review</name>"))
        assertTrue(block.contains("no tools (reasoning only)"))
        assertTrue(block.contains(SUBAGENT_DISPATCH_TOOL_NAME))
    }

    @Test
    fun `approval follows the preset flag`() {
        val tool = createSubagentTools(
            presets = listOf(
                preset("research").copy(requireApproval = false),
                preset("review").copy(requireApproval = true),
            ),
            dispatcher = dispatcher { _, _, _ -> null },
        ).single()

        assertFalse(tool.needsApproval(args("research")))
        assertFalse(tool.needsApproval(args("RESEARCH")))
        assertTrue(tool.needsApproval(args("review")))
        // 未知名字按"不需批准"（执行时会返回可纠正的错误文本，不是提权面）
        assertFalse(tool.needsApproval(args("nope")))
    }

    // ---- 执行 ----

    private fun runTool(
        presets: List<SubagentPreset>,
        args: JsonObject,
        dispatcher: SubagentDispatcher,
    ): String = runBlocking {
        val tool = createSubagentTools(presets, dispatcher).single()
        tool.execute(args).filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
    }

    @Test
    fun `completed run returns status, run id and the full summary`() {
        val longText = "摘要".repeat(800)
        val output = runTool(
            presets = listOf(preset()),
            args = args("research"),
            dispatcher = dispatcher { _, goal, timeout ->
                assertEquals("summarize this", goal)
                assertEquals(600_000L, timeout)
                SubagentRunResult(
                    runId = "run-1",
                    status = "completed",
                    summary = longText,
                    error = null,
                )
            },
        )

        assertTrue(output.contains("\"status\":\"completed\""))
        assertTrue(output.contains("\"run_id\":\"run-1\""))
        // 全文（不截断）——截断只发生在列表摘要列
        assertTrue(output.contains(longText))
    }

    @Test
    fun `preset timeout is passed to the dispatcher`() {
        runTool(
            presets = listOf(preset().copy(timeoutSeconds = 120)),
            args = args("research"),
            dispatcher = dispatcher { _, _, timeout ->
                assertEquals(120_000L, timeout)
                null
            },
        )
    }

    @Test
    fun `name lookup ignores case`() {
        val output = runTool(
            presets = listOf(preset("research")),
            args = args("RESEARCH"),
            dispatcher = dispatcher { _, _, _ -> SubagentRunResult("run-1", "completed", "ok", null) },
        )
        assertTrue(output.contains("\"status\":\"completed\""))
    }

    @Test
    fun `busy dispatcher is reported as unavailable with a do-not-retry note`() {
        val output = runTool(
            presets = listOf(preset()),
            args = args("research"),
            dispatcher = dispatcher { _, _, _ -> null },
        )

        assertTrue(output.contains("\"status\":\"unavailable\""))
        assertFalse(output.contains("run_id"))
        assertTrue(output.contains("Do not call this tool again right away"))
    }

    @Test
    fun `failed run reports the machine error and points at the run`() {
        val output = runTool(
            presets = listOf(preset()),
            args = args("research"),
            dispatcher = dispatcher { _, _, _ ->
                SubagentRunResult("run-9", "failed", null, "loop_detected: tool=search_web ×3")
            },
        )

        assertTrue(output.contains("\"status\":\"failed\""))
        assertTrue(output.contains("loop_detected"))
        assertTrue(output.contains("run-9"))
        assertFalse(output.contains("summary"))
    }

    @Test
    fun `unknown preset name fails with the list of available names`() {
        val thrown = runCatching {
            runTool(
                presets = listOf(preset("research"), preset("digest")),
                args = args("reserch"),
                dispatcher = dispatcher { _, _, _ -> null },
            )
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue(thrown!!.message!!.contains("research"))
        assertTrue(thrown.message!!.contains("digest"))
    }

    @Test
    fun `blank goal fails before dispatching`() {
        var dispatched = false
        val thrown = runCatching {
            runTool(
                presets = listOf(preset()),
                args = args("research", goal = "   "),
                dispatcher = dispatcher { _, _, _ ->
                    dispatched = true
                    null
                },
            )
        }.exceptionOrNull()

        assertNotNull(thrown)
        assertFalse("must not reach the runner with a blank goal", dispatched)
    }
}
