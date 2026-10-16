package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「规则提醒」引擎的当前唯一规则 `explore_without_landing`（借自 bigeye `loop_detector.py`）。
 *
 * 判据（标准档）：最近 12 次工具调用里，探索类 ≥10 **且** 落地类 == 0。
 */
class RuleEngineTest {

    private fun engine(
        enabled: Boolean = true,
        sensitivity: RuleReminderSensitivity = RuleReminderSensitivity.STANDARD,
    ) = RuleEngine(enabled = enabled, sensitivity = sensitivity)

    private fun RuleEngine.recordExplore(times: Int) = repeat(times) { record("workspace_read_file", null) }
    private fun RuleEngine.recordNeutral(times: Int) = repeat(times) { record("mcp_tool_call", null) }
    private fun RuleEngine.recordLanding(times: Int = 1) = repeat(times) { record("workspace_write_file", null) }

    @Test
    fun `does not remind before the window is full`() {
        val engine = engine()

        engine.recordExplore(10) // 标准档窗口 12，还没满

        assertNull("窗口没满就不该提醒", engine.reminder())
    }

    @Test
    fun `reminds when exploration reaches the threshold with zero landing`() {
        val engine = engine()

        engine.recordExplore(10)
        engine.recordNeutral(2)

        val reminder = engine.reminder()
        assertNotNull("10/12 全是探索、零落地 ⇒ 应提醒", reminder)
        assertTrue("要报出实况", reminder!!.contains("In your last 12 tool calls, 10 were read/search/execute"))
    }

    @Test
    fun `a single landing call suppresses the reminder`() {
        val engine = engine()

        engine.recordExplore(11)
        engine.recordLanding()

        assertNull("出现落地即视为有进展，不该提醒", engine.reminder())
    }

    /** 文案结构照抄 bigeye：现象 → 三步自查 → 不要暴露机制。第 3 步是防误报的出口，不能省。 */
    @Test
    fun `reminder text has the phenomenon, the three checks and the no-disclosure rule`() {
        val engine = engine()
        engine.recordExplore(10)
        engine.recordNeutral(2)

        val text = engine.reminder()!!

        assertTrue("先说现象", text.contains("You may be going in circles"))
        assertTrue("第 1 步：怀疑前提", text.contains("Is one of your assumptions wrong?"))
        assertTrue("第 2 步：该不该问用户", text.contains("Should you ask the user?"))
        assertTrue("第 3 步：正常长调研就忽略（防误报出口）", text.contains("ignore this note and continue"))
        assertTrue("要求不暴露机制", text.contains("Do not mention this note"))
        assertTrue("标明不是用户说的话", text.contains("not a user message"))
    }

    @Test
    fun `reminds at most once per turn`() {
        val engine = engine()
        engine.recordExplore(10)
        engine.recordNeutral(2)

        assertNotNull(engine.reminder())
        assertNull("同一条规则每轮只提醒一次", engine.reminder())
        assertNull(engine.reminder())
    }

    @Test
    fun `can remind again after the condition stops matching and returns`() {
        val engine = engine()
        engine.recordExplore(10)
        engine.recordNeutral(2)
        assertNotNull(engine.reminder())

        // 条件不再命中 ⇒ 解除已提醒标记（bigeye 的 fired.json 语义）
        engine.recordLanding()
        assertNull(engine.reminder())

        // 落地滑出窗口、探索重新累积 ⇒ 允许再提醒一次
        engine.recordExplore(12)

        assertNotNull("同一问题复现时应能再提醒", engine.reminder())
    }

    @Test
    fun `neutral calls count as neither progress nor exploration`() {
        val engine = engine()

        engine.recordNeutral(12)

        assertNull("全是不认识的工具：既不算探索也不算进展 ⇒ 窗口虽满但不触发", engine.reminder())
    }

    @Test
    fun `memory tool actions feed the window through the classifier`() {
        val engine = engine()

        repeat(10) { engine.record("memory_tool", buildJsonObject { put("action", "read") }) }
        engine.recordNeutral(2)

        assertNotNull("memory_tool 的 read 算探索", engine.reminder())

        val landingEngine = engine()
        landingEngine.recordExplore(11)
        landingEngine.record("memory_tool", buildJsonObject { put("action", "create") })

        assertNull("memory_tool 的 create 算落地", landingEngine.reminder())
    }

