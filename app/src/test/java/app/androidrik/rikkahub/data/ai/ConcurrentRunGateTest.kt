package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 并发 run 闸（设计 §十二）。
 *
 * 钉住四条：①到上限就**拒绝**（不排队，否则模型会在派发工具里无限等）；
 * ②释放后位置能复用（泄漏 = 之后永远开不了新 run）；
 * ③同一 id 重复 acquire 不占两个位置（重入安全）；
 * ④[ConcurrentRunGate.activeIds] 与 `isActive` 一致（取消路径靠它判断"是不是我管的那一个"）。
 */
class ConcurrentRunGateTest {

    @Test
    fun `acquires up to the limit and then refuses`() {
        val gate = ConcurrentRunGate(limit = 2)

        assertTrue(gate.tryAcquire("a"))
        assertTrue(gate.tryAcquire("b"))
        assertFalse("third must be refused, not queued", gate.tryAcquire("c"))
        assertEquals(2, gate.activeCount())
        assertFalse(gate.canAcquire())
    }

    @Test
    fun `release frees a slot for the next run`() {
        val gate = ConcurrentRunGate(limit = 2)
        gate.tryAcquire("a")
        gate.tryAcquire("b")

        gate.release("a")

        assertEquals(1, gate.activeCount())
        assertTrue(gate.canAcquire())
        assertTrue(gate.tryAcquire("c"))
        assertFalse(gate.tryAcquire("d"))
    }

    @Test
    fun `re-acquiring the same run does not consume a second slot`() {
        val gate = ConcurrentRunGate(limit = 2)

        assertTrue(gate.tryAcquire("a"))
        assertTrue(gate.tryAcquire("a"))

        assertEquals(1, gate.activeCount())
        assertTrue(gate.tryAcquire("b"))
    }

    @Test
    fun `isActive mirrors the active set`() {
        val gate = ConcurrentRunGate(limit = 2)
        gate.tryAcquire("a")

        assertTrue(gate.isActive("a"))
        assertFalse(gate.isActive("b"))
        assertEquals(setOf("a"), gate.activeIds())

        gate.release("a")

        assertFalse(gate.isActive("a"))
        assertTrue(gate.activeIds().isEmpty())
    }

    @Test
    fun `default limit is two`() {
        // 设计 §八：手机端并行 2 已属激进（同时压两份模型流 + 各自的工具进程）
        assertEquals(2, ConcurrentRunGate.DEFAULT_MAX_CONCURRENT_RUNS)
        val gate = ConcurrentRunGate()
        assertTrue(gate.tryAcquire("a"))
        assertTrue(gate.tryAcquire("b"))
        assertFalse(gate.tryAcquire("c"))
    }
}
