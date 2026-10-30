package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 定时 AI 任务（见 docs/design/design-scheduled-ai-tasks.md）。
 *
 * 调度的**唯一权威状态**是 [nextRunAt]：sweeper Worker 只查
 * `enabled = 1 AND next_run_at <= now`，认领即原子推进（乐观锁）——
 * 不设「claimed/running」中间态列，Worker 中途被杀顶多丢一轮，不会卡死。
 *
 * 执行不是本表的职责：表只回答「什么时候、往哪个会话、注入什么」。
 */
@Entity(
    tableName = "scheduled_tasks",
    indices = [Index(value = ["next_run_at"])]
)
data class ScheduledTaskEntity(
    @PrimaryKey
    val id: String,
    /** 目标会话；会话被删后任务自动失效（执行时校验，置 TARGET_GONE） */
    @ColumnInfo("conversation_id")
    val conversationId: String,
    /** 用户起的名字（通知/列表里显示） */
    @ColumnInfo("title")
    val title: String,
    /** 到点注入的 prompt（withGoal 语义在 P2 才有，当前就是全文） */
    @ColumnInfo("prompt")
    val prompt: String,
    /** [ScheduleType] 名称：ONCE / DAILY / WEEKLY / INTERVAL */
    @ColumnInfo("schedule_type")
    val scheduleType: String,
    /** [app.androidrik.rikkahub.data.scheduled.SchedulePayload] 的 JSON */
    @ColumnInfo("schedule_payload")
    val schedulePayload: String,
    /** 权威调度字段；认领时原子推进（乐观锁 WHERE next_run_at = :seen） */
    @ColumnInfo("next_run_at")
    val nextRunAt: Long,
    @ColumnInfo("last_run_at")
    val lastRunAt: Long? = null,
    /** [TaskRunStatus] 名称或 null（从未跑过） */
    @ColumnInfo("last_status")
    val lastStatus: String? = null,
    /** [MissPolicy] 名称：RUN_LATE（默认，补跑一次）/ SKIP（静默推进） */
    @ColumnInfo("miss_policy")
    val missPolicy: String,
    /** idle 门控（purrcat A4.1）：目标会话有进行中生成则本轮让路（不认领） */
    @ColumnInfo("idle_only")
    val idleOnly: Boolean,
    @ColumnInfo("enabled")
    val enabled: Boolean,
    @ColumnInfo("created_at")
    val createdAt: Long,
)

/** 周期形态；存的是 [name]，新增枚举值必须向后兼容旧库 */
enum class ScheduleType { ONCE, DAILY, WEEKLY, INTERVAL }

/** 错过策略：到点没跑成（设备关机/进程被杀超宽限窗）后怎么办 */
enum class MissPolicy { RUN_LATE, SKIP }

/** [lastStatus] 的取值； Worker 写入、管理页显示 */
object TaskRunStatus {
    const val OK = "OK"
    const val FAILED = "FAILED"
    const val TIMEOUT = "TIMEOUT"
    const val SKIPPED_MISSED = "SKIPPED_MISSED"
    const val TARGET_GONE = "TARGET_GONE"
}
