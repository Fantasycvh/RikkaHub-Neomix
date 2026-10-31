package app.androidrik.rikkahub.data.scheduled

import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 定时任务推进/错过判定的纯函数测试（docs/design/design-scheduled-ai-tasks.md §四/§五）。
 *
 * 时区用固定无 DST 的 Asia/Shanghai 为主；DST 语义（墙钟时刻不漂）单列一条用 New York。
 */
class ScheduleMathTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun millis(date: String): Long =
        LocalDateTime.parse(date).atZone(zone).toInstant().toEpochMilli()

    private fun dateTime(millis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).toString()

    // ---- advance ----

    @Test
    fun `advance ONCE returns null (claim disables the task)`() {
        assertNull(
            ScheduleMath.advance(
                ScheduleType.ONCE, SchedulePayload(runAt = millis("2026-10-30T08:00")),
                scheduledAt = millis("2026-10-30T08:00"), now = millis("2026-10-30T09:00"),
            )
        )
    }

    @Test
    fun `advance DAILY steps to next strictly future slot`() {
        val next = ScheduleMath.advance(
            ScheduleType.DAILY, SchedulePayload(hour = 8, minute = 0),
            scheduledAt = millis("2026-10-30T08:00"), now = millis("2026-10-30T09:00"),
        )!!
        assertEquals("2026-10-31T08:00", dateTime(next))
    }

    @Test
    fun `advance DAILY skips over multiple missed days in one step`() {
        // 错过 3 天才被 sweep 到：一次推进到下一个未来槽位，不连环补
        val next = ScheduleMath.advance(
            ScheduleType.DAILY, SchedulePayload(hour = 8, minute = 0),
            scheduledAt = millis("2026-10-27T08:00"), now = millis("2026-10-30T09:00"),
        )!!
        assertEquals("2026-10-31T08:00", dateTime(next))
    }

    @Test
    fun `advance DAILY DST keeps wall clock time`() {
        val ny = ZoneId.of("America/New_York")
        // 2026-11-01 是美国夏令时切换日（02:00 回拨 1 小时）；plusDays 走墙钟
        val scheduled = LocalDateTime.parse("2026-10-31T08:00").atZone(ny).toInstant().toEpochMilli()
        val now = LocalDateTime.parse("2026-11-01T10:00").atZone(ny).toInstant().toEpochMilli()
        val next = ScheduleMath.advance(
            ScheduleType.DAILY, SchedulePayload(hour = 8, minute = 0),
            scheduledAt = scheduled, now = now, zone = ny,
        )!!
        assertEquals("2026-11-02T08:00", LocalDateTime.ofInstant(Instant.ofEpochMilli(next), ny).toString())
    }

    @Test
    fun `advance WEEKLY lands on the same weekday`() {
        // 2026-10-30 是周五；槽位周五 08:00，now 同日 09:00 → 下一个周五
        val next = ScheduleMath.advance(
            ScheduleType.WEEKLY, SchedulePayload(hour = 8, minute = 0, dayOfWeek = 5),
            scheduledAt = millis("2026-10-30T08:00"), now = millis("2026-10-30T09:00"),
        )!!
        assertEquals("2026-11-06T08:00", dateTime(next))
    }

    @Test
    fun `advance INTERVAL normal case just adds the interval`() {
        val next = ScheduleMath.advance(
            ScheduleType.INTERVAL, SchedulePayload(intervalMinutes = 30),
            scheduledAt = millis("2026-10-30T08:00"), now = millis("2026-10-30T08:10"),
        )!!
        assertEquals(millis("2026-10-30T08:30"), next)
    }

    @Test
    fun `advance INTERVAL catches up by aligning to now plus interval`() {
        // 落后超过一个周期：对齐到 now + interval，不追帧
        val next = ScheduleMath.advance(
            ScheduleType.INTERVAL, SchedulePayload(intervalMinutes = 30),
            scheduledAt = millis("2026-10-30T07:00"), now = millis("2026-10-30T09:00"),
        )!!
        assertEquals(millis("2026-10-30T09:30"), next)
    }

    // ---- firstRunAt ----

    @Test
    fun `firstRunAt DAILY today when still ahead`() {
        val first = ScheduleMath.firstRunAt(
            ScheduleType.DAILY, SchedulePayload(hour = 20, minute = 0),
            from = millis("2026-10-30T09:00"),
        )
        assertEquals("2026-10-30T20:00", dateTime(first))
    }

    @Test
    fun `firstRunAt DAILY tomorrow when slot passed`() {
        val first = ScheduleMath.firstRunAt(
            ScheduleType.DAILY, SchedulePayload(hour = 8, minute = 0),
            from = millis("2026-10-30T09:00"),
        )
        assertEquals("2026-10-31T08:00", dateTime(first))
    }

    @Test
    fun `firstRunAt WEEKLY finds the next matching weekday`() {
        // 2026-10-30 是周五；目标是周三 → 下周三 2026-11-04
        val first = ScheduleMath.firstRunAt(
            ScheduleType.WEEKLY, SchedulePayload(hour = 8, minute = 30, dayOfWeek = 3),
            from = millis("2026-10-30T09:00"),
        )
        assertEquals("2026-11-04T08:30", dateTime(first))
    }

    @Test
    fun `firstRunAt ONCE passes runAt through verbatim`() {
        val runAt = millis("2026-11-01T22:00")
        val first = ScheduleMath.firstRunAt(
            ScheduleType.ONCE, SchedulePayload(runAt = runAt),
            from = millis("2026-10-30T09:00"),
        )
        assertEquals(runAt, first)
    }

    // ---- DAILY 多时段（P2：extraMinutesOfDay）----

    @Test
    fun `advance DAILY multi-times uses the next slot of today before tomorrow`() {
        // 8 点的槽被认领时 now=8:05：应推进到**今天 20 点**，而不是明天 8 点
        val next = ScheduleMath.advance(
            ScheduleType.DAILY, SchedulePayload(hour = 8, minute = 0, extraMinutesOfDay = listOf(1200)),
            scheduledAt = millis("2026-10-30T08:00"), now = millis("2026-10-30T08:05"),
        )!!
        assertEquals("2026-10-30T20:00", dateTime(next))
    }

    @Test
    fun `advance DAILY multi-times rolls to first slot tomorrow after the last one`() {
        val next = ScheduleMath.advance(
            ScheduleType.DAILY, SchedulePayload(hour = 8, minute = 0, extraMinutesOfDay = listOf(1200)),
            scheduledAt = millis("2026-10-30T20:00"), now = millis("2026-10-30T20:05"),
        )!!
        assertEquals("2026-10-31T08:00", dateTime(next))
    }

    @Test
    fun `advance DAILY multi-times catches up across missed days to the nearest future slot`() {
        // 错过两天：一次推进跨过所有错过槽，落到最近的未来槽（30 日 20 点）
        val next = ScheduleMath.advance(
            ScheduleType.DAILY, SchedulePayload(hour = 8, minute = 0, extraMinutesOfDay = listOf(1200)),
            scheduledAt = millis("2026-10-28T08:00"), now = millis("2026-10-30T09:00"),
        )!!
        assertEquals("2026-10-30T20:00", dateTime(next))
    }

    @Test
    fun `firstRunAt DAILY multi-times picks the earliest future slot of today`() {
        val first = ScheduleMath.firstRunAt(
            ScheduleType.DAILY, SchedulePayload(hour = 8, minute = 0, extraMinutesOfDay = listOf(1200, 720)),
            from = millis("2026-10-30T09:00"),
        )
        assertEquals("2026-10-30T12:00", dateTime(first))
    }

    @Test
    fun `dailyMinutes dedupes sorts and filters out-of-range values`() {
        val payload = SchedulePayload(
            hour = 20, minute = 0,
            extraMinutesOfDay = listOf(480, 1200, -5, 1500, 480),
        )
        assertEquals(listOf(480, 1200), payload.dailyMinutes)
    }

    // ---- decideMiss ----

    @Test
    fun `decideMiss within grace runs normally`() {
        assertEquals(
            MissDecision.RUN,
            ScheduleMath.decideMiss(
                scheduledAt = millis("2026-10-30T08:00"), now = millis("2026-10-30T13:00"),
                policy = MissPolicy.SKIP,
            ),
        )
    }

    @Test
    fun `decideMiss beyond grace with RUN_LATE forces one run`() {
        assertEquals(
            MissDecision.RUN_LATE_FORCED,
            ScheduleMath.decideMiss(
                scheduledAt = millis("2026-10-30T08:00"), now = millis("2026-10-30T20:00"),
                policy = MissPolicy.RUN_LATE,
            ),
        )
    }

    @Test
    fun `decideMiss beyond grace with SKIP defers silently`() {
        assertEquals(
            MissDecision.SKIP,
            ScheduleMath.decideMiss(
                scheduledAt = millis("2026-10-30T08:00"), now = millis("2026-10-30T20:00"),
                policy = MissPolicy.SKIP,
            ),
        )
    }
}
