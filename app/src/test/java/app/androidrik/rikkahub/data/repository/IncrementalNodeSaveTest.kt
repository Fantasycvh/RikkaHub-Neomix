package app.androidrik.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 增量保存的公共前缀算法（等价复刻 ConversationRepository.saveMessageNodes 的判定）。
 *
 * 关键正确性：前缀比对必须包含**内容**（id + selectIndex + messages），
 * 只比 id 会漏掉「编辑消息（追加候选、id 不变）」的变更 -> 编辑不回写。
 */
class IncrementalNodeSaveTest {

    private val sep = "\u0001"

    private fun sig(id: String, selectIndex: Int = 0, content: String = "") =
        id + sep + selectIndex + sep + content

    private fun commonPrefixLength(a: List<String>, b: List<String>): Int {
        val max = minOf(a.size, b.size)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }

    @Test
    fun `tail append only writes the new node`() {
        val db = listOf(sig("n0"), sig("n1"))
        val mem = listOf(sig("n0"), sig("n1"), sig("n2"))
        assertEquals(2, commonPrefixLength(db, mem))
        // 只写 mem.drop(2) = 1 条
    }

    @Test
    fun `editing middle node is detected via content`() {
        // 编辑 n1：id 不变，候选内容变 -> 前缀应停在 1（不能是 2）
        val db = listOf(sig("n0"), sig("n1", 0, "old"), sig("n2"))
        val mem = listOf(sig("n0"), sig("n1", 1, "old|new"), sig("n2"))
        assertEquals("内容变更必须被检出", 1, commonPrefixLength(db, mem))
    }

    @Test
    fun `switching branch is detected via select index`() {
        val db = listOf(sig("n0"), sig("n1", 0, "a|b"))
        val mem = listOf(sig("n0"), sig("n1", 1, "a|b"))
        assertEquals(1, commonPrefixLength(db, mem))
    }

    @Test
    fun `deleting tail yields prefix at deletion point`() {
        val db = listOf(sig("n0"), sig("n1"), sig("n2"))
        val mem = listOf(sig("n0"))
        // 公共前缀 1 -> 删除 node_index >= 1 的旧行
        assertEquals(1, commonPrefixLength(db, mem))
    }

    @Test
    fun `identical lists write nothing`() {
        val same = listOf(sig("n0"), sig("n1"))
        assertEquals(same.size, commonPrefixLength(same, same))
    }

    @Test
    fun `empty memory never yields partial deletes accidentally`() {
        // 空内存（防误传轻量对象）由调用方守卫：updateConversation 中 isNotEmpty() 才保存
        assertEquals(0, commonPrefixLength(listOf(sig("n0")), emptyList()))
    }
}
