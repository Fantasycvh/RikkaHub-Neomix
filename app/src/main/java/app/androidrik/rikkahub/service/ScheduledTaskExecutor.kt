package app.androidrik.rikkahub.service

import android.util.Log
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity
import app.androidrik.rikkahub.data.db.entity.TargetMode
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.rikkahub.data.db.entity.targetModeOrDefault
import app.androidrik.rikkahub.data.scheduled.MissDecision
import app.androidrik.rikkahub.data.scheduled.ScheduleMath
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 「查到期 → idle 门控 → 认领 → 执行 → 收尾重排」的**唯一实现**，被三个入口共用：
 *
 * 1. 一次性闹钟（[ScheduledTaskAlarmReceiver] → [ScheduledTaskRunService]）：主触发路径，
 *    Doze 里也能把进程拉起来，是准点性的来源；
 * 2. per-task 一次性唤醒作业（WorkManager，兜底：闹钟被系统压制/丢失时）；
 * 3. 15 分钟周期 sweep（WorkManager，兜底中的兜底：漏掉的槽位/进程死亡后自愈）。
 *
 * 三个入口**没有优先级、也没有互斥假设**——谁先跑谁认领，Room 的乐观锁
 * （[ScheduledTaskRepository.claim]）保证同一轮只执行一次；任何入口空跑、早跑、晚跑都无害。
 * 因此执行语义只需要在这一处维护，各入口只负责「活着把自己叫起来」。
 */
class ScheduledTaskExecutor(
    private val chatService: ChatService,
    private val taskRepository: ScheduledTaskRepository,
    private val runner: ScheduledTaskRunner,
    private val keeper: ScheduledTaskSweeperKeeper,
) {
    /**
     * 处理所有到期任务并重排唤醒，返回**本次查询到的到期任务数**（不代表全部执行成功）。
     *
     * [source] 只进日志，便于真机区分「闹钟拉起的执行」与「WorkManager 兜底的执行」。
     * [isStopped] 用于 WorkManager 的 worker 停止语义（`isStopped`），服务入口传 null。
     */
    suspend fun executeDueTasks(source: String, isStopped: () -> Boolean = { false }): Int {
        val due = taskRepository.dueTasks(System.currentTimeMillis())
        if (due.isEmpty()) {
            // 闹钟/兜底作业空跑是常态（认领已推进、别的入口先跑、任务已停用），不打 info 刷屏
            Log.d(TAG, "$source: no due tasks")
            return 0
        }
        Log.i(TAG, "$source: ${due.size} due")
        val now = System.currentTimeMillis()
        for (task in due) {
            if (isStopped()) break
            // idle 门控（purrcat A4.1）：目标会话在生成中就本轮让路——**认领之前**，
            // 不动任何状态，下个触发点再试；超窗后的回退由 decideMiss 决定（§5.1）。
            // 「每轮新建」没有存量会话可忙（新建的那条是本轮独占的），恒不触发门控。
            if (task.idleOnly &&
                targetModeOrDefault(task.targetMode) != TargetMode.NEW_EACH_RUN &&
                isConversationBusy(task.conversationId)
            ) {
                continue
            }

            val claimed = taskRepository.claim(task)
            Log.i(TAG, "$source: claim task=${task.title} -> $claimed")
            if (!claimed) continue // 另一个入口已认领并推进，跳过

            val policy = runCatching { MissPolicy.valueOf(task.missPolicy) }
                .getOrDefault(MissPolicy.RUN_LATE)
            when (ScheduleMath.decideMiss(task.nextRunAt, now, policy)) {
                // RUN_LATE_FORCED 也执行一次；与 RUN 的差别只是历史上有错过的槽数
                MissDecision.RUN, MissDecision.RUN_LATE_FORCED -> try {
                    runner.runClaimed(task, source)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "$source: runClaimed failed for ${task.title}", e)
                    // 单任务的失败已在 runner 内记账（TARGET_GONE/TIMEOUT/FAILED）；
                    // 崩溃类异常也不能拖垮本轮的剩余任务
                }

                // 静默推进：认领已把 next_run_at 推到位，这里补状态 + 给账本记一行
                // （跳过也是一次「运行」，运行历史里能看到，不然像凭空少了一轮）
                MissDecision.SKIP -> {
                    val runId = taskRepository.startRun(
                        taskId = task.id,
                        source = ScheduledTaskRunEntity.RunSource.SKIP,
                        conversationId = task.conversationId.takeIf { it.isNotBlank() },
                        startedAt = now,
                    )
                    taskRepository.finishRun(runId, TaskRunStatus.SKIPPED_MISSED, finishedAt = now)
                    taskRepository.recordStatus(task.id, TaskRunStatus.SKIPPED_MISSED)
                }
            }
        }
        // 失败不向上抛：任务各自记了状态，周期兜底还在。收尾重新对齐唤醒（认领推进了
        // next_run_at，旧闹钟/旧唤醒作业都已过时；重排实现见 keeper）。
        try {
            keeper.rearm(taskRepository)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 对齐失败不推翻本轮结果，周期兜底还在
        }
        return due.size
    }

    private suspend fun isConversationBusy(conversationId: String): Boolean {
        val id = runCatching { Uuid.parse(conversationId) }.getOrNull() ?: return false
        return chatService.getGenerationJobStateFlow(id).first() != null
    }

    companion object {
        const val TAG = "ScheduledTaskExec"
    }
}
