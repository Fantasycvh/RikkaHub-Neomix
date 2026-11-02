package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SubagentNoticeTest {
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
