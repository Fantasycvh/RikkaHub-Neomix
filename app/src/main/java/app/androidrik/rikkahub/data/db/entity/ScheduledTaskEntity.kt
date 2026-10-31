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
    /** 到点注入的 prompt（[withGoal] 为 true 且会话 goal 非空时，头部拼「长期目标」） */
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
    /**
     * 目标语义（purrcat A4.2，P2）：注入时把目标会话的 `goal`（长期目标）拼在 prompt 头部。
     * 只对有会话目标的目标有意义——goal 为空时执行层按普通 prompt 走，不报错。
     * NOT NULL + DEFAULT 0 ⇒ AutoMigration 无需 spec（50→51）。
     */
    @ColumnInfo("with_goal", defaultValue = "0")
    val withGoal: Boolean = false,
    /**
     * NEW_EACH_RUN 自动清理（P3，§4.2）：只保留最近 N 轮的会话，更旧的按运行账本删除。
     * 0 = 不清理（默认关——自动删用户数据必须显式开启）；仅对 NEW_EACH_RUN 有意义。
     * 保护语义见 [TaskRunStatus] 与 Runner 的清理路径：本轮结束后用户又发过消息的会话不删。
     */
    @ColumnInfo("keep_last_conversations", defaultValue = "0")
    val keepLastConversations: Int = 0,
    /**
     * 交付物契约（purrcat A1，design-deliverable-gate.md §三.2）：相对 workspace 根的路径
     * 列表的 **JSON 数组**；NULL/空 = 不设闸（默认，老行行为不变）。非空 ⇒ 执行轮在自然出口
     * 逐个校验「存在且非空」，缺失打回（上限 [app.androidrik.rikkahub.data.ai.LOOP_GATE_MAX_RETRIES]），
     * 打回次数落 `scheduled_task_runs.gate_retries`。**非空即挂闸**，不另设布尔开关。
     */
    @ColumnInfo("deliverables")
    val deliverables: String? = null,
    /**
     * 创建者（AI 管理任务，design-proactive-task-management.md §三.4）：'user' / 'ai'。
     * 管理页来源徽标 + 越权红线的数据基础（AI **不可删** user 建的任务——不是审批，是拒绝）。
     * NOT NULL + DEFAULT 'user' ⇒ AutoMigration 无需 spec（55→56），老数据语义无损。
     */
    @ColumnInfo("created_by", defaultValue = "'user'")
    val createdBy: String = CREATED_BY_USER,
    /** AI 建任务时的会话溯源（task_manage create 的发起会话）；用户建的为 null。 */
    @ColumnInfo("source_conversation_id")
    val sourceConversationId: String? = null,
    @ColumnInfo("enabled")
    val enabled: Boolean,
    @ColumnInfo("created_at")
    val createdAt: Long,
) {
    companion object {
        const val CREATED_BY_USER = "user"
        const val CREATED_BY_AI = "ai"
    }
}

/** 周期形态；存的是 [name]，新增枚举值必须向后兼容旧库 */
enum class ScheduleType { ONCE, DAILY, WEEKLY, MONTHLY, INTERVAL }

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
