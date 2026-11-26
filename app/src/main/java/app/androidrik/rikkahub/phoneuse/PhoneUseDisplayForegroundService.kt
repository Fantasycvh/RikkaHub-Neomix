package app.androidrik.rikkahub.phoneuse

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.androidrik.rikkahub.PHONE_USE_DISPLAY_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.R

private const val TAG = "PhoneUseDisplayFgs"

/**
 * Phone Use 虚拟屏前台保活服务（见 `docs/design/drafts/design-phoneuse-display-keepalive.md`）。
 *
 * - 跟随虚拟屏客观生命周期自动启停（非用户偏好开关）；
 * - 严格遵守 `startForegroundService()` + `stopSelf(startId)` 契约，避免快速启停竞态引发
 *   `ForegroundServiceDidNotStartInTimeException`；
 * - 使用 `START_NOT_STICKY`：进程死亡后 Display 已随 owner binder 消亡，不重整防孤儿通知；
 * - 不挂 WifiLock（虚拟屏走 binder + ImageReader，不经网络）。
 */
class PhoneUseDisplayForegroundService : Service() {

    companion object {
        const val ACTION_START = "app.androidrik.rikkahub.action.PHONE_USE_DISPLAY_START"
        const val ACTION_STOP = "app.androidrik.rikkahub.action.PHONE_USE_DISPLAY_STOP"

        // 与 WebServerService(2001) / KeepAliveService(2002) / MediaCreation(2003) / Backup(2004) 错开
        const val NOTIFICATION_ID = 2005
        const val CLOSED_NOTIFICATION_ID = 2006

        fun start(context: Context) {
            val app = context.applicationContext
            // 重新启动虚拟屏时撤掉上一次异常关闭的残余通知
            runCatching { NotificationManagerCompat.from(app).cancel(CLOSED_NOTIFICATION_ID) }
            val intent = Intent(app, PhoneUseDisplayForegroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(app, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start PhoneUseDisplayForegroundService", e)
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, PhoneUseDisplayForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                app.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stop via intent failed, fallback to stopService", e)
                runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
                runCatching { app.stopService(Intent(app, PhoneUseDisplayForegroundService::class.java)) }
            }
        }

        /** 虚拟屏异常中断（典型：Shizuku 进程死亡）时发一次性可取消通知，提示用户原因。 */
        internal fun notifyClosedUnexpectedly(context: Context, reason: PhoneUseDisplayCloseReason) {
            if (!reason.shouldNotifyUser()) return
            val app = context.applicationContext
            val bodyRes = when (reason) {
                PhoneUseDisplayCloseReason.SHIZUKU_DIED,
                PhoneUseDisplayCloseReason.SERVICE_DISCONNECTED,
                -> R.string.notification_phone_use_display_closed_shizuku
                PhoneUseDisplayCloseReason.DISPLAY_REMOVED -> R.string.notification_phone_use_display_closed_removed
                else -> return
            }
            val notification = NotificationCompat.Builder(app, PHONE_USE_DISPLAY_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.small_icon)
                .setContentTitle(app.getString(R.string.notification_phone_use_display_closed_title))
                .setContentText(app.getString(bodyRes))
                .setStyle(NotificationCompat.BigTextStyle().bigText(app.getString(bodyRes)))
                .setContentIntent(buildLaunchPendingIntent(app))
                .setAutoCancel(true)
                .build()
            runCatching {
                NotificationManagerCompat.from(app).notify(CLOSED_NOTIFICATION_ID, notification)
            }.onFailure {
                Log.w(TAG, "notifyClosedUnexpectedly skipped/failed", it)
            }
        }

        private fun buildLaunchPendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, app.androidrik.rikkahub.RouteActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PhoneUseVirtualDisplays.current.value?.close(PhoneUseDisplayCloseReason.USER_STOP)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // startForegroundService() 必须先履约一次 startForeground() 再停，防快速启停竞态崩溃
        val started = startForegroundCompat()
        if (!started || PhoneUseVirtualDisplays.current.value == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PhoneUseVirtualDisplays.current.value != null) {
            startForegroundCompat()
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundCompat(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to promote PhoneUseDisplayForegroundService to foreground", e)
        false
    }

    private fun buildStopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        NOTIFICATION_ID + 1,
        Intent(this, PhoneUseDisplayForegroundService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, PHONE_USE_DISPLAY_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_phone_use_display_running))
            .setContentText(getString(R.string.notification_phone_use_display_desc))
            .setContentIntent(buildLaunchPendingIntent(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.notification_phone_use_display_stop), buildStopPendingIntent())
            .build()
}
