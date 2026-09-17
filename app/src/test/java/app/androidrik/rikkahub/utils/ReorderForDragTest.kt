package app.androidrik.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 拖拽重排的边界测试（真机崩溃回归）。
 *
 * ## 事故（2026-09-17，真机实测）
 *
 * 拖拽条目排序时崩溃：
 * ```
 * java.lang.IndexOutOfBoundsException: Index: 3, Size: 2
 *   at java.util.ArrayList.add(ArrayList.java:517)
 *   at ...PresetDetailScreen$reorderableState$1$1... (PromptPage.kt:447)
 * ```
 *
 * ## 根因
 *
 * 原先各处都手写流传很广的 `add(to.index, removeAt(from.index))`。但拖拽库把
 * 「拖到列表末尾之外」表示为 `toIndex == 原列表长度`（=「追加到末尾」），
 * 而该写法里 `removeAt` **先**执行、列表已缩短，随后的 `add(原长度, …)` 就越界
 * ——3 个条目、`toIndex == 3`、移除后 size 为 2 ⇒ 崩。
 *
 * 修复：统一用 [reorderForDrag]（先移除、再按**移除后**长度夹取下标）。
 */
class ReorderForDragTest {

    private val list = listOf("a", "b", "c")

    // region 崩溃场景（本轮真机 bug）

    @Test
    fun `dragging past the last item does not crash`() {
        // 曾经崩溃：Index: 3, Size: 2
        val result = list.reorderForDrag(fromIndex = 0, toIndex = 3)
        assertEquals(listOf("b", "c", "a"), result)
    }

    @Test
    fun `dragging the last item past the end keeps order`() {
        // 最后一个元素再往后拖：toIndex == size（元素本就在末尾）→ 顺序不变
        val result = list.reorderForDrag(fromIndex = 2, toIndex = 3)
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `toIndex far beyond size is clamped`() {
        val result = list.reorderForDrag(fromIndex = 0, toIndex = 99)
        assertEquals(listOf("b", "c", "a"), result)
    }

    // endregion

    // region 常规位移

    @Test
    fun `move first element to middle`() {
        assertEquals(listOf("b", "a", "c"), list.reorderForDrag(0, 1))
    }

    @Test
    fun `move last element to front`() {
        assertEquals(listOf("c", "a", "b"), list.reorderForDrag(2, 0))
    }

    @Test
    fun `move to the same index is a no-op`() {
        assertEquals(listOf("a", "b", "c"), list.reorderForDrag(1, 1))
    }

    @Test
    fun `negative toIndex is clamped to front`() {
        assertEquals(listOf("c", "a", "b"), list.reorderForDrag(2, -5))
    }

    // endregion

    // region 防御：越界输入不丢数据

    @Test
    fun `out of range fromIndex returns the list unchanged`() {
        assertEquals(listOf("a", "b", "c"), list.reorderForDrag(5, 0))
        assertEquals(listOf("a", "b", "c"), list.reorderForDrag(-1, 0))
    }

    @Test
    fun `empty list is safe`() {
        assertEquals(emptyList<String>(), emptyList<String>().reorderForDrag(0, 0))
    }

    @Test
    fun `single element list is safe`() {
        assertEquals(listOf("only"), listOf("only").reorderForDrag(0, 1))
    }

    @Test
    fun `every element is preserved exactly once`() {
        // 任何位移都不该丢元素或产生重复
        for (from in list.indices) {
            for (to in 0..list.size) {
                val result = list.reorderForDrag(from, to)
                assertEquals("from=$from to=$to size", list.size, result.size)
                assertEquals("from=$from to=$to content", list.toSet(), result.toSet())
            }
        }
    }

    // endregion

    // region 世界书条目拖拽（按 key 定位，勿用 lazy 列表原始下标）

    @Test
    fun `key based reorder resolves display indices across header offsets`() {
        val keyed = listOf(listOf("header"), listOf("a"), listOf("b"), listOf("c"))
        val data = keyed.drop(1)
        // 原始 lazy 下标会带 header 偏移：from=2/to=3 与 key "a"/"c" 不是同一语义
        assertEquals(
            listOf(listOf("b"), listOf("c"), listOf("a")),
            data.reorderForDragByKey(fromKey = "a", toKey = "c") { it[0] },
        )
        assertEquals(keyed.drop(1), data.reorderForDragByKey("header", "a") { it[0] })
    }

    @Test
    fun `key based reorder ignores stale keys and identical keys`() {
        assertSame(list, list.reorderForDragByKey("z", "a") { it })
        assertSame(list, list.reorderForDragByKey("a", "z") { it })
        assertSame(list, list.reorderForDragByKey("a", "a") { it })
    }

    // endregion
}
