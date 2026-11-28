package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.GenerationChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationDeltaMergeTest {

    @Test
    fun `mergeGenerationDelta updates tail node in place and returns same instance when unchanged`() {
        val user = UIMessage.user("q").toMessageNode()
        val assistantMsg = UIMessage.assistant("hello")
        val assistant = assistantMsg.toMessageNode().copy(parentId = user.id)
        val conv = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            assistantId = kotlin.uuid.Uuid.random(),
            messages = listOf(user, assistant),
        ).copy(activeLeafNodeId = assistant.id)

        val same = conv.mergeGenerationDelta(listOf(assistantMsg))
        assertSame(conv, same)

        val updatedMsg = assistantMsg.copy(parts = listOf(UIMessagePart.Text("hello world")))
        val merged = conv.mergeGenerationDelta(listOf(updatedMsg))
        assertEquals("hello world", merged.messageNodes.last().currentMessage.toText())
        assertEquals(assistant.id, merged.messageNodes.last().id)
        assertSame(user, merged.messageNodes.first())
    }

    @Test
    fun `mergeGenerationDelta updates non-tail candidate without altering siblings or selectIndex`() {
        val n0 = UIMessage.user("u0").toMessageNode()
        val candidate0 = UIMessage.assistant("old branch")
        val candidate1 = UIMessage.assistant("regenerating")
        val mid = MessageNode(
            messages = listOf(candidate0, candidate1),
            selectIndex = 1,
            parentId = n0.id,
        )
        val tail = UIMessage.user("u1").toMessageNode().copy(parentId = mid.id)
        val conv = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            assistantId = kotlin.uuid.Uuid.random(),
            messages = listOf(n0, mid, tail),
        ).copy(activeLeafNodeId = tail.id)

        val updatedCandidate1 = candidate1.copy(parts = listOf(UIMessagePart.Text("regenerated text")))
        val merged = conv.applyGenerationChunk(
            GenerationChunk.Messages(listOf(updatedCandidate1), coalescible = true, isDelta = true)
        )

        val updatedMid = merged.messageNodes[1]
        assertEquals(1, updatedMid.selectIndex)
        assertEquals("old branch", updatedMid.messages[0].toText())
        assertEquals("regenerated text", updatedMid.messages[1].toText())
        assertSame(tail, merged.messageNodes[2])
    }

    @Test
    fun `mergeGenerationDelta appends new leaf node when message id is unknown`() {
        val user = UIMessage.user("q").toMessageNode()
        val conv = Conversation.ofId(
            id = kotlin.uuid.Uuid.random(),
            assistantId = kotlin.uuid.Uuid.random(),
            messages = listOf(user),
        ).copy(activeLeafNodeId = user.id)
        val newAssistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("fresh")))

        val merged = conv.mergeGenerationDelta(listOf(newAssistant))
        assertEquals(2, merged.messageNodes.size)
        assertEquals(user.id, merged.messageNodes.last().parentId)
        assertEquals(merged.messageNodes.last().id, merged.activeLeafNodeId)
    }
}
