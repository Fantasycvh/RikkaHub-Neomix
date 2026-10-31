package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 定时任务的**运行账本**（P2；见 docs/design/design-scheduled-ai-tasks.md §十、
 * `peer-apps-benchmark.md` §5「后台/无头运行必须显式状态机 + 终态」）。
 *
 * 任务表（[ScheduledTaskEntity]）只存**最后一次**的状态，诊断「为什么昨晚没跑成」不够用；
 * 本表每次执行（含 SKIP 静默跳过、TARGET_GONE 自失效）一行，终态必达：
 * - [startedAt] = 认领/判定时刻，行**插入即已带** task/source，进程死亡最多留一行无终态的行
 *   （[finishedAt] 为 null = 执行层没活着写完——这本身就是最有用的诊断信息）；
 * - [source] 记触发入口（alarm / worker / sweep / skip），区分「闹钟拉起的执行」与「兜底扫到的执行」；
 * - [conversationId] 记本轮实际目标（NEW_EACH_RUN 每轮不同，历史行各自能跳回结果会话）。
 *
 * 纯新增表（无外键——任务被删后历史行随[pruneTask]清理，不级联、不回填），AutoMigration 52→53。
 */
@Entity(
    tableName = "scheduled_task_runs",
    indices = [Index(value = ["task_id", "started_at"])]
)
data class ScheduledTaskRunEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("task_id")
    val taskId: String,
    /** 触发入口：alarm / worker / sweep / skip（[RunSource] 语义，存原始串以便扩展） */
    @ColumnInfo("source")
    val source: String,
    /** 本轮实际目标会话（NEW_EACH_RUN 每轮不同；其它模式 = 任务锚点会话） */
    @ColumnInfo("conversation_id")
    val conversationId: String? = null,
    @ColumnInfo("started_at")
    val startedAt: Long,
    /** 终态时刻；null = 执行层中途死亡（进程被杀），任务表 next_run_at 已推进所以不会卡死 */
    @ColumnInfo("finished_at")
    val finishedAt: Long? = null,
    /** [TaskRunStatus] 名称；null = 未落终态（同上） */
    @ColumnInfo("status")
    val status: String? = null,
    /**
     * 收尾闸门打回次数（purrcat A1，design-deliverable-gate.md §三.4）：本轮生成在自然出口
     * 被闸门打回（确定性交付校验未过 / 自检注入）的次数。0 = 未挂闸或一次通过；
     * 「一轮实跑了 N 次」必须在这里可见。NOT NULL DEFAULT 0 ⇒ AutoMigration 无需 spec（54→55）。
     */
    @ColumnInfo("gate_retries", defaultValue = "0")
    val gateRetries: Int = 0,
) {
    /** 触发入口的规范值；只作记录与展示，执行器不依赖它做任何分支（与 Executor 的 source 串一致） */
    object RunSource {
        const val ALARM = "alarm"
        const val SWEEP = "sweep"
        const val SKIP = "skip"
    }
}
