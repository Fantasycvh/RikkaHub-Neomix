package app.androidrik.rikkahub.ui.pages.chat

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.compression.CompressionProgress
import app.androidrik.rikkahub.data.ai.compression.CompressionProgressStage
import app.androidrik.rikkahub.data.ai.tools.local.TodoEntry
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.ui.components.ai.resolveComposerMaxLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatInteractionUpgradeTest {

    @Test
    fun `status dock prioritizes pending interaction, then compression, then plan`() {
        val nodeId = Uuid.random()
        val progress = CompressionProgress(
            stage = CompressionProgressStage.SINGLE_PASS,
        )
        val todos = listOf(TodoEntry(content = "step 1", status = "in_progress"))

        val model = resolveChatStatusDock(
            pendingInteractionCount = 2,
            pendingTargetNodeId = nodeId,
            compressionProgress = progress,
            todos = todos,
        )

        assertEquals(3, model.items.size)
        assertEquals(ChatStatusDockItem.PendingInteraction(2, nodeId), model.primary)
        assertEquals(2, model.hiddenCount)
        assertTrue(model.secondary[0] is ChatStatusDockItem.Compressing)
        assertTrue(model.secondary[1] is ChatStatusDockItem.Plan)
    }

    @Test
    fun `status dock returns single item without hidden count and empty when nothing active`() {
        val todos = listOf(TodoEntry(content = "step 1", status = "pending"))
        val single = resolveChatStatusDock(
            pendingInteractionCount = 0,
            pendingTargetNodeId = null,
            compressionProgress = null,
            todos = todos,
        )
        assertEquals(1, single.items.size)
        assertEquals(0, single.hiddenCount)
        assertTrue(single.primary is ChatStatusDockItem.Plan)

        val empty = resolveChatStatusDock(0, null, null, emptyList())
        assertTrue(empty.items.isEmpty())
        assertNull(empty.primary)
        assertEquals(0, empty.hiddenCount)
    }

    @Test
    fun `findPendingInteractionSummary counts pending tools on active path and targets latest node`() {
        val node1Id = Uuid.random()
        val node2Id = Uuid.random()
        val pendingTool1 = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "memory_tool",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        val approvedTool = UIMessagePart.Tool(
            toolCallId = "t2",
            toolName = "memory_tool",
            input = "{}",
            approvalState = ToolApprovalState.Approved,
        )
        val pendingTool2 = UIMessagePart.Tool(
            toolCallId = "t3",
            toolName = "ask_user",
            input = "{}",
            approvalState = ToolApprovalState.Pending,
        )
        val conversation = Conversation.ofId(Uuid.random(), Uuid.random()).copy(
            messageNodes = listOf(
                MessageNode(
                    id = node1Id,
                    messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pendingTool1, approvedTool))),
                ),
                MessageNode(
                    id = node2Id,
                    parentId = node1Id,
                    messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(pendingTool2))),
                ),
            ),
            activeLeafNodeId = node2Id,
        )

        val (count, target) = findPendingInteractionSummary(conversation)
        assertEquals(2, count)
        assertEquals(node2Id, target)
    }

    @Test
    fun `composer max lines scales with viewport height and keyboard visibility`() {
        assertEquals(6, resolveComposerMaxLines(windowHeightDp = 900, imeVisible = false))
        assertEquals(5, resolveComposerMaxLines(windowHeightDp = 840, imeVisible = true))
        assertEquals(3, resolveComposerMaxLines(windowHeightDp = 420, imeVisible = true))
        assertEquals(2, resolveComposerMaxLines(windowHeightDp = 320, imeVisible = true))
        assertEquals(5, resolveComposerMaxLines(windowHeightDp = 0, imeVisible = false))
    }
}
