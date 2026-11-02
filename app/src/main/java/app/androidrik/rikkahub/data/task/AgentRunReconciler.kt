package app.androidrik.rikkahub.data.task

import app.androidrik.rikkahub.data.db.dao.AgentRunDAO
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity

/**
 * **启动对账**（P0.5，设计 §6.2）：把上一个进程遗留的未完成 run 标成 INTERRUPTED。
 *
 * 为什么必须做：`agent_run.running` 的行不会因为进程死亡自己变终态。不清理的话，
 * 运行列表会永远显示「运行中」、任务中心会给出一个点了没反应的取消按钮、统计口径
 * 也会把死 run 算成活跃。这里的处理是**只改状态、不重试、不恢复半截生成**——
 * 进程内生成协程已经不存在，谎称「恢复」比显示「已中断」更糟。
 *
 * 不处理：
 * - 本次进程创建的 run（`COALESCE(started_at, created_at) >= processStartedAt`）；
 * - 已落 `finished_at` 的行（正常收尾，只是终态列没来得及改的边角）；
 * - 后台 shell 任务（内存注册表随进程消失，无 DB 行可对账；P3 才做孤儿进程扫描）。
 */
class AgentRunReconciler(
    private val agentRunDAO: AgentRunDAO,
    private val now: () -> Long = System::currentTimeMillis,
    private val processStartedAt: () -> Long = { ProcessStartClock.startedAt },
) {

    /**
     * @return 被标记为中断的行数；0 = 没有遗留或无法判断（进程启动时刻未记录）。
     */
    suspend fun reconcileProcessRestart(): Int {
        val boot = processStartedAt()
        // 0 = 本次进程没有 markStarted（单测/异常初始化）：不能拿 0 当「很久以前」，
        // 否则会把本次进程刚创建的 run 全部误标成中断。
        if (boot <= 0L) return 0
        return agentRunDAO.markInterruptedBefore(
            processStartedAt = boot,
            finishedAt = now(),
            interruptedStatus = AgentRunEntity.STATUS_INTERRUPTED,
            reason = AgentRunEntity.ERROR_PROCESS_RESTART,
            unfinishedStatuses = UNFINISHED_STATUSES,
            cancelReason = "Process restarted; generation cannot be resumed.",
        )
    }

    companion object {
        /** 可能「看起来还在跑」的状态；completed/cancelled 等终态不参与对账。 */
        val UNFINISHED_STATUSES: List<String> = listOf(
            AgentRunEntity.STATUS_PENDING,
            AgentRunEntity.STATUS_RUNNING,
            AgentRunEntity.STATUS_AWAITING_APPROVAL,
        )
    }
}
