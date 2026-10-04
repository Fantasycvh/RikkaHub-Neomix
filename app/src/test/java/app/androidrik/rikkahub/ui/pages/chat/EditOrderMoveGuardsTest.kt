package app.androidrik.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「编辑顺序」拖动逻辑的护栏，来自 2026-10-04 真机实测的两个静默缺陷：
 *
 * 1. `onMove` 里用 `items.indexOfFirst { it.id.toString() == from.key }` 定位——`from.key` 是
 *    `Uuid` **对象**而不是字符串，条件永不成立，拖拽看起来"能长按、能松手"却从不换位。
 * 2. `onDragStopped` 无条件落库：长按但没挪动也会调用一次 `applyManualOrder`，
 *    把整档白物化一遍（真机 DB 里表现为"没换位但 33 行都有了 sort_order"）。
 */
class EditOrderMoveGuardsTest {

    private fun composableSource(): String {
        val path = "app/androidrik/rikkahub/ui/pages/chat/EditConversationOrderList.kt"
        val file = listOf("src/main/java/$path", "app/src/main/java/$path")
            .map(::File)
            .firstOrNull { it.exists() }
            ?: error("找不到 EditConversationOrderList.kt（工作目录=${File(".").absolutePath}）")
        return file.readText()
    }

    @Test
    fun `同档内拖动返回落点`() {
        val pinned = listOf(true, true, false, false)
        assertEquals(1 to 0, computeReorder(4, 1, 0) { pinned[it] })
        assertEquals(2 to 3, computeReorder(4, 2, 3) { pinned[it] })
    }

    @Test
    fun `跨档位拖动被拒绝`() {
        val pinned = listOf(true, false)
        assertNull(computeReorder(2, 0, 1) { pinned[it] })
        assertNull(computeReorder(2, 1, 0) { pinned[it] })
    }

    @Test
    fun `越界与原地拖动被拒绝`() {
        val pinned = listOf(false, false)
        // 列表末尾的 Spacer 项会让 to.index == size（真机上拖到底部就会遇到）
        assertNull(computeReorder(2, 0, 2) { pinned[it] })
        assertNull(computeReorder(2, -1, 0) { pinned[it] })
        assertNull(computeReorder(2, 1, 1) { pinned[it] })
    }

    /** 只看真实代码行：注释里会写到旧的错误写法（作为反面教材），不该被当成违规。 */
    private fun codeLinesOf(source: String): List<String> = source.lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }

    @Test
    fun `onMove 用列表下标而不是把 key 当字符串比较`() {
        val code = codeLinesOf(composableSource()).joinToString("\n")
        assertTrue("拖动换位必须走 computeReorder", code.contains("computeReorder("))
        assertFalse(
            "不得再用 from.key/to.key 与 String 比较定位（Uuid 对象永不等于字符串）",
            code.contains("from.key") || code.contains("to.key"),
        )
        assertTrue("下标要取自 Lazy 列表自身", code.contains("fromIndex = from.index"))
    }

    @Test
    fun `松手落库必须要求顺序真的变了`() {
        val code = codeLinesOf(composableSource()).joinToString("\n")
        assertTrue(
            "拖动开始时要记下顺序快照，松手时据此判断是否真的换位",
            code.contains("orderAtDragStart = items.map { it.id }"),
        )
        assertTrue(
            "只有顺序变化才 persistMove（否则长按即物化整档）",
            code.contains("start != items.map { it.id }"),
        )
    }
}
