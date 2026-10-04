package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落库半截内容时的「未完成」标记（[Conversation.markGeneratingNodeIncomplete]）。
 *
 * 只有「进程被杀后重开」才暴露的两个问题：半截内容看起来像正常完成（无法继续写）、
 * 未结束的推理块让思考卡片永远走计时器。这里固定标记的精确边界。
 */
class IncompleteNodeMarkingTest {

    private fun text(t: String, role: MessageRole = MessageRole.USER) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(t)),
    )

    private fun assistantWithReasoning(reasoning: String = "thinking", finished: Boolean = false) =
        UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = reasoning,
                    finishedAt = if (finished) kotlin.time.Clock.System.now() else null,
                ),
                UIMessagePart.Text("partial answer"),
            ),
        )

    @Test
    fun `marks the selected candidate as incomplete and finalizes its reasoning`() {
        val partial = assistantWithReasoning()
        val conversation = Conversation.ofId(id = Uuid.random()).copy(
            messageNodes = listOf(MessageNode.of(text("hi")), MessageNode.of(partial)),
        )

        val marked = conversation.markGeneratingNodeIncomplete(1)
        val markedMessage = marked.messageNodes[1].messages.single()

        assertTrue("恢复出来的半截消息必须能被识别", markedMessage.finishIncomplete)
        val reasoning = markedMessage.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertTrue("推理块必须收尾，否则思考卡片永远走计时器", reasoning.finishedAt != null)
        // 其余节点不受影响
        assertFalse(marked.messageNodes[0].messages.single().finishIncomplete)
    }

    @Test
    fun `only the selected candidate is marked - other candidates stay untouched`() {
        // 同节点的历史候选可能本已正常完成，一并标记会伪造「它也被截断了」
        val oldCandidate = assistantWithReasoning(finished = true)
        val streaming = assistantWithReasoning()
        val conversation = Conversation.ofId(id = Uuid.random()).copy(
            messageNodes = listOf(
                MessageNode.of(text("hi")),
                MessageNode(messages = listOf(oldCandidate, streaming), selectIndex = 1),
            ),
        )

        val marked = conversation.markGeneratingNodeIncomplete(1)

        assertFalse("未选中的历史候选不应被标记", marked.messageNodes[1].messages[0].finishIncomplete)
        assertTrue("选中的候选应被标记", marked.messageNodes[1].messages[1].finishIncomplete)
    }

    @Test
    fun `already finished reasoning keeps its timestamp`() {
        val conversation = Conversation.ofId(id = Uuid.random()).copy(
            messageNodes = listOf(MessageNode.of(assistantWithReasoning(finished = true))),
        )
        val original = conversation.messageNodes[0].messages.single()
            .parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt

        val marked = conversation.markGeneratingNodeIncomplete(0)
        val after = marked.messageNodes[0].messages.single()
            .parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt

        assertEquals(original, after)
    }

    @Test
    fun `non assistant node and out of range index are no-ops`() {
        val conversation = Conversation.ofId(id = Uuid.random()).copy(
            messageNodes = listOf(MessageNode.of(text("hi"))),
        )
        assertEquals(conversation, conversation.markGeneratingNodeIncomplete(0))
        assertEquals(conversation, conversation.markGeneratingNodeIncomplete(5))
        assertNull(
            conversation.markGeneratingNodeIncomplete(0).messageNodes[0].messages.single().finishReason,
        )
    }
}
