package app.androidrik.rikkahub.ui.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 恢复期空 backstack 修复（nav3 空栈崩溃的自愈防线，见 `RouteActivity` 注释）。
 *
 * 真机形态：`IllegalArgumentException: NavDisplay backstack cannot be empty`，
 * 且崩溃现场会被写进 saved state ⇒ Activity 重建时还原空栈 ⇒ 崩溃循环。
 */
class BackStackRepairTest {

    @Test
    fun `empty restored stack is repaired with the fallback`() {
        val stack = mutableListOf<String>()
        assertTrue(stack.repairIfEmpty("start"))
        assertEquals(listOf("start"), stack)
    }

    @Test
    fun `non empty stack is left untouched`() {
        val stack = mutableListOf("chat", "settings")
        assertFalse(stack.repairIfEmpty("start"))
        assertEquals(listOf("chat", "settings"), stack)
    }

    @Test
    fun `repair is idempotent`() {
        val stack = mutableListOf<String>()
        assertTrue(stack.repairIfEmpty("start"))
        assertFalse(stack.repairIfEmpty("start"))
        assertEquals(listOf("start"), stack)
    }
}
