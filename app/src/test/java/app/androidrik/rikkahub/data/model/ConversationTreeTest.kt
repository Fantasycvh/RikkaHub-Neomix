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
 * 树形分支 P0 纯函数（design-conversation-tree-branching.md §六）。
 *
 * 三组钉子：
 * 1. 链/路径派生（asLinearChain / withLinearTreeMeta / pathToLeaf）——P1 树遍历的地基；
 * 2. id 制合并（mergeGenerationMessages）——与索引制 [Conversation.updateCurrentMessages]
 *    在常规场景逐字节等价，且钉住两条关键分叉：THIS_ONLY 位置兜底、跨节点 id 归位；
 * 3. id 版未完成标记。
 */
class ConversationTreeTest {

    private fun msg(text: String, role: MessageRole = MessageRole.USER) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun conversation(vararg texts: String) = Conversation.ofId(Uuid.random()).copy(
        messageNodes = texts.map { msg(it).toMessageNode() },
    )

    // ---- asLinearChain / withLinearTreeMeta ----

    @Test fun `linear chain fills parent ids with null root`() {
        val nodes = listOf(msg("a").toMessageNode(), msg("b").toMessageNode(), msg("c").toMessageNode())
        val chained = nodes.asLinearChain()
        assertNull(chained[0].parentId)
        assertEquals(chained[0].id, chained[1].parentId)
        assertEquals(chained[1].id, chained[2].parentId)
    }

    @Test fun `already chained list is returned unchanged`() {
        val nodes = listOf(msg("a").toMessageNode(), msg("b").toMessageNode()).asLinearChain()
        assertSame(nodes, nodes.asLinearChain())
    }

    @Test fun `tree meta derives leaf from tail and is identity when fresh`() {
        val conversation = conversation("a", "b").withLinearTreeMeta()
        assertEquals(conversation.messageNodes.last().id, conversation.activeLeafNodeId)
        assertSame(conversation, conversation.withLinearTreeMeta())
        val empty = Conversation.ofId(Uuid.random()).withLinearTreeMeta()
        assertNull(empty.activeLeafNodeId)
    }

    // ---- pathToLeaf（P1 权威读路径，先钉住） ----

    @Test fun `path to leaf walks linear chain root first`() {
        val nodes = listOf(msg("a").toMessageNode(), msg("b").toMessageNode(), msg("c").toMessageNode()).asLinearChain()
        val path = nodes.pathToLeaf(nodes.last().id)!!
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
        val nodes = listOf(msg("a").toMessageNode(), msg("b").toMessageNode()).asLinearChain()
        assertNull(nodes.pathToLeaf(Uuid.random()))
        val orphan = msg("orphan").toMessageNode().copy(parentId = Uuid.random())
        assertNull((nodes + orphan).pathToLeaf(orphan.id))
        val x = msg("x").toMessageNode()
        val y = msg("y").toMessageNode().copy(parentId = x.id)
        val cyclic = listOf(x.copy(parentId = y.id), y)
        assertNull(cyclic.pathToLeaf(y.id))
    }

    // ---- mergeGenerationMessages：与索引制等价的四场景（镜像 ConversationMessageUpdatesTest） ----

    @Test fun `equal snapshots retain the conversation and every node`() {
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = List(200) { msg("history $it").toMessageNode() },
        )
        val updated = conversation.mergeGenerationMessages(conversation.currentMessages.map { it.copy() })
        assertSame(conversation, updated)
        assertSame(conversation, conversation.mergeGenerationMessages(emptyList()))
    }

    @Test fun `streaming replaces only the changed node and retains trailing history`() {
        val conversation = Conversation.ofId(Uuid.random()).copy(
            messageNodes = List(200) { msg("history $it").toMessageNode() },
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
    }

    // ---- 关键分叉 1：THIS_ONLY 重生成（位置兜底，镜像 StreamCheckpointIndexTest） ----

    @Test fun `THIS_ONLY regenerate lands new reply as candidate of the middle target node`() {
        val conversation = conversation("u0", "a1-old", "u2", "a3")
        // 基线 = 0 until replyNodeIndex(=1)，流式追加新候选
        val streaming = listOf(conversation.currentMessages[0], msg("a1-new", MessageRole.ASSISTANT))
        val updated = conversation.mergeGenerationMessages(streaming)

        assertEquals(4, updated.messageNodes.size)
        val target = updated.messageNodes[1]
        assertEquals(2, target.messages.size)
        assertEquals(1, target.selectIndex)
        assertEquals("a1-new", (target.currentMessage.parts.single() as UIMessagePart.Text).text)
        // 下游未被触碰
        assertEquals("u2", (updated.messageNodes[2].messages.single().parts.single() as UIMessagePart.Text).text)
        assertEquals("a3", (updated.messageNodes[3].messages.single().parts.single() as UIMessagePart.Text).text)
    }

    @Test fun `node identity is stable across streaming updates`() {
        val conversation = conversation("u0")
        val first = conversation.mergeGenerationMessages(listOf(msg("u0"), msg("a1", MessageRole.ASSISTANT)))
        val nodeIdAfterFirst = first.messageNodes[1].id
        val second = first.mergeGenerationMessages(listOf(msg("u0"), msg("a1-longer", MessageRole.ASSISTANT)))
        assertEquals(nodeIdAfterFirst, second.messageNodes[1].id)
    }

    // ---- 关键分叉 2：跨节点 id 归位（索引制的损坏形态，id 制必须修对） ----

    @Test fun `message id owned by another node is replaced in place not duplicated`() {
        val conversation = conversation("u0", "a1", "u2")
        val owned = conversation.currentMessages[1]
        // 构造「id 错位」：owned 的更新出现在位置 2（索引制会往 node2 追加副本 ⇒ 跨节点重复）
        val updatedOwned = owned.copy(parts = listOf(UIMessagePart.Text("a1-edited")))
        val incoming = listOf(conversation.currentMessages[0], conversation.currentMessages[2], updatedOwned)
        val updated = conversation.mergeGenerationMessages(incoming)

        assertEquals(3, updated.messageNodes.size)
        assertEquals("a1-edited", (updated.messageNodes[1].currentMessage.parts.single() as UIMessagePart.Text).text)
        assertEquals(1, updated.messageNodes[1].messages.size) // 无副本追加
        assertEquals(1, updated.messageNodes[2].messages.size) // 位置节点不被污染
        val idCounts = updated.messageNodes.flatMap { it.messages }.groupingBy { it.id }.eachCount()
        assertTrue("不允许跨节点重复 id", idCounts.values.all { it == 1 })
    }

    // ---- 等价性扫描：常规线性场景下与索引制结果一致 ----

    @Test fun `merge equals index merge on linear histories`() {
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
            val byIndex = conversation.updateCurrentMessages(snapshot)
            val byId = conversation.mergeGenerationMessages(snapshot)
            assertEquals("iteration $it size", byIndex.messageNodes.size, byId.messageNodes.size)
            byIndex.messageNodes.forEachIndexed { i, n ->
                val m = byId.messageNodes[i]
                // 新追加节点的 id 是各自随机的 Uuid（比较伪差异），只钉内容与选中态；
                // 存量节点 additionally 钉 id 稳定（检查点 upsert 的前提）
                assertEquals("iteration $it node $i messages", n.messages, m.messages)
                assertEquals("iteration $it node $i selectIndex", n.selectIndex, m.selectIndex)
                if (i < conversation.messageNodes.size) {
                    assertEquals("iteration $it node $i id stable", n.id, m.id)
                }
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