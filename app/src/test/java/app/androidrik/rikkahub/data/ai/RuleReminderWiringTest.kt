package app.androidrik.rikkahub.data.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则提醒（探索/落地守卫）的**接线守卫**。
 *
 * 为什么用源码扫描：这条链路的价值全在"**接在哪、接几处**"——记录点必须与 LoopGuard 同处
 * （那里有"一次响应里的重复下发只算一次"的批内去重，窗口密度才不失真）；求值必须在**每一步**
 * 的发送路径上；reset 必须与护栏家族同处（少一处就会跨轮残留）；默认值必须是**开**。
 * 这些都不是行为测试能覆盖的（行为测试只会喂一个引擎实例，接线断了它照样绿）。
 *
 * 行为侧另见 [RuleEngineTest] / [ToolEffectTest] / `PromptAssemblyTest`。
 * 依据：`docs/design/design-rule-engine.md`。
 */
class RuleReminderWiringTest {

    private fun srcRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，工作目录=${File(".").absolutePath}")
    }

    private fun source(relative: String): String = File(srcRoot(), relative).readText()

    private val handler = "app/androidrik/rikkahub/data/ai/GenerationHandler.kt"
    private val chatService = "app/androidrik/rikkahub/service/ChatService.kt"
    private val session = "app/androidrik/rikkahub/service/ConversationSession.kt"

    /** 记录点必须与 LoopGuard 同处：那里已有批内去重，另起一处会让窗口密度失真。 */
    @Test
    fun `tool effects are recorded at the loop guard site`() {
        val src = source(handler)
        val effectAt = src.indexOf("ruleEngine.record(tool.toolName")
        val guardAt = src.indexOf("loopGuard.record(tool.toolName")
        assertTrue("没有记录效果 ⇒ 窗口永远空，规则永不触发", effectAt >= 0)
        assertTrue("没有 LoopGuard 记录点？", guardAt >= 0)
        assertTrue(
            "两处记录应紧邻（同一次批内去重之后），实际相距 ${kotlin.math.abs(effectAt - guardAt)} 字符",
            kotlin.math.abs(effectAt - guardAt) < 400,
        )
    }

    /** 求值必须在**每一步**的发送路径上，且必须在工具结果回填之后（否则窗口里没有本步的调用）。 */
    @Test
    fun `reminder is evaluated per step inside the send path`() {
        val src = source(handler)
        val stepLoop = src.indexOf("for (stepIndex in 0 until maxSteps)")
        val evaluateAt = src.indexOf("val ruleReminder = ruleEngine.reminder(")
        val sendAt = src.indexOf("if (pendingTools.isEmpty()) {")
        val generateAt = src.indexOf("generateInternal(", sendAt)

        assertTrue("没有求值调用", evaluateAt >= 0)
        assertTrue("求值不在工具循环内 ⇒ 本轮内不会生效", stepLoop in 0 until evaluateAt)
        assertTrue("求值不在发送路径上", sendAt in 0 until evaluateAt)
        assertTrue("求值应在真正发送之后（不发送就别求值）", generateAt > evaluateAt)

        // 算了就一定要发出去：reminder() 有副作用（标记已提醒），所以要跟着 reminder 一起传下去
        assertTrue(
            "求值结果没有传给 generateInternal ⇒ 提醒被静默丢弃",
            // 参数在 `generateInternal(` 之后才出现，故看调用开头往后的一段
            src.substring(generateAt, minOf(generateAt + 800, src.length)).contains("ruleReminder = ruleReminder,"),
        )
    }

    @Test
    fun `reminder is threaded into the internal message assembly`() {
        val src = source(handler)
        assertTrue(
            "generateInternal 没把规则提醒交给 buildInternalMessages",
            src.contains("ruleReminder = ruleReminder,") &&
                src.substring(src.indexOf("private suspend fun generateInternal")).contains("ruleReminder = ruleReminder,"),
        )
    }

    /** reset 必须与护栏家族同处：少一处就会把上一轮的效果窗口/已提醒标记带进新一轮。 */
    @Test
    fun `session engine is reset wherever the guard family is reset`() {
        val src = source(chatService)
        val engineResets = Regex("session\\.ruleEngine\\.reset\\(\\)").findAll(src).count()
        val guardResets = Regex("session\\.thinkingGuard\\.reset\\(\\)").findAll(src).count()
        assertTrue("没有任何 reset ⇒ 跨轮残留", engineResets > 0)
        assertEquals("reset 处数与护栏家族不一致（少了/多了）", guardResets, engineResets)
    }

    @Test
    fun `session holds the engine and the chat path passes it`() {
        assertTrue(
            "引擎没挂在会话上 ⇒ 跨审批续跑会丢窗口",
            source(session).contains("val ruleEngine = RuleEngine("),
        )
        assertTrue(
            "ChatService 没把会话上的引擎传进 generateText",
            source(chatService).contains("ruleEngine = session.ruleEngine,"),
        )
    }

    /**
     * 预算必须真的喂进引擎 —— 规则表里加了 `budget_near_limit` 却没有输入，
     * 它会**永远不命中**，而且是静默的（没有编译错误、没有异常）。
     */
    @Test
    fun `budget ratio is fed into the reminder evaluation`() {
        val src = source(handler)
        val evaluateAt = src.indexOf("ruleEngine.reminder(")
        assertTrue("没有求值调用", evaluateAt >= 0)
        assertTrue(
            "求值处没传预算比例 ⇒ budget_near_limit 永远不命中",
            src.substring(evaluateAt, minOf(evaluateAt + 600, src.length))
                .contains("budgetRatio = workspaceBudget.usageRatio()"),
        )
    }

    /**
     * 计划状态必须真的喂进引擎，而且**必须两部分都喂**：
     * ①本轮开始时分支里是否已有在进行中的计划；②本轮中途是否写过计划。
     * 少了 ① 会在有计划的会话里反复催；少了 ② 会让"模型刚写完计划就被继续催"这种自相矛盾的提醒出现。
     */
    @Test
    fun `plan state is fed into the reminder evaluation`() {
        val src = source(handler)
        val evaluateAt = src.indexOf("ruleEngine.reminder(")
        assertTrue("没有求值调用", evaluateAt >= 0)
        val call = src.substring(evaluateAt, minOf(evaluateAt + 600, src.length))
        assertTrue(
            "求值处没传计划状态 ⇒ plan_missing 永远不命中",
            call.contains("hasActivePlan = planAtTurnStart || planWrittenThisTurn"),
        )
        assertTrue(
            "求值处没传计划工具可用性 ⇒ 工具没启用时也会催模型去调它",
            call.contains("planToolAvailable = planToolAvailable"),
        )
    }

    /** 每轮只算一次的两个输入：从消息派生计划、从工具表取可用性。 */
    @Test
    fun `plan inputs are computed once per turn from messages and the tool list`() {
        val src = source(handler)
        assertTrue(
            "没从消息里派生计划",
            src.contains("val planAtTurnStart = latestTodoPlan(messages).hasUnfinishedItems()"),
        )
        assertTrue(
            "没检查计划工具是否可用",
            src.contains("val planToolAvailable = tools.any { it.name == TODO_TOOL_NAME }"),
        )
        assertTrue(
            "没在本轮写过计划时置位 ⇒ 刚写的计划压不住提醒",
            src.contains("if (tool.toolName == TODO_TOOL_NAME) planWrittenThisTurn = true"),
        )
        // 置位必须发生在记录点旁边（同一处批内去重之后），另起一处会漏掉批内重复下发
        val recordAt = src.indexOf("ruleEngine.record(tool.toolName")
        val flagAt = src.indexOf("planWrittenThisTurn = true")
        assertTrue("置位不在记录点附近", kotlin.math.abs(flagAt - recordAt) < 300)
    }

    /** 用户明确要求默认开；这条断言防的是"某次改动顺手把它关了"。 */
    @Test
    fun `reminder defaults to on`() {
        val src = source("app/androidrik/rikkahub/data/datastore/PreferencesStore.kt")
        assertTrue("设置项默认值应为 true", src.contains("val ruleReminderEnabled: Boolean = true"))
        assertTrue(
            "会话默认值应为 true（未配置时也要生效）",
            source(session).contains("ruleReminderEnabled: Boolean = true"),
        )
    }

    /** 「可配置化」要落到 UI 上：设置页必须能开关、能选档位。 */
    @Test
    fun `settings page exposes the switch and the levels`() {
        val src = source("app/androidrik/rikkahub/ui/pages/setting/SettingPreferencesGeneralPage.kt")
        assertTrue("设置页没有开关", src.contains("settings.copy(ruleReminderEnabled = it)"))
        assertTrue("设置页没有档位选择", src.contains("settings.copy(ruleReminderSensitivity = level.name)"))
        assertTrue("没用上既有档位枚举", src.contains("RuleReminderSensitivity.entries"))
    }
}
