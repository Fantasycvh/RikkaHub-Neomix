package app.androidrik.ai.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import app.androidrik.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ServerToolMergeTest {

    private fun chunk(vararg parts: UIMessagePart): MessageChunk = MessageChunk(
        id = Uuid.random().toString(),
        model = "gemini-2.5-pro",
        choices = listOf(UIMessageChoice(index = 0, delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()), message = null, finishReason = null)),
    )

    private fun serverTool(
        id: String,
        toolName: String,
        status: ServerToolStatus,
        input: Boolean = false,
        output: Boolean = false,
    ) = UIMessagePart.ServerTool(
        toolCallId = id,
        toolName = toolName,
        input = if (input) buildJsonObject { put("query", JsonPrimitive("test")) } else null,
        output = if (output) buildJsonObject { put("content", JsonPrimitive("result")) } else null,
        status = status,
    )

    @Test
    fun `server tool starts in-progress then updates to completed by id`() {
        var message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        message += chunk(
            serverTool(id = "st-1", toolName = "googleSearch", status = ServerToolStatus.IN_PROGRESS, input = true)
        )
        val progress = message.parts.single() as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.IN_PROGRESS, progress.status)
        assertEquals(true, progress.input != null)

        // 同 id 的 completed 到达：按 id 更新，不追加新 part
        message += chunk(
            serverTool(id = "st-1", toolName = "googleSearch", status = ServerToolStatus.COMPLETED, output = true)
        )
        assertEquals(1, message.parts.size)
        val done = message.parts.single() as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.COMPLETED, done.status)
        assertEquals("result", ((done.output as JsonObject)["content"] as JsonPrimitive).content)
        assertEquals("test", ((done.input as JsonObject)["query"] as JsonPrimitive).content) // input 保留
    }

    @Test
    fun `distinct server tool ids append separately`() {
        var message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        message += chunk(serverTool("st-1", "googleSearch", ServerToolStatus.IN_PROGRESS))
        message += chunk(serverTool("st-2", "urlContext", ServerToolStatus.IN_PROGRESS))
        assertEquals(2, message.parts.size)
        assertTrue(message.parts.all { it is UIMessagePart.ServerTool })
    }
}
