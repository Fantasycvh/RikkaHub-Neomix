package app.androidrik.rikkahub.data.scheduled

import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.ai.tools.taskRandomWindow
import app.androidrik.rikkahub.ui.pages.setting.scheduled.ScheduledTaskFormState
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RandomWindowTest {
    private val date = LocalDate.of(2026, 11, 8)
    private val utc = ZoneId.of("UTC")

    @Test fun `version one fixed vector survives serialization without rerolling`() {
        val window = RandomWindow(540, 1380, 3, 42)
        assertEquals(listOf(589, 1261, 1330), window.minutesOn(date))
        val payload = SchedulePayload(randomWindow = window)
        assertEquals(window.minutesOn(date), SchedulePayload.decode(payload.encode()).randomWindow!!.minutesOn(date))
        assertNotEquals(window.minutesOn(date), window.minutesOn(date.plusDays(1)))
    }

    @Test fun `samples stay inside half open window and capacity is finite`() {
        repeat(100) { seed ->
            val minutes = RandomWindow(60, 80, 20, seed.toLong()).minutesOn(date)
            assertEquals((60 until 80).toList(), minutes)
        }
        assertThrows(IllegalArgumentException::class.java) { RandomWindow(60, 61, 2, 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { RandomWindow(1380, 60, 1, 0).validate() }
        assertThrows(IllegalArgumentException::class.java) { RandomWindow(0, 60, 1, 0, 2).validate() }
    }

    @Test fun `first and advance share remaining slots and late delivery never catches up missed slots`() {
        val window = RandomWindow(540, 1380, 3, 42)
        val payload = SchedulePayload(hour = 0, extraMinutesOfDay = listOf(1), randomWindow = window)
        val start = date.atStartOfDay(utc).toInstant().toEpochMilli()
        val first = ScheduleMath.firstRunAt(ScheduleType.DAILY, payload, start, utc)
        assertEquals(start + 589 * 60_000L, first)
        val second = ScheduleMath.advance(ScheduleType.DAILY, payload, first, first, utc)!!
        assertEquals(start + 1261 * 60_000L, second)
        val late = start + 1400 * 60_000L
        val next = ScheduleMath.advance(ScheduleType.DAILY, payload, first, late, utc)!!
        val tomorrow = date.plusDays(1)
        assertEquals(tomorrow.atStartOfDay(utc).toInstant().toEpochMilli() + window.minutesOn(tomorrow).first() * 60_000L, next)
        assertThrows(IllegalArgumentException::class.java) { ScheduleMath.firstRunAt(ScheduleType.WEEKLY, payload, start, utc) }
    }

    @Test fun `spring gap slots are sorted by instant and fall overlap executes only once`() {
        val zone = ZoneId.of("America/New_York")
        val spring = LocalDate.of(2026, 3, 8)
        val gap = SchedulePayload(randomWindow = RandomWindow(179, 181, 2, 1))
        val first = ScheduleMath.firstRunAt(ScheduleType.DAILY, gap, spring.atStartOfDay(zone).toInstant().toEpochMilli(), zone)
        assertEquals(spring.atTime(3, 0).atZone(zone).toInstant().toEpochMilli(), first)
        assertEquals(spring.atTime(3, 59).atZone(zone).toInstant().toEpochMilli(), ScheduleMath.advance(ScheduleType.DAILY, gap, first, first, zone))
        val fall = LocalDate.of(2026, 11, 1)
        val overlap = SchedulePayload(randomWindow = RandomWindow(90, 91, 1, 1))
        val earlier = ScheduleMath.firstRunAt(ScheduleType.DAILY, overlap, fall.atStartOfDay(zone).toInstant().toEpochMilli(), zone)
        val next = ScheduleMath.advance(ScheduleType.DAILY, overlap, earlier, earlier, zone)!!
        assertEquals(fall.plusDays(1).atTime(1, 30).atZone(zone).toInstant().toEpochMilli(), next)
    }

    @Test fun `form preserves seed and requires a valid window before save`() {
        val state = ScheduledTaskFormState().apply {
            title = "random"; prompt = "hello"; randomDaily = true
            hourText = "9"; minuteText = "0"
        }
        assertTrue(state.isValid)
        assertEquals(state.payload().randomWindow, state.payload().randomWindow)
        state.randomCountText = "21"
        assertFalse(state.isValid)
        state.randomCountText = "3"; state.randomEndHourText = "8"
        assertFalse(state.isValid)
    }

    @Test fun `AI tool validates complete daily window and keeps the provided creation seed`() {
        fun params(text: String) = JsonInstant.parseToJsonElement(text).jsonObject
        val fields = """"schedule_type":"DAILY","random_start_minute":540,"random_end_minute":1380,"random_slots":3"""
        assertEquals(42L, taskRandomWindow(params("{$fields}"), 42)!!.seed)
        assertNull(taskRandomWindow(params("{}")))
        assertThrows(IllegalArgumentException::class.java) { taskRandomWindow(params("""{"schedule_type":"DAILY","random_slots":3}""")) }
        assertThrows(IllegalArgumentException::class.java) { taskRandomWindow(params("{${fields.replace("DAILY", "INTERVAL")}}")) }
        assertThrows(IllegalArgumentException::class.java) { SchedulePayload.decode("""{"randomWindow":{"startMinute":5}}""") }
        assertNull(SchedulePayload.decode("{}").randomWindow)
    }
}
