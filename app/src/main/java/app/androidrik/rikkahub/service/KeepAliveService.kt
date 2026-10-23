package app.androidrik.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.androidrik.rikkahub.KEEP_ALIVE_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.R

private const val TAG = "KeepAliveService"

/**
 * 前台保活服务（移植自 rikaka-hub 的标准方案）：
 *
 * 三重策略——①前台 Service + 常驻通知提升进程优先级；②START_STICKY 被系统杀死后自动重启；
 * ③onTaskRemoved 中重新断言前台状态（配合 Manifest 的 stopWithTask="false"，
 * 用户从最近任务划掉后服务不销毁）。服务存续期间持有 WifiLock（Wi-Fi 高性能模式，
 * 借鉴 SoLab 保活四件套），避免 Doze 下 Wi-Fi 进入省电中断流式请求。
 *
 * 前置条件：通知权限（Android 13+，常驻通知必须可见）；建议加入电池优化白名单防 Doze 休眠
 * （见 [isIgnoringBatteryOptimizations] 与设置页引导）。
 *
 * 开关存储：SharedPreferences("rikkahub.preferences") 的 keep_alive 键，
 * 与其他设备本地行为开关共用同一偏好文件（不进 Settings 数据类，无需备份/同步）。
 */
class KeepAliveService : Service() {

    companion object {
        const val ACTION_START = "app.androidrik.rikkahub.action.KEEP_ALIVE_START"
        const val ACTION_STOP = "app.androidrik.rikkahub.action.KEEP_ALIVE_STOP"
        // WebServerService 用 2001，此处错开
        const val NOTIFICATION_ID = 2002
        const val PREFS_NAME = "rikkahub.preferences"
        const val PREF_KEY = "keep_alive"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEY, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEY, enabled).apply()
        }

        /** Android 13+ 需要通知权限：前台服务的常驻通知必须能显示。 */
        fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

        /** 已加入电池优化白名单（防 Doze 深度休眠挂起）。 */
        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }

        /** 保活完整生效的前置条件（通知权限 + 电池白名单）。 */
        fun canKeepAlive(context: Context): Boolean =
            hasNotificationPermission(context) && isIgnoringBatteryOptimizations(context)

        /** 引导用户加入电池优化白名单的系统弹窗 Intent。 */
        fun batteryOptimizationIntent(context: Context): Intent =
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                // 设置页操作时应用在前台，startService 合法；
                // 服务未运行时短暂创建再停止，无副作用
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stop via intent failed, fallback to stopService", e)
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
                context.stopService(Intent(context, KeepAliveService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // 服务存续期间持有的 Wi-Fi 高性能锁；持有失败不阻塞保活主路径
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ 三处退出都必须用 `stopSelf(startId)`，**不能用裸 `stopSelf()`**。
        //
        // 同源缺陷在上游 `72747fc9e`（2026-10-16）被修过，但触发它的那个类
        // （`ChatGenerationForegroundService`）本仓没有 —— 本仓的对应物就是这里。
        // 竞态：`start()` 走的是 `startForegroundService()`（见上方 :86），系统要求这次启动
        // **必须调一次 `startForeground()`**；若此刻一个**更早**的 stop 请求用裸 `stopSelf()`
        // 把服务停掉，那次尚未履约的启动就永远没机会调 `startForeground()` ⇒ 系统抛
        // `ForegroundServiceDidNotStartInTimeException` **崩溃**。
        // 「更早的 stop 撞上更新的 start」在本服务完全可达：用户在设置里关掉又立刻打开保活、
        // 或 `stop()` 的 `startService(ACTION_STOP)` 与另一处的 `start()` 交错。
        //
        // `stopSelf(startId)` 只在**自己就是最新一次启动**时才停 —— 有更新的启动在排队时就让位，
        // 由那次启动自己决定去留（它也会检查 `isEnabled`，所以不会因此漏停）。
        if (intent?.action == ACTION_STOP) {
            releaseWifiLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // START_STICKY 重启时 intent 为 null；若开关已被关闭则顺势退出
        if (!isEnabled(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!startForegroundCompat()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWifiLock()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // stopWithTask="false" 下服务本身不随任务销毁，这里重新断言前台状态兜底
        if (isEnabled(this)) {
            startForegroundCompat()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWifiLock()
        super.onDestroy()
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "rikkahub:keepalive"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (e: Exception) {
            // 个别 ROM 限制 WifiLock 时不影响前台服务本身
            Log.w(TAG, "acquire WifiLock failed", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w(TAG, "release WifiLock failed", e)
        } finally {
            wifiLock = null
        }
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            true
        } catch (e: Exception) {
            // OEM ROM 可能在系统侧拒绝 FGS 类型权限（同 WebServerService 的处理）
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun buildLaunchPendingIntent() = PendingIntent.getActivity(
        this,
        0,
        // A package lookup can return null (e.g. immediately after an applicationId change).
        // The manifest activity is known and must not depend on launcher resolution to promote FGS.
        Intent(this, app.androidrik.rikkahub.RouteActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildStopPendingIntent() = PendingIntent.getService(
        this,
        0,
        Intent(this, KeepAliveService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, KEEP_ALIVE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_keep_alive_running))
            .setContentText(getString(R.string.setting_keep_alive_desc))
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notification_keep_alive_stop), buildStopPendingIntent())
            .build()
}
