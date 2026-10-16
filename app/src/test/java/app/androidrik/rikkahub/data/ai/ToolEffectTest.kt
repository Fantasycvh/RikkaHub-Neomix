package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用的效果分类（借自 bigeye `loop_detector.py` 的探索/落地二分）。
 *
 * 这张表的价值全在"**不认识的一律中性**"：误报会往正常长调研里塞噪音，
 * 而噪音会让模型学会忽略这类提醒 —— 那比不提醒更糟。
 */
class ToolEffectTest {

    private fun effect(tool: String, args: kotlinx.serialization.json.JsonElement? = null) =
        classifyToolEffect(tool, args)

    // ── 落地类：调用即产生持久化/外部可见改变 ──
    @Test
    fun `landing tools are classified as landing`() {
        val landing = listOf(
            "workspace_write_file",
            "workspace_edit_file",
            "workspace_present_file",
            "todo_write",
            "save_skill",
            "install_skill",
            "generate_image",
            "ask_user",          // 交给用户也是进展（阻塞被解除），照抄 bigeye 名单
            "calendar_create",
        )
        landing.forEach { assertEquals("$it 应算落地", ToolEffect.LANDING, effect(it)) }
    }

    // ── 探索类：只读/搜索/执行 ──
    @Test
    fun `explore tools are classified as explore`() {
        val explore = listOf(
            "workspace_read_file",
            "workspace_shell",       // 执行=未落地，照抄 bigeye 对 bash/run_python 的处理
            "workspace_task_status",
            "conversation_search",
            "read_conversation",
            "recent_chats",
            "search_web",
            "scrape_web",
            "search_knowledge_base",
            "get_tool_map",
            "use_skill",
            "get_time_info",
            "get_screen_time",
            "calendar_query",
        )
        explore.forEach { assertEquals("$it 应算探索", ToolEffect.EXPLORE, effect(it)) }
    }

    /**
     * 语义不可知的**保持中性、不猜**。`mcp_tool_call` 尤其重要：它里面装的是任意 MCP 工具，
     * 猜成落地会漏检打转、猜成探索会误报，两头都不如老实承认"不知道"。
     */
    @Test
    fun `unknown and unknowable tools stay neutral`() {
        listOf(
            "mcp_tool_call",
            "dispatch_subagent",
            "eval_javascript",
            "clipboard_tool",
            "chart_display",
            "text_to_speech",
            "some_future_tool_we_never_heard_of",
        ).forEach { assertEquals("$it 应保持中性", ToolEffect.NEUTRAL, effect(it)) }
    }

    // ── 需要看参数的一类（照抄 bigeye 对 execute_advanced_tool 的处理）──
    @Test
    fun `memory tool is classified by its action`() {
        fun memory(action: String) = effect("memory_tool", buildJsonObject { put("action", action) })

        assertEquals(ToolEffect.LANDING, memory("create"))
        assertEquals(ToolEffect.LANDING, memory("edit"))
        assertEquals(ToolEffect.LANDING, memory("delete"))
        assertEquals(ToolEffect.EXPLORE, memory("read"))
        assertEquals("未知 action 不猜", ToolEffect.NEUTRAL, memory("archive"))
    }

    @Test
    fun `memory tool with unusable arguments stays neutral`() {
        assertEquals(ToolEffect.NEUTRAL, effect("memory_tool"))
        assertEquals(ToolEffect.NEUTRAL, effect("memory_tool", buildJsonObject { }))
        assertEquals(ToolEffect.NEUTRAL, effect("memory_tool", JsonNull))
        assertEquals(
            "action 不是字符串时不许抛",
            ToolEffect.NEUTRAL,
            effect("memory_tool", buildJsonObject { put("action", buildJsonObject { }) }),
        )
    }

    // ── 窗口 ──
    @Test
    fun `window keeps only the most recent calls`() {
        val window = ToolEffectWindow(capacity = 3)

        window.record(ToolEffect.EXPLORE)
        window.record(ToolEffect.EXPLORE)
        window.record(ToolEffect.LANDING)
        assertEquals(listOf(ToolEffect.EXPLORE, ToolEffect.EXPLORE, ToolEffect.LANDING), window.snapshot())
        assertTrue(window.isFull)

        window.record(ToolEffect.NEUTRAL)

        assertEquals("最旧的应被挤出去", listOf(ToolEffect.EXPLORE, ToolEffect.LANDING, ToolEffect.NEUTRAL), window.snapshot())
        assertEquals(1, window.count(ToolEffect.EXPLORE))
        assertEquals(1, window.count(ToolEffect.LANDING))
    }

    @Test
    fun `window is not full before capacity and clear empties it`() {
        val window = ToolEffectWindow(capacity = 2)

        assertFalse(window.isFull)
        window.record(ToolEffect.EXPLORE)
        assertFalse(window.isFull)
        window.record(ToolEffect.EXPLORE)
        assertTrue(window.isFull)

        window.clear()

        assertFalse(window.isFull)
        assertTrue(window.snapshot().isEmpty())
    }
}
