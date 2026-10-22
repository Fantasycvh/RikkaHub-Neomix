package app.androidrik.rikkahub.data.plugin

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [trimTrailingWhitespace] 纯逻辑单测。
 *
 * 与真机验证互补:设备上只能观察"插件被管线调用"与"裁剪字符数"日志,
 * 这里的单测锁定裁剪语义本身(逐行 trimEnd、非 assistant 消息不动、计数准确)。
 */
class WhitespaceTrimPluginTest {

    private fun assistantMessage(text: String, role: MessageRole = MessageRole.ASSISTANT): UIMessage =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    private fun textOf(message: UIMessage): String =
        (message.parts.single() as UIMessagePart.Text).text

    @Test
    fun `trims trailing whitespace on every line`() {
        val message = assistantMessage("alpha  \nbeta \n gamma\t\n")

        val (result, removed) = trimTrailingWhitespace(listOf(message))

        assertEquals("alpha\nbeta\n gamma\n", textOf(result.single()))
        assertEquals(4, removed) // alpha 后 2 + beta 后 1 + gamma 前那行 `\t` 1
    }

    @Test
    fun `leaves non-assistant messages untouched`() {
        val user = assistantMessage("keep me  ", role = MessageRole.USER)

        val (result, removed) = trimTrailingWhitespace(listOf(user))

        assertEquals("keep me  ", textOf(result.single()))
        assertEquals(0, removed)
        assertSame(user, result.single())
    }

    @Test
    fun `no trailing whitespace means zero removals and identical text`() {
        val message = assistantMessage("clean line\nsecond line")

        val (result, removed) = trimTrailingWhitespace(listOf(message))

        assertEquals("clean line\nsecond line", textOf(result.single()))
        assertEquals(0, removed)
    }

    @Test
    fun `trims multiple text parts`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("a  "), UIMessagePart.Text("b  ")),
        )

        val (result, removed) = trimTrailingWhitespace(listOf(message))

        assertEquals(listOf("a", "b"), result.single().parts.map { (it as UIMessagePart.Text).text })
        assertEquals(4, removed)
    }
}
