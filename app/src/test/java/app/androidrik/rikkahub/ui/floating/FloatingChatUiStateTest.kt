package app.androidrik.rikkahub.ui.floating

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.GenerationPhase
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.service.ChatError
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.service.QueuedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class FloatingChatUiStateTest {
    private fun state(conversation: Conversation, replyMessageId: Uuid?, generating: Boolean = false) =
        buildFloatingChatUiState(
            conversation = conversation,
            replyMessageId = replyMessageId,
            generating = generating,
            processingStatus = null,
            queue = MessageQueueState(),
            error = null,
        )

    private fun pendingToolReply() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "1", toolName = "shell", input = "{}",
                approvalState = ToolApprovalState.Pending,
            ),
        ),
    )

    private fun regenerated(newBranch: UIMessage, selectIndex: Int = 1): Conversation {
        val node = MessageNode(messages = listOf(UIMessage.assistant("old answer"), newBranch), selectIndex = selectIndex)
        return Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(
                UIMessage.user("first").toMessageNode(),
                node,
                UIMessage.user("second").toMessageNode(),
                UIMessage.assistant("tail answer").toMessageNode(),
            ),
        )
    }

    @Test
    fun `regenerating an earlier reply previews that reply instead of the tail`() {
        val reply = UIMessage.assistant("new answer")
        val state = state(regenerated(reply), reply.id, generating = true)
        assertEquals("new answer", state.preview)
        assertEquals(GenerationPhase.Writing("new answer"), state.phase)
    }

    @Test
    fun `switching the node back to the old branch keeps previewing the generating reply`() {
        val reply = UIMessage.assistant("new answer")
        assertEquals("new answer", state(regenerated(reply, selectIndex = 0), reply.id, generating = true).preview)
    }

    @Test
    fun `pending approval in an earlier node is reported`() {
        val reply = pendingToolReply()
        val conversation = regenerated(reply)
        assertEquals(listOf("1"), state(conversation, reply.id).pendingTools.map { it.toolCallId })
        assertEquals(1, state(conversation, replyMessageId = null).pendingTools.size)
    }

    @Test
    fun `new turn without output yet does not show the previous reply`() {
        val state = state(regenerated(UIMessage.assistant("new answer")), replyMessageId = null, generating = true)
        assertFalse(state.hasReply)
        assertEquals("", state.preview)
        assertEquals(GenerationPhase.Waiting, state.phase)
    }

    @Test
    fun `reasoning is previewed only until nonblank text is available`() {
        val thinking = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Reasoning("  thinking  ", finishedAt = null), UIMessagePart.Text("  ")),
        )
        val state = state(regenerated(thinking), thinking.id, generating = true)
        assertEquals("thinking", state.preview)
        assertTrue(state.previewIsReasoning)
        val writing = thinking.copy(parts = thinking.parts + UIMessagePart.Text("  answer  "))
        val writingState = state(regenerated(writing), writing.id, generating = true)
        assertEquals("answer", writingState.preview)
        assertFalse(writingState.previewIsReasoning)
    }

    @Test
    fun `markdown tables links and code are preserved without truncation`() {
        val markdown = "| Name | URL |\n| --- | --- |\n| Example | [link](https://example.com) |\n\n```kotlin\n" +
            "val value = 1\n".repeat(100) + "```"
        val reply = UIMessage.assistant(markdown)
        assertEquals(markdown, state(regenerated(reply), reply.id).preview)
        // Rendering safety is separately verified in Compose instrumentation: the overlay
        // must always use MarkdownBlock(streaming=true), even once generation has stopped.
    }

    @Test
    fun `ordinary pending approval keeps stop enabled without a generation job`() {
        val reply = pendingToolReply()
        val state = state(regenerated(reply), reply.id, generating = false)
        assertFalse(state.generating)
        assertTrue(state.awaitingApproval)
        assertTrue(state.stopEnabled)
        assertTrue(state.subagentApprovals.isEmpty())
        val resolved = state.copy(pendingTools = emptyList())
        assertFalse(resolved.awaitingApproval)
        assertFalse(resolved.stopEnabled)
    }

    @Test
    fun `subagent approval is a separate waiting state and keeps stop enabled`() {
        val conversation = Conversation.ofId(Uuid.random())
        val tool = UIMessagePart.Tool(
            toolCallId = "child-call", toolName = "workspace_shell", input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        val request = SubagentApprovalQueue.Request(
            runId = "child-run", label = "Research agent", tool = tool,
            parentConversationId = conversation.id.toString(),
        )
        val idle = state(conversation, replyMessageId = null)
        assertFalse(idle.stopEnabled)
        val waiting = idle.copy(subagentApprovals = listOf(request))
        assertFalse(waiting.generating)
        assertTrue(waiting.awaitingApproval)
        assertTrue(waiting.stopEnabled)
        assertTrue("child approvals must not become ordinary conversation tools", waiting.pendingTools.isEmpty())
        assertEquals(listOf(request), waiting.subagentApprovals)
        assertFalse(waiting.copy(subagentApprovals = emptyList()).stopEnabled)
    }

    @Test
    fun `active generation alone keeps stop enabled without approvals`() {
        val state = state(Conversation.ofId(Uuid.random()), replyMessageId = null, generating = true)
        assertFalse(state.awaitingApproval)
        assertTrue(state.stopEnabled)
    }

    @Test
    fun `queue processing status and current error are propagated`() {
        val reply = UIMessage.assistant("answer")
        val conversation = regenerated(reply)
        val error = ChatError(error = IllegalStateException("failed"), conversationId = conversation.id)
        val state = buildFloatingChatUiState(
            conversation, reply.id, false, "Retrying",
            MessageQueueState(
                messages = listOf(QueuedMessage(parts = listOf(UIMessagePart.Text("next")))),
                paused = true,
            ),
            error,
        )
        assertEquals("Retrying", state.processingStatus)
        assertEquals(1, state.queuedCount)
        assertTrue(state.queuePaused)
        assertTrue(state.failed)
        assertFalse(state.copy(generating = true).failed)
    }
}
