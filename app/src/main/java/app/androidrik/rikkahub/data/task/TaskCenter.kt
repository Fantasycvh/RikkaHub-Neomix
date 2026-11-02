package app.androidrik.rikkahub.data.task

import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.db.dao.AgentRunDAO
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import app.androidrik.rikkahub.service.ScheduledRunRegistry
import app.androidrik.rikkahub.service.SubagentRunner
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 统一任务键（P0，`design-agent-task-runtime.md` §4.2/§4.4）。
 *
 * 三套底座的 id 空间不同：agent run 是 Uuid、shell task 是 8 位十六进制、定时 run 是
 * `taskId/runId` 两级。任务中心只做**读聚合 + 取消**，因此键必须能反查回各自的执行器；
 * 序列化形态（[wire]）只用于 UI 的稳定 key / 日志，不落库。
 */
sealed interface TaskKey {
    data class Agent(val runId: String) : TaskKey

    data class Shell(val root: String, val taskId: String) : TaskKey

    data class Scheduled(val taskId: String, val runId: String?) : TaskKey

    /** 稳定字符串（Compose `key` / 日志）；与设计 §4.4 的存储键同形。 */
    val wire: String
        get() = when (this) {
            is Agent -> "agent:$runId"
            is Shell -> "shell:$root:$taskId"
            is Scheduled -> "sched:$taskId:${runId.orEmpty()}"
        }
}

/** 任务来源（任务中心分段/筛选依据）。 */
enum class TaskSource { AGENT, SHELL, SCHEDULED }

/**
 * 任务中心的一行（**不可变快照**，不暴露实体/可变状态）。
 *
 * 刻意不在这里做本地化：文案由 UI 用 `state`/`source` 映射资源，读模型只负责「事实」。
 */
data class TaskCard(
    val key: TaskKey,
    val source: TaskSource,
    val state: TaskState,
    /** 主标题：run 的 profile 名 / 后台命令 / 定时任务标题。 */
    val title: String,
    /** 次标题：run 的目标、shell 的 cwd、定时任务的最近状态说明等。 */
    val subtitle: String?,
    val startedAt: Long?,
    val finishedAt: Long?,
    /** 是否给「取消」入口：终态/排队中的定时任务不给（取消对它们无意义）。 */
    val canCancel: Boolean,
    /** 该 run 当前待批工具数（>0 时给「批准」入口）；其他来源恒为 0。 */
    val pendingApprovalCount: Int = 0,
)

/** 取消结果：UI 据此决定提示文案，不把「没在跑」假装成「已取消」。 */
enum class TaskCancelResult {
    /** 已下发取消（run Job / shell 进程 / 定时 run）。 */
    REQUESTED,

    /** 已经结束或本来就没在跑（调用方应刷新列表，不要提示成功）。 */
    NOT_ACTIVE,

    /** 任务 id 不存在（进程重启后 shell 表丢失 / 已淘汰）。 */
    NOT_FOUND,
}

/**
 * 定时任务三源快照（任务行 / 最近账本 / 未终态账本）。
 *
 * 放在同一个快照里而不是分别 combine：同一帧里「任务行说 OK、账本说 FAILED」时，
 * 映射函数能确定地用账本，不需要靠两次 emit 的时序碰运气。
 */
private data class ScheduledSnapshot(
    val tasks: List<ScheduledTaskEntity>,
    val recentRuns: List<ScheduledTaskRunEntity>,
    val unfinishedRuns: List<ScheduledTaskRunEntity>,
)

/**
 * **任务中心读模型**（P0，`design-agent-task-runtime.md` §4.2）。
 *
 * 为什么是读模型而不是任务总表：三套执行器的状态机/生命周期载体都不同，写一张总表意味着
 * 三处写、还要处理双写不一致；而「一次查询看全设备在跑什么」只需要**聚合读**。这里只做
 * combine + 映射 + 取消转发，**不做准入**（并发上限仍由 `ConcurrentRunGate` / workspace 槽位
 * 各自裁决，避免出现第三套计数）。
 *
 * 线程模型：`observeTasks` 是冷 Flow，在收集方（VM）的协程里合并；`cancel` 是 suspend，
 * shell 取消走 IO，agent/定时取消是同步的 Job.cancel（不阻塞）。
 */
