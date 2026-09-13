package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 压缩检查点的窗口组装与失效判定（design-auto-compaction §4.1 三边界对齐）。
 *
 * 关键行为：轨迹只追加（不改 messageNodes）、边界节点消失则失效回退全量、
 * 发送窗口 = 检查点合成消息 + 边界之后的消息。
 */
class CompressionWindowTest {

    private fun node(text: String) = MessageNode(
        messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
        )
    )

    private fun conversation(nodes: List<MessageNode>, checkpoints: List<CompressionSummary>) =
        Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = nodes,
            compressionSummaries = checkpoints,
        )

    private fun checkpoint(boundaryNodeId: String?, content: String = "summary") = CompressionSummary(
        conversationId = Uuid.random(),
        content = content,
        messageCount = 3,
        boundaryNodeId = boundaryNodeId,
        createdAt = java.time.Instant.now(),
    )

    @Test
    fun `no checkpoint returns full messages`() {
        val conversation = conversation(listOf(node("a"), node("b")), emptyList())
        assertNull(conversation.activeCompression())
        assertEquals(2, conversation.requestWindowMessages().size)
        assertEquals(2, conversation.windowNodes().size)
    }

    @Test
    fun `active checkpoint replaces earlier history in send window`() {
        val nodes = listOf(node("a"), node("b"), node("c"), node("d"))
        // 边界 = 第 2 条（index 1）：它及之前被压缩，之后（c/d）原文发送
        val cp = checkpoint(boundaryNodeId = nodes[1].id.toString())
        val conversation = conversation(nodes, listOf(cp))

        assertNotNull(conversation.activeCompression())
        val window = conversation.requestWindowMessages()
        // 检查点合成消息 + c + d
        assertEquals(3, window.size)
        assertTrue(window.first().isSynthetic)
        assertEquals(MessageRole.USER, window.first().role)
        assertTrue(window.first().toText().contains("summary"))
        assertEquals(2, conversation.windowNodes().size)
    }

    @Test
    fun `checkpoint expires when boundary node disappears`() {
        val nodes = listOf(node("a"), node("b"))
        // 边界指向不存在的节点（模拟 regenerate 截断/分支切换删除）
        val cp = checkpoint(boundaryNodeId = Uuid.random().toString())
        val conversation = conversation(nodes, listOf(cp))

        assertNull("边界节点不存在时必须失效", conversation.activeCompression())
        // 失效后回退全量
        assertEquals(2, conversation.requestWindowMessages().size)
        assertEquals(2, conversation.windowNodes().size)
    }

    @Test
    fun `checkpoint without boundary never expires`() {
        val nodes = listOf(node("a"))
        val cp = checkpoint(boundaryNodeId = null)
        val conversation = conversation(nodes, listOf(cp))

        assertNotNull(conversation.activeCompression())
        // 无边界时 requestWindowMessages 无法定位起点，回退全量（保守）
        assertEquals(1, conversation.requestWindowMessages().size)
    }

    @Test
    fun `latest checkpoint wins when multiple exist`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val older = checkpoint(boundaryNodeId = nodes[0].id.toString(), content = "old")
        val newer = checkpoint(boundaryNodeId = nodes[1].id.toString(), content = "new")
        val conversation = conversation(nodes, listOf(older, newer))

        assertEquals("new", conversation.activeCompression()?.content)
    }

    @Test
    fun `window assembly does not mutate message nodes`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val cp = checkpoint(boundaryNodeId = nodes[0].id.toString())
        val conversation = conversation(nodes, listOf(cp))

        conversation.requestWindowMessages()
        // 轨迹只追加：messageNodes 必须原样保留（可回溯）
        assertEquals(3, conversation.messageNodes.size)
    }
}
