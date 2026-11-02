package app.androidrik.rikkahub.data.task

import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.APPROVAL_TIMEOUT_REASON
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.model.SubagentToolApproval
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BackgroundApprovalPolicyTest {
    private fun tool(id: String) = UIMessagePart.Tool(id, "write_file", "{}", approvalState = ToolApprovalState.Pending)

    @Test fun `background ask requires a usable notification route`() {
        assertEquals(SubagentToolApproval.DENY, backgroundApprovalDecision(SubagentToolApproval.ASK, false).policy)
        assertNotNull(backgroundApprovalDecision(SubagentToolApproval.ASK, false).note)
        assertEquals(SubagentToolApproval.ASK, backgroundApprovalDecision(SubagentToolApproval.ASK, true).policy)
        assertEquals(SubagentToolApproval.DENY, backgroundApprovalDecision(SubagentToolApproval.DENY, true).policy)
    }

    @Test fun `unreachable background does not register or post`() = runTest {
        val decisions = awaitRunApproval("a", "A", listOf(tool("a1")), "p", true, 100,
            { false }, { error("must not post") }, {})
        assertEquals(ToolApprovalState.Denied(BACKGROUND_APPROVAL_UNAVAILABLE), decisions["a1"])
        assertFalse(SubagentApprovalQueue.requests.value.any { it.runId == "a" })
    }

    @Test fun `post failure denies immediately and clears the registered batch`() = runTest {
        var cancelled = false
        val decisions = awaitRunApproval("b", "B", listOf(tool("b1")), "p", true, 100,
            { true }, { false }, { cancelled = true })
        assertEquals(ToolApprovalState.Denied(BACKGROUND_APPROVAL_UNAVAILABLE), decisions["b1"])
        assertTrue(cancelled)
        assertFalse(SubagentApprovalQueue.resolve("b1", true))
    }

    @Test fun `parallel batches resolve independently and timeout denies only the remaining batch`() = runTest {
        val a = async { awaitRunApproval("c", "C", listOf(tool("c1")), "p", true, 100,
            { true }, { true }, {}) }
        val b = async { awaitRunApproval("d", "D", listOf(tool("d1")), "p", true, 100,
            { true }, { true }, {}) }
        runCurrent()
        assertTrue(SubagentApprovalQueue.resolve("c1", true))
        runCurrent()
        assertEquals(ToolApprovalState.Approved, a.await()["c1"])
        assertTrue(SubagentApprovalQueue.requests.value.any { it.runId == "d" })
        advanceTimeBy(101); runCurrent()
        assertEquals(ToolApprovalState.Denied(APPROVAL_TIMEOUT_REASON), b.await()["d1"])
        assertFalse(SubagentApprovalQueue.resolve("d1", true))
    }

    @Test fun `cancellation removes only its own waiters and notification`() = runTest {
        var cancelled = false
        val job = async { awaitRunApproval("e", "E", listOf(tool("e1")), null, true, 100,
            { true }, { true }, { cancelled = true }) }
        runCurrent(); job.cancel(); runCurrent()
        assertTrue(cancelled)
        assertFalse(SubagentApprovalQueue.resolve("e1", true))
    }

    @Test fun `foreground blocking ask can still be answered in app without a notification`() = runTest {
        val job = async { awaitRunApproval("f", "F", listOf(tool("f1")), null, false, 100,
            { false }, { false }, {}) }
        runCurrent()
        assertTrue(SubagentApprovalQueue.resolve("f1", false))
        assertTrue(job.await()["f1"] is ToolApprovalState.Denied)
    }
}
