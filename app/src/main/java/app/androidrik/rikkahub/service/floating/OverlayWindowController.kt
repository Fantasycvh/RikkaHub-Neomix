package app.androidrik.rikkahub.service.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** 屏幕像素尺寸。 */
data class ScreenSizePx(val width: Int, val height: Int)

/** WindowManager 宿主。生成与会话不属于窗口生命周期。 */
class OverlayWindowController(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var params: WindowManager.LayoutParams? = null
    private var root: FrameLayout? = null
    private var contentView: View? = null
    private var beforeIme: WindowGeometry? = null
    private var imeBottom = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var editorFocused = false
    private val imePoll = object : Runnable {
        override fun run() {
            if (!editorFocused) return
            applyIme(screenImeBottom())
            mainHandler.postDelayed(this, 150)
        }
    }

    var geometry by mutableStateOf(WindowGeometry(0, 0, 0, 0))
        private set
    val isShowing: Boolean get() = root != null
    val isImeVisible: Boolean get() = imeBottom > 0

    fun screenSize(): ScreenSizePx =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            ScreenSizePx(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also {
                windowManager.defaultDisplay.getRealMetrics(it)
            }
            ScreenSizePx(metrics.widthPixels, metrics.heightPixels)
        }

    fun minWidthPx() = FloatingWindowGeometry.minWidthPx(context.resources.displayMetrics.density)
    fun minHeightPx() = FloatingWindowGeometry.minHeightPx(context.resources.displayMetrics.density)

    fun show(view: View, initial: WindowGeometry): Boolean {
        if (contentView === view && root != null) return true
        hide()
        val screen = screenSize()
        val clamped = clamp(initial, screen)
        val layoutParams = WindowManager.LayoutParams(
            clamped.width, clamped.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = clamped.x
            y = clamped.y
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        // ComposeView 的子 View 会消费事件，父 View 的 OnTouchListener 不一定被调用。
        // 在 dispatchTouchEvent 入口观察，必须先放开 NOT_FOCUSABLE，TextField 才能唤起 IME。
        val host = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> setInputFocus(true)
                    MotionEvent.ACTION_OUTSIDE -> setInputFocus(false)
                }
                return super.dispatchTouchEvent(event)
            }
        }
        // WindowRecomposer 从窗口根找 owner，不能只把 owner 放在子 ComposeView 上。
        host.setViewTreeLifecycleOwner(view.findViewTreeLifecycleOwner())
        host.setViewTreeViewModelStoreOwner(view.findViewTreeViewModelStoreOwner())
        host.setViewTreeSavedStateRegistryOwner(view.findViewTreeSavedStateRegistryOwner())
        host.addView(view, FrameLayout.LayoutParams(-1, -1))
        // overlay 自己收到的 IME inset 是「窗口被键盘盖住多少」（还随窗口位置变化），
        // 不是键盘高度，所以这里只用于去掉系统 fit 行为；键盘高度靠轮询屏幕 inset。
        ViewCompat.setOnApplyWindowInsetsListener(host) { _, insets -> insets }
        return runCatching { windowManager.addView(host, layoutParams) }
            .onFailure {
                host.removeAllViews()
                Log.e(TAG, "addView failed", it)
            }
            .isSuccess.also { success ->
                if (success) {
                    root = host
                    contentView = view
                    params = layoutParams
                    geometry = clamped
                    ViewCompat.requestApplyInsets(host)
                }
            }
    }

    fun setInputFocus(focused: Boolean) {
        val view = root ?: return
        val layoutParams = params ?: return
        val flags = if (focused) {
            layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (!focused) {
            context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
        if (flags == layoutParams.flags) return
        layoutParams.flags = flags
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onFailure { Log.w(TAG, "focus update failed", it) }
    }

    private fun clamp(value: WindowGeometry, screen: ScreenSizePx): WindowGeometry {
        return FloatingWindowGeometry.aboveIme(
            value, screen.width, screen.height, imeBottom, minWidthPx(), minHeightPx(),
        )
    }

    /**
     * 文本输入框焦点变化时调用（由 Compose 的 onFocusChanged 转发）。
     *
     * 键盘高度不能靠 overlay 自己的 insets：那按「窗口被盖住多少」算，窗口不重叠时恒为 0。
     * 这里在编辑器聚焦期间每 150ms 读一次屏幕 IME inset，键盘一收起立即还原几何。
     * 只在打字时轮询，且每次都做等值判断，实际开销只是一次轻量的 metrics 查询。
     */
    fun onEditorFocusChanged(focused: Boolean) {
        editorFocused = focused
        mainHandler.removeCallbacks(imePoll)
        if (focused) {
            applyIme(screenImeBottom())
            mainHandler.postDelayed(imePoll, 150)
        } else {
            applyIme(0)
        }
    }

    private fun applyIme(bottom: Int) {
        if (bottom == imeBottom) return
        if (bottom > 0) {
            if (imeBottom == 0) beforeIme = geometry
            imeBottom = bottom
            Log.d(TAG, "IME shown bottom=$bottom geometry=$geometry")
            update(geometry)
        } else {
            imeBottom = 0
            val target = beforeIme
            beforeIme = null
            Log.d(TAG, "IME hidden restore=$target")
            if (target != null) update(target)
        }
    }

    private fun screenImeBottom(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
            if (insets.isVisible(android.view.WindowInsets.Type.ime())) {
                insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            } else 0
        } else 0
    }.getOrDefault(0)

    fun onConfigurationChanged() {
        // 旋转后屏幕尺寸变了：以当前临时几何为新基准，重新等 insets 上报
        beforeIme = null
        imeBottom = 0
        update(geometry)
        root?.let(ViewCompat::requestApplyInsets)
    }

    fun update(newGeometry: WindowGeometry) {
        val view = root ?: return
        val layoutParams = params ?: return
        val clamped = clamp(newGeometry, screenSize())
        if (clamped == geometry) return
        layoutParams.x = clamped.x
        layoutParams.y = clamped.y
        layoutParams.width = clamped.width
        layoutParams.height = clamped.height
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onSuccess { geometry = clamped }
            .onFailure { Log.w(TAG, "updateViewLayout failed", it) }
    }

    fun hide() {
        val view = root ?: return
        editorFocused = false
        mainHandler.removeCallbacks(imePoll)
        setInputFocus(false)
        ViewCompat.setOnApplyWindowInsetsListener(view, null)
        runCatching { windowManager.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
        view.removeAllViews()
        root = null
        contentView = null
        params = null
        beforeIme = null
        imeBottom = 0
    }

    companion object {
        private const val TAG = "OverlayWindowController"
    }
}
