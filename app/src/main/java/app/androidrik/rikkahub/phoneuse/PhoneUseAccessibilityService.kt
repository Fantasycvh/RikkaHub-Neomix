package app.androidrik.rikkahub.phoneuse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PhoneUseService"
private const val MAX_ELEMENTS = 150
private const val MAX_LABEL_CHARS = 80

// 没有自己文字的可点击容器，用里面的前几段文字当名字
private const val MAX_LABEL_PARTS = 3
private const val SCREENSHOT_RETRY_DELAY_MS = 1000L
private const val JPEG_QUALITY = 80

/**
 * Phone Use 的执行端：用户在系统无障碍设置里开启后，把自己登记到 [PhoneUseManager]。
 * 不监听无障碍事件，只在工具调用时读取窗口、派发手势。
 *
 * 隐私边界：只读应用窗口，不读通知栏等系统窗口；密码框不返回文字。
 */
class PhoneUseAccessibilityService : AccessibilityService() {
    // 静态单例：服务可能早于 startKoin 绑定，不能走 Koin 解析
    private val manager: PhoneUseManager get() = PhoneUseManager.instance
    private val primaryController = DisplayController(this, null)

    override fun onServiceConnected() {
        Log.i(TAG, "onServiceConnected")
        manager.attach(primaryController)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        manager.detach(primaryController)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        manager.detach(primaryController)
        primaryController.dismissIndicator()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}
}

