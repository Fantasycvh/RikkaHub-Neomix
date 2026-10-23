package app.androidrik.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.WEB_SERVER_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.web.WebServerManager
import org.koin.android.ext.android.inject

private const val TAG = "WebServerService"

/**
 * 观察到「该收尾」后，先等这么久再按最新状态复核一次。
 *
 * 启动/停止都是异步的：旧 error、旧 stop 的瞬时 stopped 可能已被新一次 start 取代，
 * 直接 `stopSelf` 会连新 attempt 一起停掉（且 `latestStartId` 会指向新 attempt）。
 */
private const val RELEASE_GRACE_MS = 500L

/**
 * 状态观察器在看到某个 `WebServerManager` 状态后，是否应释放前台服务并 `stopSelf`。
 *
 * 纯函数（不碰 Android API），便于 JVM 单测覆盖三条退出路径：
 * - `isLoading` / `isRunning`：还在启动/运行，继续观察；
 * - 曾经 `running` 后变成停止：正常停止，收尾；
 * - **从未 running**：只有管理器给出明确 `error`（端口占用 / 缺口令 / 绑定异常）才收尾。
 *   这一条修的是旧缺陷：启动失败时 `wasRunning` 恒为 false，旧 `when` 三个分支全不匹配，
 *   前台服务与「启动中」通知永久驻留（且 `webServerEnabled=true` 会在下次启动继续自动拉起）。
 */
internal fun shouldReleaseForegroundAfterState(
    sawRunning: Boolean,
    isLoading: Boolean,
    isRunning: Boolean,
    hasError: Boolean,
): Boolean = when {
    isLoading || isRunning -> false
    sawRunning -> true
    else -> hasError
}

class WebServerService : Service() {

    companion object {
        const val ACTION_START = "app.androidrik.rikkahub.action.WEB_SERVER_START"
        const val ACTION_STOP = "app.androidrik.rikkahub.action.WEB_SERVER_STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_LOCALHOST_ONLY = "localhost_only"
        const val NOTIFICATION_ID = 2001
    }

    private val webServerManager: WebServerManager by inject()
    private val settingsStore: SettingsStore by inject()

    /**
     * 写设置必须用 AppScope：`ACTION_STOP` 里紧接着 `stopSelf`，`onDestroy` 会 cancel
     * [serviceScope]，用 serviceScope 的写回可能永远不执行（enabled 仍为 true，下次启动又拉起）。
     */
    private val appScope: AppScope by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    /**
     * 最近一次 `onStartCommand` 的 `startId`。
     *
     * 供 `onStartCommand` **之外**的退出路径用（状态观察器那条）——那里拿不到 `startId`，
     * 而裸 `stopSelf()` 会连一次**已排队但还没调 `startForeground()`** 的新启动一起停掉 ⇒
     * 系统抛 `ForegroundServiceDidNotStartInTimeException` 崩溃。理由详见下方 `onStartCommand`。
     */
    private var latestStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 本服务由 `startForegroundService()` 启动（`RikkaHubApp.kt:318`），所以每次启动都
        // **必须**走到一次 `startForeground()`。任何退出都要用 `stopSelf(startId)` 而不是裸
        // `stopSelf()`：后者会把**更新的、尚未履约的那次启动**一起停掉 ⇒
        // `ForegroundServiceDidNotStartInTimeException` **崩溃**。
        // 同源缺陷上游在 `72747fc9e`（2026-10-16）修过，但触发它的类本仓没有；
        // 本仓对应物是这里与 `KeepAliveService`（同一批一起改）。
        latestStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                // 安全默认：缺 extra 时按回环模式启动（旧默认 false 会把服务暴露到 LAN）。
                val localhostOnly = intent.getBooleanExtra(EXTRA_LOCALHOST_ONLY, true)
                if (!startForegroundCompat()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                // 顺序很重要：先发起 start（`WebServerManager.start` 会**同步**把旧 error 清成
                // loading 态），再挂观察器。反过来会让观察器先收到上一次失败留下的 error，
                // 在重试刚发起时就 `stopSelf` 掉前台服务。
                webServerManager.start(port = port, localhostOnly = localhostOnly)
                startObservingState()
            }

