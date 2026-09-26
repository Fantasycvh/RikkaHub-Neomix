package app.androidrik.rikkahub.service

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 子代理运行进度的纯函数（对话内工具卡的"跑到第几步"数据源）。
 *
 * 钉住三条：①步数=**模型步数**（助手消息条数，一步里并行多个工具算一步）；
 * ②最近工具取**轨迹里最后一条工具调用**（不是最后一个 message——工具结果回填会新增消息）；
 * ③空轨迹不炸、不编造。
 */
class SubagentProgressTest {

    private fun assistant(vararg parts: UIMessagePart) =
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    private fun toolPart(name: String) = UIMessagePart.Tool(
        toolCallId = "call-$name",
        toolName = name,
        input = "{}",
    )

    @Test
    fun `empty transcript reports zero steps and no tool`() {
        val snapshot = snapshotProgress(emptyList())

        assertEquals(0, snapshot.steps)
        assertNull(snapshot.lastTool)
    }

    @Test
    fun `user message alone is not a step`() {
        // 首条 user 消息（任务目标）不算一步：进度说的是"模型跑了多少步"
        val snapshot = snapshotProgress(listOf(UIMessage.user("do the thing")))

        assertEquals(0, snapshot.steps)
        assertNull(snapshot.lastTool)
    }

    @Test
    fun `parallel tool calls in one assistant message count as one step`() {
        val messages = listOf(
            UIMessage.user("go"),
            assistant(toolPart("search_web"), toolPart("scrape_web")),
        )
        val snapshot = snapshotProgress(messages)

        assertEquals(1, snapshot.steps)
        // 取最后一条工具调用（同一步里最后一个）
        assertEquals("scrape_web", snapshot.lastTool)
    }

    @Test
    fun `later steps advance the count and the last tool`() {
        val messages = listOf(
            UIMessage.user("go"),
            assistant(toolPart("search_web")),
            UIMessage(role = MessageRole.TOOL, parts = listOf(UIMessagePart.Text("result"))),
            assistant(toolPart("workspace_read_file")),
            assistant(UIMessagePart.Text("done")),
        )
        val snapshot = snapshotProgress(messages)

        // 三条助手消息 = 三步（含最后那条纯文本的收尾步）
        assertEquals(3, snapshot.steps)
        // 最近工具仍是 read_file：纯文本步不覆盖它
        assertEquals("workspace_read_file", snapshot.lastTool)
    }

    @Test
    fun `text-only transcript has steps but no tool`() {
        val snapshot = snapshotProgress(
            listOf(UIMessage.user("hi"), assistant(UIMessagePart.Text("hello")))
        )

        assertEquals(1, snapshot.steps)
        assertNull(snapshot.lastTool)
    }
}
