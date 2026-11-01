package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.TextGenerationResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置工具（ServerTool，如 googleSearch/urlContext）整 part 到达时的合并语义。
 *
 * 旧 `MessageChunk`/`handleMessageChunk` 已删除：这里改走非流式公开入口
 * [handleTextGenerationResult]，它内部调用 [UIMessage.mergeDelta]——与旧
 * `UIMessage + MessageChunk` 相同的 ServerTool 分支（按 toolCallId 找到则
 * `status/input/output` 覆盖更新、否则追加新 part）。
 */
class ServerToolMergeTest {

    /** 合并一条「整 part 到达」的 assistant 结果（同一段 mergeDelta 语义）。 */
    private fun UIMessage.merge(vararg parts: UIMessagePart): UIMessage =
        listOf(this).handleTextGenerationResult(
            TextGenerationResult(
                id = "resp-1",
                model = "gemini-2.5-pro",
                message = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
            ),
        ).last()

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
        message = message.merge(
            serverTool(id = "st-1", toolName = "googleSearch", status = ServerToolStatus.IN_PROGRESS, input = true)
        )
        val progress = message.parts.single() as UIMessagePart.ServerTool
        assertEquals(ServerToolStatus.IN_PROGRESS, progress.status)
        assertEquals(true, progress.input != null)

        // 同 id 的 completed 到达：按 id 更新，不追加新 part
        message = message.merge(
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
        message = message.merge(serverTool("st-1", "googleSearch", ServerToolStatus.IN_PROGRESS))
        message = message.merge(serverTool("st-2", "urlContext", ServerToolStatus.IN_PROGRESS))
        assertEquals(2, message.parts.size)
        assertTrue(message.parts.all { it is UIMessagePart.ServerTool })
    }
}
