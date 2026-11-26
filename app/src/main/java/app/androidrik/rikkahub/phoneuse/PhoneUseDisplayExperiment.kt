package app.androidrik.rikkahub.phoneuse

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val SHIZUKU_PERMISSION_REQUEST = 314
private const val DEVICE_STATE_CHECK_INTERVAL_MS = 500L
// 每 4 个 500ms tick（2 秒）主动 ping Shizuku 兜底，防止 OnBinderDeadListener 延迟或丢失
private const val SHIZUKU_PING_EVERY_TICKS = 4
private const val PREVIEW_FRAME_INTERVAL_MS = 100L
private val startMutex = Mutex()
private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

internal suspend fun startPhoneUseDisplay(context: Context) = startMutex.withLock {
    if (PhoneUseVirtualDisplays.current.value != null) return@withLock
    check(Build.VERSION.SDK_INT >= 35 &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
        "This experiment requires Android 15 or later with secondary activity displays."
    }
    check(Shizuku.pingBinder()) { "Start Shizuku first." }
    val factory = PhoneUseManager.instance.controller.value as? PhoneUseDisplayControllerFactory
        ?: error("Enable the Neomix Phone Use accessibility service first.")
    check(withTimeoutOrNull(45_000) { requestShizukuPermission() } != null) {
        "No permission response from Shizuku. Check its authorization screen and try again."
    }
    val uid = Shizuku.getUid()
    check(uid == SHELL_UID || uid == ROOT_UID) { "Shizuku must be running in ADB or root mode." }

    val display = PhoneUseDisplayExperiment(context.applicationContext)
    try {
        check(withTimeoutOrNull(12_000) { display.start(factory) } != null) {
            "Timed out starting the Shizuku display service."
        }
    } catch (error: Throwable) {
        display.close(PhoneUseDisplayCloseReason.STARTUP_FAILED)
        throw error
    }
}

