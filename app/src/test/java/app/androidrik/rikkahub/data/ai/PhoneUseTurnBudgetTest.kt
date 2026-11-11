package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone Use 单轮步数上限：执行后判定、超限那一次仍回填结果，随后本轮优雅停止。
 * 状态挂会话、跨审批续跑累积；粘性判定但调用点只报一次。
 */
class PhoneUseTurnBudgetTest {

    @Test
    fun `under limit continues and counts every call`() {
        val budget = PhoneUseTurnBudget()
        repeat(PhoneUseTurnBudget.DEFAULT_MAX_STEPS) {
            assertEquals(PhoneUseTurnBudget.Verdict.Continue, budget.record())
        }
        assertEquals(PhoneUseTurnBudget.DEFAULT_MAX_STEPS, budget.usedSteps)
    }

    @Test
    fun `exceeded on the call that crosses the limit`() {
        val budget = PhoneUseTurnBudget(maxSteps = 3)
        repeat(3) {
            assertEquals(PhoneUseTurnBudget.Verdict.Continue, budget.record())
        }

        val verdict = budget.record() as PhoneUseTurnBudget.Verdict.Exceeded
        assertEquals(4, verdict.steps)
        assertEquals(3, verdict.limit)
        // 粘性：超限后继续记录仍是 Exceeded，但调用点只在首次设提示
        assertTrue(budget.record() is PhoneUseTurnBudget.Verdict.Exceeded)
    }

    @Test
    fun `reset clears usage for the next turn`() {
        val budget = PhoneUseTurnBudget(maxSteps = 1)
        budget.record()
        assertTrue(budget.record() is PhoneUseTurnBudget.Verdict.Exceeded)

        budget.reset()
        assertEquals(0, budget.usedSteps)
        assertEquals(PhoneUseTurnBudget.Verdict.Continue, budget.record())
    }
}
