package app.androidrik.ai.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalStateTest {
    @Test
    fun `cancelled pending tool no longer waits for approval or resumes execution`() {
        val pending = UIMessagePart.Tool(
            toolCallId = "call",
            toolName = "write_file",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        assertTrue(pending.isPending)
        // 用户取消后 output 被填充 → isExecuted=true → 不再视为 pending（55506496 语义）
        val cancelled = pending.copy(output = listOf(UIMessagePart.Text("cancelled")))
        assertFalse(cancelled.isPending)
        assertFalse(cancelled.canResumeExecution)
        assertTrue(cancelled.approvalState is ToolApprovalState.Pending)
    }

    @Test
    fun `approved denied and answered states can resume tool execution`() {
        assertTrue(ToolApprovalState.Approved.canResumeToolExecution())
        assertTrue(ToolApprovalState.Denied("no").canResumeToolExecution())
        assertTrue(ToolApprovalState.Answered("""{"answers":{"q1":"yes"}}""").canResumeToolExecution())
    }

    @Test
    fun `auto and pending states cannot resume tool execution`() {
        assertFalse(ToolApprovalState.Auto.canResumeToolExecution())
        assertFalse(ToolApprovalState.Pending.canResumeToolExecution())
    }
}
