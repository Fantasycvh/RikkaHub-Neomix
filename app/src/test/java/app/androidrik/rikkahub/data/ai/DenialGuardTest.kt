package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.service.RunOutcome
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.service.evaluateCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无头 run 的重复拒绝护栏 + 完成判定（设计见 `docs/design/design-subagent-context.md` §9.2.1）。
 *
 * 漏洞背景：模型被信封拒绝后**换参数/换工具继续撞**，连续多次后放弃填内容、以空响应结束。
 * 该循环 LoopGuard（只认同签名）与 WorkspaceTurnBudget（只计 workspace_*）都拦不住，
 * 且空响应会被误记成「正常完成」——无头场景即静默失败。
 */
class DenialGuardTest {

    @Test
    fun `below threshold continues`() {
        val guard = DenialGuard()
        assertEquals(DenialGuard.Verdict.Continue, guard.record())
        assertEquals(DenialGuard.Verdict.Continue, guard.record())
        assertEquals(2, guard.denialCount)
    }

    @Test
    fun `threshold hit on third denial`() {
        val guard = DenialGuard()
        guard.record()
        guard.record()
        val verdict = guard.record()
        assertTrue(verdict is DenialGuard.Verdict.Exceeded)
        assertEquals(3, (verdict as DenialGuard.Verdict.Exceeded).denials)
    }

    @Test
    fun `verdict is sticky after threshold`() {
        val guard = DenialGuard()
        repeat(3) { guard.record() }
        // 调用点只在首次超限设提示；后续 record 仍返回 Exceeded 但不重复报
        assertTrue(guard.record() is DenialGuard.Verdict.Exceeded)
    }

    @Test
    fun `reset clears count`() {
        val guard = DenialGuard()
        repeat(3) { guard.record() }
        guard.reset()
        assertEquals(0, guard.denialCount)
        assertEquals(DenialGuard.Verdict.Continue, guard.record())
    }
}

/** 完成判定（§9.2.1 ③）：空响应不得记成 COMPLETED；护栏 notice 优先映射失败细分。 */
class EvaluateCompletionTest {

    private fun assistantMessage(text: String = "done") = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `normal completion takes last assistant text as summary`() {
        val messages = listOf(
            UIMessage.user("do the thing"),
            assistantMessage("Here is the result."),
        )
        val outcome = evaluateCompletion(messages, stopNotice = null)
        assertTrue(outcome is RunOutcome.Completed)
        assertEquals("Here is the result.", (outcome as RunOutcome.Completed).summary)
    }

    @Test
    fun `empty response is a failure not a completion`() {
        val messages = listOf(
            UIMessage.user("do the thing"),
            // 模型放弃：响应里只有空的 tool 调用回填、无文本
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
        )
        val outcome = evaluateCompletion(messages, stopNotice = null)
        assertTrue(outcome is RunOutcome.Failed)
        assertEquals(AgentRunEntity.ERROR_MODEL_GAVE_UP, (outcome as RunOutcome.Failed).errorKind)
    }

    @Test
    fun `blank text response is also a failure`() {
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("   "))))
        val outcome = evaluateCompletion(messages, stopNotice = null)
        assertEquals(AgentRunEntity.ERROR_MODEL_GAVE_UP, (outcome as RunOutcome.Failed).errorKind)
    }

    @Test
    fun `guard notices map to their failure kinds`() {
        val base = listOf(assistantMessage())
        listOf(
            GenerationNotice.ToolLoopStopped("t", 3) to AgentRunEntity.ERROR_LOOP_DETECTED,
            GenerationNotice.WorkspaceBudgetExhausted(1, 2, 3, WorkspaceTurnBudget.ExceedReason.OUTPUT) to
                AgentRunEntity.ERROR_BUDGET_EXHAUSTED,
            GenerationNotice.TooManyDenials(3) to AgentRunEntity.ERROR_PERMISSION_DENIED,
        ).forEach { (notice, kind) ->
            val outcome = evaluateCompletion(base, stopNotice = notice)
            assertTrue(outcome is RunOutcome.Failed)
            assertEquals(kind, (outcome as RunOutcome.Failed).errorKind)
        }
    }

    @Test
    fun `guard failure wins over the text content`() {
        // 护栏停止后即使有文本也不算 COMPLETED——结果可能是半截的
        val messages = listOf(assistantMessage("partial but non-empty"))
        val outcome = evaluateCompletion(
            messages,
            stopNotice = GenerationNotice.TooManyDenials(3),
        )
        assertEquals(AgentRunEntity.ERROR_PERMISSION_DENIED, (outcome as RunOutcome.Failed).errorKind)
    }

    @Test
    fun `summary keeps the full text for the caller`() {
        // 截断**不在这里**做：对话内派发要把全文回给父模型（设计 §十），
        // 列表摘要列的截断落在落库那一处（SubagentRunner）。
        val outcome = evaluateCompletion(
            listOf(assistantMessage("x".repeat(5000))),
            stopNotice = null,
        ) as RunOutcome.Completed
        assertEquals(5000, outcome.summary.length)
    }
}
