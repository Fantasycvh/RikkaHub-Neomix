package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 并发 run 闸（设计 §十二 / §5.1）。
 *
 * 钉住六条：①到上限就**拒绝**（不排队，否则模型会在派发工具里无限等）；
 * ②释放后位置能复用（泄漏 = 之后永远开不了新 run）；
 * ③同一 id 重复 acquire 不占两个位置（重入安全）；
 * ④[ConcurrentRunGate.activeIds] 与 `isActive` 一致（取消路径靠它判断"是不是我管的那一个"）；
 * ⑤上限**每次现读 provider**——用户改设置后不用重启、不用重建 gate，下一次 acquire 就按新值裁决；
 * ⑥provider 返回 0/负数时钳制到 1（否则整个功能静默失效）。
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

    @Test
    fun `limit change takes effect on the very next acquire`() {
        // 模拟 SettingsStore.subagentMaxConcurrentRuns 被用户改动：provider 闭包读的是
        // settingsFlow.value，所以 gate 不重建、进程不重启，下一次裁决就用新值。
        var configured = 1
        val gate = ConcurrentRunGate(limitProvider = { configured })

        assertEquals(1, gate.limit())
        assertTrue(gate.tryAcquire("a"))
        assertFalse("configured=1 时必须拒绝第二个", gate.tryAcquire("b"))
        assertFalse(gate.canAcquire())

        configured = 3

        assertEquals(3, gate.limit())
        assertTrue(gate.canAcquire())
        assertTrue(gate.tryAcquire("b"))
        assertTrue(gate.tryAcquire("c"))
        assertFalse("configured=3 时第四个仍然拒绝", gate.tryAcquire("d"))
        assertEquals(3, gate.activeCount())
    }

    @Test
    fun `non-positive limit is clamped to one`() {
        val zero = ConcurrentRunGate(limitProvider = { 0 })
        assertEquals(1, zero.limit())
        assertTrue(zero.tryAcquire("a"))
        assertFalse("上限 0 不能把闸焊死", zero.tryAcquire("b"))

        val negative = ConcurrentRunGate(limitProvider = { -5 })
        assertEquals(1, negative.limit())
        assertTrue(negative.tryAcquire("a"))
        assertFalse(negative.canAcquire())
    }
}
