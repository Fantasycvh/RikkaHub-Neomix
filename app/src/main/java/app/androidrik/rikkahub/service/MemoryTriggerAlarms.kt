package app.androidrik.rikkahub.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.RouteActivity
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * 记忆整理墙钟触发的**闹钟唤醒层**（design-memory-consolidation-trigger.md §六）。
 *
 * 档位与真机教训照抄 [ScheduledTaskAlarmScheduler]（2026-10-30 实测）：
 * `setAlarmClock`（主，免权限、Doze 不压，状态栏会出现闹钟图标）→
 * `setExactAndAllowWhileIdle`（需「闹钟和提醒」授权）→ `setAndAllowWhileIdle`（不精确档）。
 *
 * 与定时任务**不复用同一类**：requestCode 命名空间、receiver、生命周期（这里只有一个全局
 * 配置槽位）都不同，硬抽公共层会把两条特性线焊死。一次只排一个闹钟：全局配置只有一份，
 * `FLAG_UPDATE_CURRENT` 覆盖式重排；模式切走 / ONCE 完成时由 [MemoryTriggerScheduleManager] 显式 cancel。
 */
class MemoryTriggerAlarmScheduler(private val context: Context) {
    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun arm(nextRunAt: Long, now: Long) {
        val alarmManager = alarmManager ?: return
        val pendingIntent = pendingIntent(create = true) ?: return
        val triggerAt = alarmTriggerAt(nextRunAt, now)

        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent()), pendingIntent)
            Log.i(TAG, "arm: at=$triggerAt mode=alarmClock")
            return
        } catch (e: Exception) {
            Log.w(TAG, "arm: setAlarmClock failed, fallback to exact/inexact", e)
        }

        try {
            if (canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                Log.i(TAG, "arm: at=$triggerAt mode=exact")
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                Log.i(TAG, "arm: at=$triggerAt mode=inexact")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "arm: exact denied, falling back to inexact", e)
            runCatching {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }.onFailure { Log.w(TAG, "arm: inexact also failed", it) }
        }
    }

    fun cancel() {
        val pendingIntent = pendingIntent(create = false) ?: return
        runCatching { alarmManager?.cancel(pendingIntent) }
        pendingIntent.cancel()
        Log.i(TAG, "cancel")
    }

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager?.canScheduleExactAlarms() == true
    }

    private fun pendingIntent(create: Boolean): PendingIntent? {
        val intent = Intent(context, MemoryTriggerAlarmReceiver::class.java).apply {
            action = MemoryTriggerAlarmReceiver.ACTION_RUN
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

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
        private const val TAG = "MemoryTriggerAlarm"

        /** 与定时任务同款 slop：早醒会「查不到 due」空转 */
        const val SLOP_MS = 5_000L

        fun alarmTriggerAt(nextRunAt: Long, now: Long): Long = maxOf(nextRunAt, now) + SLOP_MS

        /** 全局只有一个配置槽位，requestCode 恒定 */
        val REQUEST_CODE: Int = "memory-trigger".hashCode()
    }
}

/**
 * 闹钟到点广播：只拉起 [MemoryTriggerRunService]，不做任何执行（onReceive 10s 上限）。
 * 闹钟广播带临时豁免，允许在后台 startForegroundService。
 */
class MemoryTriggerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN) return
        Log.i(TAG, "alarm fired")
        if (MemoryTriggerRunService.start(context)) return
        // FGS 被拒（ROM 剥夺豁免）时**不补跑**：槽位还留在配置里，下次 app 启动的
        // reconcile 会重新排闹钟再试一次（整理积压没有时效性）
        Log.w(TAG, "foreground service rejected; slot stays due, retried on next reconcile")
    }

    companion object {
        private const val TAG = "MemoryTriggerAlarm"
        const val ACTION_RUN = "app.androidrik.rikkahub.action.MEMORY_TRIGGER_ALARM"
    }
}

/**
 * 开机重排：闹钟不跨重启，`nextRunAt` 持久化在配置里——reconcile 凭它把闹钟排回去。
 * 错过的槽位也走这里（排「现在」的闹钟，让**带豁免的闹钟广播**去拉服务，而不是在
 * BOOT 态直接 startForegroundService——后者不在 BG-FGS 豁免名单里）。
 */
class MemoryTriggerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val scope: AppScope = GlobalContext.get().get()
        scope.launch {
            try {
                GlobalContext.get().get<MemoryTriggerScheduleManager>().reconcileCurrent()
                Log.i(TAG, "boot rearm done")
            } catch (e: Exception) {
                Log.w(TAG, "boot rearm failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MemoryTriggerAlarm"
    }
}
