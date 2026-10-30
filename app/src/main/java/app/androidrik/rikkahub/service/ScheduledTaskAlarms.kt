package app.androidrik.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import app.androidrik.rikkahub.RouteActivity

/**
 * 定时任务的**闹钟唤醒层**（docs/design/design-scheduled-ai-tasks.md §六「触发层」）。
 *
 * 为什么必须加这一层：WorkManager 的 `setExact`/expedited 都无法保证 Doze 熄屏下的到点执行
 * （2026-10-30 真机实测：22:13:07 到点的任务，息屏 Doze 下 JobScheduler 直接不执行，直到
 * 22:15:31 亮屏才跑）。`AlarmManager` 的闹钟是系统允许在 Doze 中投递的通用机制，闹钟广播还带
 * 临时豁免，允许接收器在后台启动前台服务（[ScheduledTaskRunService]）——生成因此能脱离
 * WorkManager 的生命周期。
 *
 * 档位（详见 [arm] 的 KDoc）：`setAlarmClock`（主，闹钟语义）→ `setExactAndAllowWhileIdle`
 * （需「闹钟和提醒」授权）→ `setAndAllowWhileIdle`（无需授权的不精确档）。
 *
 * 一次只排一个闹钟：PendingIntent 的 requestCode 由 taskId 派生，`FLAG_UPDATE_CURRENT` 覆盖式
 * 重排；任务停用/删除时由 [ScheduledTaskSweeperKeeper.rearm] 显式 cancel。
 */
class ScheduledTaskAlarmScheduler(private val context: Context) {
    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * 为任务排下一次到点闹钟（覆盖旧闹钟）。
     *
     * 三条投递路径，按「系统压制」的强弱排序（2026-10-30 真机实测决定）：
     *
     * 1. **闹钟档** `setAlarmClock`（主路径）：系统按「用户可见的闹钟」投递，Doze、应用待机
     *    都不压制，**且不需要 `SCHEDULE_EXACT_ALARM`**。代价是状态栏会出现闹钟图标、系统
     *    「下次闹钟」显示它——这是 Android 里唯一能稳定把后台进程叫醒的公开机制。
     * 2. **精确档** `setExactAndAllowWhileIdle`：`setAlarmClock` 意外失败时退到这里（需要
     *    「闹钟和提醒」授权；真机实测即使授权，OnePlus 也会把 while-idle 闹钟放进 ~67s 的
     *    窗口里批处理，且 ROM 的「应用启动管理」仍可能拦掉广播投递）。
     * 3. **不精确档** `setAndAllowWhileIdle`：什么权限都没有时的最后手段（Doze 放行但窗口更长）。
     *
     * ⚠️ 真机教训：仅靠 1/2 还不够——ColorOS 的 `OplusAppStartupManager` 会
     * `prevent start ... by broadcast system[alarmManger]`，所以用户仍须把本应用加进
     * 「应用启动管理 / 允许自启动 + 后台活动」（设置页有引导）。
     */
    fun arm(taskId: String, nextRunAt: Long, now: Long) {
        val alarmManager = alarmManager ?: return
        val pendingIntent = pendingIntent(taskId, create = true) ?: return
        val triggerAt = alarmTriggerAt(nextRunAt, now)

        // 1. 闹钟档：无需权限，Doze/应用待机都不压（ROM 启动管理除外）
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent()), pendingIntent)
            Log.i(TAG, "arm: task=$taskId at=$triggerAt mode=alarmClock")
            return
        } catch (e: Exception) {
            Log.w(TAG, "arm: setAlarmClock failed, fallback to exact/inexact task=$taskId", e)
        }

        // 2/3. 精确档 / 不精确档（Android 12+ 需「闹钟和提醒」授权）
        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                Log.i(TAG, "arm: task=$taskId at=$triggerAt mode=exact")
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                Log.i(TAG, "arm: task=$taskId at=$triggerAt mode=inexact")
            }
        } catch (e: SecurityException) {
            // 权限在「设置里刚被撤销」与「canScheduleExactAlarms 返回 true」之间有窗口，兜一手
            Log.w(TAG, "arm: exact denied, falling back to inexact task=$taskId", e)
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }.onFailure { Log.w(TAG, "arm: inexact also failed task=$taskId", it) }
        }
    }

    /** 撤销任务的到点闹钟（任务停用/删除/已执行）。 */
    fun cancel(taskId: String) {
        val pendingIntent = pendingIntent(taskId, create = false) ?: return
        runCatching { alarmManager?.cancel(pendingIntent) }
        pendingIntent.cancel()
        Log.i(TAG, "cancel: task=$taskId")
    }

    /** 精确档是否可用（Android 12+ 需用户在系统设置里授予「闹钟和提醒」）。 */
    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager?.canScheduleExactAlarms() == true
    }

    private fun pendingIntent(taskId: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ScheduledTaskAlarmReceiver.ACTION_RUN
            putExtra(ScheduledTaskAlarmReceiver.EXTRA_TASK_ID, taskId)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, alarmRequestCode(taskId), intent, flags)
    }

    /**
     * 闹钟档的「点击闹钟图标」入口：用户点系统「下次闹钟」或状态栏闹钟图标时打开 App。
     * `setAlarmClock` 要求给 `AlarmClockInfo` 传 showIntent（可为 null，但给了体验更好）。
     */
    private fun showIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, RouteActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val TAG = "ScheduledTaskAlarm"

        /** 与 WorkManager 唤醒同一套 slop：早 5 秒醒来会「查不到 due」空转 */
        const val SLOP_MS = 5_000L

        /** 闹钟触发时刻（纯函数，单测覆盖早到/已过期/正常三态）。 */
        fun alarmTriggerAt(nextRunAt: Long, now: Long): Long =
            maxOf(nextRunAt, now) + SLOP_MS

        /** taskId → PendingIntent requestCode（同任务稳定，不同任务碰撞概率可忽略）。 */
        fun alarmRequestCode(taskId: String): Int = taskId.hashCode()
    }
}

/**
 * 闹钟到点广播：拉起 [ScheduledTaskRunService] 执行。
 *
 * 接收器里**只做拉起**，不做任何执行——`onReceive` 有 10s 上限，而一轮生成要几十秒到几分钟。
 * 前台服务启动失败（理论上只在 ROM 剥夺闹钟豁免时发生）时退化到 WorkManager 一次性作业，
 * 至少让兜底链路还有一次机会。
 */
class ScheduledTaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        Log.i(TAG, "alarm fired: task=$taskId")
        if (ScheduledTaskRunService.start(context)) return
        Log.w(TAG, "alarm: foreground service rejected, fallback to one-time work")
        runCatching {
            val request = OneTimeWorkRequestBuilder<ScheduledTaskSweeperWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }.onFailure { Log.w(TAG, "alarm: fallback work enqueue failed", it) }
    }

    companion object {
        const val TAG = "ScheduledTaskAlarm"
        const val ACTION_RUN = "app.androidrik.rikkahub.action.SCHEDULED_TASK_ALARM"
        const val EXTRA_TASK_ID = "task_id"
    }
}
