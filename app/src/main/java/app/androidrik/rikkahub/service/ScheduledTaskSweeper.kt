package app.androidrik.rikkahub.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import app.androidrik.rikkahub.KEEP_ALIVE_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * 定时任务 sweeper（docs/design/design-scheduled-ai-tasks.md §三/§五/§六）。
 *
 * ## 触发层：三层冗余，全部收敛到 [ScheduledTaskExecutor]
 *
 * 1. **一次性闹钟**（[ScheduledTaskAlarmScheduler] → [ScheduledTaskAlarmReceiver] →
 *    [ScheduledTaskRunService]）：主触发路径。`setExactAndAllowWhileIdle` 在 Doze 里也能
 *    把进程叫起来；广播接收器**持闹钟带来的临时豁免**，因此允许后台启动前台服务，
 *    生成期间由前台服务 + partial wakelock 保护（2026-10-30 真机：纯 WorkManager 在
 *    息屏 Doze 下到点不跑、或被 ROM 冻结，只有亮屏/回前台才续上）。
 * 2. **per-task 一次性唤醒作业**（本文件的 Worker，expedited）：闹钟被系统压制/丢失时的兜底；
 * 3. **15 分钟周期 sweep**：漏槽与进程死亡的兜底网。
 *
 * 三个入口共享 [ScheduledTaskExecutor] 的「查到期 → 认领 → 执行」语义，没有优先级与互斥
 * 假设——Room 乐观锁保证同一轮只执行一次，其余入口空跑无害。
 *
 * Koin 里不注册本类：WorkManager 的 KoinWorkerFactory 查不到定义时回退反射构造
 * （(Context, WorkerParameters)），依赖在 [doWork] 里按需解析——worker 不是长活单例，
 * 值得省掉一份 DSL 定义。
 */
class ScheduledTaskSweeperWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val executor: ScheduledTaskExecutor by inject()

    override suspend fun doWork(): Result {
        // 执行期仍尝试挂 WorkManager 的前台服务：这条是兜底入口，闹钟入口自己起 FGS。
        // ROM 拒绝后台 FGS 启动时退化（闹钟入口不受影响），记一条 warning 便于真机排查。
        try {
            setForeground(foregroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sweep: setForeground rejected, continue without FGS", e)
        }
        executor.executeDueTasks(source = "sweep", isStopped = { isStopped })
        // 单次 sweep 内的失败不喂给 WorkManager 重试：任务各自记了状态，下个周期自然重查。
        return Result.success()
    }

    /** 执行期前台服务通知（dataSync 类型，与 MediaCreation 同款先例） */
    private fun foregroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(
            applicationContext, KEEP_ALIVE_NOTIFICATION_CHANNEL_ID,
        )
            .setContentTitle(applicationContext.getString(R.string.scheduled_task_fgs_notif_title))
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FGS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(FGS_NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val TAG = "ScheduledTaskSweeper"

        /** 与 ChatNotificationManager(+10000)/Runner(+30000) 错开 */
        const val FGS_NOTIFICATION_ID = 40000
    }
}

/**
 * 一次性唤醒作业的构造参数（纯函数，可单测）。
 *
 * **WorkManager 的 expedited 作业带 `initialDelay` 会在 `build()` 直接抛
 * `IllegalArgumentException: Expedited jobs cannot be delayed`**——真机事故（revision 6）：
 * 新建/开关任务走 `rearm()` → `armPrompt()` 必然崩溃（存量任务全是直接写 DB 造出来的，
 * 所以之前没暴露）。因此这里只在「已经到点」时才用 expedited 立即执行；未来的时刻退回
 * 普通延迟作业（闹钟仍是主路径，WorkManager 只是兜底网）。
 *
 * @param expedited true = 立即执行 + expedited（不带延迟）；false = 带 `delayMs` 的普通延迟作业
 * @param delayMs 距执行时刻的毫秒数；`expedited` 为 true 时必须为 0
 */
internal data class PromptWorkPlan(val expedited: Boolean, val delayMs: Long)

internal fun promptWorkPlan(nextRunAt: Long, now: Long): PromptWorkPlan {
    val untilDue = nextRunAt - now
    return if (untilDue <= 0) {
        // 已到期：立即跑，且值得插队（App 刚启动/刚对账时的补跑）
        PromptWorkPlan(expedited = true, delayMs = 0)
    } else {
        PromptWorkPlan(expedited = false, delayMs = untilDue + ScheduledTaskSweeperKeeper.PROMPT_SLOP_MS)
    }
}

