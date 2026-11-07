package app.androidrik.rikkahub.data.task

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledMemoryApprovalBridgeTest {
    private fun tool(id: String = "memory-approval") = UIMessagePart.Tool(id, "memory_tool", "{}", approvalState = ToolApprovalState.Pending)

    @Test fun `scheduled job remains alive while awaiting approval and resumes only after decision`() = runTest {
        var pending = listOf(tool())
        var resumed = 0
        var withdrawn = 0
        val job = launch {
            bridgeScheduledMemoryApprovals({ pending }, { tools ->
                awaitRunApproval("sched:test", "scheduled", tools, "parent", true, 300_000,
                    { true }, { true }, { withdrawn++ })
            }, { decisions ->
                assertEquals(ToolApprovalState.Approved, decisions.values.single())
                pending = emptyList()
            }, { resumed++ }, { fail("unexpected limit") })
        }
        runCurrent()
        assertFalse(job.isCompleted)
        assertEquals(0, resumed)
        assertTrue(SubagentApprovalQueue.resolve(pending.single().toolCallId, true))
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1, resumed)
        assertEquals(1, withdrawn)
        assertEquals(0, SubagentApprovalQueue.pendingCount())
    }

    @Test fun `unavailable notification route denies without waiting or silently approving`() = runTest {
        var pending = listOf(tool())
        var applied: ToolApprovalState? = null
        bridgeScheduledMemoryApprovals({ pending }, { tools ->
            awaitRunApproval("sched:test", "scheduled", tools, "parent", true, 300_000,
                { false }, { error("must not post") }, {})
        }, { decisions -> applied = decisions.values.single(); pending = emptyList() }, {}, {})
        assertTrue(applied is ToolApprovalState.Denied)
        assertEquals(0, SubagentApprovalQueue.pendingCount())
    }

    @Test fun `stop cancels the waiter and notification without resuming`() = runTest {
        val pending = listOf(tool("cancelled-memory"))
        var withdrawn = false
        var resumed = false
        val job = launch {
            bridgeScheduledMemoryApprovals({ pending }, { tools ->
                awaitRunApproval("sched:test", "scheduled", tools, "parent", true, 300_000,
                    { true }, { true }, { withdrawn = true })
            }, { error("cancelled decision must not be applied") }, { resumed = true }, {})
        }
        runCurrent()
        job.cancel(); runCurrent()
        assertTrue(withdrawn)
        assertFalse(resumed)
        assertFalse(SubagentApprovalQueue.resolve("cancelled-memory", true))
        assertEquals(0, SubagentApprovalQueue.pendingCount())
    }

    @Test fun `fresh write requests cannot create an unbounded approval chain`() = runTest {
        var resumed = 0
        var limited = false
        var last: ToolApprovalState? = null
        bridgeScheduledMemoryApprovals({ listOf(tool("request-$resumed")) }, { tools ->
            tools.associate { it.toolCallId to ToolApprovalState.Approved }
        }, { last = it.values.single() }, { resumed++ }, { limited = true }, maxRounds = 2)
        assertEquals(2, resumed)
        assertTrue(limited)
        assertTrue(last is ToolApprovalState.Denied)
    }

    @Test fun `only pending memory tools belong to this bridge`() {
        val pending = tool()
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pending,
            pending.copy(toolCallId = "done", output = listOf(UIMessagePart.Text("saved"))),
            pending.copy(toolCallId = "other", toolName = "workspace_write_file")))
        assertEquals(listOf(pending), pendingScheduledMemoryTools(listOf(message)))
    }
}