class TaskCenter(
    private val agentRunDAO: AgentRunDAO,
    private val workspaceRepository: WorkspaceRepository,
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val scheduledRunRegistry: ScheduledRunRegistry,
    private val subagentRunner: SubagentRunner,
) {

    /**
     * 定时任务的三条流先合成一个快照，再与其余来源 combine。
     *
     * Kotlin 的 `combine` 只有 5 个参数的重载；这里把「任务行 + 最近账本 + 未终态账本」
     * 收成一个，既避免 6 源嵌套，也让定时卡的映射拿到**同一帧**的数据。
     */
    private val scheduledSnapshot: Flow<ScheduledSnapshot> = combine(
        scheduledTaskRepository.flowAll(),
        scheduledTaskRepository.flowRecentRuns(),
        scheduledTaskRepository.flowUnfinishedRuns(),
    ) { tasks, recentRuns, unfinishedRuns ->
        ScheduledSnapshot(tasks, recentRuns, unfinishedRuns)
    }

    /**
     * 三源 + 审批队列 + 定时运行注册表的合并流。
     *
     * P4：定时任务卡不再只看任务行的 `last_status`，而是以**运行账本**为准——
     * 运行中的一轮来自注册表 + 账本，结束的一轮带真实 `finished_at`；进程死亡留下的
     * 未终态账本显示为 INTERRUPTED，而不是永远 RUNNING。
     */
    fun observeTasks(): Flow<List<TaskCard>> = combine(
        agentRunDAO.observeRuns(),
        workspaceRepository.observeBackgroundTasks(),
        scheduledSnapshot,
        SubagentApprovalQueue.requests,
        scheduledRunRegistry.activeRunIds,
    ) { runs, shells, scheduled, approvals, _ ->
        val approvalsByRun = approvals.groupingBy { it.runId }.eachCount()
        val recentRunsByTask = scheduled.recentRuns.groupBy { it.taskId }
        val unfinishedRunsByTask = scheduled.unfinishedRuns.groupBy { it.taskId }
        buildList {
            runs.forEach { add(agentTaskCard(it, approvalsByRun[it.id] ?: 0)) }
            shells.forEach { add(shellTaskCard(it)) }
            scheduled.tasks.forEach { task ->
                val activeRunId = scheduledRunRegistry.activeRunIdOfTask(task.id)
                // 注册表先于账本行插入也不会丢卡：优先用未终态账本，退而求其次查最近账本。
                val activeRun = activeRunId?.let { id ->
                    unfinishedRunsByTask[task.id]?.firstOrNull { it.id == id }
                        ?: recentRunsByTask[task.id]?.firstOrNull { it.id == id }
                }
                add(
                    scheduledTaskCard(
                        task = task,
                        activeRunId = activeRunId,
                        activeRun = activeRun,
                        latestRun = recentRunsByTask[task.id]?.firstOrNull(),
                    )
                )
            }
        }.sortedWith(
            compareByDescending<TaskCard> { it.state == TaskState.RUNNING || it.state == TaskState.WAITING_APPROVAL }
                .thenByDescending { it.startedAt ?: 0L },
        )
    }

    /**
     * 取消一个任务。
     *
     * 三个来源各走自己的取消语义：
     * - agent：`SubagentRunner.cancelRun`（Job.cancel，级联收本轮 shell）；
     * - shell：`WorkspaceTaskRegistry.kill`（SIGKILL 整棵进程树）；
     * - scheduled：`ScheduledRunRegistry.cancel`（只取消本轮 generation Job，不 pause 用户队列）。
     */
    suspend fun cancel(key: TaskKey): TaskCancelResult = when (key) {
        is TaskKey.Agent -> {
            val id = runCatching { Uuid.parse(key.runId) }.getOrNull()
                ?: return TaskCancelResult.NOT_FOUND
            if (!subagentRunner.isRunActive(id)) {
                TaskCancelResult.NOT_ACTIVE
            } else {
                subagentRunner.cancelRun(id)
                TaskCancelResult.REQUESTED
            }
        }

        is TaskKey.Shell -> when (workspaceRepository.killBackgroundTaskByTaskId(key.taskId)) {
            app.androidrik.workspace.WorkspaceKillResult.KILLED -> TaskCancelResult.REQUESTED
            app.androidrik.workspace.WorkspaceKillResult.ALREADY_FINISHED -> TaskCancelResult.NOT_ACTIVE
            app.androidrik.workspace.WorkspaceKillResult.NOT_FOUND -> TaskCancelResult.NOT_FOUND
        }

        is TaskKey.Scheduled -> {
            val runId = key.runId
            when {
                runId.isNullOrBlank() -> TaskCancelResult.NOT_ACTIVE
                scheduledRunRegistry.cancel(runId) -> TaskCancelResult.REQUESTED
                else -> TaskCancelResult.NOT_ACTIVE
            }
        }
    }

    /** 批准/拒绝一个待批工具（与通知栏按钮同一条队列）。false = 无人在等（已超时/进程重启）。 */
    fun resolveApproval(toolCallId: String, approved: Boolean): Boolean =
        subagentRunner.resolveApproval(toolCallId, approved)
}

