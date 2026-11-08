package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NoticeProtocolTest {
    @Test fun `mixed notices expand in place with stable unique identities and intact tools`() {
        val tool = UIMessagePart.Tool("call", "shell", "{}", listOf(UIMessagePart.Text("result")))
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool,
            UIMessagePart.Notice("event", "Untrusted task output"), UIMessagePart.Text("answer")))
        val expanded = listOf(original).expandNotices()
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.SYSTEM, MessageRole.ASSISTANT), expanded.map { it.role })
        assertEquals(tool, expanded.first().parts.single())
        assertEquals(3, expanded.map { it.id }.toSet().size)
        assertEquals(expanded, listOf(original).expandNotices())
        assertEquals(expanded, expanded.expandNotices())
        assertEquals(3, original.parts.size)
        assertEquals(2, listOf(original).messagesForAuxGeneration().size)
    }

    @Test fun `notice part round trips and contributes to visible text`() {
        val original = UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Notice("id", "bounded output")))
        val encoded = Json.encodeToString(UIMessage.serializer(), original)
        assertEquals(original, Json.decodeFromString(UIMessage.serializer(), encoded))
        assertEquals("bounded output", original.toText())
        assertTrue(original.isExternalNotice)
        assertTrue(listOf(original).messagesForAuxGeneration().isEmpty())
    }
}
