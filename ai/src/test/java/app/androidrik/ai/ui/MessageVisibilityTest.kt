package app.androidrik.ai.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageVisibilityTest {
    @Test
    fun `client tool is visible UI content`() {
        val parts = listOf(
            UIMessagePart.Tool(
                toolCallId = "call_1",
                toolName = "ask_user",
                input = "{}",
            )
        )

        // tool-only 消息不应被判空——操作按钮（重新生成/审批）保持可见（321443d8 / #1689）
        assertFalse(parts.isEmptyUIMessage())
    }

    @Test
    fun `server tool is visible UI content`() {
        val parts = listOf(
            UIMessagePart.ServerTool(
                toolCallId = "call_1",
                toolName = "web_search",
                status = ServerToolStatus.IN_PROGRESS,
            )
        )

        // server-tool-only 消息同样应保持操作按钮可见
        assertFalse(parts.isEmptyUIMessage())
    }

    @Test
    fun `blank text remains empty UI content`() {
        assertTrue(listOf(UIMessagePart.Text(" ")).isEmptyUIMessage())
    }
}
