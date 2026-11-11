package app.androidrik.rikkahub.data.ai

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 最小号 **5 字段 cron**（分 时 日 月 周），只服务记忆整理的墙钟触发
 * （design-memory-consolidation-trigger.md §十「cron 缓做」的落地）。
 *
 * 不引第三方库：需要的语法就是「星号 / 星号加步进 / 区间 / 区间加步进 / 列表」的组合，
 * 解析 + next-occurrence 两百行内可钉死；引库反倒要为一个字段背依赖与它的时区语义。
 *
 * 语义（POSIX 子集，逐条写死防歧义）：
 * - 周字段 0 与 7 都表示周日，内部统一归一到 0（周一=1 … 周六=6）；
 * - **日与周同时受限时取 OR**（POSIX 传统语义：`0 0 13 * 5` = 每月 13 号 **或** 每周五）；
 * - 单字段上限：分 0-59、时 0-23、日 1-31、月 1-12、周 0-7；
 * - 时区用 `ZoneId.systemDefault()`（与定时任务的「本地墙钟」一致）；DST 跳变时
 *   `atZone` 按 java.time 语义落到偏移最近的合法时刻；
 * - 首槽**严格大于** from（认领时传 now：不会重复命中刚跑过的这一分钟）。
 */
data class CronExpression(
    val minutes: Set<Int>,
    val hours: Set<Int>,
    val daysOfMonth: Set<Int>,
    val months: Set<Int>,
    val daysOfWeek: Set<Int>,
    /** 日字段不是 `*`（POSIX 的 OR 规则要区分「未限制」与「限制到集合」） */
    val dayOfMonthRestricted: Boolean,
    /** 周字段不是 `*`（同上） */
    val dayOfWeekRestricted: Boolean,
) {
    fun matches(day: java.time.LocalDate): Boolean {
        if (day.monthValue !in months) return false
        val domMatch = day.dayOfMonth in daysOfMonth
        val dowMatch = (day.dayOfWeek.value % 7) in daysOfWeek // ISO 1..7（周一..周日）→ 0..6（周日=0）
        return when {
            dayOfMonthRestricted && dayOfWeekRestricted -> domMatch || dowMatch
            dayOfMonthRestricted -> domMatch
            dayOfWeekRestricted -> dowMatch
            else -> true
        }
    }

    /**
     * 从 [from] 之后（严格大于）的第一个触发时刻（epoch millis）。
     *
     * 实现是**逐天步进**：每天扫一遍时×分集合（最多 24×60 组合，实际集合很小）。
     * 天数上限 8 年：`0 0 29 2 *`（闰日）在跨世纪附近的最坏间隔是 8 年，超出即视为
     * 「事实上不再触发」返回 null——比死循环诚实。
     */
    fun nextAfter(from: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        var day: ZonedDateTime = Instant.ofEpochMilli(from).atZone(zone)
        var checkedDays = 0
        while (checkedDays <= MAX_SCAN_DAYS) {
            val date = day.toLocalDate()
            if (matches(date)) {
                for (hour in hours.sorted()) {
                    for (minute in minutes.sorted()) {
                        val slot = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                        if (slot > from) return slot
                    }
                }
            }
            day = date.plusDays(1).atStartOfDay(zone)
            checkedDays++
        }
        return null
    }

    companion object {
        /** 8 年：覆盖闰日表达式在最坏日历下（跨非闰世纪）的间隔 */
        private const val MAX_SCAN_DAYS = 366 * 8 + 2

        /**
         * 解析表达式；非法时抛 [IllegalArgumentException]（消息面向用户，UI 直接展示）。
         *
         * 只接受 5 字段。`@daily` 之类的别名与秒字段（6 字段）**明确拒绝**——静默接受
         * 部分语义会让用户以为配置生效了。
         */
        fun parse(expression: String): CronExpression {
            val fields = expression.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            require(fields.size == 5) {
                "cron 需要 5 个字段（分 时 日 月 周），实际 ${fields.size} 个"
            }
            val minutes = parseField(fields[0], 0, 59, "分")
            val hours = parseField(fields[1], 0, 23, "时")
            val daysOfMonth = parseField(fields[2], 1, 31, "日")
            val months = parseField(fields[3], 1, 12, "月")
            val rawDow = parseField(fields[4], 0, 7, "周")
            // 0 与 7 都是周日：归一化到 0
            val daysOfWeek = rawDow.map { if (it == 7) 0 else it }.toSet()
            return CronExpression(
                minutes = minutes,
                hours = hours,
                daysOfMonth = daysOfMonth,
                months = months,
                daysOfWeek = daysOfWeek,
                dayOfMonthRestricted = fields[2] != "*",
                dayOfWeekRestricted = fields[4] != "*",
            )
        }

        /** 解析单个字段：星号、星号加步进、区间、区间加步进、列表及其组合 */
        private fun parseField(field: String, min: Int, max: Int, name: String): Set<Int> {
            val values = mutableSetOf<Int>()
            field.split(',').forEach { part ->
                require(part.isNotEmpty()) { "$name 字段含空片段：$field" }
                val (rangePart, step) = if ('/' in part) {
                    val pieces = part.split('/')
                    require(pieces.size == 2) { "$name 字段步进格式错误：$part" }
                    val parsedStep = pieces[1].toIntOrNull()
                    require(parsedStep != null && parsedStep > 0) { "$name 字段步进必须是正整数：$part" }
                    pieces[0] to parsedStep
                } else {
                    part to 1
                }
                val range = when {
                    rangePart == "*" -> min..max
                    '-' in rangePart -> {
                        val bounds = rangePart.split('-')
                        require(bounds.size == 2) { "$name 字段区间格式错误：$part" }
                        val start = bounds[0].toIntOrNull()
                        val end = bounds[1].toIntOrNull()
                        require(start != null && end != null && start <= end) {
                            "$name 字段区间格式错误：$part"
                        }
                        start..end
                    }

                    else -> {
                        val single = rangePart.toIntOrNull()
                        require(single != null) { "$name 字段不是数字：$part" }
                        single..single
                    }
                }
                require(range.first >= min && range.last <= max) {
                    "$name 字段超出范围 $min-$max：$part"
                }
                values += range.filter { (it - range.first) % step == 0 }
            }
            return values
        }
    }
}
