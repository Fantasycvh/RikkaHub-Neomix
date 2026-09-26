package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上下文预算规划器（设计 §十二）。
 *
 * 钉住三组契约（都对"跑偏了会静默"的场景）：
 * ① **usage 锚点优先**：provider 回传的 `promptTokens` 是"这次真的发出去多少"，
 *    比本地估算准；有锚点时**不能再叠固定开销**（锚点已包含 system + 工具 schema，叠了会虚高）；
 * ② 无锚点时退化成本地估算 + 固定开销，并如实标记 `anchored=false`；
 * ③ 判定阈值：剩余不足 [MIN_REPLY_TOKENS] 才是 Exhausted（撞满窗口是硬墙），
 *    ≥ 警告比例只是 Warning（记日志、不打断——打断会丢掉已经攒到的结果）。
 */
class ContextBudgetPlannerTest {

    private fun assistant(text: String, usage: TokenUsage? = null) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
        usage = usage,
    )

    // ---- 锚点 ----

    @Test
    fun `usage anchor wins over the local estimate and ignores overhead`() {
        val messages = listOf(
            UIMessage.user("goal"),
            assistant("step", usage = TokenUsage(promptTokens = 12_000)),
        )

        val budget = planContextBudget(
            windowTokens = 100_000,
            messages = messages,
            overheadTokens = 5_000,
        )

        assertEquals(12_000, budget.usedTokens)
        assertTrue(budget.anchored)
        assertEquals(88_000, budget.remainingTokens)
        assertEquals(ContextBudget.Verdict.Ok, budget.verdict)
    }

    @Test
    fun `only the newest assistant usage is used as the anchor`() {
        val messages = listOf(
            assistant("older", usage = TokenUsage(promptTokens = 3_000)),
            UIMessage.user("more"),
            assistant("newer", usage = TokenUsage(promptTokens = 9_000)),
        )

        val budget = planContextBudget(100_000, messages, overheadTokens = 0)

        assertEquals(9_000, budget.usedTokens)
    }

    @Test
    fun `zero usage falls back to the estimate`() {
        // 有些 provider 不回 usage（或回 0）：不能把 0 当成"没占用"
        val messages = listOf(assistant("x".repeat(400), usage = TokenUsage(promptTokens = 0)))

        val budget = planContextBudget(100_000, messages, overheadTokens = 100)

        assertFalse(budget.anchored)
        assertEquals(200, budget.usedTokens) // 400 字符 / 4 + 100 固定开销
    }

    @Test
    fun `usage on a non-assistant message is not an anchor`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.TOOL,
                parts = listOf(UIMessagePart.Text("result")),
                usage = TokenUsage(promptTokens = 50_000),
            )
        )

        val budget = planContextBudget(100_000, messages, overheadTokens = 0)

        assertFalse(budget.anchored)
        assertTrue(budget.usedTokens < 50_000)
    }

    // ---- 无锚点估算 ----

    @Test
    fun `estimate combines transcript and fixed overhead`() {
        val messages = listOf(
            UIMessage.user("12345678"), // 8 字符 / 4 = 2
            assistant("1234"), // 4 / 4 = 1
        )

        val budget = planContextBudget(100_000, messages, overheadTokens = 1_000)

        assertFalse(budget.anchored)
        assertEquals(1_003, budget.usedTokens)
    }

    @Test
    fun `negative overhead is clamped instead of inventing room`() {
        val budget = planContextBudget(100_000, listOf(UIMessage.user("12345678")), overheadTokens = -500)

        assertEquals(2, budget.usedTokens)
    }

    // ---- 判定 ----

    @Test
    fun `running out of room for a reply is exhausted`() {
        // 窗口 10K、已用 7K ⇒ 只剩 3K < MIN_REPLY_TOKENS(4K) ⇒ 停（撞满窗口 provider 会直接报错）
        val budget = planContextBudget(
            windowTokens = 10_000,
            messages = listOf(assistant("x", usage = TokenUsage(promptTokens = 7_000))),
            overheadTokens = 0,
        )

        assertEquals(3_000, budget.remainingTokens)
        assertEquals(ContextBudget.Verdict.Exhausted, budget.verdict)
    }

    @Test
    fun `exactly the minimum reply room is still ok`() {
        val budget = planContextBudget(
            windowTokens = 10_000,
            messages = listOf(assistant("x", usage = TokenUsage(promptTokens = 6_000))),
            overheadTokens = 0,
        )

        assertEquals(MIN_REPLY_TOKENS, budget.remainingTokens)
        assertEquals(ContextBudget.Verdict.Ok, budget.verdict)
    }

    @Test
    fun `over the window is exhausted and the ratio exceeds one`() {
        val budget = planContextBudget(
            windowTokens = 10_000,
            messages = listOf(assistant("x", usage = TokenUsage(promptTokens = 12_000))),
            overheadTokens = 0,
        )

        assertEquals(-2_000, budget.remainingTokens)
        assertEquals(ContextBudget.Verdict.Exhausted, budget.verdict)
        assertTrue(budget.usedRatio > 1f)
    }

    @Test
    fun `eighty percent is a warning, not a stop`() {
        val budget = planContextBudget(
            windowTokens = 100_000,
            messages = listOf(assistant("x", usage = TokenUsage(promptTokens = 80_000))),
            overheadTokens = 0,
        )

        // 还有 20K 余量：run 的产物是"已经攒到的结论"，能继续就该继续
        assertEquals(ContextBudget.Verdict.Warning, budget.verdict)
    }

    @Test
    fun `zero window is treated as exhausted instead of dividing by zero`() {
        val budget = planContextBudget(0, listOf(UIMessage.user("x")), overheadTokens = 0)

        assertEquals(1f, budget.usedRatio)
        assertEquals(ContextBudget.Verdict.Exhausted, budget.verdict)
    }

    @Test
    fun `describe reports the anchor kind for log triage`() {
        val anchored = planContextBudget(
            10_000,
            listOf(assistant("x", usage = TokenUsage(promptTokens = 5_000))),
            overheadTokens = 0,
        )
        val estimated = planContextBudget(10_000, listOf(UIMessage.user("12345678")), overheadTokens = 0)

        assertTrue(anchored.describe().contains("anchor"))
        assertTrue(estimated.describe().contains("estimate"))
    }

    // ---- 守卫薄封装 ----

    @Test
    fun `guard evaluates against its own window`() {
        val guard = ContextBudgetGuard(windowTokens = 8_000)

        val budget = guard.evaluate(
            messages = listOf(assistant("x", usage = TokenUsage(promptTokens = 5_000))),
            overheadTokens = 0,
        )

        assertEquals(8_000, budget.windowTokens)
        assertEquals(ContextBudget.Verdict.Exhausted, budget.verdict)
    }
}
