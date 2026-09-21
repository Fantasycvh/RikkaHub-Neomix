package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.ToolApprovalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具输出接进预览（此前**完全不计**）。
 *
 * 背景：`UIMessagePart.Tool.output` 是独立的一类 part，文本嵌在 `output` 里，而预览的正文
 * 此前只取顶层 `Text` ⇒ 整条工具输出计 0；而 `ContextLength.estimateTokenCount` 一直计入
 * （口径不一致）。fork 单条工具输出上限 32KB，agent 会话里几十次调用即可超过对话正文。
 *
 * 设计事实：真实请求里工具结果是**独立的 `tool` 角色消息**，故这里每条输出一段
 * （与实发消息序列对应），而不是拼进 assistant 消息。
 */
class PromptToolResultSegmentTest {

    private val labels = PromptInspectionLabels(
        system = "System",
        checkpoint = "Checkpoint",
        injection = "Injected",
        history = "History",
        toolResult = "Tool output · %1\$s",
    )

    private fun toolPart(
        name: String = "read_file",
        output: List<UIMessagePart> = listOf(UIMessagePart.Text("file contents")),
    ) = UIMessagePart.Tool(
        toolCallId = "call_1",
        toolName = name,
        input = "{}",
        output = output,
    )

    private fun assistantWith(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    @Test
    fun `executed tool output becomes its own segment`() {
        val segments = toolResultSegments(
            assistantWith(UIMessagePart.Text("let me read"), toolPart()),
            labels,
        )
        assertEquals(1, segments.size)
        val seg = segments.single()
        assertEquals(PromptSegmentKind.TOOL_RESULT, seg.kind)
        assertEquals("tool", seg.role)
        assertEquals("file contents", seg.content)
        // 段名带工具名：目录里能分辨是哪次调用
        assertEquals("Tool output · read_file", seg.label)
        assertTrue(seg.tokens > 0)
    }

    @Test
    fun `message without tools yields no tool segments`() {
        val segments = toolResultSegments(
            assistantWith(UIMessagePart.Text("just text")),
            labels,
        )
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `pending tool is not counted (no output yet)`() {
        // 未执行的工具 output 为空 —— 实发时也不会有对应结果消息，不应成段
        val pending = UIMessagePart.Tool(
            toolCallId = "call_1",
            toolName = "read_file",
            input = "{}",
            output = emptyList(),
            approvalState = ToolApprovalState.Pending,
        )
        assertTrue(toolResultSegments(assistantWith(pending), labels).isEmpty())
    }

    @Test
    fun `tool with blank text output is skipped as noise`() {
        val blank = toolPart(output = listOf(UIMessagePart.Text("   ")))
        assertTrue(toolResultSegments(assistantWith(blank), labels).isEmpty())
    }

    @Test
    fun `each tool output gets its own segment matching the sent message sequence`() {
        // 一次 agent 多步：请求里是多个独立的 tool 消息，不是拼成一条
        val segments = toolResultSegments(
            assistantWith(
                toolPart(name = "read_file", output = listOf(UIMessagePart.Text("A"))),
                toolPart(name = "write_file", output = listOf(UIMessagePart.Text("B"))),
            ),
            labels,
        )
        assertEquals(2, segments.size)
        assertEquals(listOf("Tool output · read_file", "Tool output · write_file"), segments.map { it.label })
        assertEquals(listOf("A", "B"), segments.map { it.content })
    }

    @Test
    fun `multiple text sub-parts of one output are joined`() {
        val multi = toolPart(output = listOf(UIMessagePart.Text("line1"), UIMessagePart.Text("line2")))
        val seg = toolResultSegments(assistantWith(multi), labels).single()
        assertEquals("line1\nline2", seg.content)
    }

    @Test
    fun `non-text output only does not create a segment`() {
        // 仅图片输出：不计文本 token（与 estimateTokenCount 同口径），成段只会是空段
        val imageOnly = toolPart(
            output = listOf(UIMessagePart.Image(url = "file:///tmp/x.png")),
        )
        assertTrue(toolResultSegments(assistantWith(imageOnly), labels).isEmpty())
    }
}
