package app.androidrik.rikkahub.data.scheduled

import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.hours

/**
 * 定时任务的周期参数（docs/design/design-scheduled-ai-tasks.md §四）。
 *
 * 按 [ScheduleType] 只读对应字段，其余忽略；JSON 存进 `scheduled_tasks.schedule_payload`，
 * 加字段带默认值即可向后兼容（JsonInstant ignoreUnknownKeys + 默认值）。
 */
@Serializable
data class SchedulePayload(
    /** DAILY/WEEKLY 的时刻（本地时区） */
    val hour: Int = 8,
    val minute: Int = 0,
    /** WEEKLY 的目标日，ISO 语义（1=周一 … 7=周日） */
    val dayOfWeek: Int = 1,
    /** INTERVAL 的间隔（分钟），下限 15（WorkManager 粒度） */
    val intervalMinutes: Long = 240,
    /** ONCE 的执行时刻（epoch millis） */
    val runAt: Long = 0,
    /**
     * DAILY 多时段（P2）：主时刻（hour/minute）之外的**当天分钟数**（0..1439）。
     * 有效时刻 = 主时刻 ∪ 本集合，去重升序；空 = 单时刻（老数据无此字段，行为不变）。
     */
    val extraMinutesOfDay: List<Int> = emptyList(),
) {
    /** DAILY 的全部时刻（当天分钟数，去重升序；含越界防御） */
    val dailyMinutes: List<Int>
        get() = (listOf(hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)) + extraMinutesOfDay
            .filter { it in 0..1439 })
            .distinct()
            .sorted()

    fun encode(): String = JsonInstant.encodeToString(this)

    companion object {
        fun decode(json: String): SchedulePayload =
            runCatching { JsonInstant.decodeFromString<SchedulePayload>(json) }.getOrDefault(SchedulePayload())
    }
}

/** 任务该执行、但需要先回答「是不是错过」时的判定结果 */
enum class MissDecision { RUN, RUN_LATE_FORCED, SKIP }

object ScheduleMath {
    /** 错过宽限窗：超窗才算「错过」，才走 [MissPolicy]（设计稿 §五） */
    val DEFAULT_GRACE_WINDOW: kotlin.time.Duration = 6.hours

