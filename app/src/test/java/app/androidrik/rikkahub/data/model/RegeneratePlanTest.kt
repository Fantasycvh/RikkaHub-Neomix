package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 重生成的纯计算（`docs/design-regenerate-scope.md`）。
 *
 * 核心语义：**scope 与消息角色无关**——用户编辑后既可能想"只修正这一段"（THIS_ONLY）、
 * 也可能想"从这里重做"（INCLUDE_FOLLOWING），两种需求都正当，故不再按角色分派。
 */
class RegeneratePlanTest {

    private fun msg(role: MessageRole, text: String): UIMessage =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    /** 构造 [user, assistant, user, assistant] 的四节点会话。 */
    private fun conversation4(): Conversation {
        val nodes = listOf(
            msg(MessageRole.USER, "u1"),
            msg(MessageRole.ASSISTANT, "a1"),
            msg(MessageRole.USER, "u2"),
            msg(MessageRole.ASSISTANT, "a2"),
        ).map { MessageNode.of(it) }
        return Conversation(id = Uuid.random(), assistantId = Uuid.random(), messageNodes = nodes)
    }

    private fun textOf(node: MessageNode): String =
        (node.currentMessage.parts.first() as UIMessagePart.Text).text

    // ---- 目标节点 ----

    @Test
    fun `user message regenerates its reply (next node)`() {
        // 用户消息重做的是「它的回复」→ 目标 = 下一节点（新回复作为该节点候选）
        assertEquals(2, regenerateReplyNodeIndex(nodeIndex = 1, isUserMessage = true))
    }

    @Test
    fun `assistant message regenerates itself`() {
        assertEquals(1, regenerateReplyNodeIndex(nodeIndex = 1, isUserMessage = false))
    }

    // ---- 基线范围（决定新回复落成候选而非新节点） ----

    @Test
    fun `baseline excludes the target node so the reply becomes a candidate`() {
        // 目标 2、共 4 节点 → 基线 0..1（不含目标）→ 新回复追加为 node 2 的候选
        assertEquals(0..1, regenerateBaselineRange(replyNodeIndex = 2, nodeCount = 4))
    }

    @Test
    fun `target at the end yields null baseline so full context is used`() {
        // 目标 == 节点数（最后一条用户消息尚无回复）→ null（走全量 + 允许自动压缩）
        assertNull(regenerateBaselineRange(replyNodeIndex = 4, nodeCount = 4))
        assertNull(regenerateBaselineRange(replyNodeIndex = 5, nodeCount = 4))
    }

    // ---- 截断计划（scope B） ----

    @Test
    fun `truncation removes strictly after the clicked message`() {
        val conversation = conversation4()
        // 点第 2 条（u1）→ 移除 a1/u2/a2，保留 u1 本身
        val plan = conversation.truncationPlan(conversation.messageNodes[0].id.let { _ ->
            conversation.messageNodes[0].currentMessage.id
        })!!
        assertEquals(1, plan.sizeAfterTruncation)
        assertEquals(listOf("a1", "u2", "a2"), plan.removedNodes.map { textOf(it) })
    }

    @Test
    fun `truncation on the last node is null (nothing to remove)`() {
        val conversation = conversation4()
        val lastId = conversation.messageNodes.last().currentMessage.id
        assertNull("末条无下游 → 无破坏性、无需确认", conversation.truncationPlan(lastId))
    }

    @Test
    fun `truncation on unknown message is null`() {
        assertNull(conversation4().truncationPlan(Uuid.random()))
    }

    @Test
    fun `following count and messages agree with truncation`() {
        val conversation = conversation4()
        val firstId = conversation.messageNodes[0].currentMessage.id
        assertEquals(3, conversation.followingCount(firstId))
        assertEquals(listOf("a1", "u2", "a2"), conversation.followingMessages(firstId).map { m ->
            (m.parts.first() as UIMessagePart.Text).text
        })
        // 与截断计划同口径
        assertEquals(conversation.followingCount(firstId), conversation.truncationPlan(firstId)!!.removedNodes.size)
    }

