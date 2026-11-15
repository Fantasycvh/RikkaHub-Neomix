package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 稀疏加载（阶段 4，`design-conversation-lazy-load.md` §三/§六）的模型侧语义：
 * 摘要全量 + 正文窗口、摘要路径上的边界判定、哨兵窗口的发送组装。
 */
class ConversationSparseModeTest {

    private fun node(text: String, parentId: Uuid? = null) = MessageNode(
        parentId = parentId,
        messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
        ),
    )

    private fun checkpoint(boundaryNodeId: String?, content: String = "summary") = CompressionSummary(
        conversationId = Uuid.random(),
        content = content,
        messageCount = 2,
        boundaryNodeId = boundaryNodeId,
        createdAt = java.time.Instant.now(),
    )

    private fun summariesOf(nodes: List<MessageNode>): List<NodeSummary> =
        nodes.mapIndexed { index, node -> NodeSummary(node.id, node.parentId, index, messageBytes = 100) }

    /**
     * 线性链 a→b→c→d，检查点边界 = b；稀疏会话只加载 c/d（边界及之前按设计排除）。
     */
    private fun sparseConversation(
        fingerprint: String? = null,
    ): Pair<Conversation, List<MessageNode>> {
        val a = node("a")
        val b = node("b", a.id)
        val c = node("c", b.id)
        val d = node("d", c.id)
        val all = listOf(a, b, c, d)
        val cp = checkpoint(boundaryNodeId = b.id.toString()).copy(areaFingerprint = fingerprint)
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(c, d),
            activeLeafNodeId = d.id,
            compressionSummaries = listOf(cp),
            nodeSummaries = summariesOf(all),
        )
        return conversation to all
    }

    @Test
    fun `sparse conversation keeps boundary checkpoint valid via summaries`() {
        val (conversation, _) = sparseConversation()

        assertTrue(conversation.isSparse)
        assertNotNull("边界在摘要路径上即有效，不因正文未加载而失效", conversation.activeCompression())
        assertEquals(listOf("c", "d"), conversation.pathNodes().map { it.currentMessage.toText() })
        assertEquals(2, conversation.omittedNodeCount)
    }

    @Test
    fun `sparse send window is checkpoint plus loaded tail`() {
        val (conversation, _) = sparseConversation()

        val window = conversation.requestWindowMessages()
        assertEquals(3, window.size)
        assertTrue("检查点由摘要合成", window.first().isSynthetic)
        assertTrue(window.first().toText().contains("summary"))
        assertEquals(listOf("c", "d"), window.drop(1).map { it.toText() })
        assertEquals(listOf("c", "d"), conversation.windowNodes().map { it.currentMessage.toText() })
    }

    /** 哨兵边界（-1）：本回合新增的工具调用/结果必须随 applyTo 进入下一步上下文。 */
    @Test
    fun `sparse sentinel window keeps in-turn messages`() {
        val (conversation, _) = sparseConversation()
        val window = conversation.compressionWindow()!!
        val toolCall = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(toolCallId = "t1", toolName = "memory_tool", input = "{}")),
        )

        val applied = window.applyTo(conversation.currentMessages + toolCall)
        assertTrue(applied.first().isSynthetic)
        assertTrue("回合内新增的工具调用不能被哨兵窗口丢在边界外", applied.any { it.id == toolCall.id })
        assertEquals(4, applied.size)
    }

    @Test
    fun `sparse checkpoint expires when boundary missing from summary path`() {
        val (conversation, _) = sparseConversation()
        // 边界被剪枝：摘要路径里没有它
        val pruned = conversation.copy(
            compressionSummaries = listOf(checkpoint(boundaryNodeId = Uuid.random().toString()))
        )

        assertNull(pruned.activeCompression())
        assertNull(pruned.compressionWindow())
        // 无窗口时只发送已加载尾巴（不静默冒充全量由仓库层守卫，模型层保持可预测）
        assertEquals(listOf("c", "d"), pruned.requestWindowMessages().map { it.toText() })
    }

    /** 稀疏时摘要区域未加载，指纹无法在摘要层校验：信任落库值（阶段 5 再摘要化指纹）。 */
    @Test
    fun `sparse mode trusts persisted fingerprint`() {
        val (conversation, _) = sparseConversation(fingerprint = "stale-but-unverifiable")

        assertNotNull(conversation.activeCompression())
    }

    /** 一旦全量补齐（nodeSummaries 清空），指纹校验恢复，陈旧检查点重新判失效。 */
    @Test
    fun `full load resumes fingerprint validation`() {
        val (conversation, all) = sparseConversation(fingerprint = "stale-but-unverifiable")
        val full = conversation.copy(messageNodes = all, nodeSummaries = emptyList())

        assertFalse(full.isSparse)
        assertNull("全量后指纹不符必须失效", full.activeCompression())
    }

    @Test
    fun `omitted count ignores already hydrated prefix`() {
        val (conversation, all) = sparseConversation()
        val hydrated = conversation.copy(messageNodes = all.drop(1)) // 补了 b

        assertEquals(1, hydrated.omittedNodeCount)
        assertEquals(3, hydrated.requestWindowMessages().size)
    }

    /**
     * 摘要只在打开会话时读一次：本轮新生成的尾部节点不在摘要里，必须仍出现在激活路径上，
     * 否则刚生成的回复会从发送窗口/UI 里消失。
     */
    @Test
    fun `new tail nodes absent from summaries stay on the path`() {
        val (conversation, _) = sparseConversation()
        val appended = node("e", conversation.messageNodes.last().id)
        val updated = conversation.copy(
            messageNodes = conversation.messageNodes + appended,
            activeLeafNodeId = appended.id,
        )

        assertEquals(listOf("c", "d", "e"), updated.pathNodes().map { it.currentMessage.toText() })
        assertEquals(4, updated.requestWindowMessages().size)
        assertTrue(updated.requestWindowMessages().last().toText() == "e")
    }
}
