package app.androidrik.rikkahub.service

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「每轮新建」产出的会话标题（docs/design/design-scheduled-ai-tasks.md §4.1）。
 *
 * 为什么要带触发时刻：同一任务每轮都产生一条新会话，标题若只写任务名，会话列表里会出现
 * 一串同名行、根本分不出哪条是哪一轮。时刻用 `MM-dd HH:mm`（跨天也能排序）。
 */
class ScheduledTaskRunTitleTest {

    private val utc = ZoneId.of("UTC")

    private fun millis(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun `title is task name plus trigger time`() {
        assertEquals(
            "日报 · 10-31 08:05",
            scheduledRunConversationTitle("日报", millis(2026, 10, 31, 8, 5), utc),
        )
    }

    @Test
    fun `blank task title falls back to the timestamp alone`() {
        assertEquals(
            "10-31 08:05",
            scheduledRunConversationTitle("   ", millis(2026, 10, 31, 8, 5), utc),
        )
    }

    @Test
    fun `different runs produce different titles`() {
        val first = scheduledRunConversationTitle("日报", millis(2026, 10, 31, 8, 5), utc)
        val second = scheduledRunConversationTitle("日报", millis(2026, 11, 1, 8, 5), utc)
        assertTrue(first != second)
    }
}
