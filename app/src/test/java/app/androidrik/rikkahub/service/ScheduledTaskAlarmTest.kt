package app.androidrik.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 闹钟触发时刻的纯函数（docs/design/design-scheduled-ai-tasks.md §六「触发层」）。
 *
 * 钉住两件事：
 * 1. 正常任务 = `nextRunAt + slop`（早 5 秒醒来会查不到 due，空转一轮）；
 * 2. 已经过期的任务（认领后进程死了、或错过的槽位）= `now + slop`，**不能**用过去的时刻
 *    去排闹钟——AlarmManager 对过去时刻会立即投递，造成一次无意义的空跑。
 */
class ScheduledTaskAlarmTest {

    private val now = 1_793_000_000_000L

    @Test
    fun `trigger is nextRunAt plus slop for future task`() {
        val nextRunAt = now + 60_000L
        assertEquals(
            nextRunAt + ScheduledTaskAlarmScheduler.SLOP_MS,
            ScheduledTaskAlarmScheduler.alarmTriggerAt(nextRunAt, now),
        )
    }

    @Test
    fun `trigger falls back to now plus slop for overdue task`() {
        val nextRunAt = now - 120_000L
        assertEquals(
            now + ScheduledTaskAlarmScheduler.SLOP_MS,
            ScheduledTaskAlarmScheduler.alarmTriggerAt(nextRunAt, now),
        )
    }

    @Test
    fun `trigger never fires in the past`() {
        assertEquals(
            now + ScheduledTaskAlarmScheduler.SLOP_MS,
            ScheduledTaskAlarmScheduler.alarmTriggerAt(now, now),
        )
    }

    @Test
    fun `request code is stable per task and distinct across tasks`() {
        val a = "71a0ca9b-d3b0-4bdb-aa8b-26c1c01c7582"
        val b = "1dbb5d02-0c94-485e-87dc-7ab9e2d401c3"
        assertEquals(
            ScheduledTaskAlarmScheduler.alarmRequestCode(a),
            ScheduledTaskAlarmScheduler.alarmRequestCode(a),
        )
        assertNotEquals(
            ScheduledTaskAlarmScheduler.alarmRequestCode(a),
            ScheduledTaskAlarmScheduler.alarmRequestCode(b),
        )
    }
}