    /**
     * 认领时推进 `nextRunAt` 的纯函数（设计稿 §四推进规则）。
     *
     * - ONCE：返回 null（认领即禁用，由调用方落 `enabled = false`）；
     * - DAILY：**当天剩余槽位 → 明天起的首槽位**（多时段见 [SchedulePayload.dailyMinutes]；
     *   单时刻时退化为老的「逐日步进」语义）；
     * - WEEKLY：从 `current` 槽位逐周步进，找到**严格大于 now** 的下一个槽位
     *   （连续错过多次不会连环补——一次推进跨过所有错过的槽数）；
     * - INTERVAL：`current + interval`，落后超过一个周期就对齐到 `now + interval`（不追帧）。
     *
     * 传 [scheduledAt] = 被认领的 `next_run_at`（<= now），[now] 为认领时刻。
     * DAILY/WEEKLY 用 ZonedDateTime 的墙钟步进，DST 切换日自动落在同一墙钟时刻。
     */
    fun advance(
        type: ScheduleType,
        payload: SchedulePayload,
        scheduledAt: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = when (type) {
        ScheduleType.ONCE -> null
        ScheduleType.INTERVAL -> {
            val interval = payload.intervalMinutes.coerceAtLeast(15) * 60_000L
            val next = scheduledAt + interval
            if (next <= now) now + interval else next
        }

        ScheduleType.DAILY -> {
            // 从「现在」所在的一天起逐日扫：当天还有没过的时刻就用它，否则明天的首槽位。
            // 不从 scheduledAt 起扫：多时段下「今天 8 点的槽被认领」应推进到今天 20 点而非明天 8 点。
            var day = Instant.ofEpochMilli(now).atZone(zone)
            repeat(MAX_ADVANCE_STEPS) {
                val slot = nextSlotOfDay(day, payload, if (it == 0) now else Long.MIN_VALUE)
                if (slot != null) return slot
                day = day.plusDays(1)
            }
            // 逻辑上不可达（每天至少一个合法槽位）；防御性返回 now+1 天，避免坏数据把认领卡死
            now + 24 * 60 * 60_000L
        }

        ScheduleType.WEEKLY -> advanceWallClock(payload, scheduledAt, now, zone) { it.plusWeeks(1) }
    }

    private inline fun advanceWallClock(
        payload: SchedulePayload,
        scheduledAt: Long,
        now: Long,
        zone: ZoneId,
        step: (ZonedDateTime) -> ZonedDateTime,
    ): Long {
        var slot = Instant.ofEpochMilli(scheduledAt).atZone(zone)
        // 兜底：槽位损坏（未来时间）时从 now 重算，避免死循环推进
        if (slot.toInstant().toEpochMilli() > now) {
            slot = Instant.ofEpochMilli(now).atZone(zone)
        }
        repeat(MAX_ADVANCE_STEPS) {
            slot = step(slot)
            val millis = slot.toInstant().toEpochMilli()
            if (millis > now) return millis
        }
        // 逻辑上不可达（每次至少 +1 天）；防御性返回 now+1 天，避免坏数据把认领卡死
        return now + 24 * 60 * 60_000L
    }

    /**
     * [day] 当天的下一个槽位：时刻集合升序里第一个**严格大于 [after]** 的。
     * 没有（全部已过）返回 null，调用方步进到下一天。墙钟时刻由 minute-of-day 重建，
     * DST 跳变时 `withHour/withMinute` 落在合法墙钟上（不存在 2:30 → 次日 3:30 的歧义，
     * java.time 对不存在的墙钟取偏移最近的合法时刻）。
     */
    private fun nextSlotOfDay(day: ZonedDateTime, payload: SchedulePayload, after: Long): Long? {
        for (minute in payload.dailyMinutes) {
            val slot = day.withHour(minute / 60).withMinute(minute % 60).withSecond(0).withNano(0)
            val millis = slot.toInstant().toEpochMilli()
            if (millis > after) return millis
        }
        return null
    }

    /**
     * 新建任务时的首个 `nextRunAt`：从 [from] 起算第一个**未来**槽位。
     * ONCE 直接用 [payload.runAt]（调用方保证 > from；不保证也照存——sweeper 会立即执行/走错过逻辑）。
     * DAILY 多时段：今天剩余时刻 → 明天首槽位。
     */
    fun firstRunAt(
        type: ScheduleType,
        payload: SchedulePayload,
        from: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = when (type) {
        ScheduleType.ONCE -> payload.runAt
        ScheduleType.INTERVAL -> from + payload.intervalMinutes.coerceAtLeast(15) * 60_000L
        ScheduleType.DAILY -> {
            var day = Instant.ofEpochMilli(from).atZone(zone)
            repeat(MAX_ADVANCE_STEPS) {
                val slot = nextSlotOfDay(day, payload, if (it == 0) from else Long.MIN_VALUE)
                if (slot != null) return slot
                day = day.plusDays(1)
            }
            from + 24 * 60 * 60_000L
        }

        ScheduleType.WEEKLY -> {
            val target = DayOfWeek.of(payload.dayOfWeek.coerceIn(1, 7))
            var slot = from.toSlot(payload, zone)
            while (slot.dayOfWeek != target || slot.toInstant().toEpochMilli() <= from) {
                slot = slot.plusDays(1)
            }
            slot.toInstant().toEpochMilli()
        }
    }

    private fun Long.toSlot(payload: SchedulePayload, zone: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(this).atZone(zone)
            .withHour(payload.hour.coerceIn(0, 23))
            .withMinute(payload.minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)

    /**
     * 到期任务的错过判定（sweeper 逐条调，设计稿 §五）：
     * 宽限窗内照跑；超窗按策略补跑一次（RUN_LATE）或静默推进（SKIP）。
     */
    fun decideMiss(
        scheduledAt: Long,
        now: Long,
        policy: MissPolicy,
        graceWindow: kotlin.time.Duration = DEFAULT_GRACE_WINDOW,
    ): MissDecision {
        val late = now - scheduledAt
        val graceMs = graceWindow.inWholeMilliseconds
        return when {
            late <= graceMs -> MissDecision.RUN
            policy == MissPolicy.RUN_LATE -> MissDecision.RUN_LATE_FORCED
            else -> MissDecision.SKIP
        }
    }

    private const val MAX_ADVANCE_STEPS = 400
}
