package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 树形分支纯函数（design-conversation-tree-branching.md）。
 *
 * 钉子：
 * 1. 父链自愈（healMissingParents——P0 惰性回填的 null 行补前驱，显式父绝不覆写）；
 * 2. pathToLeaf（树遍历 + 环/断链防御）；
 * 3. id 制合并（mergeGenerationMessages）——线性场景与旧索引制等价 + P1 树形语义：
 *    新节点挂父并推进 activeLeafNodeId、THIS_ONLY 候选落点、scope B 收短路径开新子树。
 */
class ConversationTreeTest {

    private fun msg(text: String, role: MessageRole = MessageRole.USER) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun textOf(node: MessageNode) =
        (node.currentMessage.parts.single() as UIMessagePart.Text).text

    private fun conversation(vararg texts: String) = Conversation.ofId(Uuid.random()).copy(
        // 生产路径的会话一律先过加载 heal（parent 链完整）；夹具保持同构
        messageNodes = texts.map { msg(it).toMessageNode() }.healMissingParents(),
    )

    // ---- healMissingParents（P1 权威化后的兜底自愈） ----

    @Test fun `heal fills null parents by list order keeping explicit ones`() {
        val root = msg("a").toMessageNode()
        val explicit = msg("b").toMessageNode().copy(parentId = root.id) // 分支显式父
        val legacy = msg("c").toMessageNode() // P0 未回填行
        val healed = listOf(root, explicit, legacy).healMissingParents()
        assertNull(healed[0].parentId)
        assertEquals(root.id, healed[1].parentId)
        assertEquals("null 行补列表前驱", explicit.id, healed[2].parentId)
    }

    @Test fun `heal never overwrites a divergent explicit parent`() {
        val a = msg("a").toMessageNode()
        val b = msg("b").toMessageNode().copy(parentId = Uuid.random()) // 兄弟分支根，前驱不是 a
        val healed = listOf(a, b).healMissingParents()
        assertEquals("显式父原样保留", b.parentId, healed[1].parentId)
    }

    @Test fun `heal is identity when nothing to fill`() {
        val nodes = listOf(msg("a").toMessageNode(), msg("b").toMessageNode().copy(parentId = Uuid.random()))
        assertSame(nodes, nodes.healMissingParents())
    }

    // ---- pathToLeaf（权威读路径） ----

    @Test fun `path to leaf walks linear chain root first`() {
        val a = msg("a").toMessageNode()
        val b = msg("b").toMessageNode().copy(parentId = a.id)
        val c = msg("c").toMessageNode().copy(parentId = b.id)
        val nodes = listOf(a, b, c)
        val path = nodes.pathToLeaf(c.id)!!
        assertEquals(nodes.map { it.id }, path.map { it.id })
    }

    @Test fun `path to leaf picks the branch subtree`() {
        // root → (branchA → a2), (branchB → b2)：两条独立时间线
        val root = msg("root").toMessageNode()
        val a1 = msg("a1").toMessageNode().copy(parentId = root.id)
        val a2 = msg("a2").toMessageNode().copy(parentId = a1.id)
        val b1 = msg("b1").toMessageNode().copy(parentId = root.id)
        val b2 = msg("b2").toMessageNode().copy(parentId = b1.id)
        val nodes = listOf(root, a1, a2, b1, b2)
        assertEquals(listOf(root.id, b1.id, b2.id), nodes.pathToLeaf(b2.id)!!.map { it.id })
        assertEquals(listOf(root.id, a1.id, a2.id), nodes.pathToLeaf(a2.id)!!.map { it.id })
    }

    @Test fun `path to leaf degrades to null on missing leaf broken parent or cycle`() {
        val a = msg("a").toMessageNode()
        val b = msg("b").toMessageNode().copy(parentId = a.id)
        val nodes = listOf(a, b)
        assertNull(nodes.pathToLeaf(Uuid.random()))
        val orphan = msg("orphan").toMessageNode().copy(parentId = Uuid.random())
        assertNull((nodes + orphan).pathToLeaf(orphan.id))
        val x = msg("x").toMessageNode()
        val y = msg("y").toMessageNode().copy(parentId = x.id)
        val cyclic = listOf(x.copy(parentId = y.id), y)
        assertNull(cyclic.pathToLeaf(y.id))
    }

