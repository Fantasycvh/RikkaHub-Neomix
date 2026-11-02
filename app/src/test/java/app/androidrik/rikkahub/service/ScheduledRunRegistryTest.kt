package app.androidrik.rikkahub.service

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScheduledRunRegistry] 的纯逻辑测试（P0.5 定时取消，design-agent-task-runtime §5.2 第 4 条）。
 *
 * 覆盖的核心风险是**时序**：取消入口（FGS 通知）和执行层（注入 → 等结束）互不等待，
 * 取消可能早于 Job 登记（消息还排在用户轮次之后）、可能重复到达、也可能晚于 `unregister`
 * （那时不能再报「已取消」，否则会把已经落库的终态改坏）。
 */
class ScheduledRunRegistryTest {

    @Test
    fun `cancel before attach cancels the job when it is finally attached`() {
        val registry = ScheduledRunRegistry()
        registry.register("run-1")

        // 取消先到：还没有 Job 可取消，但标记必须记下来。
        assertTrue(registry.cancel("run-1"))
        assertTrue(registry.isCancelled("run-1"))

        // Job 随后才出现（消息排在用户轮次之后，派发晚了）——attach 必须当场取消它。
        val job = Job()
        assertTrue(registry.attachJob("run-1", job))
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancel after attach cancels the registered job`() {
        val registry = ScheduledRunRegistry()
        registry.register("run-1")
        val job = Job()
        assertFalse(registry.attachJob("run-1", job))
        assertFalse(job.isCancelled)

        assertTrue(registry.cancel("run-1"))
        assertTrue(job.isCancelled)
        assertTrue(registry.isCancelled("run-1"))
    }

    @Test
    fun `repeated cancel stays true and keeps the marker`() {
        val registry = ScheduledRunRegistry()
        registry.register("run-1")
        val job = Job()
        registry.attachJob("run-1", job)

        assertTrue(registry.cancel("run-1"))
        assertTrue(registry.cancel("run-1"))
        assertTrue(registry.isCancelled("run-1"))
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancel after unregister returns false and does not resurrect the entry`() {
        val registry = ScheduledRunRegistry()
        registry.register("run-1")
        assertTrue(registry.unregister("run-1"))

        // 已收尾的轮次：取消不能再「生效」，否则 Runner 会把已落库的终态改成 CANCELLED。
        assertFalse(registry.cancel("run-1"))
        assertFalse(registry.isCancelled("run-1"))
        assertTrue(registry.activeRunIds.value.isEmpty())
        assertTrue(registry.cancelledRunIds.value.isEmpty())
    }

    @Test
    fun `cancel of an unknown run is a no-op`() {
        val registry = ScheduledRunRegistry()
        assertFalse(registry.cancel("never-registered"))
        assertFalse(registry.unregister("never-registered"))
    }

    @Test
    fun `attach after unregister does not cancel the job`() {
        val registry = ScheduledRunRegistry()
        registry.register("run-1")
        registry.unregister("run-1")

        val job = Job()
        assertFalse(registry.attachJob("run-1", job))
        assertFalse(job.isCancelled)
    }

    @Test
    fun `observable snapshots track registration and cancellation`() {
        val registry = ScheduledRunRegistry()
        registry.register("run-1")
        registry.register("run-2")

        assertEquals(listOf("run-1", "run-2"), registry.activeRunIds.value)
        assertTrue(registry.cancelledRunIds.value.isEmpty())

        registry.cancel("run-1")
        assertEquals(setOf("run-1"), registry.cancelledRunIds.value)
        assertEquals(listOf("run-1", "run-2"), registry.activeRunIds.value)

        registry.unregister("run-1")
        assertEquals(listOf("run-2"), registry.activeRunIds.value)
        assertTrue(registry.cancelledRunIds.value.isEmpty())
    }

    @Test
    fun `attach with null job reports the current cancel state`() {
        val registry = ScheduledRunRegistry()
        registry.register("run-1")
        assertFalse(registry.attachJob("run-1", null))
        registry.cancel("run-1")
        assertTrue(registry.attachJob("run-1", null))
    }

    @Test
    fun `jobOf returns the registered job`() {
        val registry = ScheduledRunRegistry()
        registry.register("run-1")
        val job = Job()
        registry.attachJob("run-1", job)
        assertSame(job, registry.jobOf("run-1"))
        assertSame(null, registry.jobOf("run-2"))
    }

    @Test
    fun `turn owner is sched prefixed and unique per run`() {
        assertEquals("sched:abc", scheduledRunTurnOwner("abc"))
        assertTrue(scheduledRunTurnOwner("a") != scheduledRunTurnOwner("b"))
    }
}