            ACTION_STOP -> {
                webServerManager.stop()
                // 用 AppScope 写：下一行 stopSelf 会触发 onDestroy 取消 serviceScope。
                appScope.launch {
                    settingsStore.update { it.copy(webServerEnabled = false) }
                }
                // **立即收尾**，不再等状态流：服务可能「从未进入 running」（启动失败 / 空口令
                // 被安全策略拒绝 / 端口占用），那条路径下观察器的 wasRunning 恒为 false，
                // 旧实现永远停不掉 ⇒ 前台服务 +「启动中」通知常驻。
                // 仍用 stopSelf(startId) 保持 F10 同款保护：若有更新的启动排队，让那次启动自己决定去留。
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }

            null -> {
                // 兜底：intent 为 null 时根据设置决定是否启动
                if (!startForegroundCompat()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    val settings = settingsStore.settingsFlowRaw.first()
                    if (settings.webServerEnabled) {
                        // 同样先发起 start（同步清旧 error）再挂观察器，避免用上一次的
                        // error 误判本次启动失败。
                        webServerManager.start(
                            port = settings.webServerPort,
                            localhostOnly = settings.webServerLocalhostOnly
                        )
                        startObservingState()
                    } else {
                        stopSelf(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildStartingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildStartingNotification())
            }
            true
        } catch (e: Exception) {
            // 部分 OEM ROM (如 realme UI/ColorOS) 会在系统侧拒绝 FGS 类型权限，
            // 即使 Manifest 已声明 FOREGROUND_SERVICE_SPECIAL_USE 也会抛 SecurityException
            Log.e(TAG, "Failed to start foreground service", e)
            webServerManager.reportError("Failed to start foreground service: ${e.message}")
            false
        }
    }

    private fun startObservingState() {
        if (stateObserverJob != null) return
        stateObserverJob = serviceScope.launch {
            var sawRunning = false
            webServerManager.state.collect { state ->
                if (state.isRunning) {
                    sawRunning = true
                    // 取不到 WiFi IP 时不要把 localhost 冒充成局域网地址（见 SettingWebPage 同款处理）：
                    // 依次退到 mDNS 主机名，都没有就明确提示不可用。
                    val text = when {
                        state.localhostOnly -> "http://localhost:${state.port}"
                        state.address != null -> "http://${state.address}:${state.port}"
                        state.hostname != null -> "http://${state.hostname}:${state.port}"
                        else -> getString(R.string.setting_page_web_server_lan_unavailable)
                    }
                    updateNotification(buildRunningNotification(text))
                    return@collect
                }

                // 旧实现只在 wasRunning=true 时收尾 ⇒ 启动失败（端口占用/空口令/绑定异常）
                // 会让 FGS 永久驻留。判据见 shouldReleaseForegroundAfterState 的 kdoc。
                if (!shouldReleaseForegroundAfterState(
                        sawRunning = sawRunning,
                        isLoading = state.isLoading,
                        isRunning = state.isRunning,
                        hasError = state.error != null,
                    )
                ) {
                    return@collect
                }
                // 启停是异步的：这次看到的 error / stopped 可能只是「已被更新的 start 取代」的
                // 中间态（旧 stop 收尾、上一次失败残留）。给一次短暂宽限后按**最新**状态重新
                // 判定，避免 stopSelf 连正在排队/进行的新 attempt 一起停掉。
                delay(RELEASE_GRACE_MS)
                val latest = webServerManager.state.value
                if (!shouldReleaseForegroundAfterState(
                        sawRunning = sawRunning,
                        isLoading = latest.isLoading,
                        isRunning = latest.isRunning,
                        hasError = latest.error != null,
                    )
                ) {
                    return@collect
                }
                latest.error?.let { Log.w(TAG, "Releasing foreground service: $it") }
                stopForeground(STOP_FOREGROUND_REMOVE)
                // 观察器只在 `onStartCommand` 跑过之后才存在 ⇒ `latestStartId` 必然 > 0。
                // 真为 0 属状态异常，此时**宁可不停**（留给下一次 STOP 或系统回收），
                // 也不退回裸 `stopSelf()` —— 那正是本条守卫要禁的崩溃写法。
                val id = latestStartId
                if (id > 0) stopSelf(id)
            }
        }
    }

    private fun updateNotification(notification: android.app.Notification) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildLaunchPendingIntent() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, app.androidrik.rikkahub.RouteActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildStartingNotification() = NotificationCompat.Builder(this, WEB_SERVER_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.notification_channel_web_server))
        .setContentText(getString(R.string.notification_web_server_starting))
        .setContentIntent(buildLaunchPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun buildRunningNotification(url: String): android.app.Notification {
        val stopIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, WEB_SERVER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_web_server_running))
            .setContentText(url)
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, getString(R.string.notification_web_server_stop), stopPendingIntent)
            .build()
    }
}
