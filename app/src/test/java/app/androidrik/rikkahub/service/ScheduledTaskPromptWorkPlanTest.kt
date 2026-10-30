package app.androidrik.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒作业参数 [promptWorkPlan] 的回归护栏。
 *
 * 背景（revision 6 真机事故）：`armPrompt` 曾把所有唤醒作业都 `setExpedited` + `setInitialDelay`，
 * 而 WorkManager 的 expedited 作业**不允许带延迟**，`.build()` 直接抛
 * `IllegalArgumentException: Expedited jobs cannot be delayed`；因为异常发生在 ViewModel 的
 * coroutine 里，用户看到的是「新建/开关定时任务 → App 停止运行」。
 *
 * 约束：expedited ⇒ delayMs == 0；非 expedited ⇒ delayMs > 0。
 */
class ScheduledTaskPromptWorkPlanTest {

    private val now = 1_000_000L

    @Test
    fun `future run is a plain delayed job`() {
        val plan = promptWorkPlan(nextRunAt = now + 3_600_000L, now = now)

        assertFalse("未来的时刻不能 expedited（WorkManager 会抛异常）", plan.expedited)
        assertTrue("延迟作业必须带真实延迟", plan.delayMs > 0)
        assertEquals(3_600_000L + ScheduledTaskSweeperKeeper.PROMPT_SLOP_MS, plan.delayMs)
    }

    @Test
    fun `just passed run is expedited without delay`() {
        val plan = promptWorkPlan(nextRunAt = now - 1L, now = now)

        assertTrue(plan.expedited)
        assertEquals(0L, plan.delayMs)
    }

    @Test
    fun `exactly due run is expedited`() {
        val plan = promptWorkPlan(nextRunAt = now, now = now)

        assertTrue(plan.expedited)
        assertEquals(0L, plan.delayMs)
    }

    @Test
    fun `expedited always implies zero delay and vice versa`() {
        // 只要这条不变量成立，armPrompt 里就不会再拼出「expedited + initialDelay」的非法组合
        val samples = listOf(-86_400_000L, -1L, 0L, 1L, 5_000L, 86_400_000L)
        samples.forEach { offset ->
            val plan = promptWorkPlan(nextRunAt = now + offset, now = now)
            if (plan.expedited) {
                assertEquals("expedited 必须无延迟 (offset=$offset)", 0L, plan.delayMs)
            } else {
                assertTrue("延迟作业必须有延迟 (offset=$offset)", plan.delayMs > 0)
            }
        }
    }
}
