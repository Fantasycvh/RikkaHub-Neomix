package app.androidrik.rikkahub.data.repository

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.db.dao.MessageNodeDiffRow
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.asLinearChain
import app.androidrik.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * id 键落库 diff 的纯计算钉子（树形分支 P0，design-conversation-tree-branching.md §五.2）。
 *
 * 核心性质：
 * - 常规场景（尾部追加 / 原地编辑）只写 1 行——与前缀版同成本；
 * - 存活行保持原 node_index（创建序），新行 max+1，**永不复用**旧下标；
 * - 消失行按 id 删除（与列表顺序解耦，P1 分支乱序存储无需再改）。
 */
class NodeDiffPlanTest {

    private val convId = "conv-1"

    private fun msg(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun node(text: String) = MessageNode(messages = listOf(msg(text)))

    private fun row(node: MessageNode, index: Int) = MessageNodeDiffRow(
        id = node.id.toString(),
        nodeIndex = index,
        signature = node.selectIndex.toString() + "\u0001" + JsonInstant.encodeToString(node.messages),
    )

    @Test fun `unchanged conversation writes nothing`() {
        val nodes = listOf(node("a"), node("b"))
        val existing = nodes.mapIndexed { i, n -> row(n, i) }
        val plan = computeNodeDiff(convId, existing, nodes.asLinearChain())
        assertTrue(plan.toWrite.isEmpty())
        assertTrue(plan.removedIds.isEmpty())
    }

    @Test fun `tail append writes only the new node with max plus one index`() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        val existing = listOf(row(a, 0), row(b, 1))
        val plan = computeNodeDiff(convId, existing, listOf(a, b, c).asLinearChain())
        assertEquals(listOf(c.id.toString()), plan.toWrite.map { it.id })
        assertEquals(2, plan.toWrite.single().nodeIndex)
        assertEquals(b.id.toString(), plan.toWrite.single().parentId)
        assertTrue(plan.removedIds.isEmpty())
    }

    @Test fun `in-place edit keeps the original creation index`() {
        val a = node("a")
        val b = node("b")
        val bEdited = b.copy(messages = b.messages + msg("b-edited"), selectIndex = 1)
        val existing = listOf(row(a, 3), row(b, 7)) // 创建序带间隙（历史截断遗留）
        val plan = computeNodeDiff(convId, existing, listOf(a, bEdited).asLinearChain())
        assertEquals(listOf(b.id.toString()), plan.toWrite.map { it.id })
        assertEquals("存活行保持原创建序", 7, plan.toWrite.single().nodeIndex)
        assertTrue(plan.removedIds.isEmpty())
    }

    @Test fun `select index change alone is detected`() {
        val a = node("a")
        val multi = MessageNode(messages = listOf(msg("x"), msg("y")), selectIndex = 0)
        val switched = multi.copy(selectIndex = 1)
        val existing = listOf(row(a, 0), row(multi, 1))
        val plan = computeNodeDiff(convId, existing, listOf(a, switched).asLinearChain())
        assertEquals(listOf(multi.id.toString()), plan.toWrite.map { it.id })
    }

    @Test fun `truncation removes missing ids regardless of order`() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        val existing = listOf(row(a, 0), row(b, 1), row(c, 2))
        val plan = computeNodeDiff(convId, existing, listOf(a).asLinearChain())
        assertEquals(setOf(b.id.toString(), c.id.toString()), plan.removedIds.toSet())
        assertTrue(plan.toWrite.isEmpty())
    }

    @Test fun `new index never reuses removed tail indices`() {
        val a = node("a")
        val b = node("b")
        val existing = listOf(row(a, 0), row(b, 1))
        // 截断掉 b 后追加新节点：新下标 = max(0,1)+1 = 2，不复用 1
        val fresh = node("fresh")
        val plan = computeNodeDiff(convId, existing, listOf(a, fresh).asLinearChain())
        assertEquals(listOf(b.id.toString()), plan.removedIds)
        assertEquals(2, plan.toWrite.single().nodeIndex)
    }

    @Test fun `empty database and empty chain produce empty plan`() {
        val plan = computeNodeDiff(convId, emptyList(), emptyList())
        assertTrue(plan.toWrite.isEmpty())
        assertTrue(plan.removedIds.isEmpty())
    }

    @Test fun `first node gets index zero and null parent`() {
        val a = node("a")
        val plan = computeNodeDiff(convId, emptyList(), listOf(a).asLinearChain())
        assertEquals(0, plan.toWrite.single().nodeIndex)
        assertEquals(null, plan.toWrite.single().parentId)
    }

    @Test fun `parent id is derived from chain order`() {
        val a = node("a")
        val b = node("b")
        val plan = computeNodeDiff(convId, emptyList(), listOf(a, b).asLinearChain())
        assertEquals(null, plan.toWrite[0].parentId)
        assertEquals(a.id.toString(), plan.toWrite[1].parentId)
    }
}