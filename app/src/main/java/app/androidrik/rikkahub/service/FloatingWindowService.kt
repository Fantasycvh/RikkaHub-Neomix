package app.androidrik.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import androidx.core.content.edit
import androidx.core.net.toUri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.RouteActivity
import app.androidrik.rikkahub.service.floating.FloatingWindowGeometry
import app.androidrik.rikkahub.service.floating.OverlayWindowController
import app.androidrik.rikkahub.service.floating.WindowGeometry
import app.androidrik.rikkahub.ui.components.floating.FloatingWindowPane
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮小窗（原型）的前台服务：持有系统悬浮窗的生命周期。
 *
 * 为什么必须是前台服务：overlay 窗口本身不限制进程存活，但**聊天生成是在后台跑的**，
 * 没有前台服务时切到别的 app 后进程可能被回收，小窗会凭空消失（比不做还吓人）。
 *
 * 角色边界：
 * - 本服务只管「窗口在那」，**不持有会话/不驱动生成**——生成归 `ChatService`（AppScope），
 *   所以关掉小窗不会打断正在生成的回复（与「后台生成」的既有语义一致）；
 * - 窗口几何与手势在 [OverlayWindowController] + `FloatingWindowPane`；
 * - 无「显示在其他应用上层」权限时**直接拒绝启动**，不做半吊子降级（那只会得到"点了没反应"）。
 */
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"

        private const val CHANNEL_ID = "floating_window"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_STOP = "app.androidrik.rikkahub.action.STOP_FLOATING_WINDOW"

        private const val PREFS = "floating_window_prefs"
        private const val KEY_X = "geometry_x"
        private const val KEY_Y = "geometry_y"
        private const val KEY_WIDTH = "geometry_width"
        private const val KEY_HEIGHT = "geometry_height"

        /** 是否已获得「显示在其他应用上层」权限。 */
        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /**
         * 系统授权页。**注意**：回来之后必须重新读 [canDrawOverlays]（有的 ROM 会延迟生效），
         * 调用方（设置页）在 ON_RESUME 时刷新。
         */
        fun overlayPermissionIntent(context: Context): Intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:${context.packageName}".toUri(),
        )

        /** 启动小窗。调用点必须在**前台**（Android 12+ 后台启动 FGS 会被系统拒绝）。 */
        fun start(context: Context) {
            if (!canDrawOverlays(context)) {
                Log.w(TAG, "start refused: SYSTEM_ALERT_WINDOW not granted")
                return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FloatingWindowService::class.java),
                )
            }.onFailure { Log.e(TAG, "startForegroundService failed", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FloatingWindowService::class.java))
            }.onFailure { Log.w(TAG, "stopService failed", it) }
        }

        private val _running = MutableStateFlow(false)

        /** 小窗是否在运行（设置页据此显示开关状态）。 */
        val running: StateFlow<Boolean> = _running.asStateFlow()
    }

    private lateinit var controller: OverlayWindowController
    private var composeView: ComposeView? = null
    private var composeOwner: ServiceComposeOwner? = null
    private var contentSet = false
    private var prefs: SharedPreferences? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val persistGeometry = Runnable { saveGeometry() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = OverlayWindowController(this)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!canDrawOverlays(this)) {
            // 权限在运行期间被撤销（用户去系统设置关掉）也会走到这里
            Log.w(TAG, "onStartCommand: overlay permission missing, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundCompat()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (showWindow()) {
            _running.value = true
        } else {
            stopSelf()
        }
        // 不自动重建：小窗消失必须由用户主动再次开启（overlay 弹窗不能悄悄回来）
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(persistGeometry)
        saveGeometry()
        // 先撤窗口再销毁 owner：反过来的话窗口上还挂着一份已 DESTROYED 的 composition
        if (controller.isShowing) controller.hide()
        composeOwner?.onDestroy()
        composeOwner = null
        composeView = null
        contentSet = false
        _running.value = false
        super.onDestroy()
    }

    private fun showWindow(): Boolean {
        val screen = controller.screenSize()
        if (screen.width <= 0 || screen.height <= 0) {
            Log.w(TAG, "showWindow: invalid screen size $screen")
            return false
        }
        val view = composeView ?: ComposeView(this).also { composeView = it }
        val owner = composeOwner ?: ServiceComposeOwner().also {
            composeOwner = it
            it.onCreate()
            it.onResume()
        }
        // ComposeView 挂在 Service 上：这三个 owner 不设，ComposeView 一 attach 就会抛
        // 「ViewTreeLifecycleOwner not found」。这是 overlay 里跑 Compose 的必要样板。
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)

        if (!contentSet) {
            contentSet = true
            view.setContent {
                RikkahubTheme {
                    FloatingWindowPane(
                        geometry = controller.geometry,
                        screenSize = controller.screenSize(),
                        onGeometryChange = {
                            controller.update(it)
                            // 拖动期间会高频回调：节流后再落盘，别让 SharedPreferences 跟着抖
                            mainHandler.removeCallbacks(persistGeometry)
                            mainHandler.postDelayed(persistGeometry, 400)
                        },
                        onClose = { stopSelf() },
                        onOpenApp = { openApp() },
                    )
                }
            }
        }

        val initial = loadGeometry()
            ?: FloatingWindowGeometry.defaultGeometry(
                screenWidth = screen.width,
                screenHeight = screen.height,
                density = resources.displayMetrics.density,
            )
        return controller.show(view, initial)
    }

    /** 「打开应用」按钮：回主界面（小窗保留，用户自己关）。 */
    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, RouteActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            )
        }.onFailure { Log.w(TAG, "openApp failed", it) }
    }

    // ---- 几何持久化（设备本地，不进设置备份） ----

    private fun loadGeometry(): WindowGeometry? {
        val p = prefs ?: return null
        if (!p.contains(KEY_WIDTH) || !p.contains(KEY_HEIGHT)) return null
        val width = p.getInt(KEY_WIDTH, 0)
        val height = p.getInt(KEY_HEIGHT, 0)
        if (width <= 0 || height <= 0) return null
        val geometry = WindowGeometry(
            x = p.getInt(KEY_X, 0),
            y = p.getInt(KEY_Y, 0),
            width = width,
            height = height,
        )
        // 启动即最大化没有意义（用户看不到「还原」该回到哪个尺寸），丢弃这种记录
        val screen = controller.screenSize()
        if (FloatingWindowGeometry.isMaximized(geometry, screen.width, screen.height)) return null
        return geometry
    }

    private fun saveGeometry() {
        val p = prefs ?: return
        if (!controller.isShowing) return
        val g = controller.geometry
        // 最大化状态不落盘：否则下次启动直接是满屏且「还原」无参照尺寸
        val screen = controller.screenSize()
        if (FloatingWindowGeometry.isMaximized(g, screen.width, screen.height)) return
        p.edit {
            putInt(KEY_X, g.x)
            putInt(KEY_Y, g.y)
            putInt(KEY_WIDTH, g.width)
            putInt(KEY_HEIGHT, g.height)
        }
    }

    // ---- 通知 ----

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_window_notification_channel),
                // LOW：常驻但不响铃；小窗是用户自己开的，不该每次都叮一下
                NotificationManager.IMPORTANCE_LOW,
            )
        )
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
        // OEM ROM 可能拒绝该 FGS 类型（与 KeepAliveService 同款处理）
        Log.e(TAG, "startForeground failed", e)
        false
    }

    private fun buildNotification(): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.floating_window_notification_title))
            .setContentText(getString(R.string.floating_window_notification_text))
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.floating_window_notification_stop), buildStopPendingIntent())
            .build()

    private fun buildLaunchPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        // 不能 getLaunchIntentForPackage：debug 变体（applicationId 带 .debug）可能返回 null，
        // PendingIntent.getActivity(…, null, …) 会直接 NPE 把 startForeground 带崩。
        Intent(this, RouteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildStopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, FloatingWindowService::class.java).apply { action = ACTION_STOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/**
 * Service 里承载 Compose 所需的最小 owner 组合。
 *
 * ComposeView 要求视图树上有 Lifecycle / ViewModelStore / SavedStateRegistry 三个 owner；
 * Activity 里由 ComponentActivity 提供，Service 里必须自己实现。**非官方路径**，
 * 升级 compose 时要回来回归（已在设计稿 §11 记为技术债）。
 */
private class ServiceComposeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
