package app.androidrik.rikkahub.data.task

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isExternalNotice
import app.androidrik.rikkahub.data.db.entity.ExternalNoticeEntity
import app.androidrik.workspace.WorkspaceTaskNoticeTarget
import app.androidrik.workspace.WorkspaceTaskSnapshot
import app.androidrik.workspace.WorkspaceTaskStatus
import app.androidrik.workspace.WorkspaceTaskTerminal
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ExternalNoticesTest {
    private fun row() = ExternalNoticeEntity(
        Uuid.random().toString(), "conversation", "assistant", "workspace", "anchor", "turn:root",
        "task", "EXITED", "result", 1L, true,
    )

    @Test fun `routing requires original assistant workspace and selected anchor`() {
        val row = row()
        assertTrue(externalNoticeMatches(row, "assistant", "workspace", setOf("anchor")))
        assertFalse(externalNoticeMatches(row, "other", "workspace", setOf("anchor")))
        assertFalse(externalNoticeMatches(row, "assistant", null, setOf("anchor")))
        assertFalse(externalNoticeMatches(row, "assistant", "workspace", setOf("other-branch")))
    }

    @Test fun `source event must be an originating tool result on the selected branch`() {
        val id = row().id
        val text = UIMessagePart.Text("""{"taskId":"task","completionEventId":"$id"}""")
        val tool = UIMessagePart.Tool("call", "workspace_run_background", "{}", listOf(text))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        assertEquals(setOf(id), selectedWorkspaceCompletionEvents(listOf(message)))
        assertTrue(selectedWorkspaceCompletionEvents(listOf(UIMessage.user(text.text))).isEmpty())
        assertTrue(selectedWorkspaceCompletionEvents(listOf(message.copy(parts = listOf(tool.copy(output = emptyList()))))).isEmpty())
    }

    @Test fun `notice identity is deterministic and message is synthetic system data`() {
        val row = row()
        val first = externalNoticeMessage(row)
        assertEquals(first.id, externalNoticeMessage(row).id)
        assertEquals(row.id, first.id.toString())
        assertEquals(MessageRole.SYSTEM, first.role)
        assertTrue(first.isSynthetic)
        assertTrue(first.isExternalNotice)
    }

    @Test fun `terminal snapshot bounds output removes controls and suppresses killed wake`() {
        val target = WorkspaceTaskNoticeTarget("c", "a", "w", "anchor", "turn:root", true)
        val task = WorkspaceTaskSnapshot(
            "task", "root", "command", "", WorkspaceTaskStatus.KILLED, 137,
            "prefix" + "x".repeat(10_000) + "\u0000tail", "error".repeat(4_000),
            10_000, 20_000, true, 1L, 2L,
        )
        val row = WorkspaceTaskTerminal(Uuid.random().toString(), target, task).toExternalNotice()
        assertTrue(row.body.length <= 2_000)
        assertFalse(row.body.contains('\u0000'))
        assertFalse(row.body.contains("prefix"))
        assertFalse(row.allowWake)
    }
}
