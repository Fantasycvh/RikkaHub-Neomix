package app.androidrik.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.androidrik.rikkahub.KEEP_ALIVE_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.RouteActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 记忆整理墙钟触发的**执行载体**（design-memory-consolidation-trigger.md §五.3/§六）。
 *
 * 由 [MemoryTriggerAlarmReceiver] 在到点时拉起：服务本身就是保活（V3 留白的
 * `MemoryDreamKeepAlive` 由此补上——前台服务态不被 cached-app freezer 冻结），
 * 另持 partial wakelock 保证 Doze/熄屏下模型调用不被断。执行委托
 * [MemoryTriggerScheduleManager.executeDueRun]：认领（推进槽位）→ 逐库 `runScheduled`。
 */
class MemoryTriggerRunService : Service(), KoinComponent {
    private val manager: MemoryTriggerScheduleManager by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 每一轮 start 都必须在返回前履约 startForeground（同 ScheduledTaskRunService 的注释）
        if (!startForegroundCompat()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "already running, ignore extra start (startId=$startId)")
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                manager.executeDueRun()
            } catch (e: Exception) {
                Log.w(TAG, "executeDueRun failed", e)
            } finally {
                running.set(false)
                releaseWakeLock()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = runCatching {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:wakelock").apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun startForegroundCompat(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "failed to start foreground service", e)
        false
    }

    private fun buildNotification() = NotificationCompat.Builder(this, KEEP_ALIVE_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.memory_trigger_fgs_notif_title))
        .setSmallIcon(R.drawable.ic_stat_rikkahub)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RouteActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        private const val TAG = "MemoryTriggerRun"
        private const val NOTIFICATION_ID = 10086
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L

        fun start(context: Context): Boolean = runCatching {
            context.startForegroundService(Intent(context, MemoryTriggerRunService::class.java))
            true
        }.getOrElse {
            Log.w(TAG, "startForegroundService failed", it)
            false
        }
    }
}
