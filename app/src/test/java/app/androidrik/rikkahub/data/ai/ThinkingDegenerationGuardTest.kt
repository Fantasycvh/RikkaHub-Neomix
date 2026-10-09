package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考退化护栏（家族第四员）的判定测试。
 *
 * 判据来源：DSH discussion #5976（零工具调用的退化三形态）+ 社区 Cordis 插件的标定数据
 * （逐字判据 vs 低熵比值：后者对合法内容只差 0.06，不足以作为截断边界）。
 */
class ThinkingDegenerationGuardTest {

    private fun reasoningDelta(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Reasoning(reasoning = text)),
    )

    private fun textDelta(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun toolDelta() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Tool(toolCallId = "c1", toolName = "t", input = "{}")),
    )

    private fun feed(guard: ThinkingDegenerationGuard, count: Int, text: String) {
        repeat(count) { guard.onDelta(reasoningDelta(text)) }
    }

    // ===== 调用边界：纯思考空转 / 复述上一步 =====

    @Test
    fun `three consecutive reasoning-only calls stall`() {
        val guard = ThinkingDegenerationGuard()
        val longReasoning = "思考".repeat(1500) // 3000 字符 > minReasoningChars

        assertEquals(
            ThinkingDegenerationGuard.Verdict.Continue,
            guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = false),
        )
        assertEquals(
            ThinkingDegenerationGuard.Verdict.Continue,
            guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = false),
        )
        val third = guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = false)
        assertTrue("第三次应判定停止，实际=$third", third is ThinkingDegenerationGuard.Verdict.Stalled)
        assertEquals(3, (third as ThinkingDegenerationGuard.Verdict.Stalled).stalledCalls)
    }

    @Test
    fun `short reasoning-only calls do not count`() {
        val guard = ThinkingDegenerationGuard()
        repeat(5) {
            assertEquals(
                ThinkingDegenerationGuard.Verdict.Continue,
                guard.onCallFinished("ok, continuing", hasVisibleText = false, hasToolCalls = false),
            )
        }
        assertEquals(0, guard.stalledCallCount)
    }

    @Test
    fun `different reasoning with visible text resets the stall counter`() {
        val guard = ThinkingDegenerationGuard()
        val longReasoning = "思考".repeat(1500)
        val freshReasoning = "换了一条完全不同的思路：先查依赖，再改配置，最后跑测试。".repeat(60)

        guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = false)
        guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = false)
        assertEquals(2, guard.stalledCallCount)

        // 有正文 + 推理内容与上一步不同 = 真在推进：计数清零
        assertEquals(
            ThinkingDegenerationGuard.Verdict.Continue,
            guard.onCallFinished(freshReasoning, hasVisibleText = true, hasToolCalls = false),
        )
        assertEquals(0, guard.stalledCallCount)
    }

    @Test
    fun `a call that produces tool calls resets the stall counter`() {
        val guard = ThinkingDegenerationGuard()
        val longReasoning = "思考".repeat(1500)
        guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = false)
        guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = false)
        assertEquals(2, guard.stalledCallCount)

        // 工具调用 = 实打实的进展：即使推理写得一样长也清零
        //（工具类失控另有 ToolCallLoopGuard / WorkspaceTurnBudget 负责）
        assertEquals(
            ThinkingDegenerationGuard.Verdict.Continue,
            guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = true),
        )
        assertEquals(0, guard.stalledCallCount)
    }

    @Test
    fun `restated reasoning with some visible text counts as stalled`() {
        val guard = ThinkingDegenerationGuard()
        val reasoning = "第一步已经确认了 A、B、C 三件事，接下来我要把结论整理成一段完整的说明文字。".repeat(80)
        val slightlyExtended = reasoning + "另外还要补充一句。"

        // 第一次没有「上一步」可比 ⇒ 不计数
        guard.onCallFinished(reasoning, hasVisibleText = true, hasToolCalls = false)
        assertEquals(0, guard.stalledCallCount)

        // 之后连续三次「复述上一步 + 少量正文」：每次都像在推进（有正文），实质是原地打转
        guard.onCallFinished(slightlyExtended, hasVisibleText = true, hasToolCalls = false)
        assertEquals(1, guard.stalledCallCount)
        guard.onCallFinished(slightlyExtended, hasVisibleText = true, hasToolCalls = false)
        assertEquals(2, guard.stalledCallCount)
        val third = guard.onCallFinished(slightlyExtended, hasVisibleText = true, hasToolCalls = false)
        assertTrue("复述上一步应计入并最终停止，实际=$third", third is ThinkingDegenerationGuard.Verdict.Stalled)
        assertTrue((third as ThinkingDegenerationGuard.Verdict.Stalled).restated)
    }

    @Test
    fun `reset clears both call-boundary and intra-call state`() {
        val guard = ThinkingDegenerationGuard()
        val longReasoning = "思考".repeat(1500)
        guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = false)
        guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = false)
        feed(guard, 59, "同一段文本")

        guard.reset()

        assertEquals(0, guard.stalledCallCount)
        assertNull(guard.takeCut())
        // 重置后两处状态都不残留：不再前进，也不再接近断流阈值
        assertEquals(
            ThinkingDegenerationGuard.Verdict.Continue,
            guard.onCallFinished(longReasoning, hasVisibleText = false, hasToolCalls = false),
        )
        assertEquals(1, guard.stalledCallCount)
        assertEquals(ThinkingDegenerationGuard.Verdict.Continue, guard.onDelta(reasoningDelta("同一段文本")))
    }

    // ===== 调用内：逐字重复（周期 1）=====

    @Test
    fun `sixty identical deltas cut the stream`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        feed(guard, 59, "好。")
        val verdict = guard.onDelta(reasoningDelta("好。"))
        assertTrue("第 60 个相同 delta 应断流，实际=$verdict", verdict is ThinkingDegenerationGuard.Verdict.Repeating)
        assertEquals(
            ThinkingDegenerationGuard.Rule.IDENTICAL_DELTAS,
            (verdict as ThinkingDegenerationGuard.Verdict.Repeating).rule,
        )
        assertEquals(60, verdict.repeated)
        // 断流后不再重复报警（调用方可能在收尾前又收到几个 delta）
        assertEquals(ThinkingDegenerationGuard.Verdict.Continue, guard.onDelta(reasoningDelta("好。")))
        assertEquals(60, guard.takeCut()?.repeated)
        assertNull("takeCut 应清空", guard.takeCut())
    }

    @Test
    fun `identical deltas are compared after trim and tolerate interleaved blanks`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        repeat(30) { guard.onDelta(reasoningDelta(" 执行。 ")) }
        repeat(29) { guard.onDelta(reasoningDelta("执行。")) }
        // 空 delta 既不推进也不重置
        guard.onDelta(null)
        assertEquals(ThinkingDegenerationGuard.Verdict.Continue, guard.onDelta(null))
        val verdict = guard.onDelta(reasoningDelta("执行。"))
        assertTrue(verdict is ThinkingDegenerationGuard.Verdict.Repeating)
    }

    @Test
    fun `a tool delta means progress and clears the repetition evidence`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        feed(guard, 55, "好。")
        assertEquals(ThinkingDegenerationGuard.Verdict.Continue, guard.onDelta(toolDelta()))
        repeat(55) { guard.onDelta(reasoningDelta("好。")) }
        assertEquals(ThinkingDegenerationGuard.Verdict.Continue, guard.onDelta(reasoningDelta("好。")))
    }

    @Test
    fun `fifty-nine identical deltas are below the threshold`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        feed(guard, 58, "继续。")
        assertEquals(ThinkingDegenerationGuard.Verdict.Continue, guard.onDelta(reasoningDelta("继续。")))
        assertNull(guard.takeCut())
    }

    // ===== 调用内：短句循环（周期 > 1）=====

    @Test
    fun `a short-line cycle cuts the stream once the tail is periodic`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        // #5976 / #7043 的实测形态：周期 5 字符的短句循环（逐字符下发也要能判出）
        val cycle = "好。执行。"
        var verdict: ThinkingDegenerationGuard.Verdict = ThinkingDegenerationGuard.Verdict.Continue
        repeat(320) { index ->
            if (verdict is ThinkingDegenerationGuard.Verdict.Continue) {
                verdict = guard.onDelta(reasoningDelta(cycle[index % cycle.length].toString()))
            }
        }
        assertTrue("逐字周期应断流，实际=$verdict", verdict is ThinkingDegenerationGuard.Verdict.Repeating)
        assertEquals(
            ThinkingDegenerationGuard.Rule.REPEATING_CYCLE,
            (verdict as ThinkingDegenerationGuard.Verdict.Repeating).rule,
        )
        assertEquals("周期应为 5（好。执行。）", 5, verdict.repeated)
    }

    @Test
    fun `cycle detection fires through one delta per line as well`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        // 两种行交替 = 周期 5 字符（「好。」+「执行。」）。
        // 注意每条 delta 都会先 trim（见 onDelta），所以带换行的「好。\n」同样归约成 2 字符——
        // 行式输出不会因为换行符把周期判丢。
        val lines = listOf("好。", "执行。")
        var verdict: ThinkingDegenerationGuard.Verdict = ThinkingDegenerationGuard.Verdict.Continue
        repeat(200) { index ->
            if (verdict is ThinkingDegenerationGuard.Verdict.Continue) {
                verdict = guard.onDelta(reasoningDelta(lines[index % lines.size] + "\n"))
            }
        }
        assertTrue(verdict is ThinkingDegenerationGuard.Verdict.Repeating)
        val repeating = verdict as ThinkingDegenerationGuard.Verdict.Repeating
        assertEquals(ThinkingDegenerationGuard.Rule.REPEATING_CYCLE, repeating.rule)
        assertEquals(5, repeating.repeated)
    }

    // ===== 合法内容不得误杀（逐字判据的关键理由）=====

    @Test
    fun `a long rule line of a single character is not a cycle`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        // 长分隔线：周期 1 但不含 ≥2 个不同字符 ⇒ 不得断流
        repeat(4) { guard.onDelta(textDelta("=".repeat(100))) }
        assertNull("长 ==== 分隔线不该被当循环", guard.takeCut())
    }

    @Test
    fun `a markdown table is not a cycle`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        val table = buildString {
            appendLine("| 项目 | 说明 | 值 |")
            appendLine("|---|---|---|")
            repeat(40) { appendLine("| 行$it | 说明文本 $it | ${it * 3} |") }
        }
        table.chunked(40).forEach { guard.onDelta(textDelta(it)) }
        assertNull("markdown 表格不该被当循环", guard.takeCut())
    }

    @Test
    fun `a json dump is not a cycle`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        val json = buildString {
            appendLine("{")
            repeat(60) { appendLine("""  "field$it": "value-$it",""") }
            appendLine("}")
        }
        json.chunked(60).forEach { guard.onDelta(textDelta(it)) }
        assertNull("JSON 输出不该被当循环", guard.takeCut())
    }

    @Test
    fun `a log listing is not a cycle`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        val logs = (1..80).joinToString("\n") { "2026-10-09 12:00:%02d INFO  worker %d processed %d items".format(it % 60, it, it * 7) }
        logs.chunked(64).forEach { guard.onDelta(textDelta(it)) }
        assertNull("日志清单不该被当循环", guard.takeCut())
    }

    @Test
    fun `boundary - one delta shorter than the span does not trip the cycle rule`() {
        val guard = ThinkingDegenerationGuard()
        guard.onCallStarted()
        guard.onDelta(reasoningDelta("好。".repeat(50)))  // 100 字符 < 256 跨度
        assertNull(guard.takeCut())
    }

    // ===== 纯函数组件 =====

    @Test
    fun `minimalPeriod finds the shortest period`() {
        assertEquals(5, ThinkingDegenerationGuard.minimalPeriod("好。执行。好。执行。"))
        assertEquals(3, ThinkingDegenerationGuard.minimalPeriod("abcabcabc"))
        assertEquals(4, ThinkingDegenerationGuard.minimalPeriod("abcd"))
    }

    @Test
    fun `distinctChars counts the first period only`() {
        assertEquals(1, ThinkingDegenerationGuard.distinctChars("=".repeat(300), 1))
        assertEquals(3, ThinkingDegenerationGuard.distinctChars("abcabc", 3))
    }

    @Test
    fun `containment tolerates appended material but not unrelated text`() {
        val base = "用户的偏好是简洁回答，并且周末更活跃。".repeat(30)
        val appended = base + "另外还要提醒一句关于时区的事。"
        assertTrue(
            "追加内容后仍应视为复述（containment 不会被新内容稀释）",
            ThinkingDegenerationGuard.containment(base, appended) >= 0.8,
        )
        val unrelated = "完全不同的主题：今天讨论了构建脚本与依赖升级的细节。".repeat(30)
        assertTrue(
            "无关文本不该被判为复述",
            ThinkingDegenerationGuard.containment(base, unrelated) < 0.8,
        )
    }
}
