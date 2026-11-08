package app.androidrik.rikkahub.ui.components.message

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class MessageDisplayDocumentTest {
    private val id = Uuid.parse("00000000-0000-4000-8000-000000000001")

    @Test fun `projection preserves raw parts grouping order attachments and edit targets`() {
        val parts = listOf(UIMessagePart.Text("before"), UIMessagePart.Text(" "),
            UIMessagePart.Reasoning("reasoning"), UIMessagePart.Tool("call", "search_web", "{}"),
            UIMessagePart.Image("file:///image.png"), UIMessagePart.Text("after"))
        for (fold in listOf(false, true)) {
            val document = messageDisplayDocument(id, parts, MessageRole.ASSISTANT, fold)
            assertSame(parts, document.sourceParts)
            assertEquals(parts.groupMessageParts(MessageRole.ASSISTANT, fold), document.body.map { it.block })
            assertEquals(listOf(0, 2, 3, 4, 5), document.body.flatMap { it.sourcePartIndices })
            assertEquals(MessageTextDocumentId(id, 5), document.body.last().textDocumentId)
            val keys = listOf(document.header.key) + document.body.map { it.key } + document.footer.key
            assertEquals(keys.size, keys.distinct().size)
        }
    }

    @Test fun `equal and repeated parts do not collapse edit indices`() {
        val same = UIMessagePart.Reasoning("same")
        val parts = listOf(same, same, UIMessagePart.Reasoning("same"), UIMessagePart.Text("same"), UIMessagePart.Text("same"))
        val document = messageDisplayDocument(id, parts, MessageRole.ASSISTANT, false)
        assertEquals(listOf(0, 1, 2, 3, 4), document.body.flatMap { it.sourcePartIndices })
        assertEquals(listOf(3, 4), document.body.mapNotNull { it.textDocumentId?.partIndex })
    }

    @Test fun `append keeps part identities while branch messages remain isolated`() {
        val first = messageDisplayDocument(id, listOf(UIMessagePart.Text("start")), MessageRole.ASSISTANT, false)
        val next = messageDisplayDocument(id, listOf(UIMessagePart.Text("start extended")), MessageRole.ASSISTANT, false)
        assertEquals(first.body.single().key, next.body.single().key)
        assertEquals(first.body.single().textDocumentId, next.body.single().textDocumentId)
        val branch = messageDisplayDocument(Uuid.random(), next.sourceParts, MessageRole.ASSISTANT, false)
        assertNotEquals(next.body.single().key, branch.body.single().key)
        assertNotEquals(next.body.single().textDocumentId, branch.body.single().textDocumentId)
    }

    @Test fun `user tool parts remain in content and empty raw text is retained`() {
        val parts = listOf(UIMessagePart.Text(""), UIMessagePart.Tool("call", "search_web", "{}"), UIMessagePart.Text("user"))
        val document = messageDisplayDocument(id, parts, MessageRole.USER, true)
        assertSame(parts, document.sourceParts)
        assertTrue(document.body.all { it.block is MessagePartBlock.ContentBlock })
        assertEquals(listOf(1, 2), document.body.flatMap { it.sourcePartIndices })
    }
}
