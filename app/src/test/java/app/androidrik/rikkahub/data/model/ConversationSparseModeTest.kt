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
        nodes.mapIndexed { index, node ->
            NodeSummary(node.id, node.parentId, index, node.selectIndex, messageBytes = 100)
        }

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

    // ---- 阶段 5：摘要化指纹（id + selectIndex）----

    private fun Conversation.withV2FingerprintAt(boundaryPathIndex: Int): Conversation {
        val full = copy(messageNodes = fullNodes(), nodeSummaries = emptyList())
        val fp = requireNotNull(full.areaFingerprintFor(boundaryPathIndex))
        return copy(
            compressionSummaries = compressionSummaries.map {
                it.copy(areaFingerprint = fp)
            }
        )
    }

    private fun Conversation.fullNodes(): List<MessageNode> {
        // 用摘要路径 id 重建同构完整链（selectIndex 与摘要一致，均为 0）。
        var parentId: Uuid? = null
        return summaryPathNodeIds().mapIndexed { index, id ->
            node("n$index", parentId).copy(id = id).also { parentId = id }
        }
    }

    @Test
    fun `v2 fingerprint validates in sparse mode without bodies`() {
        val (conversation, _) = sparseConversation()
        val boundaryId = conversation.compressionSummaries.last().boundaryNodeId
        val boundaryIndex = conversation.summaryPathNodeIds().indexOfFirst { it.toString() == boundaryId }
        val stamped = conversation.withV2FingerprintAt(boundaryIndex)

        assertTrue(stamped.compressionSummaries.last().areaFingerprint!!.startsWith(FINGERPRINT_V2_PREFIX))
        assertNotNull("摘要层校验不得依赖区域正文", stamped.activeCompression())
        assertEquals(1, boundaryIndex)
    }

    /** 未加载区域的 selectIndex 变了（例如别的会话/分支操作落库）：v2 指纹必须判失效。 */
    @Test
    fun `v2 fingerprint invalidates when summary selection changed`() {
        val (conversation, _) = sparseConversation()
        val stamped = conversation.withV2FingerprintAt(1)
        val boundaryId = stamped.compressionSummaries.last().boundaryNodeId
        val changed = stamped.copy(
            nodeSummaries = stamped.nodeSummaries.map {
                if (it.id.toString() == boundaryId) it.copy(selectIndex = 1) else it
            }
        )

        assertNull(changed.activeCompression())
    }

    /** 已 hydrate 区域的节点改了选中候选（内存态）：v2 指纹同样要判失效。 */
    @Test
    fun `v2 fingerprint invalidates when loaded area selection changed`() {
        val (conversation, _) = sparseConversation()
        // 边界取 c（路径下标 2），c 已加载
        val cId = conversation.summaryPathNodeIds()[2]
        val stamped = conversation.copy(
            compressionSummaries = listOf(checkpoint(boundaryNodeId = cId.toString()))
        ).withV2FingerprintAt(2)
        assertNotNull(stamped.activeCompression())

        val edited = stamped.copy(
            messageNodes = stamped.messageNodes.map {
                if (it.id == cId) it.copy(selectIndex = 1) else it
            }
        )
        assertNull("已加载节点的选中候选变化必须让检查点失效", edited.activeCompression())
    }

    // ---- 阶段 6：压缩前缀物理清理 ----

    @Test
    fun `prefix prune keeps boundary subtree only`() {
        val a = node("a")
        val b = node("b", a.id)
        val c = node("c", b.id)
        val d = node("d", c.id)
        val sibling = node("sib", b.id) // 边界之外的旁支：同样要删

        val candidates = prefixPruneCandidates(summariesOf(listOf(a, b, c, d, sibling)), c.id)

        assertEquals(setOf(a.id, b.id, sibling.id), candidates)
    }

    /** 清理后按保留子树重写指纹：稀疏会话（正文只剩边界之后）仍判定检查点有效。 */
    @Test
    fun `pruned checkpoint stays valid with rewritten fingerprint`() {
        val (conversation, _) = sparseConversation()
        val boundaryId = requireNotNull(conversation.compressionSummaries.last().boundaryNodeId)
        val boundaryUuid = Uuid.parse(boundaryId)
        val candidates = prefixPruneCandidates(conversation.nodeSummaries, boundaryUuid)
        val kept = conversation.nodeSummaries.filter { it.id !in candidates }
        val fingerprint = requireNotNull(
            summaryAreaFingerprint(kept, conversation.activeLeafNodeId, boundaryUuid)
        )

        val pruned = conversation.copy(
            messageNodes = conversation.messageNodes.filter { it.id !in candidates },
            nodeSummaries = kept,
            compressionSummaries = listOf(
                checkpoint(boundaryId).copy(areaFingerprint = fingerprint)
            ),
        )

        assertNotNull(pruned.activeCompression())
        assertEquals(listOf("c", "d"), pruned.pathNodes().map { it.currentMessage.toText() })
        assertEquals(3, pruned.requestWindowMessages().size) // 摘要检查点 + 已加载的 c、d
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