    @Test
    fun `node index lookup uses message id`() {
        val conversation = conversation4()
        assertEquals(0, conversation.nodeIndexOfMessage(conversation.messageNodes[0].currentMessage.id))
        assertEquals(2, conversation.nodeIndexOfMessage(conversation.messageNodes[2].currentMessage.id))
        assertNull(conversation.nodeIndexOfMessage(Uuid.random()))
    }

    // ---- scope A 的关键性质：不删下游 ----

    @Test
    fun `scope A never truncates (no plan is applied)`() {
        // THIS_ONLY 不调用 truncationPlan —— 由调用方决定；这里固化「计划本身只描述 B 的破坏」
        val conversation = conversation4()
        val plan = conversation.truncationPlan(conversation.messageNodes[1].currentMessage.id)!!
        // 即使对助手消息（scope A 常见目标）也存在截断计划，说明"是否截断"由 scope 参数决定、
        // 与角色无关——这正是本设计要的性质
        assertEquals(2, plan.sizeAfterTruncation)
        assertEquals(2, plan.removedNodes.size)
    }

    // ---- 撤销还原 ----

    @Test
    fun `restore brings back removed tail when nothing was generated`() {
        // 截断后未生成 → 还原 = 原样
        val conversation = conversation4()
        val firstId = conversation.messageNodes[0].currentMessage.id
        val plan = conversation.truncationPlan(firstId)!!
        val afterTruncation = conversation.messageNodes.take(plan.sizeAfterTruncation)

        val restored = restoreAfterTruncation(afterTruncation, plan)
        assertEquals(conversation.messageNodes.map { it.id }, restored.map { it.id })
    }

    @Test
    fun `restore discards nodes generated after truncation`() {
        // 截断后已生成新回复（新节点）→ 撤销必须一并撤掉（撤销语义 = 回到截断前）
        val conversation = conversation4()
        val firstId = conversation.messageNodes[0].currentMessage.id
        val plan = conversation.truncationPlan(firstId)!!

        val afterTruncation = conversation.messageNodes.take(plan.sizeAfterTruncation)
        val withNewReply = afterTruncation + MessageNode.of(msg(MessageRole.ASSISTANT, "a1-new"))

        val restored = restoreAfterTruncation(withNewReply, plan)
        assertEquals(
            "新生成的回复应被撤掉、尾巴应复原",
            listOf("u1", "a1", "u2", "a2"),
            restored.map { textOf(it) },
        )
        assertTrue("新回复不应残留", restored.none { textOf(it) == "a1-new" })
    }

    @Test
    fun `restore of half-written reply also discards it`() {
        // #1812 半截草稿同样是"截断后新产生的节点"，撤销应一并撤掉
        val conversation = conversation4()
        val firstId = conversation.messageNodes[0].currentMessage.id
        val plan = conversation.truncationPlan(firstId)!!
        val afterTruncation = conversation.messageNodes.take(plan.sizeAfterTruncation)
        val withHalfReply = afterTruncation + MessageNode.of(msg(MessageRole.ASSISTANT, "half..."))

        val restored = restoreAfterTruncation(withHalfReply, plan)
        assertEquals(4, restored.size)
        assertTrue(restored.none { textOf(it) == "half..." })
    }

    // ---- 多分支（编辑/重生成产生的候选） ----

    @Test
    fun `truncation keeps the clicked node with all its candidates`() {
        // 节点内有多个候选（编辑/重生成过）时，截断只按节点粒度、不动该节点的候选
        val multi = MessageNode(
            messages = listOf(
                msg(MessageRole.USER, "u1"),
                msg(MessageRole.USER, "u1-edited"),
            ),
            selectIndex = 1,
        )
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(multi, MessageNode.of(msg(MessageRole.ASSISTANT, "a1"))),
        )
        val plan = conversation.truncationPlan(multi.currentMessage.id)!!
        assertEquals(1, plan.sizeAfterTruncation)
        assertEquals(1, plan.removedNodes.size)
        // 还原后候选仍在（非破坏性前提）
        val restored = restoreAfterTruncation(conversation.messageNodes.take(1), plan)
        assertEquals(2, restored.size)
        assertEquals(2, restored[0].messages.size)
    }
}
