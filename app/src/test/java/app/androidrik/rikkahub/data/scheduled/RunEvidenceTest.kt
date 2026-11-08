package app.androidrik.rikkahub.data.scheduled

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class RunEvidenceTest {
    private fun call(action: String, output: String? = null, state: ToolApprovalState = ToolApprovalState.Auto) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("call", "memory_tool", """{"action":"$action"}""",
            output?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(), state)))

    @Test fun `absence is unassessed and only an explicit no candidate receipt is sufficient`() {
        assertEquals("unassessed", memorySyncStatus(emptyList(), true))
        assertEquals("no_candidate", memorySyncStatus(listOf(call("no_candidate", """{"memory_sync":"no_candidate"}""")), true))
        assertFalse(safeToRemoveRunSource("unassessed", "summary"))
        assertFalse(safeToRemoveRunSource("saved", null))
        assertTrue(safeToRemoveRunSource("no_candidate", "summary"))
    }

    @Test fun `mutation evidence distinguishes pending denial failures and proposals from committed writes`() {
        assertEquals("saved", memorySyncStatus(listOf(call("create", """{"id":"opaque","revision":1,"memory_sync":"saved"}""")), true))
        assertEquals("pending_approval", memorySyncStatus(listOf(call("edit", state = ToolApprovalState.Pending)), true))
        assertEquals("denied", memorySyncStatus(listOf(call("edit", state = ToolApprovalState.Denied())), true))
        assertEquals("failed", memorySyncStatus(listOf(call("create", "saved")), true))
        assertEquals("proposed", memorySyncStatus(listOf(call("edit", """{"success":true,"proposal":true,"applied":false}""")), true))
        assertEquals("disabled", memorySyncStatus(emptyList(), false))
        assertEquals("unsupported", memorySyncStatus(emptyList(), true, headless = true))
    }

    @Test fun `approved write in progress is not a failed write and final unresolved approval is interrupted`() {
        val approved = call("create", state = ToolApprovalState.Approved)
        assertEquals("writing", memorySyncStatus(listOf(approved), true))
        assertEquals("failed", memorySyncStatus(listOf(approved), true, finished = true))
        val pending = call("edit", state = ToolApprovalState.Pending)
        assertEquals("interrupted", memorySyncStatus(listOf(pending), true, finished = true))
    }

    @Test fun `new user input bounds the old run and cannot overwrite its summary`() {
        val trigger = UIMessage.user("scheduled").copy(annotations = listOf(UIMessageAnnotation.ScheduledTrigger("run")))
        val answer = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("original result")))
        val newAnswer = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("OTHER USER RESULT")))
        val evidence = captureRunEvidence("run", listOf(trigger, answer, UIMessage.user("new input"), newAnswer), true)
        assertTrue(evidence.summary.contains("original result"))
        assertFalse(evidence.summary.contains("OTHER USER RESULT"))
    }

    @Test fun `snapshot belongs only to this run bounds text and retains receipt identities without private bodies`() {
        val old = call("create", """{"memory_sync":"saved","content":"PRIVATE"}""")
        val trigger = UIMessage.user("task").copy(annotations = listOf(UIMessageAnnotation.ScheduledTrigger("new")))
        val write = call("create", """{"id":"opaque","revision":2,"memory_sync":"saved","content":"PRIVATE"}""")
        val answer = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("x".repeat(20_000))))
        val evidence = captureRunEvidence("new", listOf(old, trigger, write, answer), true, "file.txt")
        assertEquals("saved", evidence.memorySync)
        assertFalse(evidence.receipts.contains("PRIVATE"))
        assertTrue(evidence.receipts.contains("opaque"))
        assertTrue(evidence.summary.length < 13_000)
        assertTrue(evidence.summary.contains("file.txt"))
        assertEquals("unassessed", captureRunEvidence("missing", listOf(old), true).memorySync)
    }
}
