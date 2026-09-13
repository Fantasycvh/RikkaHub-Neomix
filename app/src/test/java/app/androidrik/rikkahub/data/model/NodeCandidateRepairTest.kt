package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 跨节点候选副本自愈（压缩功能早期 bug 的数据修复）。
 *
 * 损坏模式：窗口消息被按索引追加到各节点候选末尾，导致同一 message id 出现在多个节点。
 * 修复：受损节点只保留首个候选（追加在末尾，首候选必为原始消息）。
 */
class NodeCandidateRepairTest {

    private fun msg(text: String, role: MessageRole = MessageRole.USER) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun node(vararg messages: UIMessage, select: Int = 0) =
        MessageNode(messages = messages.toList(), selectIndex = select)

    @Test
    fun `node with cross-node duplicate is collapsed to first candidate`() {
        val shared = msg("shared")
        val nodes = listOf(
            node(msg("orig1"), shared, select = 1),   // 受损：shared 也出现在 node 2
            node(shared, msg("orig2"), select = 0),
        )
        val repaired = nodes.repairCorruptedCandidates()

        assertEquals(listOf("orig1"), repaired[0].messages.map { it.toText() })
        assertEquals(0, repaired[0].selectIndex)
        // node 1 的候选 shared 与其他节点重复 -> 也保留首候选 shared
        assertEquals(listOf("shared"), repaired[1].messages.map { it.toText() })
    }

    @Test
    fun `clean node keeps alternatives`() {
        // 编辑/重生成历史：id 各不相同，不应被清理
        val nodes = listOf(
            node(msg("v1"), msg("v2"), msg("v3"), select = 2),
        )
        val repaired = nodes.repairCorruptedCandidates()

        assertEquals(3, repaired[0].messages.size)
        assertEquals(2, repaired[0].selectIndex)
    }

    @Test
    fun `single candidate node is untouched`() {
        val nodes = listOf(node(msg("only")))
        assertEquals(1, nodes.repairCorruptedCandidates()[0].messages.size)
    }

    @Test
    fun `repair is idempotent`() {
        val shared = msg("shared")
        val nodes = listOf(
            node(msg("a"), shared, select = 1),
            node(shared, msg("b"), select = 0),
            node(msg("c"), msg("d"), msg("e"), select = 1),
        )
        val once = nodes.repairCorruptedCandidates()
        val twice = once.repairCorruptedCandidates()
        assertEquals(once, twice)
    }

    @Test
    fun `node order and identity preserved`() {
        val shared = msg("shared")
        val nodes = listOf(
            node(msg("a"), shared, select = 1),
            node(shared),
        )
        val repaired = nodes.repairCorruptedCandidates()
        assertEquals(nodes.map { it.id }, repaired.map { it.id })
    }
}
