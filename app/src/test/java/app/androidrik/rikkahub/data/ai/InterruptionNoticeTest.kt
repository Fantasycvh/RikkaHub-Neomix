package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.appendInterruptionNotice
import app.androidrik.ai.ui.isUiNotice
import app.androidrik.ai.ui.withoutUiNotices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptionNoticeTest {

    private val notice = "\n⚠️ interrupted"

    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private val text = UIMessagePart.Text("half reply")

    @Test
    fun `interrupted reply gets a visible notice`() {
        val (updated, appended) = listOf(assistant(text)).appendInterruptionNotice(notice)
        assertTrue(appended)
        assertEquals(2, updated.last().parts.size)
        val added = updated.last().parts.last() as UIMessagePart.Text
        assertEquals(notice, added.text)
        assertTrue("标记为 UI 提示以便发请求时过滤", added.isUiNotice)
    }

    @Test
    fun `appending twice is idempotent`() {
        val (once, _) = listOf(assistant(text)).appendInterruptionNotice(notice)
        val (twice, appendedAgain) = once.appendInterruptionNotice(notice)
        assertFalse(appendedAgain)
        assertEquals(once.last().parts.size, twice.last().parts.size)
    }

    @Test
    fun `user tail is never marked`() {
        val (updated, appended) = listOf(UIMessage(role = MessageRole.USER, parts = listOf(text)))
            .appendInterruptionNotice(notice)
        assertFalse(appended)
        assertEquals(1, updated.last().parts.size)
    }

    @Test
    fun `zero-output interruption is not marked as incomplete reply`() {
        // 没有可用内容时提示等于把「空回复」伪装成「有回复」
        val (_, appended) = listOf(assistant(UIMessagePart.Text("   "))).appendInterruptionNotice(notice)
        assertFalse(appended)
    }

    @Test
    fun `reasoning-only reply still counts as usable content`() {
        val (_, appended) = listOf(assistant(UIMessagePart.Reasoning(reasoning = "thinking..."))).appendInterruptionNotice(notice)
        assertTrue(appended)
    }

    @Test
    fun `ui notices are stripped before sending but content stays`() {
        val (withNotice, _) = listOf(assistant(text)).appendInterruptionNotice(notice)
        val sent = withNotice.last().withoutUiNotices()
        assertEquals(listOf("half reply"), sent.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })
    }

    @Test
    fun `message with only a notice is left untouched`() {
        // 过滤后为空 ⇒ 原样返回（不制造空消息）
        val onlyNotice = assistant(UIMessagePart.Text(notice).also { it.metadata = kotlinx.serialization.json.buildJsonObject {
            put("uiNotice", kotlinx.serialization.json.JsonPrimitive(true))
        } })
        assertEquals(1, onlyNotice.withoutUiNotices().parts.size)
    }
}
