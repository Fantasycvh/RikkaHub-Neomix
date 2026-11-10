package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.prompts.CONTINUATION_HINT
import app.androidrik.rikkahub.data.ai.prompts.buildCompactionCheckpointText
import app.androidrik.rikkahub.data.ai.prompts.withContinuationHintFor
import app.androidrik.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中途压缩的**请求面**装配（上游 `bcc649abc` 语义）：
 *
 * - 新开的那条空回复只是流式落点，发请求前裁掉；
 * - 检查点后面不是用户消息时，请求里的检查点带续跑提示；
 * - 两步叠加后，发送窗口里只剩「带提示的检查点」。
 */
class MidTurnCompressionRequestFaceTest {

    private fun checkpoint() = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(buildCompactionCheckpointText("摘要正文"))),
        isSynthetic = true,
    )

    private fun reply(text: String = "working") =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private fun emptyAssistant() =
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

    private fun UIMessage.texts() =
        parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

    @Test
    fun `trailing empty assistant is not sent`() {
        val messages = listOf(UIMessage.user("hi"), reply(), emptyAssistant())

        val requestBasis = messages.withoutTrailingEmptyAssistantForRequest()

        assertEquals(2, requestBasis.size)
        assertEquals("hi", requestBasis.first().texts().single())
    }

    @Test
    fun `normal history is returned as the same instance`() {
        val messages = listOf(UIMessage.user("hi"), reply())

        assertSame(messages, messages.withoutTrailingEmptyAssistantForRequest())
    }

    @Test
    fun `mid-turn window sends only the hinted checkpoint`() {
        // 场景：中途压缩完成 → 下一步请求。基线 = [user, old reply(边界), 新空回复]
        val oldReply = reply("done with tools")
        val messages = listOf(UIMessage.user("hi"), oldReply, emptyAssistant())
        val window = Conversation.CompressionWindow(
            checkpointMessage = checkpoint(),
            boundaryIndex = messages.indexOf(oldReply),
        )

        // 请求面两步：裁掉空回复 → 判定续跑 → 窗口套用
        val requestBasis = messages.withoutTrailingEmptyAssistantForRequest()
        val sendWindow = window.withContinuationHintFor(requestBasis)
        val sent = buildSendWindowMessages(
            messages = requestBasis,
            compressionWindow = sendWindow,
            contextMessageLimit = 0,
        )

        assertEquals(1, sent.size)
        val sentCheckpoint = sent.single()
        assertEquals(MessageRole.USER, sentCheckpoint.role)
        assertEquals(2, sentCheckpoint.texts().size)
        assertEquals(CONTINUATION_HINT, sentCheckpoint.texts().last())
        // 被压进摘要的部分（旧回复）不能再以原文出现在发送窗口里
        assertTrue(sent.none { it.role == MessageRole.ASSISTANT })
    }

    @Test
    fun `compressed turn with a pending user message keeps the user message and gains no hint`() {
        // 轮前压缩：边界之后是本轮的 user 消息 ⇒ 正常发送、无续跑提示
        val oldReply = reply()
        val messages = listOf(UIMessage.user("hi"), oldReply, UIMessage.user("next"))
        val window = Conversation.CompressionWindow(
            checkpointMessage = checkpoint(),
            boundaryIndex = messages.indexOf(oldReply),
        )

        val sendWindow = window.withContinuationHintFor(messages)
        val sent = buildSendWindowMessages(
            messages = messages,
            compressionWindow = sendWindow,
            contextMessageLimit = 0,
        )

        assertEquals(2, sent.size)
        assertEquals(1, (sent.first().texts()).size)
        assertEquals("next", sent.last().texts().single())
    }
}
