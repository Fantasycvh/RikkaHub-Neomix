package app.androidrik.rikkahub.service

import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.task.selectedSubagentRunIds
import app.androidrik.rikkahub.data.task.selectedWorkspaceCompletionEvents
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.core.MessageRole
import app.androidrik.workspace.WorkspaceTaskSnapshot
import app.androidrik.workspace.WorkspaceTaskStatus
import org.junit.Assert.*
import org.junit.Test

class RetainedToolResultTest {
    @Test fun `started subagent gets a receipt that prevents redispatch and retains original result routing`() {
        val tool = UIMessagePart.Tool("call", "dispatch_subagent", "{}")
        assertNull(retainedSubagentToolResult(tool, emptyList()))
        assertNull(retainedSubagentToolResult(tool.copy(toolName = "write_file"), listOf("run")))
        val retained = retainedSubagentToolResult(tool, listOf("run"))!!
        assertTrue(retained.isExecuted)
        assertFalse(retained.canResumeExecution)
        assertEquals(setOf("run"), selectedSubagentRunIds(listOf(UIMessage(
            role = MessageRole.ASSISTANT, parts = listOf(retained)))))
    }

    @Test fun `background shell cancelled before receipt commit receives its original completion event`() {
        val tool = UIMessagePart.Tool("call", "workspace_run_background", """{"command":"sleep 10"}""")
        val task = WorkspaceTaskSnapshot("task", "/root", "sleep 10", "", WorkspaceTaskStatus.RUNNING,
            null, "", "", 0, 0, false, 0, null, noticeEventId = "event")
        assertNull(retainedWorkspaceToolResult(tool, emptyList()))
        assertNull(retainedWorkspaceToolResult(tool, listOf(task.copy(command = "other"))))
        val retained = retainedWorkspaceToolResult(tool, listOf(task))!!
        assertEquals(setOf("event"), selectedWorkspaceCompletionEvents(listOf(UIMessage(
            role = MessageRole.ASSISTANT, parts = listOf(retained)))))
    }
}
