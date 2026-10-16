package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一次工具调用的**效果**（借自 bigeye `loop_detector.py` 的探索/落地二分，见
 * `docs/design/design-rule-engine.md`）。
 *
 * 为什么要这个维度：本仓原有的 [ToolCallLoopGuard] 抓的是「**同签名重复**」——同一工具同一参数
 * 反复调用。它抓不住「12 次调用各不相同、但全都在读」这种形态：**重复 ≠ 无进展**，
 * 而"光看不干"恰恰是长任务里最常见的打转方式。两者互补，不重叠。
 */
enum class ToolEffect {
    /** 调用即产生**持久化或外部可见的改变** ⇒ 有实质进展 */
    LANDING,

    /** 只读/搜索/执行 ⇒ 本身不产生进展 */
    EXPLORE,

    /** 判不出来（未知工具、语义不可知的包装器） */
    NEUTRAL,
}

/**
 * 落地类：写文件、沉淀记忆、落计划、把结论交给用户。
 *
 * `ask_user` 算落地是照抄 bigeye 的名单 —— 它的理由很直白：**把问题交给用户就是进展**
 * （阻塞被解除了），而不是继续自己转。`workspace_present_file` 同理（交付）。
 */
private val LANDING_TOOLS = setOf(
    "workspace_write_file",
    "workspace_edit_file",
    "workspace_present_file",
    "todo_write",
    "save_skill",
    "install_skill",
    "generate_image",
    "ask_user",
    "calendar_create",
)

/**
 * 探索类：读、搜、查、执行。
 *
 * `workspace_shell` 归探索是照抄 bigeye 对 `bash`/`run_python` 的处理（它把执行算作探索）。
 * 理由：shell 的**效果不可知**（可能只是 `ls`，也可能在写），而把不可知的一律算作"无进展"
 * 会让守卫偏保守；反之算作进展则会**漏检**真正的打转。取前者。
 */
private val EXPLORE_TOOLS = setOf(
    "workspace_read_file",
    "workspace_shell",
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

/**
 * 判定一次工具调用的效果。
 *
 * **不认识的一律 [ToolEffect.NEUTRAL]**（宁可漏检、不可误报）：误报会往正常长调研里塞噪音，
 * 而噪音会让模型学会忽略这类提醒——那比不提醒更糟。
 */
internal fun classifyToolEffect(toolName: String, arguments: JsonElement?): ToolEffect {
    // 唯一需要看参数的：memory_tool 的 action（create/edit/delete = 落地，read = 探索）。
    // 照抄 bigeye 对 execute_advanced_tool 的处理方式（按内层动作名分类）。
    if (toolName == "memory_tool") {
        val action = (arguments as? JsonObject)
            ?.get("action")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            .orEmpty()
        return when (action) {
            "create", "edit", "delete" -> ToolEffect.LANDING
            "read" -> ToolEffect.EXPLORE
            else -> ToolEffect.NEUTRAL
        }
    }
    return when (toolName) {
        in LANDING_TOOLS -> ToolEffect.LANDING
        in EXPLORE_TOOLS -> ToolEffect.EXPLORE
        // mcp_tool_call / dispatch_subagent / eval_javascript / clipboard_tool / chart_display /
        // text_to_speech 等：语义不可知，**不猜**
        else -> ToolEffect.NEUTRAL
    }
}

/**
 * 最近 N 次工具调用的效果窗口（环形）。
 *
 * 「出现落地即视为有进展」不需要额外状态：窗口内有 [ToolEffect.LANDING] 就不触发；
 * 等它滑出窗口，探索才可能重新累积到阈值 ⇒ **自然重新武装**（对应 bigeye 的"落地即重置"）。
 */
internal class ToolEffectWindow(private val capacity: Int) {
    private val buffer = ArrayDeque<ToolEffect>()

    fun record(effect: ToolEffect) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(effect)
    }

    fun clear() = buffer.clear()

    val isFull: Boolean get() = buffer.size >= capacity

    fun count(effect: ToolEffect): Int = buffer.count { it == effect }

    /** 仅用于测试与日志：窗口内容的可读快照 */
    fun snapshot(): List<ToolEffect> = buffer.toList()
}