    // ---- pathNodes（叶子权威 + 回退） ----

    @Test fun `pathNodes follows the active leaf through the tree`() {
        val root = msg("root").toMessageNode()
        val a1 = msg("a1").toMessageNode().copy(parentId = root.id)
        val b1 = msg("b1").toMessageNode().copy(parentId = root.id)
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(root, a1, b1),
            activeLeafNodeId = b1.id,
        )
        assertEquals(listOf(root.id, b1.id), conversation.pathNodes().map { it.id })
        // 叶子切回另一分支
        assertEquals(listOf(root.id, a1.id), conversation.copy(activeLeafNodeId = a1.id).pathNodes().map { it.id })
    }

    @Test fun `pathNodes falls back to the full list on null leaf or broken chain`() {
        val conversation = conversation("a", "b")
        assertEquals(conversation.messageNodes, conversation.pathNodes())
        val broken = conversation.copy(activeLeafNodeId = Uuid.random())
        assertEquals("断链回退全量", conversation.messageNodes, broken.pathNodes())
    }

    // ---- mergeGenerationMessages：线性场景（与旧索引制等价） ----

    @Test fun `equal snapshots retain the conversation and every node`() {
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = List(200) { msg("history $it").toMessageNode() }.healMissingParents(),
        )
        val updated = conversation.mergeGenerationMessages(conversation.currentMessages.map { it.copy() })
        assertSame(conversation, updated)
        assertSame(conversation, conversation.mergeGenerationMessages(emptyList()))
    }

    @Test fun `streaming replaces only the changed node and retains trailing history`() {
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = List(200) { msg("history $it").toMessageNode() }.healMissingParents(),
        )
        val changed = conversation.currentMessages[198].copy(parts = listOf(UIMessagePart.Text("new tail")))
        val updated = conversation.mergeGenerationMessages(conversation.currentMessages.take(198) + changed)
        assertNotSame(conversation.messageNodes[198], updated.messageNodes[198])
        assertSame(changed, updated.messageNodes[198].currentMessage)
        conversation.messageNodes.indices.filter { it != 198 }.forEach {
            assertSame("node $it", conversation.messageNodes[it], updated.messageNodes[it])
        }
    }

    @Test fun `updating existing alternate preserves selection and metadata`() {
        val first = msg("first")
        val second = msg("second")
        val node = MessageNode(messages = listOf(first, second), selectIndex = 1, isFavorite = true)
        val conversation = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(node))
        val updated = conversation.mergeGenerationMessages(listOf(first.copy(parts = emptyList())))
        assertEquals(1, updated.messageNodes.single().selectIndex)
        assertTrue(updated.messageNodes.single().isFavorite)
        assertEquals(node.id, updated.messageNodes.single().id)
        assertSame(second, updated.messageNodes.single().currentMessage)
    }

    @Test fun `new candidates become selected and new nodes append`() {
        val original = msg("original").toMessageNode()
        val alternate = msg("alternate")
        val tail = msg("tail")
        val updated = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(original))
            .mergeGenerationMessages(listOf(alternate, tail))
        assertEquals(original.id, updated.messageNodes.first().id)
        assertEquals(listOf(original.currentMessage, alternate), updated.messageNodes.first().messages)
        assertEquals(1, updated.messageNodes.first().selectIndex)
        assertSame(tail, updated.messageNodes.last().currentMessage)
        // P1：新节点显式挂父，叶子推进
        assertEquals(original.id, updated.messageNodes.last().parentId)
        assertEquals(updated.messageNodes.last().id, updated.activeLeafNodeId)
    }

    // ---- P1 树形语义 ----

    @Test fun `THIS_ONLY regenerate lands new reply as candidate of the middle target node`() {
        val conversation = conversation("u0", "a1-old", "u2", "a3")
        // 基线 = 0 until replyNodeIndex(=1)，流式追加新候选
        val streaming = listOf(conversation.currentMessages[0], msg("a1-new", MessageRole.ASSISTANT))
        val updated = conversation.mergeGenerationMessages(streaming)

        assertEquals(4, updated.messageNodes.size)
        val target = updated.messageNodes[1]
        assertEquals(2, target.messages.size)
        assertEquals(1, target.selectIndex)
        assertEquals("a1-new", textOf(target))
        // 候选追加不推进叶子、不建新节点
        assertNull(updated.activeLeafNodeId)
        // 下游未被触碰
        assertEquals("u2", textOf(updated.messageNodes[2]))
        assertEquals("a3", textOf(updated.messageNodes[3]))
    }

    @Test fun `node identity is stable across streaming updates`() {
        val conversation = conversation("u0")
        val first = conversation.mergeGenerationMessages(listOf(msg("u0"), msg("a1", MessageRole.ASSISTANT)))
        val nodeIdAfterFirst = first.messageNodes[1].id
        val second = first.mergeGenerationMessages(listOf(msg("u0"), msg("a1-longer", MessageRole.ASSISTANT)))
        assertEquals(nodeIdAfterFirst, second.messageNodes[1].id)
    }

    @Test fun `message id owned by another node is replaced in place not duplicated`() {
        val conversation = conversation("u0", "a1", "u2")
        val owned = conversation.currentMessages[1]
        // 构造「id 错位」：owned 的更新出现在位置 2（索引制会往 node2 追加副本 ⇒ 跨节点重复）
        val updatedOwned = owned.copy(parts = listOf(UIMessagePart.Text("a1-edited")))
        val incoming = listOf(conversation.currentMessages[0], conversation.currentMessages[2], updatedOwned)
        val updated = conversation.mergeGenerationMessages(incoming)

        assertEquals(3, updated.messageNodes.size)
        assertEquals("a1-edited", textOf(updated.messageNodes[1]))
        assertEquals(1, updated.messageNodes[1].messages.size) // 无副本追加
        assertEquals(1, updated.messageNodes[2].messages.size) // 位置节点不被污染
        val idCounts = updated.messageNodes.flatMap { it.messages }.groupingBy { it.id }.eachCount()
        assertTrue("不允许跨节点重复 id", idCounts.values.all { it == 1 })
    }

    @Test fun `scope B branch appends a sibling node under the branch point and moves the leaf`() {
        // scope B 前置：激活叶子收回到分支点（路径收短）→ 流式新回复超出路径尾部 → 新子树
        val root = msg("u0").toMessageNode()
        val oldReply = msg("a1-old", MessageRole.ASSISTANT).toMessageNode().copy(parentId = root.id)
        val oldTail = msg("u2").toMessageNode().copy(parentId = oldReply.id)
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(root, oldReply, oldTail),
            activeLeafNodeId = oldTail.id,
        )
        // 收短叶子（regenerateAtMessage scope B 的动作）
        val branched = conversation.copy(activeLeafNodeId = root.id)
        val updated = branched.mergeGenerationMessages(
            branched.currentMessages + msg("a1-new", MessageRole.ASSISTANT),
        )
        assertEquals("全量节点保留（旧子树不删）", 4, updated.messageNodes.size)
        val sibling = updated.messageNodes.last()
        assertEquals("a1-new", textOf(sibling))
        assertEquals("兄弟：与旧回复同父（= 分支点）", root.id, sibling.parentId)
        assertEquals(root.id, updated.messageNodes[1].parentId)
        assertEquals("叶子推进到新分支", sibling.id, updated.activeLeafNodeId)
        // 旧子树原样
        assertSame(conversation.messageNodes[1], updated.messageNodes[1])
        assertSame(conversation.messageNodes[2], updated.messageNodes[2])
    }

    @Test fun `merge chains consecutive new nodes under the active path tail`() {
        val root = msg("u0").toMessageNode()
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(root),
            activeLeafNodeId = root.id,
        )
        val updated = conversation.mergeGenerationMessages(
            listOf(root.currentMessage, msg("a1", MessageRole.ASSISTANT), msg("u2")),
        )
        assertEquals(3, updated.messageNodes.size)
        assertEquals(root.id, updated.messageNodes[1].parentId)
        assertEquals(updated.messageNodes[1].id, updated.messageNodes[2].parentId)
        assertEquals(updated.messageNodes[2].id, updated.activeLeafNodeId)
        assertEquals(listOf(root.id, updated.messageNodes[1].id, updated.messageNodes[2].id),
            updated.pathNodes().map { it.id })
    }

    @Test fun `merge on tree conversation appends candidates via path positions not list positions`() {
        // 关键差异：列表里有 off-path 节点，位置兜底必须按**路径**算
        val root = msg("u0").toMessageNode()
        val a1 = msg("a1-old", MessageRole.ASSISTANT).toMessageNode().copy(parentId = root.id)
        val sibling = msg("b1", MessageRole.ASSISTANT).toMessageNode().copy(parentId = root.id)
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(root, a1, sibling),
            activeLeafNodeId = a1.id, // 路径 = [root, a1]；sibling off-path
        )
        // THIS_ONLY 对 a1 重生成：基线 = [u0]，流式 [u0, a1-new]
        val updated = conversation.mergeGenerationMessages(
            listOf(root.currentMessage, msg("a1-new", MessageRole.ASSISTANT)),
        )
        // a1-new 落到 a1（路径位置 1），而不是列表位置 1（= sibling）
        assertEquals(2, updated.messageNodes.first { it.id == a1.id }.messages.size)
        assertEquals(1, updated.messageNodes.first { it.id == sibling.id }.messages.size)
        assertEquals("叶子不推进", a1.id, updated.activeLeafNodeId)
    }

    // ---- 不变量扫描：线性历史下 id 寻址的结构守恒 ----

    @Test fun `merge keeps one node per message id and advances leaf on linear histories`() {
        val random = kotlin.random.Random(42)
        repeat(50) {
            val conversation = Conversation.ofId(Uuid.random()).copy(
                messageNodes = List(random.nextInt(1, 12)) { msg("h$it").toMessageNode() },
            )
            // 随机快照：前缀 + 末位改写 / 追加新消息 / 原样
            val base = conversation.currentMessages
            val snapshot = when (random.nextInt(3)) {
                0 -> base.map { it.copy() }
                1 -> base.dropLast(1) + base.last().copy(parts = listOf(UIMessagePart.Text("changed")))
                else -> base + msg("new", MessageRole.ASSISTANT)
            }
            val byId = conversation.mergeGenerationMessages(snapshot)
            assertEquals("iteration $it size", maxOf(conversation.messageNodes.size, snapshot.size), byId.messageNodes.size)
            // 每条消息 id 至多出现一次；存量节点 id 稳定
            val idCounts = byId.messageNodes.flatMap { it.messages }.groupingBy { it.id }.eachCount()
            assertTrue("iteration $it 无跨节点重复", idCounts.values.all { it == 1 })
            conversation.messageNodes.forEachIndexed { i, n ->
                assertEquals("iteration $it node $i id stable", n.id, byId.messageNodes[i].id)
            }
            // 存量内容逐字节保持
            conversation.currentMessages.forEachIndexed { i, m ->
                assertEquals("iteration $it message $i", m.id, byId.currentMessages[i].id)
            }
            // 新建节点时叶子推进到末节点；未新建时叶子不变
            if (snapshot.size > conversation.messageNodes.size) {
                assertEquals(byId.messageNodes.last().id, byId.activeLeafNodeId)
            } else {
                assertNull(byId.activeLeafNodeId)
            }
        }
    }

    // ---- id 版未完成标记 ----

    @Test fun `incomplete marking by id hits the selected candidate of the right node`() {
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = listOf(msg("u0").toMessageNode(), msg("a1", MessageRole.ASSISTANT).toMessageNode()),
        )
        val targetId = conversation.messageNodes[1].id
        val marked = conversation.markGeneratingNodeIncompleteById(targetId)
        assertTrue(marked.messageNodes[1].currentMessage.finishIncomplete)
        assertTrue(!marked.messageNodes[0].currentMessage.finishIncomplete)
        assertSame(conversation, conversation.markGeneratingNodeIncompleteById(Uuid.random()))
    }
}
