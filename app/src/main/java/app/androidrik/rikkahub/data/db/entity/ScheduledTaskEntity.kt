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
     *
     * 「往哪个会话」有第三种形态见 [TargetMode]：**每轮新建**（不绑定存量会话）。
     */
    @Entity(
        tableName = "scheduled_tasks",
        indices = [Index(value = ["next_run_at"])]
    )
    data class ScheduledTaskEntity(
        @PrimaryKey
        val id: String,
        /**
         * 目标会话。语义随 [targetMode] 变：
         * - [TargetMode.EXISTING] / [TargetMode.DEDICATED]：真实会话 id（会话被删后任务自动失效，
         *   执行时校验，置 TARGET_GONE）；
         * - [TargetMode.NEW_EACH_RUN]：**空串**（没有存量锚点），实际会话每次执行新建，
         *   最近一次见 [lastConversationId]。
         */
        @ColumnInfo("conversation_id")
        val conversationId: String,
        /** 目标形态（[TargetMode] 名称）；迁移前的老行全部是 EXISTING */
        @ColumnInfo("target_mode", defaultValue = "'EXISTING'")
        val targetMode: String = TargetMode.EXISTING.name,
        /**
         * 执行时挂哪个助手。NEW_EACH_RUN / DEDICATED 用（后者也记一份，会话被删后
         * 详情页仍能显示是哪个助手）；EXISTING 为 null（助手由会话自带）。
         */
        @ColumnInfo("assistant_id")
        val assistantId: String? = null,
        /** NEW_EACH_RUN：最近一次执行新建出来的会话（列表/详情可直接跳过去看结果） */
        @ColumnInfo("last_conversation_id")
        val lastConversationId: String? = null,
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

/**
 * 任务「往哪儿执行」（revision 8，见 docs/design/design-scheduled-ai-tasks.md §4.1）。
 *
 * - [EXISTING]：绑定一条已有会话，每轮都注入到它（老行为，迁移默认值）；
 * - [DEDICATED]：创建任务时用选定助手**新建**一条会话，之后每轮都在它里面跑（专属会话，
 *   上下文跨周期连续）；
 * - [NEW_EACH_RUN]：每轮都用选定助手**新建**一条会话执行（历史互不污染，每轮结果各成一条）。
 *
 * 存 [name]：新增值必须能向后兼容旧库（未知值一律回落 EXISTING）。
 */
enum class TargetMode { EXISTING, DEDICATED, NEW_EACH_RUN }

/** 解析 [ScheduledTaskEntity.targetMode]；脏值/未知值一律回落 [TargetMode.EXISTING]（老行为）。 */
fun targetModeOrDefault(raw: String?): TargetMode =
    runCatching { TargetMode.valueOf(raw.orEmpty()) }.getOrDefault(TargetMode.EXISTING)

/** 错过策略：到点没跑成（设备关机/进程被杀超宽限窗）后怎么办 */
enum class MissPolicy { RUN_LATE, SKIP }

/** [lastStatus] 的取值； Worker 写入、管理页显示 */
object TaskRunStatus {
    const val OK = "OK"
    const val FAILED = "FAILED"
    const val TIMEOUT = "TIMEOUT"
    const val SKIPPED_MISSED = "SKIPPED_MISSED"
    const val TARGET_GONE = "TARGET_GONE"

    /** NEW_EACH_RUN / DEDICATED 的助手已删：任务自失效（与「会话没了」区分开，便于排查） */
    const val ASSISTANT_GONE = "ASSISTANT_GONE"
}
