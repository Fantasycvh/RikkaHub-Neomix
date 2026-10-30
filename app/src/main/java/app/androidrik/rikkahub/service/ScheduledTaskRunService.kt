package app.androidrik.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.androidrik.rikkahub.KEEP_ALIVE_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.RouteActivity
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 定时任务的**执行载体**（docs/design/design-scheduled-ai-tasks.md §六「触发层」）。
 *
 * 由 [ScheduledTaskAlarmReceiver] 在闹钟到点时拉起：闹钟广播带临时豁免，因此允许在后台
 * `startForegroundService`。服务期间同时持有：
 * - 前台服务（dataSync）：进程进入前台服务态，ROM 的 cached-app freezer 不冻它；
 * - `PARTIAL_WAKE_LOCK`：Doze/熄屏时 CPU 不睡，流式回包能持续写入（WorkManager 的 worker
 *   自带 wakelock，这条是闹钟路径必须自己补的）；
 * - Wi-Fi 高性能锁：避免 Wi-Fi 在休眠间隙进省电档断流（与 [KeepAliveService] 同款做法）。
 *
 * 执行本身完全委托 [ScheduledTaskExecutor]——与 WorkManager 兜底入口共享同一套
 * 「查到期 → 认领 → 执行 → 重排」语义，因此重复唤醒（闹钟与兜底作业同时触发）不会重复执行。
 */
class ScheduledTaskRunService : Service(), KoinComponent {
    private val executor: ScheduledTaskExecutor by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private val latestStartId = AtomicInteger(0)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.set(startId)
        if (intent?.action == ACTION_STOP) {
            finish(startId)
            return START_NOT_STICKY
        }
        // ⚠️ 每一轮 start 都必须在返回前履约 startForeground，否则系统会抛
        // ForegroundServiceDidNotStartInTimeException（同 KeepAliveService 的注释）。所以
        // 「已在跑」也要先起前台再忽略多余 start。
        if (!startForegroundCompat()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireLocks()
        if (!running.compareAndSet(false, true)) {
            Log.i(TAG, "already running, ignore extra start (startId=$startId)")
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                executor.executeDueTasks(source = "alarm")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "executeDueTasks failed", e)
            } finally {
                running.set(false)
                withContext(Dispatchers.Main) { finish(latestStartId.get()) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(startId: Int) {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // 用 startId 版本：若在 stop 决策之后又有新 start 进来，让那一次 start 决定去留
        stopSelf(startId)
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "rikkahub:scheduled_task",
                ).apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "acquire wake lock failed", e)
            }
        }
        if (wifiLock?.isHeld != true) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, "rikkahub:scheduled_task",
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                Log.w(TAG, "acquire wifi lock failed", e)
            }
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "release wake lock failed", e)
        } finally {
            wakeLock = null
        }
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "release wifi lock failed", e)
        } finally {
            wifiLock = null
        }
    }

    private fun startForegroundCompat(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                FGS_NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FGS_NOTIFICATION_ID, buildNotification())
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to start foreground service", e)
        false
    }

    private fun buildNotification() = NotificationCompat.Builder(
        this, KEEP_ALIVE_NOTIFICATION_CHANNEL_ID,
    )
        .setContentTitle(getString(R.string.scheduled_task_fgs_notif_title))
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
        private const val TAG = "ScheduledTaskRun"

        /** 与 ChatNotificationManager(+10000)/Runner(+30000) 错开 */
        const val FGS_NOTIFICATION_ID = 40000

        const val ACTION_STOP = "app.androidrik.rikkahub.action.SCHEDULED_TASK_RUN_STOP"

        /** 等待生成上限 10 分钟（与 Runner 的 GENERATION_TIMEOUT 同量级），锁不无限期持有 */
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        /** 启动执行服务；ROM 拒绝后台 FGS 启动时返回 false（调用方退化到兜底作业）。 */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScheduledTaskRunService::class.java),
            )
            true
        }.onFailure {
            Log.w(TAG, "unable to start run service", it)
        }.getOrDefault(false)
    }
}
