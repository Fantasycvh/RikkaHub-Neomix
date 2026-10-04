package app.androidrik.rikkahub.data.repository

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式检查点的**节点索引**语义（`ConversationRepository.checkpointGeneratingNode` 的前提）。
 *
 * 为什么需要钉住：检查点只 upsert 一个节点，索引取错就会把生成中的内容写进别的节点（数据破坏）。
 * 索引来源 = `chunk.messages.lastIndex`，其正确性依赖 [Conversation.updateCurrentMessages] 的
 * 「按索引映射」语义——而这条语义在两类路径下结论不同，正是本测试要固定的：
 * - 常规生成：基线是全会话 → 变动节点就是 `messageNodes.lastIndex`
 * - `THIS_ONLY` 重生成：基线是**前缀**，目标节点之后仍有下游节点 → 变动节点**不是** lastIndex
 */
class StreamCheckpointIndexTest {

    private fun msg(text: String, role: MessageRole = MessageRole.USER) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun node(vararg messages: UIMessage) = MessageNode(messages = messages.toList())

    @Test
    fun `normal streaming appends the node at the last index`() {
        val conversation = Conversation.ofId(id = Uuid.random()).copy(
            messageNodes = listOf(node(msg("u0")), node(msg("u1"))),
        )
        // 基线 = 全会话；流式追加助手回复
        val streaming = listOf(msg("u0"), msg("u1"), msg("a2", MessageRole.ASSISTANT))
        val updated = conversation.updateCurrentMessages(streaming)

        assertEquals(3, updated.messageNodes.size)
        assertEquals(streaming.lastIndex, updated.messageNodes.lastIndex)
        // 变动节点即末位：其候选里能看到生成中的助手消息
        val target = updated.messageNodes[streaming.lastIndex]
        assertTrue(target.messages.any { it.role == MessageRole.ASSISTANT })
    }

    @Test
    fun `THIS_ONLY regenerate mutates a middle node so lastIndex is wrong`() {
        // 4 个节点；对 index=1 的消息重新生成（THIS_ONLY：下游 2/3 保留）
        val conversation = Conversation.ofId(id = Uuid.random()).copy(
            messageNodes = listOf(
                node(msg("u0")),
                node(msg("a1-old", MessageRole.ASSISTANT)),
                node(msg("u2")),
                node(msg("a3", MessageRole.ASSISTANT)),
            ),
        )
        // 重生成基线 = 0 until replyNodeIndex(=1)，随后流式追加新候选
        val streaming = listOf(msg("u0"), msg("a1-new", MessageRole.ASSISTANT))
        val updated = conversation.updateCurrentMessages(streaming)

        val mutatedIndex = streaming.lastIndex
        assertEquals("变动节点索引 = 1", 1, mutatedIndex)
        assertNotEquals(
            "THIS_ONLY 重生成时变动节点不是末位（下游节点仍在）——检查点不能用 lastIndex",
            updated.messageNodes.lastIndex,
            mutatedIndex,
        )
        // 变动节点拿到新候选（候选追加 + selectIndex 指向新候选）
        val target = updated.messageNodes[mutatedIndex]
        assertEquals(2, target.messages.size)
        assertEquals(1, target.selectIndex)
        // 下游节点未被触碰
        assertEquals("u2", (updated.messageNodes[2].messages.single().parts.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `node identity is stable across streaming updates`() {
        // 检查点用 @Insert(REPLACE) 按主键覆盖 ⇒ 依赖「同一节点在多次回写间 id 不变」
        val conversation = Conversation.ofId(id = Uuid.random()).copy(
            messageNodes = listOf(node(msg("u0"))),
        )
        val first = conversation.updateCurrentMessages(
            listOf(msg("u0"), msg("a1", MessageRole.ASSISTANT)),
        )
        val nodeIdAfterFirst = first.messageNodes[1].id
        val second = first.updateCurrentMessages(
            listOf(msg("u0"), msg("a1-longer", MessageRole.ASSISTANT)),
        )
        assertEquals(nodeIdAfterFirst, second.messageNodes[1].id)
    }
}
