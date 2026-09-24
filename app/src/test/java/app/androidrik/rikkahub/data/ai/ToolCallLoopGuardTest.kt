package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用失控护栏（[ToolCallLoopGuard]）。
 *
 * 重点钉住三组易被破坏的契约：
 * ① **失败不算成功重复**（`markFailed`，同源实现的 `forget()` 补丁）；
 * ② **但反复失败也要停**——这是本仓对补丁语义的**有意偏离**：按字面「移出窗口」实现时，
 *    真机实测「反复读不存在的文件」永远不触发（见类注释）；
 * ③ 轮询类工具豁免，否则模型连问几次时间就被误判。
 */
class ToolCallLoopGuardTest {

    private val sig = "tool\u0000{\"a\":1}"

    private fun ToolCallLoopGuard.recordTimes(n: Int, signature: String = sig, tool: String = "read_file") =
        (1..n).map { record(tool, signature) }

    // ---- 成功路径基本判定 ----

    @Test
    fun `single call does not trigger`() {
        assertEquals(ToolCallLoopGuard.Verdict.Continue, ToolCallLoopGuard().record("read_file", sig))
    }

    @Test
    fun `two identical successful calls do not trigger (below threshold)`() {
        val verdicts = ToolCallLoopGuard().recordTimes(2)
        assertTrue(verdicts.all { it == ToolCallLoopGuard.Verdict.Continue })
    }

    @Test
    fun `three identical successful calls trigger with repeat count`() {
        val verdicts = ToolCallLoopGuard().recordTimes(3)
        assertTrue(verdicts.take(2).all { it == ToolCallLoopGuard.Verdict.Continue })
        val last = verdicts.last()
        assertTrue(last is ToolCallLoopGuard.Verdict.Loop)
        last as ToolCallLoopGuard.Verdict.Loop
        assertEquals("read_file", last.toolName)
        assertEquals(3, last.repeats)
        assertFalse("成功路径命中时 failed 应为 false", last.failed)
    }

    @Test
    fun `different args do not count as the same call`() {
        val guard = ToolCallLoopGuard()
        guard.record("read_file", "read_file\u0000{\"path\":\"a\"}")
        guard.record("read_file", "read_file\u0000{\"path\":\"b\"}")
        assertEquals(
            ToolCallLoopGuard.Verdict.Continue,
            guard.record("read_file", "read_file\u0000{\"path\":\"c\"}"),
        )
    }

    @Test
    fun `alternating two tools still triggers on the repeat that matters`() {
        val a = "read_file\u0000{}"
        val b = "list_dir\u0000{}"
        val guard = ToolCallLoopGuard()
        guard.record("read_file", a)
        guard.record("list_dir", b)
        guard.record("read_file", a)
        guard.record("list_dir", b)
        assertTrue("A 第三次出现应命中", guard.record("read_file", a) is ToolCallLoopGuard.Verdict.Loop)
    }

    @Test
    fun `old history falls out of the sliding window`() {
        val guard = ToolCallLoopGuard(windowSize = 3, successThreshold = 3, failureThreshold = 3)
        guard.record("read_file", sig)
        guard.record("read_file", sig)
        guard.record("x", "x1")
        guard.record("y", "y1")
        assertEquals(ToolCallLoopGuard.Verdict.Continue, guard.record("read_file", sig))
    }

    // ---- markFailed：失败不算成功重复（补丁意图） ----

    @Test
    fun `marked failed attempts do not count toward the success threshold`() {
        val guard = ToolCallLoopGuard()
        guard.record("read_file", sig)
        guard.markFailed(sig) // 该次失败
        guard.record("read_file", sig)
        guard.markFailed(sig)
        // 两次失败都不算成功重复 ⇒ 第 3 次不应因「成功 3 连」而命中
        val verdict = guard.record("read_file", sig)
        assertEquals("失败调用不该累计成成功重复", ToolCallLoopGuard.Verdict.Continue, verdict)
    }

    @Test
    fun `network hiccup retry is allowed`() {
        // 真实恢复路径：先失败（网络抖动），重试仍失败，第三次成功 —— 全程不应命中。
        // 注意顺序：失败在前才叫「抖动后恢复」；若先连续成功两次，第 3 次本身就是循环（见下一条）。
        val guard = ToolCallLoopGuard()
        repeat(2) {
            guard.record("read_file", sig)
            guard.markFailed(sig)
        }
        assertEquals(
            "两次失败后的成功重试应放行",
            ToolCallLoopGuard.Verdict.Continue,
            guard.record("read_file", sig),
        )
    }

    @Test
    fun `two successes then a third identical request is a loop (success rule unchanged)`() {
        val guard = ToolCallLoopGuard()
        guard.record("read_file", sig)
        guard.record("read_file", sig)
        val verdict = guard.record("read_file", sig)
        assertTrue("相同调用第 3 次即停（与失败无关）", verdict is ToolCallLoopGuard.Verdict.Loop)
        assertFalse((verdict as ToolCallLoopGuard.Verdict.Loop).failed)
    }

