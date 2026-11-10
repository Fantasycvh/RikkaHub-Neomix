package app.androidrik.rikkahub.data.ai.prompts

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中途压缩的请求面构造（上游 `bcc649abc` 语义）：
 *
 * - 检查点后面不是用户消息时追加 [CONTINUATION_HINT]（只进请求面、不进检查点正文）；
 * - 续跑提示不会让模型把摘要当用户消息回应；
 * - 中途压缩的摘要指令要求写清「已做到哪 / 下一步」。
 */
class MidTurnCompressPromptTest {

    private fun checkpointText(summary: String) = buildCompactionCheckpointText(summary)

    private fun checkpoint() = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(checkpointText("摘要正文"))),
        isSynthetic = true,
    )

    private fun reply(text: String = "working"): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private fun emptyAssistant(): UIMessage =
        UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

    private fun window(checkpointMessage: UIMessage = checkpoint(), boundaryIndex: Int) =
        Conversation.CompressionWindow(checkpointMessage = checkpointMessage, boundaryIndex = boundaryIndex)

    private fun UIMessage.texts() =
        parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

    @Test
    fun `checkpoint as the last window message gets the continuation hint`() {
        val messages = listOf(UIMessage.user("hi"), reply())
        val window = window(boundaryIndex = messages.lastIndex)

        val hinted = window.withContinuationHintFor(messages)

        assertTrue(hinted !== window)
        assertEquals(2, hinted.checkpointMessage.texts().size)
        assertEquals(CONTINUATION_HINT, hinted.checkpointMessage.texts().last())
        // 检查点正文（第一段）一个字都不动：它会被落库、被后续压缩合并
        assertEquals(checkpointText("摘要正文"), hinted.checkpointMessage.texts().first())
    }

    @Test
    fun `checkpoint followed by the fresh reply still gets the hint`() {
        // 中途压缩刚发生：窗口 = [检查点] + [新开的空回复]（请求面会被裁掉，但判定先看到它）
        val messages = listOf(UIMessage.user("hi"), reply(), emptyAssistant())
        val window = window(boundaryIndex = messages.size - 2)

        val hinted = window.withContinuationHintFor(messages)

        assertTrue(hinted !== window)
        assertEquals(CONTINUATION_HINT, hinted.checkpointMessage.texts().last())
    }

    @Test
    fun `checkpoint followed by a user message is left alone`() {
        // 轮前自动压缩：边界之后就是本轮的 user 消息 ⇒ 不需要续跑提示
        val messages = listOf(UIMessage.user("hi"), reply(), UIMessage.user("next"))
        val window = window(boundaryIndex = 1)

        val hinted = window.withContinuationHintFor(messages)

        assertSame(window, hinted)
        assertEquals(1, window.checkpointMessage.texts().size)
    }

    @Test
    fun `imported checkpoint behaves like a checkpoint for the hint`() {
        // 导入的原版检查点没有合成前导语，但同样是「历史被摘要替代」的边界：模型一样会把摘要
        // 当成用户新提的要求，故续跑提示对它同样生效（只改请求面副本，不改持久化正文）
        val imported = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("导入的摘要")),
            isContextCheckpoint = true,
        )
        val messages = listOf(imported, reply())
        val window = window(checkpointMessage = imported, boundaryIndex = messages.lastIndex)

        val hinted = window.withContinuationHintFor(messages)

        assertTrue(hinted !== window)
        assertEquals(2, hinted.checkpointMessage.texts().size)
        assertEquals(CONTINUATION_HINT, hinted.checkpointMessage.texts().last())
        // 原消息对象不动
        assertEquals(1, imported.texts().size)
    }

    @Test
    fun `hinted checkpoint is still partitioned as a checkpoint`() {
        // 提示追加成**第二个 Text part**：检测靠「任一段以检查点前导语开头」，不能因此失效
        val messages = listOf(UIMessage.user("hi"), reply())
        val hinted = window(boundaryIndex = messages.lastIndex).withContinuationHintFor(messages)
        val applied = hinted.applyTo(messages)

        val (checkpoints, rest) = applied.partitionCheckpointMessages()

        assertEquals(1, checkpoints.size)
        assertTrue(rest.isEmpty())
        assertEquals(CONTINUATION_HINT, checkpoints.single().texts().last())
    }

    @Test
    fun `messages without a checkpoint report no continuation need`() {
        val plain = listOf(UIMessage.user("hi"), reply())

        assertFalse(plain.hasCheckpointNotFollowedByUser())
        // 检查点后面直接是用户消息（正常轮次）也不算「需要续跑」
        assertFalse((listOf(checkpoint(), UIMessage.user("next"))).hasCheckpointNotFollowedByUser())
    }

    @Test
    fun `mid-turn compress instruction asks for state and next step`() {
        // 判据：读摘要的是「同一条任务的下一步」，不是下一轮对话 ⇒ 必须要求写清进度与下一步
        assertTrue(MID_TURN_COMPRESS_INSTRUCTION.contains("Current Work"))
        assertTrue(MID_TURN_COMPRESS_INSTRUCTION.contains("Next Step"))
        assertTrue(MID_TURN_COMPRESS_INSTRUCTION.contains("already done"))
        assertFalse(MID_TURN_COMPRESS_INSTRUCTION.isBlank())
    }
}
