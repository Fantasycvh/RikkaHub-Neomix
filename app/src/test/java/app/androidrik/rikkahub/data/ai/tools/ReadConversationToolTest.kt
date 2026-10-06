package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ReadConversationToolTest {

    private fun conversation(vararg texts: String): Conversation {
        val messages = texts.map { UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(it))) }
        return Conversation.ofId(id = Uuid.random()).updateCurrentMessages(messages)
    }

    @Test
    fun `anchored read returns window around the anchor`() {
        val c = conversation("m0", "m1", "m2", "m3", "m4")
        val anchorId = c.currentMessages[2].id.toString()
        val payload = Json.parseToJsonElement(
            buildConversationReadPayload(c, anchorId, context = 1)
        ).jsonObject

        val items = payload.getValue("messages").jsonArray
        assertEquals(3, items.size)  // m1, m2(anchor), m3
        assertEquals(true, items[1].jsonObject.getValue("anchor").jsonPrimitive.content.toBoolean())
        assertTrue(payload.getValue("anchored").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `missing anchor falls back to beginning with a note`() {
        val c = conversation("m0", "m1", "m2")
        val payload = Json.parseToJsonElement(
            buildConversationReadPayload(c, "no-such-id", context = 1)
        ).jsonObject

        assertTrue(payload.getValue("anchored").jsonPrimitive.content.toBoolean() == false)
        assertTrue(payload.containsKey("note"))
        val items = payload.getValue("messages").jsonArray
        assertEquals(2, items.size)  // 开头两条（0±1）
    }

    @Test
    fun `total budget truncates and notes`() {
        val long = "x".repeat(20_000)
        val c = conversation(long, long, long)
        val payload = Json.parseToJsonElement(
            buildConversationReadPayload(c, null, context = 5)
        ).jsonObject

        assertTrue(payload.containsKey("note"))
        // 每条都被单条上限截断
        payload.getValue("messages").jsonArray.forEach { item ->
            assertTrue(item.jsonObject.getValue("content").jsonPrimitive.content.length <= 20_100)
        }
    }

    @Test
    fun `tool calls and images render as placeholders`() {
        val c = Conversation.ofId(id = Uuid.random()).updateCurrentMessages(listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
                UIMessagePart.Tool(toolCallId = "t1", toolName = "search", input = "{}"),
                UIMessagePart.Image(url = "file:///x.png"),
            ))
        ))
        val payload = Json.parseToJsonElement(
            buildConversationReadPayload(c, null, context = 3)
        ).jsonObject
        val content = payload.getValue("messages").jsonArray[0]
            .jsonObject.getValue("content").jsonPrimitive.content
        assertTrue(content.contains("[tool call: search]"))
        assertTrue(content.contains("[image]"))
    }
}
