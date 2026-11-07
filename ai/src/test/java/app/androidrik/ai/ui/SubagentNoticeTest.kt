package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SubagentNoticeTest {
    @Test fun `workspace notices roundtrip with sources and share external semantic filters`() {
        val annotation = UIMessageAnnotation.ExternalNotice(listOf("event"), "workspace", listOf("task"))
        val encoded = Json.encodeToString(UIMessageAnnotation.serializer(), annotation)
        assertEquals(annotation, Json.decodeFromString(UIMessageAnnotation.serializer(), encoded))
        val message = UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Text("result")), annotations = listOf(annotation))
        assertTrue(message.isExternalNotice)
        assertFalse(message.isSubagentNotice)
        assertEquals(MessageRole.SYSTEM, message.semanticRole)
        assertTrue(listOf(message).messagesForAuxGeneration().isEmpty())
        assertEquals(listOf(message), listOf(message).messagesForRequest())
    }

    @Test fun `user carrier is framed once on request copies and keeps system semantics`() {
        val notice = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("external value")),
            annotations = listOf(UIMessageAnnotation.SubagentNotice(listOf("a"))))
        assertEquals(MessageRole.SYSTEM, notice.semanticRole)
        val request = listOf(notice).messagesForRequest()
        assertEquals(MessageRole.USER, request.single().role)
        assertEquals("<system-reminder>\nexternal value\n</system-reminder>", request.single().toText())
        assertEquals(request, request.messagesForRequest())
        assertEquals("external value", notice.toText())
        val user = UIMessage.user("real input")
        assertEquals(listOf(user), listOf(user).messagesForRequest())
    }
    @Test fun `notice discriminator and source ids survive serialization`() {
        val notice = UIMessageAnnotation.SubagentNotice(listOf("a", "b"))
        val encoded = Json.encodeToString(UIMessageAnnotation.serializer(), notice)
        assertTrue(encoded.contains("subagent_notice"))
        assertEquals(notice, Json.decodeFromString(UIMessageAnnotation.serializer(), encoded))
    }

    @Test fun `auxiliary filtering uses the annotation even for the fallback carrier`() {
        val ordinary = UIMessage.user("user intent")
        for (role in listOf(MessageRole.SYSTEM, MessageRole.USER)) {
            val notice = UIMessage(role = role, parts = listOf(UIMessagePart.Text("external output")),
                annotations = listOf(UIMessageAnnotation.SubagentNotice(listOf("a"))))
            assertTrue(notice.isSubagentNotice)
            assertEquals(listOf(ordinary), listOf(ordinary, notice).messagesForAuxGeneration())
        }
    }
}