/**
 * 三源 → [TaskCard] 的纯映射（internal 便于表驱动单测，不让测试为了三行 when 去搭
 * `TaskCenter` 的五个依赖）。UI 需要的「事实」只在这里定义一次。
 */
internal fun agentTaskCard(run: AgentRunEntity, pendingApprovals: Int): TaskCard {
    val state = TaskStateMappings.fromAgentRun(run.status, run.error)
    return TaskCard(
        key = TaskKey.Agent(run.id),
        source = TaskSource.AGENT,
        state = state,
        title = run.profileName,
        subtitle = run.goal.takeIf { it.isNotBlank() },
        startedAt = run.startedAt ?: run.createdAt,
        finishedAt = run.finishedAt,
        canCancel = state == TaskState.PENDING || state == TaskState.RUNNING ||
            state == TaskState.WAITING_APPROVAL,
        pendingApprovalCount = pendingApprovals,
    )
}

internal fun shellTaskCard(task: app.androidrik.workspace.WorkspaceTaskSnapshot): TaskCard {
    val state = TaskStateMappings.fromWorkspace(task.status, task.exitCode)
    return TaskCard(
        key = TaskKey.Shell(task.root, task.id),
        source = TaskSource.SHELL,
        state = state,
        title = task.command.lineSequence().firstOrNull().orEmpty().ifBlank { task.id },
        subtitle = task.cwd.takeIf { it.isNotBlank() },
        startedAt = task.startedAtMillis,
        finishedAt = task.finishedAtMillis,
        canCancel = state == TaskState.RUNNING,
    )
}

internal fun scheduledTaskCard(
    task: ScheduledTaskEntity,
    activeRunId: String?,
    activeRun: ScheduledTaskRunEntity? = null,
    latestRun: ScheduledTaskRunEntity? = null,
): TaskCard {
    val running = activeRunId != null
    // TARGET_GONE / ASSISTANT_GONE 是任务级摘要（目标被删，没有对应的执行轮次），
    // 不能被更早的一条成功账本盖掉，否则任务失效在任务中心完全不可见。
    val summaryOnly = task.lastStatus == TaskRunStatus.TARGET_GONE ||
        task.lastStatus == TaskRunStatus.ASSISTANT_GONE
    val ledgerRun = when {
        running -> activeRun
        summaryOnly -> null
        else -> latestRun
    }
    val state = when {
        running -> TaskState.RUNNING
        ledgerRun == null -> TaskStateMappings.fromScheduledRun(task.lastStatus) ?: TaskState.PENDING
        // 账本行没有终态 = 执行层进程中途死亡；没有对账写回，不能继续显示 PENDING。
        ledgerRun.finishedAt == null || ledgerRun.status == null -> TaskState.INTERRUPTED
        else -> TaskStateMappings.fromScheduledRun(ledgerRun.status) ?: TaskState.FAILED
    }
    return TaskCard(
        key = TaskKey.Scheduled(task.id, if (running) activeRunId else ledgerRun?.id),
        source = TaskSource.SCHEDULED,
        state = state,
        title = task.title,
        subtitle = when {
            running -> null
            // 账本才是这一轮的事实；任务行 last_status 可能还停在上上轮。
            ledgerRun != null -> ledgerRun.status
            else -> task.lastStatus
        },
        startedAt = ledgerRun?.startedAt ?: task.lastRunAt,
        finishedAt = if (running) null else ledgerRun?.finishedAt,
        canCancel = running,
    )
}
