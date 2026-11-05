package app.androidrik.rikkahub.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.androidrik.rikkahub.data.sync.BackupRunCoordinator
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "BackupFgs"

/**
 * 备份/恢复任务的前台服务载体：任务本身由 [BackupRunCoordinator] 持有并挂在应用级作用域上，
 * 本服务只负责把进程留在前台，让用户离开备份页或切到后台后任务继续（通知栏可看进度、可取消）。
 *
 * 只借用通知展示与进程优先级；任务状态、进度、取消都由 coordinator 单点管理，
 * 服务被系统回收也不会让任务逻辑丢状态（进程还在就继续跑）。
 */
class BackupForegroundService : Service(), KoinComponent {
    companion object {
        const val NOTIFICATION_ID = 2004

        private const val ACTION_START = "app.androidrik.rikkahub.action.BACKUP_FGS_START"
        private const val ACTION_RELEASE = "app.androidrik.rikkahub.action.BACKUP_FGS_RELEASE"
        private const val ACTION_CANCEL = "app.androidrik.rikkahub.action.BACKUP_FGS_CANCEL"
        private const val EXTRA_NOTIFICATION = "notification"

        fun cancelIntent(context: Context): Intent =
            Intent(context, BackupForegroundService::class.java).setAction(ACTION_CANCEL)

        /**
         * 把备份任务挂到前台。失败（后台启动限制、通知权限被极端限制等）不阻塞任务本身：
         * 进程还活着就继续跑，只是没有前台优先级与通知。
         *
         * @return true = 前台服务已请求启动
         */
        fun acquire(context: Context, notification: Notification): Boolean = runCatching {
            val intent = Intent(context, BackupForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_NOTIFICATION, notification)
            }
            ContextCompat.startForegroundService(context, intent)
            true
        }.onFailure {
            Log.e(TAG, "Unable to start backup foreground service", it)
        }.getOrDefault(false)

        /** 任务结束：脱离前台但保留通知，由 coordinator 改成最终结果通知。 */
        fun release(context: Context) {
            runCatching {
                context.startService(Intent(context, BackupForegroundService::class.java).setAction(ACTION_RELEASE))
            }.onFailure {
                Log.e(TAG, "Unable to release backup foreground service", it)
            }
        }
    }

    private val coordinator: BackupRunCoordinator by inject()
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_RELEASE -> releaseService()
            ACTION_CANCEL -> {
                runCatching { coordinator.cancel() }
                    .onFailure { Log.e(TAG, "Unable to cancel backup task", it) }
            }
            else -> releaseService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // 备份是长任务，前台服务超时只停止前台通知；任务本身仍由 coordinator 继续。
        Log.w(TAG, "Foreground service timed out (type=$fgsType)")
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun start(intent: Intent) {
        @Suppress("DEPRECATION")
        val notification = intent.getParcelableExtra(EXTRA_NOTIFICATION) as? Notification
            ?: return releaseService()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (failure: Exception) {
            Log.e(TAG, "Failed to enter foreground", failure)
            stopSelf()
        }
    }

    private fun releaseService() {
        if (isForeground) {
            // DETACH：通知留在通知栏，coordinator 随后把它更新成任务结果
            stopForeground(STOP_FOREGROUND_DETACH)
            isForeground = false
        }
        stopSelf()
    }
}
