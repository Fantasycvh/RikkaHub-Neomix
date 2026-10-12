package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ===== isWithinCompressionBoundary（编辑/删除边界内消息 -> 检查点失效）=====

    @Test
    fun `nodes within boundary including boundary itself are flagged`() {
        val nodes = listOf(node("a"), node("b"), node("c"), node("d"))
        val cp = checkpoint(boundaryNodeId = nodes[1].id.toString())
        val conversation = conversation(nodes, listOf(cp))

        assertTrue("边界之前", conversation.isWithinCompressionBoundary(0))
        assertTrue("边界节点本身", conversation.isWithinCompressionBoundary(1))
        assertFalse("边界之后", conversation.isWithinCompressionBoundary(2))
        assertFalse("边界之后", conversation.isWithinCompressionBoundary(3))
    }

    @Test
    fun `without checkpoint nothing is within boundary`() {
        val conversation = conversation(listOf(node("a"), node("b")), emptyList())
        assertFalse(conversation.isWithinCompressionBoundary(0))
        assertFalse(conversation.isWithinCompressionBoundary(1))
    }

    @Test
    fun `expired checkpoint does not flag any node`() {
        val nodes = listOf(node("a"), node("b"))
        val cp = checkpoint(boundaryNodeId = Uuid.random().toString())
        val conversation = conversation(nodes, listOf(cp))

        assertFalse(conversation.isWithinCompressionBoundary(0))
    }

    // ===== 区域指纹校验（B 方案：一次性覆盖全部改动路径）=====

    /** 构造带正确指纹的检查点（模拟压缩落库时的记录） */
    private fun conversationWithFingerprint(nodes: List<MessageNode>, boundaryIndex: Int):
        Pair<Conversation, CompressionSummary> {
        val base = conversation(nodes, emptyList())
        val fingerprint = base.areaFingerprintFor(boundaryIndex)
        val cp = checkpoint(boundaryNodeId = nodes[boundaryIndex].id.toString())
            .copy(areaFingerprint = fingerprint)
        return conversation(nodes, listOf(cp)) to cp
    }

    @Test
    fun `checkpoint valid when area unchanged`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val conversation = conversationWithFingerprint(nodes, boundaryIndex = 1).first

        assertNotNull("区域未变时检查点应有效", conversation.activeCompression())
    }

    @Test
    fun `editing a node within area invalidates checkpoint`() {
        // 编辑 = 节点候选追加新消息并选中（updateCurrentMessages 语义）
        val nodes = listOf(node("a"), node("b"), node("c"))
        val (conversation, _) = conversationWithFingerprint(nodes, boundaryIndex = 1)
        val edited = conversation.copy(
            messageNodes = conversation.messageNodes.mapIndexed { index, n ->
                if (index == 0) n.copy(messages = n.messages + node("a-edited").messages.first(), selectIndex = 1)
                else n
            }
        )

        assertNull("区域内编辑必须使检查点失效", edited.activeCompression())
    }

    @Test
    fun `regenerating a node within area invalidates checkpoint`() {
        // 重新生成 = 新增候选并被选中
        val nodes = listOf(node("a"), node("b"), node("c"))
        val (conversation, _) = conversationWithFingerprint(nodes, boundaryIndex = 1)
        val regenerated = conversation.copy(
            messageNodes = conversation.messageNodes.mapIndexed { index, n ->
                if (index == 1) n.copy(messages = n.messages + node("b-v2").messages.first(), selectIndex = 1)
                else n
            }
        )

        assertNull("区域内重新生成必须使检查点失效", regenerated.activeCompression())
    }

    @Test
    fun `scope B truncation invalidates checkpoint and undo restores it`() {
        // 交叉用例：重生成 scope B（截断下游）后检查点必须失效；撤销还原后应重新有效
        // （指纹是纯函数 → 失效可逆，见 docs/design/design-regenerate-scope.md §七）
        val nodes = listOf(node("a"), node("b"), node("c"), node("d"))
        val (conversation, _) = conversationWithFingerprint(nodes, boundaryIndex = 2)
        assertNotNull("前置：检查点应有效", conversation.activeCompression())

        // 点第 2 条 → scope B 截断掉 c/d
        val clickedId = conversation.messageNodes[1].currentMessage.id
        val plan = conversation.truncationPlan(clickedId)!!
        val truncated = conversation.copy(messageNodes = conversation.messageNodes.take(plan.sizeAfterTruncation))
        assertEquals(2, truncated.messageNodes.size)
        assertNull("截断掉边界节点后检查点必须失效", truncated.activeCompression())

        // 撤销还原 → 检查点重新有效（可逆）
        val restored = truncated.copy(messageNodes = restoreAfterTruncation(truncated.messageNodes, plan))
        assertEquals(4, restored.messageNodes.size)
        assertNotNull("撤销还原后检查点应重新有效（可逆）", restored.activeCompression())
    }

    @Test
    fun `switching branch within area invalidates checkpoint`() {
        // 切换分支 = selectIndex 变化（候选不变）
        val multi = MessageNode(
            messages = listOf(node("v1").messages.first(), node("v2").messages.first()),
            selectIndex = 0,
        )
        val nodes = listOf(multi, node("b"), node("c"))
        val (conversation, _) = conversationWithFingerprint(nodes, boundaryIndex = 1)
        val switched = conversation.copy(
            messageNodes = conversation.messageNodes.mapIndexed { index, n ->
                if (index == 0) n.copy(selectIndex = 1) else n
            }
        )

        assertNull("区域内切换分支必须使检查点失效", switched.activeCompression())
    }

    @Test
    fun `deleting a node within area invalidates checkpoint`() {
        val nodes = listOf(node("a"), node("b"), node("c"), node("d"))
        val (conversation, _) = conversationWithFingerprint(nodes, boundaryIndex = 1)
        val afterDelete = conversation.copy(
            messageNodes = conversation.messageNodes.filterIndexed { index, _ -> index != 0 }
        )

        assertNull("区域内删除节点必须使检查点失效", afterDelete.activeCompression())
    }

    @Test
    fun `changes after boundary keep checkpoint valid`() {
        // 窗口（边界之后）的改动不影响——那部分本就原样发送
        val nodes = listOf(node("a"), node("b"), node("c"), node("d"))
        val (conversation, _) = conversationWithFingerprint(nodes, boundaryIndex = 1)
        val windowChanged = conversation.copy(
            messageNodes = conversation.messageNodes.mapIndexed { index, n ->
                if (index == 3) n.copy(messages = n.messages + node("d-v2").messages.first(), selectIndex = 1)
                else n
            }
        )

        assertNotNull("窗口内改动不应使检查点失效", windowChanged.activeCompression())
    }

    @Test
    fun `checkpoint without fingerprint degrades to boundary-existence check`() {
        // 旧数据（无指纹）保持原行为：仅校验边界节点存在
        val nodes = listOf(node("a"), node("b"), node("c"))
        val cp = checkpoint(boundaryNodeId = nodes[1].id.toString()) // 无指纹
        val conversation = conversation(nodes, listOf(cp))

        assertNotNull(conversation.activeCompression())
    }

    // ===== CompressionWindow：逐步重算（工具结果必须进入下一步上下文）=====

    /**
     * 回归（真机缺陷）：回合内工具调用会向 live messages 追加 assistant 消息与工具结果。
     * 若沿用回合初冻结的窗口，模型下一步看不到工具结果 → 重复调用同一工具
     * （实测 memory_tool create 重复执行、search_web 同一 query 跑两次）。
     */
    @Test
    fun `recomputing window against live messages carries in-turn tool result`() {
        val nodes = listOf(node("a"), node("b"), node("c"), node("d"))
        val cp = checkpoint(boundaryNodeId = nodes[1].id.toString()) // 边界 = index 1
        val conversation = conversation(nodes, listOf(cp))
        val window = conversation.compressionWindow()!!

        // 回合初：检查点 + c + d
        val turnStart = conversation.currentMessages
        assertEquals(3, window.applyTo(turnStart).size)

        // 回合内：追加 assistant（含 tool call）+ 工具结果
        val toolCall = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Tool(toolCallId = "t1", toolName = "memory_tool", input = "{}")),
        )
        val live = turnStart + toolCall

        val applied = window.applyTo(live)
        assertTrue("检查点仍在最前", applied.first().isSynthetic)
        assertTrue(
            "本回合新增的工具调用必须出现在下一步上下文里（否则模型会重复调用）",
            applied.any { it.id == toolCall.id },
        )
    }

    /** 边界之前的 live 消息仍被检查点替代（不因重算而泄漏被压缩的历史）。 */
    @Test
    fun `recomputed window still replaces pre-boundary history`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val cp = checkpoint(boundaryNodeId = nodes[1].id.toString())
        val conversation = conversation(nodes, listOf(cp))
        val window = conversation.compressionWindow()!!

        val applied = window.applyTo(conversation.currentMessages)
        // 只剩：检查点（替代 a/b）+ c
        assertEquals(2, applied.size)
        assertTrue(applied.first().isSynthetic)
        assertTrue(applied.first().toText().contains("summary"))
    }

    /** 无有效检查点时不产生窗口（调用方直接发送 live messages）。 */
    @Test
    fun `no checkpoint yields null window`() {
        val conversation = conversation(listOf(node("a")), emptyList())
        assertNull(conversation.compressionWindow())
    }

    /** 边界下标越界（列表被截断等）时保守回退，不丢消息。 */
    @Test
    fun `applyTo returns messages unchanged when boundary out of range`() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val cp = checkpoint(boundaryNodeId = nodes[1].id.toString())
        val conversation = conversation(nodes, listOf(cp))
        val window = conversation.compressionWindow()!!

        // 传入比边界更短的列表（模拟异常截断）→ 原样返回，不静默丢内容
        val short = listOf(node("x").currentMessage)
        assertEquals(short, window.applyTo(short))
    }
}
