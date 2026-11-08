package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMessageUpdatesTest {
    private fun message(text: String) = UIMessage(
        role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)),
    )

    @Test fun `equal snapshots retain the conversation and every node`() {
        val conversation = Conversation.ofId(Uuid.random(), Uuid.random()).copy(messageNodes = List(1000) { message("history $it").toMessageNode() })
        val updated = conversation.updateCurrentMessages(conversation.currentMessages.map { it.copy() })
        assertSame(conversation, updated)
        assertSame(conversation, conversation.updateCurrentMessages(emptyList()))
    }

    @Test fun `streaming replaces only the changed node and retains trailing history`() {
        val conversation = Conversation.ofId(Uuid.random(), Uuid.random()).copy(messageNodes = List(1000) { message("history $it").toMessageNode() })
        val changed = conversation.currentMessages[998].copy(parts = listOf(UIMessagePart.Text("new tail")))
        val updated = conversation.updateCurrentMessages(conversation.currentMessages.take(998) + changed)
        assertNotSame(conversation.messageNodes[998], updated.messageNodes[998])
        assertSame(changed, updated.messageNodes[998].currentMessage)
        conversation.messageNodes.indices.filter { it != 998 }.forEach {
            assertSame("node $it", conversation.messageNodes[it], updated.messageNodes[it])
        }
    }

    @Test fun `updating existing alternate preserves selection and metadata`() {
        val first = message("first")
        val second = message("second")
        val node = MessageNode(messages = listOf(first, second), selectIndex = 1, isFavorite = true)
        val conversation = Conversation.ofId(Uuid.random(), Uuid.random()).copy(messageNodes = listOf(node))
        val updated = conversation.updateCurrentMessages(listOf(first.copy(parts = emptyList())))
        assertEquals(1, updated.messageNodes.single().selectIndex)
        assertTrue(updated.messageNodes.single().isFavorite)
        assertEquals(node.id, updated.messageNodes.single().id)
        assertSame(second, updated.messageNodes.single().currentMessage)
    }

    @Test fun `new candidates become selected and new nodes append`() {
        val original = message("original").toMessageNode()
        val alternate = message("alternate")
        val tail = message("tail")
        val updated = Conversation.ofId(Uuid.random(), Uuid.random()).copy(messageNodes = listOf(original))
            .updateCurrentMessages(listOf(alternate, tail))
        assertEquals(original.id, updated.messageNodes.first().id)
        assertEquals(listOf(original.currentMessage, alternate), updated.messageNodes.first().messages)
        assertEquals(1, updated.messageNodes.first().selectIndex)
        assertSame(tail, updated.messageNodes.last().currentMessage)
    }
}
