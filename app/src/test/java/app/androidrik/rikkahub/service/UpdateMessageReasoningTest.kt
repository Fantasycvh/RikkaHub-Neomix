package app.androidrik.rikkahub.service

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toMetadata
import app.androidrik.rikkahub.data.model.MessageNode
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1929 编辑思考过程的纯函数核（[updateMessageReasoningInNodes]）。
 *
 * 契约：原地改（不新建分支）、只替换目标 Reasoning part 的文本、
 * createdAt/finishedAt 与其余 part 一个不动、目标找不到 = 无操作（null）。
 */
class UpdateMessageReasoningTest {

    private val t0 = Instant.fromEpochMilliseconds(1_000_000)
    private val t1 = Instant.fromEpochMilliseconds(2_000_000)

    private fun assistantMessage(
        reasoning: String,
        reasoningCreatedAt: Instant = t0,
        text: String = "answer",
    ) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Reasoning(reasoning = reasoning, createdAt = reasoningCreatedAt, finishedAt = t1),
            UIMessagePart.Text(text),
        ),
    )

    private fun nodeOf(vararg messages: UIMessage) = MessageNode(messages = messages.toList())

    @Test
    fun `refuses to edit provider-signed reasoning`() {
        // Claude 签名 thinking / Gemini thoughtSignature：上游要求原样回传 ⇒ 纯函数核必须拒绝
        val signed = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "signed reasoning",
                    createdAt = t0,
                    finishedAt = t1,
                    metadata = app.androidrik.ai.ui.ClaudeReasoningMetadata(signature = "sig-abc").toMetadata(),
                ),
                UIMessagePart.Text("answer"),
            ),
        )
        val node = nodeOf(signed)
        assertNull(
            "签名块的编辑必须被拒绝（无操作 = null）",
            updateMessageReasoningInNodes(listOf(node), signed.id, t0, "edited"),
        )
    }

    @Test
    fun `replaces only target reasoning text in place`() {
        val node = nodeOf(assistantMessage("old reasoning"))
        val target = node.messages[0].parts.filterIsInstance<UIMessagePart.Reasoning>()[0]

        val updated = updateMessageReasoningInNodes(
            nodes = listOf(node),
            messageId = node.messages[0].id,
            reasoningCreatedAt = target.createdAt,
            reasoningText = "new reasoning",
        )

        assertNotNull(updated)
        val message = updated!![0].messages[0]
        assertEquals("new reasoning", message.parts.filterIsInstance<UIMessagePart.Reasoning>()[0].reasoning)
        // 不新建分支
        assertEquals(1, updated[0].messages.size)
        // 时间戳保留（"思考了 X 秒"不失真）
        val reasoningPart = message.parts.filterIsInstance<UIMessagePart.Reasoning>()[0]
        assertEquals(t0, reasoningPart.createdAt)
        assertEquals(t1, reasoningPart.finishedAt)
        // 其余 part 不动
        assertEquals("answer", message.parts.filterIsInstance<UIMessagePart.Text>()[0].text)
    }

    @Test
    fun `only the matching reasoning part is touched when message has several`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "first", createdAt = t0, finishedAt = t1),
                UIMessagePart.Reasoning(reasoning = "second", createdAt = t1, finishedAt = t1),
                UIMessagePart.Text("answer"),
            ),
        )
        val node = nodeOf(msg)

        val updated = updateMessageReasoningInNodes(
            nodes = listOf(node),
            messageId = msg.id,
            reasoningCreatedAt = t1,
            reasoningText = "edited",
        )!!

        val reasons = updated[0].messages[0].parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals("first", reasons[0].reasoning)
        assertEquals("edited", reasons[1].reasoning)
    }

    @Test
    fun `sibling branches and other nodes stay untouched`() {
        val target = assistantMessage("old reasoning")
        val sibling = assistantMessage("sibling reasoning")
        val otherNode = nodeOf(assistantMessage("other reasoning"))
        val targetNode = MessageNode(messages = listOf(target, sibling), selectIndex = 1)

        val updated = updateMessageReasoningInNodes(
            nodes = listOf(targetNode, otherNode),
            messageId = target.id,
            reasoningCreatedAt = t0,
            reasoningText = "edited",
        )!!

        // 目标分支原地改、selectIndex 不动
        assertEquals(1, updated[0].selectIndex)
        assertEquals("edited", updated[0].messages[0].parts.filterIsInstance<UIMessagePart.Reasoning>()[0].reasoning)
        // 兄弟分支与其他 node 原样
        assertEquals(
            "sibling reasoning",
            updated[0].messages[1].parts.filterIsInstance<UIMessagePart.Reasoning>()[0].reasoning,
        )
        assertEquals(
            "other reasoning",
            updated[1].messages[0].parts.filterIsInstance<UIMessagePart.Reasoning>()[0].reasoning,
        )
    }

    @Test
    fun `missing message or part is a no-op`() {
        val node = nodeOf(assistantMessage("reasoning"))

        // 消息不存在
        assertNull(
            updateMessageReasoningInNodes(listOf(node), Uuid.random(), t0, "edited"),
        )
        // 消息在但 Reasoning part 不存在（用户消息等）
        val userMessage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("hi")),
        )
        val userNode = nodeOf(userMessage)
        assertNull(
            updateMessageReasoningInNodes(listOf(userNode), userMessage.id, t0, "edited"),
        )
        // 消息在但 createdAt 对不上
        assertNull(
            updateMessageReasoningInNodes(listOf(node), node.messages[0].id, t1, "edited"),
        )
    }
}
