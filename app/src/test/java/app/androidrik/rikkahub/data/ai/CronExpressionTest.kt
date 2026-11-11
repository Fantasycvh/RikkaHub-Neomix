package app.androidrik.rikkahub.data.ai

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最小号 cron 解析与 next-occurrence（`docs/design/design-memory-consolidation-trigger.md` §十）。
 *
 * 全部用固定本地时区 + 固定日期：next-occurrence 的正确性就是「日期 + 时刻」的算术，
 * 不该引入当前时间或随机时区。
 */
class CronExpressionTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun millis(text: String): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun format(epochMillis: Long): String =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), zone).toString()

    // ---- 解析 ----

    @Test
    fun `wildcards cover the whole range`() {
        val cron = CronExpression.parse("* * * * *")
        assertEquals(60, cron.minutes.size)
        assertEquals(24, cron.hours.size)
        assertEquals(31, cron.daysOfMonth.size)
        assertEquals(12, cron.months.size)
        assertEquals(7, cron.daysOfWeek.size)
    }

    @Test
    fun `steps ranges and lists combine`() {
        val cron = CronExpression.parse("*/15 9-17/2 1,15 * 1-5")
        assertEquals(setOf(0, 15, 30, 45), cron.minutes)
        assertEquals(setOf(9, 11, 13, 15, 17), cron.hours)
        assertEquals(setOf(1, 15), cron.daysOfMonth)
        assertEquals(setOf(1, 2, 3, 4, 5), cron.daysOfWeek)
    }

    @Test
    fun `sunday can be written as 0 or 7`() {
        val sunday0 = CronExpression.parse("0 12 * * 0")
        val sunday7 = CronExpression.parse("0 12 * * 7")
        assertEquals("0 与 7 都是周日（归一化到 0）", sunday0.daysOfWeek, sunday7.daysOfWeek)
    }

    @Test
    fun `invalid expressions are rejected loudly`() {
        listOf(
            "0 3 * *",          // 4 字段
            "0 3 * * * *",      // 6 字段
            "60 * * * *",       // 分越界
            "0 24 * * *",       // 时越界
            "0 0 0 * *",        // 日从 1 起
            "0 0 * 13 *",       // 月越界
            "0 0 * * 8",        // 周越界
            "*/0 * * * *",      // 步进为 0
            "a b c d e",        // 非数字
            "5-1 * * * *",      // 区间倒序
            "@daily",           // 别名明确拒绝（静默接受会让用户误以为生效）
        ).forEach { expression ->
            val failed = runCatching { CronExpression.parse(expression) }.isFailure
            assertTrue("应判非法：$expression", failed)
        }
    }

    // ---- next occurrence ----

    @Test
    fun `daily expression rolls to the next day`() {
        val cron = CronExpression.parse("0 3 * * *")
        assertEquals(
            "2026-11-12T03:00",
            format(cron.nextAfter(millis("2026-11-11T15:00:00"))!!),
        )
    }

    @Test
    fun `expression later the same day fires today`() {
        val cron = CronExpression.parse("30 9 * * 1-5")
        // 2026-11-11 周三 08:00 ⇒ 当天 09:30
        assertEquals(
            "2026-11-11T09:30",
            format(cron.nextAfter(millis("2026-11-11T08:00:00"))!!),
        )
        // 周三 15:00 已过 ⇒ 周四 09:30
        assertEquals(
            "2026-11-12T09:30",
            format(cron.nextAfter(millis("2026-11-11T15:00:00"))!!),
        )
    }

    @Test
    fun `next slot is strictly after the given instant`() {
        val cron = CronExpression.parse("0 3 * * *")
        assertEquals(
            "刚好命中当前分钟时不许原地返回（认领会原地打转）",
            "2026-11-12T03:00",
            format(cron.nextAfter(millis("2026-11-11T03:00:00"))!!),
        )
    }

    @Test
    fun `day-of-month and day-of-week restrictions are OR-ed`() {
        // POSIX：日与周同时受限 ⇒ 任一匹配即可。2026-11-11 是周三。
        val cron = CronExpression.parse("0 0 25 * 1")
        assertEquals(
            "下一个周一（11-16）早于 25 号，OR 语义应选周一",
            "2026-11-16T00:00",
            format(cron.nextAfter(millis("2026-11-11T10:00:00"))!!),
        )
        assertEquals(
            "再下一个周一（11-23）仍早于 25 号 ⇒ 还是周一",
            "2026-11-23T00:00",
            format(cron.nextAfter(millis("2026-11-17T00:00:00"))!!),
        )
        assertEquals(
            "11-24 之后轮到 25 号（日字段命中）",
            "2026-11-25T00:00",
            format(cron.nextAfter(millis("2026-11-24T00:00:00"))!!),
        )
    }

    @Test
    fun `day-of-month alone means monthly`() {
        val cron = CronExpression.parse("0 0 13 * *")
        assertEquals(
            "2026-12-13T00:00",
            format(cron.nextAfter(millis("2026-11-14T00:00:00"))!!),
        )
    }

    @Test
    fun `leap-day expression reaches the next leap year`() {
        val cron = CronExpression.parse("0 0 29 2 *")
        assertEquals(
            "2026 之后的下一个 2 月 29 日",
            "2028-02-29T00:00",
            format(cron.nextAfter(millis("2026-11-11T00:00:00"))!!),
        )
    }

    @Test
    fun `impossible expression returns null instead of looping`() {
        // 2 月 30 日不存在：扫描上限内无命中 ⇒ null（诚实放弃，不死循环）
        val cron = CronExpression.parse("0 0 30 2 *")
        assertNull(cron.nextAfter(millis("2026-11-11T00:00:00")))
    }
}
