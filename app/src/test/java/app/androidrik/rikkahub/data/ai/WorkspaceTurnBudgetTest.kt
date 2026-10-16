package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * workspace（rootfs）单轮总预算护栏（LoopGuard 项的剩余部分）。
 *
 * 防的形态：模型**每步换参数**的 workspace 调用（`cat file1`、`cat file2`…）——
 * `ToolCallLoopGuard` 只拦同签名重复，防不住这种；全局 `maxSteps`（256）兜底太晚，
 * 单轮累计输出此前无上限（256 步 × 32K = 8M chars）。
 *
 * 判定语义与 LoopGuard 一致：**执行后判定、优雅停止**（结果照常回填后结束本轮）；
 * 状态挂会话、跨审批续跑累积（挂载教训同源）。
 */
class WorkspaceTurnBudgetTest {

    @Test
    fun `under budget continues`() {
        val budget = WorkspaceTurnBudget()
        repeat(10) {
            assertEquals(WorkspaceTurnBudget.Verdict.Continue, budget.record(1024))
        }
        // 累计可见（诊断日志与提示要用）
        assertEquals(10_240L, budget.usedOutputChars)
        assertEquals(10, budget.usedSteps)
    }

    @Test
    fun `output budget exceeded on the call that crosses the line`() {
        val budget = WorkspaceTurnBudget()
        // 默认 256K：3 个满额输出（32K×3=96K）不超
        repeat(3) {
            assertEquals(WorkspaceTurnBudget.Verdict.Continue, budget.record(32 * 1024))
        }
        // 继续满额输出，超限发生在累计首次 > 256K 的那次（96K + 32K×5 = 256K 恰好允许，
        // 第 6 次累计 288K > 256K 报 Exceeded）
        var verdict: WorkspaceTurnBudget.Verdict = WorkspaceTurnBudget.Verdict.Continue
        while (verdict is WorkspaceTurnBudget.Verdict.Continue) {
            verdict = budget.record(32 * 1024)
        }
        verdict as WorkspaceTurnBudget.Verdict.Exceeded
        assertEquals(WorkspaceTurnBudget.ExceedReason.OUTPUT, verdict.by)
        assertEquals(288 * 1024L, verdict.usedChars)
        assertEquals((256 * 1024).toLong(), verdict.budgetChars)
        // 超限后继续 record 仍返回 Exceeded（粘性），但调用点只在首次设提示
        assertTrue(budget.record(0) is WorkspaceTurnBudget.Verdict.Exceeded)
    }

    @Test
    fun `step budget exceeded with tiny outputs`() {
        val budget = WorkspaceTurnBudget(maxSteps = 4)
        repeat(4) {
            assertEquals(WorkspaceTurnBudget.Verdict.Continue, budget.record(0))
        }
        val verdict = budget.record(0)
        assertTrue(verdict is WorkspaceTurnBudget.Verdict.Exceeded)
        assertEquals(WorkspaceTurnBudget.ExceedReason.STEPS, (verdict as WorkspaceTurnBudget.Verdict.Exceeded).by)
    }

    @Test
    fun `zero-output steps still count toward steps`() {
        // 失败的调用没有输出可计，但仍是一步（调用点对失败调用也应 record(0)——
        // 否则「反复失败的 shell 调用」不消耗预算，与「反复成功」同样失控）
        val budget = WorkspaceTurnBudget(maxSteps = 2)
        assertEquals(WorkspaceTurnBudget.Verdict.Continue, budget.record(0))
        assertEquals(WorkspaceTurnBudget.Verdict.Continue, budget.record(0))
        assertTrue(budget.record(0) is WorkspaceTurnBudget.Verdict.Exceeded)
    }

    @Test
    fun `reset clears both counters`() {
        val budget = WorkspaceTurnBudget()
        budget.record(300 * 1024) // 远超预算
        assertTrue(budget.record(0) is WorkspaceTurnBudget.Verdict.Exceeded)

        budget.reset()
        assertEquals(0L, budget.usedOutputChars)
        assertEquals(0, budget.usedSteps)
        // 新一轮从干净额度开始
        assertEquals(WorkspaceTurnBudget.Verdict.Continue, budget.record(1024))
    }

    @Test
    fun `exact budget boundary is allowed`() {
        // 恰好等于预算不超（> 判定）：8 × 32K = 256K 允许，第 9 次才停
        val budget = WorkspaceTurnBudget()
        repeat(8) {
            assertEquals(WorkspaceTurnBudget.Verdict.Continue, budget.record(32 * 1024))
        }
        assertTrue(budget.record(1) is WorkspaceTurnBudget.Verdict.Exceeded)
    }

    @Test
    fun `non-workspace tools are not charged - prefix check is the caller contract`() {
        // 计费过滤在调用点（toolName.startsWith("workspace_")），本类只管记账。
        // 这里钉住前缀常量本身：workspace_* 五个工具都应命中。
        listOf(
            "workspace_read_file",
            "workspace_write_file",
            "workspace_edit_file",
            "workspace_shell",
            "workspace_present_file",
        ).forEach {
            assertTrue("'$it' 应以 workspace_ 前缀计费", it.startsWith(WorkspaceTurnBudget.TOOL_NAME_PREFIX))
        }
    }

    // ── usageRatio：给「规则提醒」的 budget_near_limit 用（撞上限**之前**提醒收敛）──

    @Test
    fun `usage ratio is zero before any workspace call`() {
        val budget = WorkspaceTurnBudget(outputBudgetChars = 1000, maxSteps = 10)

        assertEquals(0.0, budget.usageRatio(), 0.0001)
    }

    @Test
    fun `usage ratio takes the larger of characters and steps`() {
        val budget = WorkspaceTurnBudget(outputBudgetChars = 1000, maxSteps = 10)

        budget.record(200) // 字符 20%，步数 10% ⇒ 取 20%

        assertEquals(0.2, budget.usageRatio(), 0.0001)

        repeat(7) { budget.record(0) } // 步数 80%，字符仍 20% ⇒ 取 80%

        assertEquals(0.8, budget.usageRatio(), 0.0001)
    }

    @Test
    fun `usage ratio passes one after exceeding`() {
        val budget = WorkspaceTurnBudget(outputBudgetChars = 1000, maxSteps = 10)

        budget.record(2500)

        assertTrue("超了要如实反映（>1），提醒文案据此报百分比", budget.usageRatio() > 1.0)
    }

    @Test
    fun `usage ratio survives zero limits instead of dividing by zero`() {
        val budget = WorkspaceTurnBudget(outputBudgetChars = 0, maxSteps = 0)

        budget.record(10)

        assertTrue("不能抛也不能 NaN", budget.usageRatio().isFinite())
    }
}
