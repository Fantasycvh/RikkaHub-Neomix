package app.androidrik.rikkahub.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顺序算术与档位移动决策（设计稿 §二/§三）。这些是**纯函数**，DB/UI 只做接线——
 * 顺序算法的 bug（下溢、间距耗尽未归一化、档内混用 null）在这里就该被挡住。
 */
class ConversationOrderingTest {

    // ---- 物化与插槽 ----

    @Test
    fun `materialize keeps the given order with 1000 steps from origin`() {
        assertEquals(listOf(1_000_000L, 1_001_000L, 1_002_000L), ConversationOrdering.materialize(3))
        assertEquals(emptyList<Long>(), ConversationOrdering.materialize(0))
    }

    @Test
    fun `top slot goes below the current minimum`() {
        assertEquals(999_000L, ConversationOrdering.topSlot(listOf(1_000_000L, 1_001_000L)))
        // 空档从起始值起步（首次进入自定义顺序的档位）
        assertEquals(ConversationOrdering.ORIGIN, ConversationOrdering.topSlot(emptyList()))
    }

    @Test
    fun `bottom slot goes above the current maximum`() {
        assertEquals(1_003_000L, ConversationOrdering.bottomSlot(listOf(1_001_000L, 1_002_000L)))
    }

    // ---- 中值插入与间距耗尽 ----

    @Test
    fun `midpoint splits the gap`() {
        assertEquals(1_000_500L, ConversationOrdering.midpoint(1_000_000L, 1_001_000L))
        assertEquals(1L, ConversationOrdering.midpoint(0L, 3L))
    }

    @Test
    fun `midpoint reports exhaustion when no integer fits`() {
        assertNull("相邻整数之间放不下任何值 ⇒ 必须整档归一化", ConversationOrdering.midpoint(5L, 6L))
        assertNull(ConversationOrdering.midpoint(5L, 5L))
    }

    @Test
    fun `repeated midpoint insertions exhaust after about ten rounds`() {
        // 可预测性说明（设计稿 §二）：1000 步长下同一间隙约 10 次取中值即耗尽
        var prev = 0L
        var next = 1000L
        var rounds = 0
        while (true) {
            val mid = ConversationOrdering.midpoint(prev, next) ?: break
            next = mid
            rounds++
        }
        assertEquals(9, rounds)
    }

    @Test
    fun `slot before first reports null near the long minimum instead of overflowing`() {
        assertEquals(1_000L - ConversationOrdering.STEP, ConversationOrdering.slotBefore(1_000L))
        assertNull(ConversationOrdering.slotBefore(Long.MIN_VALUE + 500))
    }

    // ---- 拖拽写库方案 ----

    @Test
    fun `between placement writes a single row in the normal case`() {
        val write = planOrderWrite(
            movedId = "c2",
            placement = OrderPlacement.Between(1_000_000L, 1_001_000L),
            existingValues = listOf(1_000_000L, 1_001_000L, 1_002_000L),
            orderedIds = listOf("c1", "c2", "c3"),
        )
        assertEquals(OrderWrite.Single("c2", 1_000_500L), write)
    }

    @Test
    fun `top placement writes below the first value`() {
        val write = planOrderWrite(
            movedId = "c3",
            placement = OrderPlacement.Top,
            existingValues = listOf(1_000_000L, 1_001_000L),
            orderedIds = listOf("c3", "c1", "c2"),
        )
        assertEquals(OrderWrite.Single("c3", 999_000L), write)
    }

    @Test
    fun `exhausted gap renormalizes the whole tier in the target order`() {
        val write = planOrderWrite(
            movedId = "c2",
            placement = OrderPlacement.Between(5L, 6L),
            existingValues = listOf(5L, 6L),
            orderedIds = listOf("c1", "c2", "c3"),
        )
        assertTrue("间距耗尽必须整档重排，而不是放弃这次拖拽", write is OrderWrite.Renormalize)
        write as OrderWrite.Renormalize
        assertEquals(listOf("c1", "c2", "c3"), write.orderedIds)
        assertEquals(listOf(1_000_000L, 1_001_000L, 1_002_000L), write.values)
    }

    // ---- 档位移动（置顶/取消置顶）决策表 ----

    @Test
    fun `tier move decision table`() {
        // 源有值 + 目标已物化 ⇒ 保留（设计稿 §一.6：能回到原来的相对位置）
        assertEquals(
            TierMoveAction.KEEP_VALUE,
            decideTierMove(hasValue = true, destinationMaterialized = true),
        )
        // 源有值 + 目标自动 ⇒ 先物化目标再保留（否则档内混用 null）
        assertEquals(
            TierMoveAction.MATERIALIZE_THEN_KEEP,
            decideTierMove(hasValue = true, destinationMaterialized = false),
        )
        // 源无值 + 目标已物化 ⇒ 给档位最前（null 会沉到已排序项之后，不能靠它）
        assertEquals(
            TierMoveAction.MATERIALIZE_AND_PLACE_TOP,
            decideTierMove(hasValue = false, destinationMaterialized = true),
        )
        // 两边都自动 ⇒ 零写入（没碰过自定义顺序的用户完全不受影响）
        assertEquals(
            TierMoveAction.NOTHING,
            decideTierMove(hasValue = false, destinationMaterialized = false),
        )
    }
}