private class DisplayController(
    private val service: PhoneUseAccessibilityService,
    private val virtual: PhoneUseVirtualDisplay?,
) : PhoneController, PhoneUseDisplayControllerFactory {
    override val isVirtual: Boolean get() = virtual != null
    private val displayId: Int get() = virtual?.id ?: Display.DEFAULT_DISPLAY
    private val indicator by lazy { GestureIndicator(service) }

    fun dismissIndicator() = indicator.dismiss()

    // 元素编号只指向这个显示屏的最近一次快照。
    @Volatile
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun forDisplay(display: PhoneUseVirtualDisplay): PhoneController = DisplayController(service, display)

    private fun requireVirtualDisplay(): Display {
        PhoneUseVirtualDisplays.checkTarget(checkNotNull(virtual))
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        check(display != null && display.isValid && displayId != Display.DEFAULT_DISPLAY) {
            "The Phone Use virtual display is no longer available."
        }
        return display
    }

    private fun targetWindows(): List<AccessibilityWindowInfo> {
        if (!isVirtual) return service.windows
        requireVirtualDisplay()
        service.clearCache()
        val windows = service.windowsOnAllDisplays[displayId].orEmpty()
        check(windows.none { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == service.packageName }) {
            "Neomix is on the Phone Use display. Switch apps with open_app."
        }
        return windows
    }

    private fun applicationWindows(): List<AccessibilityWindowInfo> = targetWindows().filter { window ->
        window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            (!isVirtual || window.root?.packageName?.toString()?.let { it != "com.android.systemui" } == true)
    }

    // ---- 读取屏幕 ----

    override fun visiblePackages(): Set<String> = targetRoots().packages()

    private fun List<AccessibilityNodeInfo>.packages(): Set<String> =
        mapNotNullTo(mutableSetOf()) { it.packageName?.toString() }

    override suspend fun captureScreen(): PhoneScreen = withContext(Dispatchers.Default) {
        val size = screenSize()
        val screenBounds = Rect(0, 0, size.x, size.y)
        val roots = targetRoots()
        val elements = mutableListOf<PhoneElement>()
        val collected = mutableListOf<AccessibilityNodeInfo>()
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, absorbed: Boolean) {
            if (elements.size >= MAX_ELEMENTS) {
                truncated = true
                return
            }
            var childrenAbsorbed = absorbed
            if (node.isVisibleToUser) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val interactive = node.isClickable || node.isLongClickable || node.isEditable ||
                    node.isScrollable || node.isCheckable
                var label = node.ownLabel()
                if (label.isEmpty() && node.isClickable) {
                    label = node.descendantLabels().joinToString(" ")
                    // 文字已经算进这个容器了，子节点里的纯文字不再单独列一遍
                    childrenAbsorbed = label.isNotEmpty()
                }
                val listed = interactive || (label.isNotEmpty() && !absorbed)
                if (listed && bounds.intersect(screenBounds)) {
                    elements += PhoneElement(
                        index = elements.size,
                        className = node.className?.toString()?.substringAfterLast('.') ?: "View",
                        label = label.clean(),
                        // 形如 com.example:id/btn_send，只留名字
                        resourceId = node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                        scrollable = node.isScrollable,
                        checked = if (node.isCheckable) node.isChecked else null,
                        focused = node.isEditable && node.isFocused,
                        password = node.isPassword,
                    )
                    collected += node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it, childrenAbsorbed) }
            }
        }
        roots.forEach { visit(it, absorbed = false) }
        nodes = collected

        val topPackage = roots.firstOrNull()?.packageName?.toString()
        PhoneScreen(
            packageName = topPackage,
            appLabel = topPackage?.let { name -> virtual?.apps?.firstOrNull { it.packageName == name }?.label ?: appLabel(name) },
            packages = roots.packages(),
            width = size.x,
            height = size.y,
            keyboardVisible = targetWindows().any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD },
            elements = elements,
            truncated = truncated,
        )
    }

    /**
     * 要读取的窗口根节点，最上层的在前。
     *
     * **只读应用窗口**：通知栏等系统窗口里可能有验证码、消息预览，默认不进模型上下文。
     * 状态栏、导航栏、输入法的按键也不列出来；本应用自己的窗口（悬浮窗）跳过。
     */
    private fun targetRoots(): List<AccessibilityNodeInfo> {
        val roots = applicationWindows().mapNotNull { it.root }
        // 个别系统拿不到窗口列表，退回当前活动窗口
        return (if (isVirtual) roots else roots.ifEmpty { listOfNotNull(service.rootInActiveWindow) })
            .filter { it.packageName != service.packageName }
    }

    private fun AccessibilityNodeInfo.ownLabel(): String {
        // 密码框的 text 可能是明文，也可能是掩码；一律不交给模型
        if (isPassword) return ""
        return text?.toString()?.takeIf { it.isNotBlank() }
            ?: contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: hintText?.toString()?.takeIf { it.isNotBlank() }
            ?: ""
    }

    private fun AccessibilityNodeInfo.descendantLabels(): List<String> {
        val labels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo) {
            for (i in 0 until node.childCount) {
                if (labels.size >= MAX_LABEL_PARTS) return
                val child = node.getChild(i) ?: continue
                // 可点击的子节点自己会单独列出
                if (child.isClickable) continue
                child.ownLabel().takeIf { it.isNotEmpty() }?.let(labels::add)
                collect(child)
            }
        }
        collect(this)
        return labels
    }

    private fun String.clean() = replace('\n', ' ').trim().let {
        if (it.length > MAX_LABEL_CHARS) it.take(MAX_LABEL_CHARS) + "…" else it
    }

    private fun screenSize(): Point {
        if (isVirtual) {
            val display = requireVirtualDisplay()
            return Point().also { display.getRealSize(it) }
        }
        val windowManager = service.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return Point(bounds.width(), bounds.height())
        }
        @Suppress("DEPRECATION")
        return Point().also { windowManager.defaultDisplay.getRealSize(it) }
    }

    private fun appLabel(packageName: String): String? = runCatching {
        service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    // ---- 截图 ----

    @RequiresApi(Build.VERSION_CODES.R)
    override suspend fun screenshot(maxSide: Int): ByteArray {
        val bitmap = try {
            takeScreenshot()
        } catch (e: ScreenshotIntervalException) {
            // 系统限制了两次截图的最小间隔
            delay(SCREENSHOT_RETRY_DELAY_MS)
            takeScreenshot()
        }
        return withContext(Dispatchers.Default) {
            val ratio = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (ratio < 1f) {
                bitmap.scale((bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt())
            } else {
                bitmap
            }
            if (scaled !== bitmap) bitmap.recycle()
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                .toByteArray()
            scaled.recycle()
            bytes
        }
    }

    private class ScreenshotIntervalException : IllegalStateException("Screenshot requested too frequently.")

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(): Bitmap {
        // Android 14+ 只截最上层应用窗口：通知栏、系统弹窗不进图
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val window = applicationWindows().firstOrNull()
                ?: error("No app window to capture.")
            val bitmap = awaitScreenshot { callback ->
                service.takeScreenshotOfWindow(window.id, service.mainExecutor, callback)
            }
            if (!isVirtual) return bitmap
            return withContext(Dispatchers.Default) {
                val size = screenSize()
                if (bitmap.width == size.x && bitmap.height == size.y) return@withContext bitmap
                // ColorOS can launch an activity in a floating window on the secondary display.
                // Keep screenshot coordinates in the same space as accessibility bounds and taps.
                val bounds = Rect().also(window::getBoundsInScreen)
                val fullFrame = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
                Canvas(fullFrame).drawBitmap(bitmap, null, bounds, null)
                bitmap.recycle()
                fullFrame
            }
        }
        // Android 11–13 只能整屏；通知栏展开时系统窗口会一起进图，直接拒绝
        check(targetWindows().none { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isFocused }) {
            "A system window (such as the notification shade) is open. Close it before taking a screenshot."
        }
        return awaitScreenshot { callback ->
            service.takeScreenshot(displayId, service.mainExecutor, callback)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun awaitScreenshot(
        capture: (AccessibilityService.TakeScreenshotCallback) -> Unit,
    ): Bitmap = suspendCancellableCoroutine { continuation ->
        capture(object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                // 硬件位图不能缩放和压缩，转成普通位图；包裹位图必须显式 recycle——
                // 底层 gralloc 分配（整屏 ARGB_8888 约 14MB）只能等 GC 兜底，
                // 而连续截图时 Java 堆几乎无压力，触发不了 GC，native 堆会堆积成 OOM
                val bitmap = screenshot.hardwareBuffer.use { buffer ->
                    val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped?.recycle()
                    copy
                }
                if (bitmap != null) {
                    if (continuation.isActive) {
                        continuation.resume(bitmap)
                    } else {
                        bitmap.recycle()
                    }
                } else {
                    continuation.resumeWithException(IllegalStateException("Screenshot failed."))
                }
            }

            override fun onFailure(errorCode: Int) {
                continuation.resumeWithException(screenshotError(errorCode))
            }
        })
    }

    private fun screenshotError(errorCode: Int): IllegalStateException = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> ScreenshotIntervalException()
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
            IllegalStateException("Screenshot blocked by a secure window (FLAG_SECURE).")
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            IllegalStateException("Screenshot failed: invalid display.")
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
            IllegalStateException("Screenshot failed: the app window went away.")
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            IllegalStateException("Screenshot failed: no accessibility access.")
        else -> IllegalStateException("Screenshot failed (code $errorCode).")
    }

    // ---- 操作 ----

    override suspend fun gesture(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean {
        // 坐标不能为负；点击也补一段到原地的线，保证路径不为空
        val path = Path().apply {
            moveTo(fromX.coerceAtLeast(0f), fromY.coerceAtLeast(0f))
            lineTo(toX.coerceAtLeast(0f), toY.coerceAtLeast(0f))
        }
        if (isVirtual) check(targetRoots().isNotEmpty()) { "No app window on the Phone Use display." }
        val gesture = GestureDescription.Builder()
            .apply { if (isVirtual) setDisplayId(displayId) }
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        // 效果和手势同时开始；覆盖层不接收触摸，不会挡住手势
        if (!isVirtual) withContext(Dispatchers.Main) { indicator.show(fromX, fromY, toX, toY, durationMs) }
        return suspendCancellableCoroutine { continuation ->
            val dispatched = service.dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
    }

    override suspend fun setText(elementIndex: Int?, text: String): Boolean = withContext(Dispatchers.Default) {
        if (isVirtual) targetWindows()
        val target = if (elementIndex != null) nodes.getOrNull(elementIndex) else focusedInput()
        // 模型可能选中了输入框外面的容器
        val input = target?.let { if (it.isEditable) it else it.firstEditableDescendant() }
            ?: return@withContext false
        // 拿到输入焦点，之后按回车才会落在这个输入框上
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
        )
    }

    private fun focusedInput(): AccessibilityNodeInfo? =
        targetRoots().firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }

    private fun AccessibilityNodeInfo.firstEditableDescendant(): AccessibilityNodeInfo? {
        for (i in 0 until childCount) {
            val child = getChild(i) ?: continue
            if (child.isEditable) return child
            child.firstEditableDescendant()?.let { return it }
        }
        return null
    }

    override suspend fun pressKey(key: PhoneKey): Boolean {
        if (isVirtual && key != PhoneKey.ENTER) {
            check(key == PhoneKey.BACK) { "Use open_app to switch apps on the Phone Use virtual display." }
            targetWindows()
            return withContext(Dispatchers.IO) { checkNotNull(virtual).back() }
        }
        return when (key) {
            PhoneKey.BACK -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            PhoneKey.HOME -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            PhoneKey.RECENTS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            PhoneKey.NOTIFICATIONS -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            PhoneKey.ENTER -> withContext(Dispatchers.Default) {
                check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Enter needs Android 11 or later. Tap the search or send button on screen instead."
                }
                focusedInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
            }
        }
    }

    // ---- 应用 ----

    override fun listApps(): List<PhoneApp> {
        virtual?.let { return it.apps }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return service.packageManager.queryIntentActivities(launcher, 0)
            .map { PhoneApp(it.activityInfo.packageName, it.loadLabel(service.packageManager).toString()) }
            .filter { it.packageName != service.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    // 开启了无障碍服务的应用可以从后台启动 Activity
    override fun openApp(packageName: String): Boolean {
        virtual?.let {
            requireVirtualDisplay()
            return it.openApp(packageName)
        }
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
