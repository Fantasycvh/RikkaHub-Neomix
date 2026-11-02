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
import androidx.core.app.NotificationManagerCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
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

    /** P0.5 定时取消：通知动作只有 runId，靠注册表反查并取消本轮的 generation Job */
    private val runRegistry: ScheduledRunRegistry by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private val latestStartId = AtomicInteger(0)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 通知观察者只启动一次：闹钟/兜底入口可能重复 `startService`，每次 start 都 `combine(...).collect`
     * 会让同一条 FGS 通知被反复 `notify`（浪费 binder 调用，且多个 collector 互相覆盖顺序不保证）。
     */
    private var notificationObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.set(startId)
        if (intent?.action == ACTION_STOP) {
            handleStop(intent, startId)
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
        // 把「停止」动作挂到 FGS 通知上（P0.5，design-agent-task-runtime §5.2 第 4 条）：
        // 只对**未取消**的运行中轮次给入口——点过一次之后动作自动消失，避免重复点击的歧义。
        // 通知要先 startForeground 才有，故观察放在这里（而不是 onStartCommand 开头）。
        // 只启动一次：重复 start 不该叠加 collector（见 notificationObserverJob 的 KDoc）。
        if (notificationObserverJob?.isActive != true) {
            notificationObserverJob = scope.launch {
                combine(runRegistry.activeRunIds, runRegistry.cancelledRunIds) { active, cancelled ->
                    active.filterNot { it in cancelled }
                }.collect { stoppableRunIds -> updateNotification(stoppableRunIds) }
            }
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

    /**
     * 处理通知上的「停止」（P0.5 定时取消）。
     *
     * 取消**只取消 generation Job**（`ScheduledRunRegistry.cancel`），**不调
     * `ChatService.stopGeneration`**——后者会 `messageQueue.pause()`，把 EXISTING 目标会话里
     * 用户自己的待发消息一起冻结；用户点「停止定时任务」不该影响到他自己的队列。
     *
     * 这里也**不立刻 `finish()`**：`onDestroy` 会取消 [scope]，正在等生成结束的执行协程随之
     * 被取消，那一轮就写不到终态（`finished_at` 留空 = 伪「进程死亡」）。取消后执行协程会以
     * [TaskRunStatus.CANCELLED] 收尾并自己走 `finish()`；通知动作由上面的观察流撤掉。
     */
    private fun handleStop(intent: Intent, startId: Int) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID)
        val cancelled = runId != null && runRegistry.cancel(runId)
        Log.i(TAG, "stop requested: runId=$runId cancelled=$cancelled")
        if (!cancelled) {
            // 没有可取消的在跑轮次（已自然结束/通知残留）：直接收前台，免得留一条点不掉的常驻通知。
            finish(startId)
        }
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

    /**
     * 运行中通知可能已经变了（订阅到新的运行/取消），原地更新同 id 的通知即可——
     * FGS 状态挂在服务上，不需要重新 `startForeground`。
     */
    private fun updateNotification(stoppableRunIds: List<String>) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(FGS_NOTIFICATION_ID, buildNotification(stoppableRunIds))
        }.onFailure { Log.w(TAG, "update FGS notification failed", it) }
    }

    private fun buildNotification(stoppableRunIds: List<String> = emptyList()) =
        NotificationCompat.Builder(
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
            .apply {
                // 同时只有一轮在跑的场景是常态（执行器串行）；多轮时给最近登记的那一轮挂入口，
                // 避免一条通知上叠多个看不出区别的「停止」。
                stoppableRunIds.lastOrNull()?.let { runId ->
                    addAction(0, getString(R.string.stop), buildStopPendingIntent(runId))
                }
            }
            .build()

    /**
     * 「停止」动作的 PendingIntent：requestCode 按 runId 稳定区分（Android 12+ 必须
     * `FLAG_IMMUTABLE`；同一 runId 重复构建复用同一 PendingIntent，不同 run 不互相覆盖）。
     */
    private fun buildStopPendingIntent(runId: String) = PendingIntent.getService(
        this,
        runId.hashCode(),
        Intent(this, ScheduledTaskRunService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_RUN_ID, runId)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "ScheduledTaskRun"

        /** 与 ChatNotificationManager(+10000)/Runner(+30000) 错开 */
        const val FGS_NOTIFICATION_ID = 40000

        const val ACTION_STOP = "app.androidrik.rikkahub.action.SCHEDULED_TASK_RUN_STOP"

        /** 通知「停止」动作携带的 runId（P0.5 定时取消；账本行 id，见 `ScheduledTaskRepository.startRun`） */
        const val EXTRA_RUN_ID = "app.androidrik.rikkahub.extra.SCHEDULED_TASK_RUN_ID"

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