    @Test
    fun `disabled engine never reminds`() {
        val engine = engine(enabled = false)

        engine.recordExplore(12)

        assertNull("关掉就不该产生任何注入", engine.reminder())
    }

    // ── 灵敏度三档（沿用 LoopGuardSensitivity 的档位形态，不做自由数字）──

    @Test
    fun `strict level fires earlier than standard`() {
        val strict = engine(sensitivity = RuleReminderSensitivity.STRICT)
        strict.recordExplore(8)
        strict.recordNeutral(2) // 严格档窗口 10 / 阈值 8

        assertNotNull("严格档：8/10 探索就该提醒", strict.reminder())

        val standard = engine(sensitivity = RuleReminderSensitivity.STANDARD)
        standard.recordExplore(8)
        standard.recordNeutral(2)

        assertNull("标准档：窗口还没满", standard.reminder())
    }

    @Test
    fun `loose level tolerates more exploration before firing`() {
        val loose = engine(sensitivity = RuleReminderSensitivity.LOOSE)

        loose.recordExplore(14) // 宽松档窗口 16 / 阈值 14：14 次时窗口都还没满

        assertNull("宽松档：连窗口都没满", loose.reminder())

        loose.recordExplore(2)

        assertNotNull("达到宽松档阈值才提醒", loose.reminder())

        // 注意滑窗：容量 16 装不下「14 次探索 + 若干中性」——超容量时最旧的探索会被挤出去，
        // 所以宽松档真正能容忍的是「窗口更长」（同样的探索次数更不容易触顶），而不是"探索多还带中性"。
    }

    @Test
    fun `parse falls back to standard on unknown value`() {
        assertEquals(RuleReminderSensitivity.STRICT, parseRuleReminderSensitivity("STRICT"))
        assertEquals(RuleReminderSensitivity.STANDARD, parseRuleReminderSensitivity(null))
        assertEquals(RuleReminderSensitivity.STANDARD, parseRuleReminderSensitivity("NOPE"))
    }

    @Test
    fun `reset clears the window and the fired marks`() {
        val engine = engine()
        engine.recordExplore(10)
        engine.recordNeutral(2)
        assertNotNull(engine.reminder())

        engine.reset()

        assertTrue("窗口应清空", engine.windowSnapshot().isEmpty())
        assertNull("刚重置、窗口为空 ⇒ 不该提醒", engine.reminder())

        engine.recordExplore(10)
        engine.recordNeutral(2)

        assertNotNull("重置后重新累积应能再提醒（fired 也清空了）", engine.reminder())
    }

    /**
     * 端到端形状（JVM 层）：窗口记满 → 引擎求值 → 结果落到 `buildInternalMessages` 的尾部。
     *
     * 钉的是两段之间的**契约**：引擎返回的文本必须能原样成为尾部合成消息，
     * 且**没命中时一个消息都不多**。真机侧另有假上游复现（见 handoff）。
     */
    @Test
    fun `engine output becomes the tail block and nothing else when idle`() {
        val assistant = Assistant()
        val settings = Settings.dummy()
        val model = app.androidrik.ai.provider.Model(modelId = "test-model", displayName = "Test")
        fun assemble(reminder: String?) = buildInternalMessages(
            assistant = assistant,
            settings = settings,
            model = model,
            messages = listOf(
                UIMessage.user(prompt = "hi").copy(
                    parts = listOf(UIMessagePart.Text("hi")),
                ),
            ),
            memories = emptyList(),
            tools = emptyList(),
            conversationSystemPrompt = null,
            compressionWindow = null,
            ruleReminder = reminder,
        )

        val idle = assemble(null)
        val engine = engine()

        // 还没到位：求值为 null ⇒ 拼装结果与不传参数逐字节同构
        engine.recordExplore(6)
        assertNull(engine.reminder())
        assertEquals(idle.size, assemble(null).size)

        // 到位：求值有文本 ⇒ 尾部多出一条合成消息，内容就是引擎给的
        engine.recordExplore(4)
        engine.recordNeutral(2)
        val reminder = engine.reminder()
        assertNotNull(reminder)

        val injected = assemble(reminder)
        assertEquals("只多一条尾部消息", idle.size + 1, injected.size)
        assertTrue(
            "尾部消息内容必须就是引擎给的文本",
            injected.last().parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains(reminder!!.trim()) },
        )
    }
}
