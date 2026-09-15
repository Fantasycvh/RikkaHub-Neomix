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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.WEB_SERVER_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.web.WebServerManager
import org.koin.android.ext.android.inject

private const val TAG = "WebServerService"

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val localhostOnly = intent.getBooleanExtra(EXTRA_LOCALHOST_ONLY, false)
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startObservingState()
                webServerManager.start(port = port, localhostOnly = localhostOnly)
            }

            ACTION_STOP -> {
                webServerManager.stop()
                serviceScope.launch {
                    settingsStore.update { it.copy(webServerEnabled = false) }
                }
                // 不立即 stopSelf，等状态流检测到停止后再结束
            }

            null -> {
                // 兜底：intent 为 null 时根据设置决定是否启动
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    val settings = settingsStore.settingsFlowRaw.first()
                    if (settings.webServerEnabled) {
                        startObservingState()
                        webServerManager.start(
                            port = settings.webServerPort,
                            localhostOnly = settings.webServerLocalhostOnly
                        )
                    } else {
                        stopSelf()
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
            var wasRunning = false
            webServerManager.state.collect { state ->
                when {
                    state.isRunning -> {
                        wasRunning = true
                        // 取不到 WiFi IP 时不要把 localhost 冒充成局域网地址（见 SettingWebPage 同款处理）：
                        // 依次退到 mDNS 主机名，都没有就明确提示不可用。
                        val text = when {
                            state.localhostOnly -> "http://localhost:${state.port}"
                            state.address != null -> "http://${state.address}:${state.port}"
                            state.hostname != null -> "http://${state.hostname}:${state.port}"
                            else -> getString(R.string.setting_page_web_server_lan_unavailable)
                        }
                        updateNotification(buildRunningNotification(text))
                    }

                    wasRunning && !state.isRunning && !state.isLoading -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
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
        packageManager.getLaunchIntentForPackage(packageName),
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