/**
 * 唤醒层的排/撤（App 启动与任务增删改后对账）。
 *
 * 每个启用任务同时排**两条** Trigger：
 * - 一次性闹钟（主路径，Doze 也放行；精确档需要 `SCHEDULE_EXACT_ALARM` 特殊权限，
 *   未授权时退化为 `setAndAllowWhileIdle` 不精确档）；
 * - 一次性 WorkManager 唤醒作业（兜底；闹钟被系统抑制时仍有概率触发。**未来时刻不能
 *   expedited**，见 [promptWorkPlan]）。
 *
 * 周期 sweep 只按「是否有启用任务」保留/取消，不因对账重置相位。
 */
class ScheduledTaskSweeperKeeper(private val context: Context) {
    private val alarmScheduler = ScheduledTaskAlarmScheduler(context)

    fun reconcile(hasEnabledTasks: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!hasEnabledTasks) {
            workManager.cancelUniqueWork(SWEEPER_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ScheduledTaskSweeperWorker>(
            SWEEP_INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(networkConstraints())
            .build()
        // KEEP：启动对账不重置既有周期（否则每次开 App 都把下个 sweep 往后推 15 分钟）
        workManager.enqueueUniquePeriodicWork(SWEEPER_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * 为单个任务排「到点唤醒」。
     *
     * 闹钟用 taskId 派生的固定 requestCode 覆盖式重排（`FLAG_UPDATE_CURRENT`），所以同一任务
     * 任意次对账只留最近一个闹钟；WorkManager 唤醒名含 `nextRunAt`，认领推进后旧名自然退役
     * （跑完即完，不积累运行中工作），**永不与运行中的 sweep 同名**——因此不会出现
     * REPLACE/KEEP 在「自己取消自己」上的两难。
     */
    fun armPrompt(taskId: String, nextRunAt: Long, now: Long) {
        alarmScheduler.arm(taskId, nextRunAt, now)

        val plan = promptWorkPlan(nextRunAt, now)
        val builder = OneTimeWorkRequestBuilder<ScheduledTaskSweeperWorker>()
            .setConstraints(networkConstraints())
        if (plan.delayMs > 0) {
            builder.setInitialDelay(plan.delayMs, TimeUnit.MILLISECONDS)
        }
        if (plan.expedited) {
            // expedited：让系统把它当「用户期望尽快执行」的工作，执行期前台的豁免更强、
            // 排队更靠前；配额耗尽时退化为普通作业（RUN_AS_NON_EXPEDITED），不抛异常。
            // 注意：只有「已经到点」才允许——未来时刻的 expedited + initialDelay 会直接崩溃。
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            PROMPT_WORK_PREFIX + taskId + ":" + nextRunAt,
            ExistingWorkPolicy.KEEP,
            builder.build(),
        )
    }

    /**
     * 全量对账：周期兜底 + 为每个启用任务重排唤醒 + 清掉停用任务的残留闹钟。
     * 建/改/删/开关后与启动时调用；每次执行结束也调一次（认领已把 next_run_at 推到下个槽位）。
     * 已删除任务的残留闹钟/唤醒触发后查不到 due，空跑一轮自愈。
     *
     * 单任务排程失败只记日志、不影响其余任务与调用方（历史事故：armPrompt 抛异常把
     * ViewModel 的 coroutine 打崩，用户看到「新建任务 → App 停止运行」）。
     */
    suspend fun rearm(repository: ScheduledTaskRepository) {
        val tasks = repository.allTasks()
        val enabled = tasks.filter { it.enabled }
        reconcile(enabled.isNotEmpty())
        val now = System.currentTimeMillis()
        enabled.forEach { task ->
            try {
                armPrompt(task.id, task.nextRunAt, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "armPrompt failed for ${task.id}", e)
            }
        }
        // 停用/被删的任务：闹钟是一次性 PendingIntent，不清会留下「到点白跑一轮」的残留
        tasks.filter { !it.enabled }.forEach { alarmScheduler.cancel(it.id) }
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    companion object {
        private const val TAG = "ScheduledTaskKeeper"

        const val SWEEPER_WORK_NAME = "scheduled_task_sweeper"
        const val SWEEP_INTERVAL_MINUTES = 15L

        const val PROMPT_WORK_PREFIX = "scheduled_task_prompt:"

        /** 唤醒比 nextRunAt 晚这一点，避免「提前 1ms 醒来查不到 due」的空转抖动 */
        const val PROMPT_SLOP_MS = 5_000L
    }
}
