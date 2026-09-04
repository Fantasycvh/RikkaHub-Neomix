package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPartialFailureTest {

    private fun assistant(vararg parts: UIMessagePart): UIMessage =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts.toList(),
        )

    @Test
    fun `streaming cut with visible text over threshold is partial`() {
        // 模拟流式中途断：已合并足够可见文本
        val messages = listOf(assistant(UIMessagePart.Text("A".repeat(80))))
        assertTrue("≥50 可见字符应判定为半截输出", isPartialOutput(messages))
    }

    @Test
    fun `zero output with no text is not partial`() {
        // 开局即断：还没有任何文本
        val messages = listOf(assistant())
        assertFalse("无输出不应判定为半截", isPartialOutput(messages))
    }

    @Test
    fun `short output below threshold is not partial`() {
        val messages = listOf(assistant(UIMessagePart.Text("你好")))
        assertFalse("低于阈值（<50）不应判定为半截", isPartialOutput(messages))
    }

    @Test
    fun `reasoning parts do not count toward partial threshold`() {
        // 只有 thinking 没有 text：即使内容很多也不算「可见输出」
        val messages = listOf(
            assistant(UIMessagePart.Reasoning(reasoning = "R".repeat(500)))
        )
        assertFalse("reasoning 不参与阈值判定", isPartialOutput(messages))
    }

    @Test
    fun `empty message list is not partial`() {
        assertFalse("空列表不应判定为半截", isPartialOutput(emptyList()))
    }

    @Test
    fun `last message being user means not partial`() {
        val messages = listOf(
            UIMessage.user("What is the weather?"),
        )
        assertFalse("最后一条非 assistant 不应判定为半截", isPartialOutput(messages))
    }

    @Test
    fun `partial exception carries cause and partial messages`() {
        val cause = RuntimeException("stream interrupted")
        val partial = listOf(assistant(UIMessagePart.Text("半截".repeat(40))))

        val exception = GenerationPartialFailureException(cause, partial)

        assertSame("cause 必须保持原异常", cause, exception.cause)
        assertEquals("partialMessages 必须携带半截消息", partial, exception.partialMessages)
    }
}
