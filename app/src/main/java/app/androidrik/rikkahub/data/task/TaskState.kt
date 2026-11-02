package app.androidrik.rikkahub.data.task

import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.workspace.WorkspaceTaskStatus

/**
 * 三套执行底座的**统一任务态**（设计 `design-agent-task-runtime.md` §4.3）。
 *
 * **为什么需要统一态**：子代理 run、工作区后台命令、定时任务各有一套状态字符串/枚举，
 * 任务中心、通知、统计要各写一个 `when`；三处必然漂移（漏一个状态就是一个永远不结束的卡片）。
 * 这里只做**纯映射**，不持有任何运行时状态、不依赖 Android —— 便于表驱动单测，也避免
 * 「UI 层自己解释状态」的分叉。
 *
 * **不引入公共 TaskExecutor**：状态统一不等于执行统一。执行仍由 `SubagentRunner` /
 * `WorkspaceTaskRegistry` / `ScheduledTaskExecutor` 各自完成（设计 §〇 第 1 条）。
 */
enum class TaskState {
    /** 已登记、尚未开始（run 账本的 pending；定时任务在 claim 前不产生任务卡）。 */
    PENDING,

    /** 正在执行（模型循环 / 进程在跑）。 */
    RUNNING,

    /** 停在等人批准（仅 `ASK` 策略 + 有人的路径会出现）。 */
    WAITING_APPROVAL,

    /** 正常完成且**验收通过**（定时任务：产出了回复；后台命令：exitCode=0）。 */
    COMPLETED,

    /** 失败终态（含 provider 报错、退出码非 0、任务目标已删除等）。 */
    FAILED,

    /** 被取消（用户主动 / 调度跳过；**不是**成功）。 */
    CANCELLED,

    /** 超过截止时间被终止（run 硬超时 / 后台命令 lifetime 上限）。 */
    TIMED_OUT,

    /**
     * 进程死亡/启动对账判定的**非自然终态**：账本行停在 running/awaiting_approval，
     * 但产生它的进程已经不存在（`AgentRunEntity.STATUS_INTERRUPTED`）。
     *
     * 与 FAILED 分开：UI 文案不同（「已中断，可重试」而不是「失败：原因」），
     * 统计也不该把进程被杀算成模型/工具失败。
     */
    INTERRUPTED;

    /** 是否已经不会再有状态变化（任务中心据此决定是否显示「取消」按钮）。 */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED ||
            this == TIMED_OUT || this == INTERRUPTED
}

/**
 * 三套状态 → [TaskState] 的**唯一映射**（设计 §4.3）。
 *
 * 所有分支都「宁可保守」：未知值一律落到 [TaskState.FAILED]，绝不猜成 COMPLETED
 * ——把没跑完的任务显示成成功，比显示成失败更糟（用户会以为结果已经可用）。
 */
object TaskStateMappings {

    /**
     * `agent_run.status` + `agent_run.error` → 统一态。
     *
     * `error` 参与映射的原因：run 没有独立的 TIMED_OUT 状态，超时由
     * `failRun(ERROR_TIMEOUT, …)` 记成 `failed: timeout: …`（见 `SubagentRunner` 的
     * `withTimeout` 兜底），这里按机器前缀还原成 TIMED_OUT，避免用户把「跑了 30 分钟被
     * 截止时间收掉」误读成模型报错。
     */
    fun fromAgentRun(status: String, error: String? = null): TaskState = when (status) {
        AgentRunEntity.STATUS_PENDING -> TaskState.PENDING
        AgentRunEntity.STATUS_RUNNING -> TaskState.RUNNING
        AgentRunEntity.STATUS_AWAITING_APPROVAL -> TaskState.WAITING_APPROVAL
        AgentRunEntity.STATUS_COMPLETED -> TaskState.COMPLETED
        AgentRunEntity.STATUS_CANCELLED -> TaskState.CANCELLED
        AgentRunEntity.STATUS_INTERRUPTED -> TaskState.INTERRUPTED
        AgentRunEntity.STATUS_FAILED ->
            if (error.orEmpty().startsWith(AgentRunEntity.ERROR_TIMEOUT)) TaskState.TIMED_OUT
            else TaskState.FAILED
        // 未知/脏值：可能是旧版本写入的新状态（降级安装、回滚）。保守显示 FAILED。
        else -> TaskState.FAILED
    }

    /**
     * 工作区后台命令 → 统一态。
     *
     * `EXITED` 需要看 `exitCode`：进程自己退出不等于成功，退出码非 0 是失败
     * （`WorkspaceTaskStatus` 的 KDoc 也只承诺「已自行结束」）。`exitCode` 为 null 的
     * `EXITED` 视为异常（回收线程没拿到退出码），按 FAILED 处理而不是猜成功。
     */
    fun fromWorkspace(status: WorkspaceTaskStatus, exitCode: Int? = null): TaskState =
        when (status) {
            WorkspaceTaskStatus.RUNNING -> TaskState.RUNNING
            WorkspaceTaskStatus.EXITED -> when (exitCode) {
                0 -> TaskState.COMPLETED
                null -> TaskState.FAILED
                else -> TaskState.FAILED
            }
            WorkspaceTaskStatus.KILLED -> TaskState.CANCELLED
            WorkspaceTaskStatus.TIMED_OUT -> TaskState.TIMED_OUT
            WorkspaceTaskStatus.FAILED -> TaskState.FAILED
        }

    /**
     * 定时任务运行账本 → 统一态；null/空 = 尚未落终态（返回 null，由调用方决定显示
     * PENDING 还是 RUNNING —— 调度层「已认领未跑完」的窗口很窄，不适合在这里猜）。
     *
     * `SKIPPED_MISSED` 归入 CANCELLED：它是「本轮没执行」（错过窗口），不是成功也不是
     * 执行失败；归入 CANCELLED 让任务中心能统一按「未完成」处理，详情页仍显示原始状态。
     */
    fun fromScheduledRun(status: String?): TaskState? = when {
        status.isNullOrBlank() -> null
        status == TaskRunStatus.OK -> TaskState.COMPLETED
        status == TaskRunStatus.FAILED -> TaskState.FAILED
        status == TaskRunStatus.TIMEOUT -> TaskState.TIMED_OUT
        status == TaskRunStatus.SKIPPED_MISSED -> TaskState.CANCELLED
        status == TaskRunStatus.TARGET_GONE -> TaskState.FAILED
        status == TaskRunStatus.ASSISTANT_GONE -> TaskState.FAILED
        status == TaskRunStatus.CANCELLED -> TaskState.CANCELLED
        // P4b：定时账本已补 INTERRUPTED 常量（旧数据的字符串值相同，仍走同一分支）。
        status == TaskRunStatus.INTERRUPTED -> TaskState.INTERRUPTED
        else -> TaskState.FAILED
    }
}
