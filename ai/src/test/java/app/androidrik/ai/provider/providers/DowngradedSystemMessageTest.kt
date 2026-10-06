package app.androidrik.ai.provider.providers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中部 system 注入降级为 user 时的标记包裹（③-3b，2026-10-06）。
 *
 * Claude / Gemini / Responses 不能承载「位置在对话中部」的 system 角色 ⇒ 只能降级为 user；
 * 裸降级会让模型把系统指令读成「用户说过的话」，故包一层 `<system-reminder>`（与 Claude Code 的
 * 同名约定、本仓 system 正文里的 `<recovery_note>` 同风格）。
 */
class DowngradedSystemMessageTest {

    @Test
    fun `text parts are wrapped and role becomes user`() {
        val message = UIMessage.system("注入的系统指令")

        val downgraded = message.asDowngradedSystemMessage()

        assertEquals(MessageRole.USER, downgraded.role)
        assertEquals(
            "<system-reminder>\n注入的系统指令\n</system-reminder>",
            (downgraded.parts.single() as UIMessagePart.Text).text,
        )
    }

    @Test
    fun `non text parts are left untouched`() {
        val message = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(
                UIMessagePart.Text("系统指令"),
                UIMessagePart.Image(url = "https://example.com/a.png"),
            ),
        )

        val downgraded = message.asDowngradedSystemMessage()

        assertTrue((downgraded.parts[0] as UIMessagePart.Text).text.startsWith(SYSTEM_REMINDER_OPEN))
        // 图片 part 不得被塞进标记文本
        assertEquals("https://example.com/a.png", (downgraded.parts[1] as UIMessagePart.Image).url)
    }

    @Test
    fun `wrapping is idempotent`() {
        val once = UIMessage.system("指令").asDowngradedSystemMessage()

        val twice = once.asDowngradedSystemMessage()

        val text = (twice.parts.single() as UIMessagePart.Text).text
        assertEquals(text, (once.parts.single() as UIMessagePart.Text).text)
        assertEquals(1, Regex(Regex.escape(SYSTEM_REMINDER_OPEN)).findAll(text).count())
    }
}