    @Test
    fun `markFailed only marks the most recent matching attempt`() {
        val guard = ToolCallLoopGuard()
        guard.record("read_file", sig)
        guard.record("read_file", sig)
        guard.markFailed(sig) // 只标最近一次
        // 窗口内仍是「1 次成功 + 1 次失败」；再记一次成功 ⇒ 成功计数 2，未达 3
        assertEquals(ToolCallLoopGuard.Verdict.Continue, guard.record("read_file", sig))
        // 再来一次成功 ⇒ 成功计数 3，命中
        val verdict = guard.record("read_file", sig)
        assertTrue(verdict is ToolCallLoopGuard.Verdict.Loop)
        assertFalse((verdict as ToolCallLoopGuard.Verdict.Loop).failed)
    }

    @Test
    fun `markFailed on unknown signature is a no-op`() {
        val guard = ToolCallLoopGuard()
        guard.record("read_file", sig)
        guard.markFailed("never-recorded")
        assertEquals(ToolCallLoopGuard.Verdict.Continue, guard.record("read_file", sig))
    }

    // ---- 反复失败也要停（本仓对补丁语义的偏离，真机事故驱动） ----

    @Test
    fun `repeated identical failures eventually trigger with the failure threshold`() {
        // 真机事故：模型反复读一个不存在的文件，每次都抛异常。
        // 若按同源实现「失败即移出窗口」，窗口恒为 1、永不触发 ⇒ 可重试到 256 步。
        //
        // 语义：计数只统计**已标记失败**的历史，故允许 failureThreshold 次失败重试，
        // 第 failureThreshold+1 次相同请求才被拦下（即「最多试 N 次」）。
        val guard = ToolCallLoopGuard()
        val allowed = (1..ToolCallLoopGuard.DEFAULT_FAILURE_THRESHOLD).map {
            val v = guard.record("read_file", sig)
            guard.markFailed(sig)
            v
        }
        assertTrue(
            "前 $ToolCallLoopGuard.DEFAULT_FAILURE_THRESHOLD 次失败重试应放行",
            allowed.all { it == ToolCallLoopGuard.Verdict.Continue },
        )
        val verdict = guard.record("read_file", sig)
        assertTrue(
            "反复撞同一个必然失败必须能停（这正是对 forget 语义的偏离所在）",
            verdict is ToolCallLoopGuard.Verdict.Loop,
        )
        val loop = verdict as ToolCallLoopGuard.Verdict.Loop
        assertTrue("应报告为失败路径命中", loop.failed)
        assertEquals(ToolCallLoopGuard.DEFAULT_FAILURE_THRESHOLD, loop.repeats)
    }

    @Test
    fun `failure threshold is higher than success threshold`() {
        // 失败多给一次机会（网络抖动的合理重试），但仍有界
        val guard = ToolCallLoopGuard()
        val verdicts = (1..3).map {
            val v = guard.record("read_file", sig)
            guard.markFailed(sig)
            v
        }
        assertTrue(
            "3 次失败不应命中（比成功路径多一次余量）",
            verdicts.all { it == ToolCallLoopGuard.Verdict.Continue },
        )
    }

    @Test
    fun `success triggers before failures exhaust their higher threshold`() {
        val guard = ToolCallLoopGuard()
        guard.record("read_file", sig)
        guard.markFailed(sig)
        guard.record("read_file", sig)
        guard.record("read_file", sig)
        // 3 次成功（其中一次失败已标记）已达成功阈值 → 命中成功路径
        val verdict = guard.record("read_file", sig)
        assertTrue(verdict is ToolCallLoopGuard.Verdict.Loop)
        assertFalse((verdict as ToolCallLoopGuard.Verdict.Loop).failed)
    }

    // ---- reset ----

    @Test
    fun `reset clears the window`() {
        val guard = ToolCallLoopGuard()
        guard.record("read_file", sig)
        guard.record("read_file", sig)
        guard.reset()
        assertEquals(ToolCallLoopGuard.Verdict.Continue, guard.record("read_file", sig))
    }

    // ---- 轮询豁免 ----

    @Test
    fun `polling tool is exempt from counting`() {
        val guard = ToolCallLoopGuard()
        val timeSig = "get_time_info\u0000{}"
        val verdicts = (1..10).map { guard.record("get_time_info", timeSig) }
        assertTrue(
            "取时间是轮询行为，结果本就每次不同，不该被判成死循环",
            verdicts.all { it == ToolCallLoopGuard.Verdict.Continue },
        )
    }

    @Test
    fun `exempt tool does not pollute the window for other tools`() {
        val guard = ToolCallLoopGuard(windowSize = 3, successThreshold = 3, failureThreshold = 3)
        guard.record("read_file", sig)
        guard.record("get_time_info", "get_time_info\u0000{}")
        guard.record("read_file", sig)
        assertTrue(guard.record("read_file", sig) is ToolCallLoopGuard.Verdict.Loop)
    }

    // ---- 与既有 dedup 的关系（同一响应内的重复下发） ----

    @Test
    fun `in batch duplicates recorded once do not trigger by themselves`() {
        // Gemini 系会在一响应里下发内容相同、id 不同的两条调用；调用点只在**批内首次**记录。
        // 此用例模拟「一次响应 = 一次 record」的约定：两步各记一次 → 不应命中。
        // 若调用点退化成逐条记录，两步就会变成 4 次而误判。
        val guard = ToolCallLoopGuard()
        guard.record("memory_tool", sig)
        assertFalse(guard.record("memory_tool", sig) is ToolCallLoopGuard.Verdict.Loop)
    }
}