private suspend fun requestShizukuPermission() {
    if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
    val granted = suspendCancellableCoroutine { continuation ->
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (continuation.isActive) continuation.resume(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        continuation.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
        try {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
        } catch (error: Exception) {
            Shizuku.removeRequestPermissionResultListener(listener)
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
    check(granted) { "Grant Neomix access from Shizuku to start the virtual display." }
}

@RequiresApi(35)
private class PhoneUseDisplayExperiment(private val context: Context) : PhoneUseVirtualDisplay {
    private val closed = AtomicBoolean(false)
    private val ownerToken = Binder()
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val worker = HandlerThread("PhoneUseDisplayFrames")
    private val args = Shizuku.UserServiceArgs(ComponentName(context, PhoneUseDisplayService::class.java))
        .processNameSuffix("phone_use_display")
        .tag("phone-use-${UUID.randomUUID()}")
        .daemon(false)
        .version(2)
    private var reader: ImageReader? = null
    private var connection: ServiceConnection? = null
    private var deviceMonitor: Job? = null
    @Volatile private var service: IPhoneUseDisplayService? = null
    @Volatile private var displayId = Display.INVALID_DISPLAY
    private val _previewFrames = MutableStateFlow<ImageBitmap?>(null)
    override val previewFrames: StateFlow<ImageBitmap?> get() = _previewFrames
    @Volatile private var previewActive = false
    // 只在主线程增减（Compose DisposableEffect 配对调用）
    private var previewClients = 0
    // 只在帧回调线程（worker looper）上访问
    private var lastPreviewFrameAt = 0L
    private val previewCopyBuffer =
        ByteBuffer.allocateDirect(PHONE_USE_DISPLAY_WIDTH * PHONE_USE_DISPLAY_HEIGHT * 4)
    override val id: Int get() = displayId
    override lateinit var controller: PhoneController
        private set
    override var apps: List<PhoneApp> = emptyList()
        private set

    private val serverDied = Shizuku.OnBinderDeadListener { close(PhoneUseDisplayCloseReason.SHIZUKU_DIED) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == id) close(PhoneUseDisplayCloseReason.DISPLAY_REMOVED)
        }
    }
    suspend fun start(factory: PhoneUseDisplayControllerFactory) = withContext(Dispatchers.Main.immediate) {
        checkUnlocked()
        Shizuku.addBinderDeadListener(serverDied)
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        val remote = bind()
        service = remote
        check(!closed.get()) { "The Phone Use display stopped during startup." }
        worker.start()
        val frames = ImageReader.newInstance(PHONE_USE_DISPLAY_WIDTH, PHONE_USE_DISPLAY_HEIGHT, PixelFormat.RGBA_8888, 2)
        reader = frames
        // The surface keeps the virtual display rendering; while the preview is off its
        // frames are discarded immediately. Model images still use the accessibility
        // screenshot path so its secure-window handling applies to what the model sees.
        frames.setOnImageAvailableListener({ source ->
            if (!previewActive) {
                runCatching { source.acquireLatestImage()?.close() }
                return@setOnImageAvailableListener
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastPreviewFrameAt < PREVIEW_FRAME_INTERVAL_MS) {
                runCatching { source.acquireLatestImage()?.close() }
                return@setOnImageAvailableListener
            }
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            image.use { frame ->
                val bitmap = runCatching { frame.toPreviewBitmap() }.getOrNull() ?: return@use
                lastPreviewFrameAt = now
                _previewFrames.value = bitmap
            }
        }, Handler(worker.looper))
        withContext(Dispatchers.IO) {
            apps = remote.listLaunchers().mapNotNull { app ->
                val packageName = app.getString("package") ?: return@mapNotNull null
                PhoneApp(packageName, app.getString("label") ?: packageName)
            }.filterNot { it.packageName == context.packageName }.sortedBy { it.label.lowercase() }
            displayId = remote.create(frames.surface, ownerToken)
        }
        checkUnlocked()
        check(!closed.get() && displayId > Display.DEFAULT_DISPLAY && displayManager.getDisplay(displayId)?.isValid == true) {
            "The Phone Use display is unavailable."
        }
        check(PhoneUseManager.instance.controller.value === factory) { "The accessibility service disconnected." }
        controller = factory.forDisplay(this@PhoneUseDisplayExperiment)
        check(PhoneUseVirtualDisplays.attach(this@PhoneUseDisplayExperiment)) { "A Phone Use virtual display is already active." }
        PhoneUseDisplayOverlay.show(context)
        PhoneUseDisplayForegroundService.start(context)
        // Check the primary display directly because some ROMs delay the screen-off broadcast,
        // and ping Shizuku every 2s as a watchdog fallback if OnBinderDeadListener is delayed.
        deviceMonitor = cleanupScope.launch {
            var ticks = 0
            while (true) {
                if (!canUseDisplay()) {
                    close(PhoneUseDisplayCloseReason.DEVICE_LOCKED)
                    return@launch
                }
                if (ticks % SHIZUKU_PING_EVERY_TICKS == 0 && !runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                    close(PhoneUseDisplayCloseReason.SHIZUKU_DIED)
                    return@launch
                }
                ticks++
                delay(DEVICE_STATE_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun checkUnlocked() {
        check(canUseDisplay()) {
            "Unlock the phone to use the virtual display."
        }
    }

    private fun canUseDisplay(): Boolean {
        val primaryState = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state
        return powerManager.isInteractive && !keyguardManager.isKeyguardLocked &&
            primaryState != Display.STATE_OFF && primaryState != Display.STATE_DOZE &&
            primaryState != Display.STATE_DOZE_SUSPEND
    }

    private suspend fun bind(): IPhoneUseDisplayService = suspendCancellableCoroutine { continuation ->
        val client = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (continuation.isActive) continuation.resume(IPhoneUseDisplayService.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                close(PhoneUseDisplayCloseReason.SERVICE_DISCONNECTED)
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Shizuku disconnected."))
            }
        }
        connection = client
        try {
            Shizuku.bindUserService(args, client)
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    override fun openApp(packageName: String): Boolean = remote().launch(packageName)
    override fun openUrl(packageName: String, url: String): Boolean = remote().openUrl(packageName, url)
    override fun back(): Boolean = remote().back()

    override fun listRecent(): List<PhoneApp> = remote().listRecentTasks().mapNotNull { task ->
        task.getString("package")?.let { PhoneApp(it, task.getString("label") ?: it) }
    }

    override fun setPreviewActive(active: Boolean) {
        // 多个观察端（悬浮窗 / 面板预览）各自配对调用，引用计数归零才真正停帧
        previewClients = (previewClients + if (active) 1 else -1).coerceAtLeast(0)
        previewActive = previewClients > 0
        if (!previewActive) _previewFrames.value = null
    }

    // ImageReader 是虚拟屏 Surface 的消费者：拿到的是绕过 FLAG_SECURE 的原始帧，
    // 预览侧必须自己拦黑名单应用（见 PhoneUseExperimentPreview）。
    private fun Image.toPreviewBitmap(): ImageBitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val bitmap = Bitmap.createBitmap(PHONE_USE_DISPLAY_WIDTH, PHONE_USE_DISPLAY_HEIGHT, Bitmap.Config.ARGB_8888)
        val rowStride = plane.rowStride
        if (rowStride == PHONE_USE_DISPLAY_WIDTH * 4) {
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val dst = previewCopyBuffer
            dst.clear()
            for (row in 0 until PHONE_USE_DISPLAY_HEIGHT) {
                buffer.clear()
                buffer.position(row * rowStride)
                buffer.limit(row * rowStride + PHONE_USE_DISPLAY_WIDTH * 4)
                dst.put(buffer)
            }
            dst.rewind()
            bitmap.copyPixelsFromBuffer(dst)
        }
        return bitmap.asImageBitmap()
    }

    private fun remote(): IPhoneUseDisplayService {
        checkUnlocked()
        check(!closed.get() && Shizuku.pingBinder()) { "The Phone Use display disconnected." }
        return checkNotNull(service)
    }

    override fun close(reason: PhoneUseDisplayCloseReason) {
        if (!closed.compareAndSet(false, true)) return
        // FGS 停止放最前，不依赖后续任何 binder 调用
        PhoneUseDisplayForegroundService.stop(context)
        if (reason.shouldNotifyUser()) {
            PhoneUseDisplayForegroundService.notifyClosedUnexpectedly(context, reason)
        }
        previewClients = 0
        previewActive = false
        _previewFrames.value = null
        PhoneUseDisplayOverlay.hide()
        PhoneUseVirtualDisplays.detach(this)
        cleanupScope.launch {
            deviceMonitor?.cancel()
            Shizuku.removeBinderDeadListener(serverDied)
            displayManager.unregisterDisplayListener(displayListener)
            withContext(Dispatchers.IO) {
                runCatching { service?.release() }
                connection?.let { runCatching { Shizuku.unbindUserService(args, it, true) } }
            }
            reader?.close()
            worker.quitSafely()
        }
    }
}
