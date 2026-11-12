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
 * 重生成/剪枝的纯计算（`docs/design/design-regenerate-scope.md` +
 * `docs/design/in-progress/design-conversation-tree-branching.md`）。
 *
 * 核心语义：**scope 与消息角色无关**——用户编辑后既可能想"只修正这一段"（THIS_ONLY）、
 * 也可能想"从这里重做"（INCLUDE_FOLLOWING）。P1 起 scope B = 非破坏兄弟分支
 * （激活叶子收回到分支点，旧子树原样保留），截断/撤销机制移除；删除 = 剪枝子树。
 */
class RegeneratePlanTest {

    private fun msg(role: MessageRole, text: String): UIMessage =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    /** 构造 [user, assistant, user, assistant] 的四节点会话（parent 链完整，同生产加载态）。 */
    private fun conversation4(): Conversation {
        val nodes = listOf(
            msg(MessageRole.USER, "u1"),
            msg(MessageRole.ASSISTANT, "a1"),
            msg(MessageRole.USER, "u2"),
            msg(MessageRole.ASSISTANT, "a2"),
        ).map { MessageNode.of(it) }.healMissingParents()
        return Conversation(id = Uuid.random(), assistantId = Uuid.random(), messageNodes = nodes)
    }

    private fun textOf(node: MessageNode): String =
        (node.currentMessage.parts.first() as UIMessagePart.Text).text

    // ---- 目标节点 ----

    @Test
    fun `user message regenerates its reply (next node)`() {
        // 用户消息重做的是「它的回复」→ 目标 = 下一节点
        assertEquals(2, regenerateReplyNodeIndex(nodeIndex = 1, isUserMessage = true))
    }

    @Test
    fun `assistant message regenerates itself`() {
        assertEquals(1, regenerateReplyNodeIndex(nodeIndex = 1, isUserMessage = false))
    }

    // ---- 基线范围（THIS_ONLY：新回复落成候选而非新节点） ----

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

    // ---- 下游计数（scope B 文案 / 编辑开关显示判据） ----

    @Test
    fun `following count and messages describe the downstream on the active path`() {
        val conversation = conversation4()
        val firstId = conversation.messageNodes[0].currentMessage.id
        assertEquals(3, conversation.followingCount(firstId))
        assertEquals(listOf("a1", "u2", "a2"), conversation.followingMessages(firstId).map { m ->
            (m.parts.first() as UIMessagePart.Text).text
        })
    }

    @Test
    fun `node index lookup uses message id`() {
        val conversation = conversation4()
        assertEquals(0, conversation.nodeIndexOfMessage(conversation.messageNodes[0].currentMessage.id))
        assertEquals(2, conversation.nodeIndexOfMessage(conversation.messageNodes[2].currentMessage.id))
        assertNull(conversation.nodeIndexOfMessage(Uuid.random()))
    }

    // ---- 剪枝（删除整节点 = 剪子树；设计稿 §四.2 删除语义） ----

    @Test
    fun `prune removes the target node with all descendants`() {
        val conversation = conversation4()
        val pruned = conversation.pruneSubtree(conversation.messageNodes[1].id)!!
        assertEquals("子树（a1 及其后代）整体移除", listOf("u1"), pruned.messageNodes.map { textOf(it) })
    }

    @Test
    fun `prune of unknown node is null`() {
        assertNull(conversation4().pruneSubtree(Uuid.random()))
    }

    @Test
    fun `prune retracts active leaf to the pruned node parent`() {
        val base = conversation4()
        val conversation = base.copy(activeLeafNodeId = base.messageNodes.last().id)
        val replyNode = conversation.messageNodes[1]
        val pruned = conversation.pruneSubtree(replyNode.id)!!
        assertEquals("叶子收回被剪节点的父（u1 节点）", conversation.messageNodes[0].id, pruned.activeLeafNodeId)
    }
    @Test
    fun `prune keeps active leaf when it lives outside the pruned subtree`() {
        // 两条独立时间线：root → (A 链), (B 链)；激活叶子在 B，剪 A 的后代不影响叶子
        val root = MessageNode.of(msg(MessageRole.USER, "root"))
        val a1 = MessageNode.of(msg(MessageRole.ASSISTANT, "a1")).copy(parentId = root.id)
        val a2 = MessageNode.of(msg(MessageRole.USER, "a2")).copy(parentId = a1.id)
        val b1 = MessageNode.of(msg(MessageRole.ASSISTANT, "b1")).copy(parentId = root.id)
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(root, a1, a2, b1),
            activeLeafNodeId = b1.id,
        )
        val pruned = conversation.pruneSubtree(a1.id)!!
        assertEquals(listOf("root", "b1"), pruned.messageNodes.map { textOf(it) })
        assertEquals("off-path 叶子不动", b1.id, pruned.activeLeafNodeId)
    }

    @Test
    fun `prune of a mid-branch node retracts leaf to the shared parent`() {
        val root = MessageNode.of(msg(MessageRole.USER, "root"))
        val a1 = MessageNode.of(msg(MessageRole.ASSISTANT, "a1")).copy(parentId = root.id)
        val b1 = MessageNode.of(msg(MessageRole.ASSISTANT, "b1")).copy(parentId = root.id)
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(root, a1, b1),
            activeLeafNodeId = a1.id,
        )
        val pruned = conversation.pruneSubtree(a1.id)!!
        assertEquals(listOf("root", "b1"), pruned.messageNodes.map { textOf(it) })
        assertEquals("被剪节点非根 → 叶子收回共享父（root），另一分支原样保留", root.id, pruned.activeLeafNodeId)
    }

    @Test
    fun `prune keeps all candidates of the clicked node semantics via subtree identity`() {
        // 多候选节点：剪枝按节点粒度（节点与其后代整棵走），不动候选粒度——候选删除走 deleteMessage 分支
        val multi = MessageNode(
            messages = listOf(
                msg(MessageRole.USER, "u1"),
                msg(MessageRole.USER, "u1-edited"),
            ),
            selectIndex = 1,
        )
        val child = MessageNode.of(msg(MessageRole.ASSISTANT, "a1")).copy(parentId = multi.id)
        val conversation = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messageNodes = listOf(multi, child),
        )
        val pruned = conversation.pruneSubtree(multi.id)!!
        assertTrue(pruned.messageNodes.isEmpty())
    }
}
