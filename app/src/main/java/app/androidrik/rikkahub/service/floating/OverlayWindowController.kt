package app.androidrik.rikkahub.service.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 屏幕像素尺寸。 */
data class ScreenSizePx(val width: Int, val height: Int)

/**
 * 系统悬浮窗的 `WindowManager` 外壳：只负责「窗口存在 / 几何变化」，不含任何交互策略。
 *
 * 三层分工，各自可独立测试：
 * - 本类：`addView` / `updateViewLayout` / `removeView`，线程与异常兜底；
 * - [FloatingWindowGeometry]：纯坐标算术（拖拽、8 向拉伸、贴边、旋转 clamp）；
 * - `FloatingWindowPane`：Compose 手势与外观。
 */
class OverlayWindowController(private val context: Context) {

    companion object {
        private const val TAG = "OverlayWindowController"

        /** 渲染在系统层（API 26+）；低于 26 本应用不支持（minSdk 26） */
        private const val WINDOW_TYPE =
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    private val windowManager: WindowManager =
        context.getSystemService(WindowManager::class.java)

    private var params: WindowManager.LayoutParams? = null
    private var root: View? = null

    /**
     * 当前几何，Compose 侧以此为唯一真相源。
     *
     * 刻意用 `mutableStateOf` 而不是普通字段：Pane 需要在 `updateViewLayout` 之后自动重组
     * （尺寸读数、最大化按钮态），否则要额外加一层 Flow 转发。
     */
    var geometry by mutableStateOf(WindowGeometry(0, 0, 0, 0))
        private set

    val isShowing: Boolean get() = root != null

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

    fun minWidthPx(): Int = FloatingWindowGeometry.minWidthPx(context.resources.displayMetrics.density)

    fun minHeightPx(): Int = FloatingWindowGeometry.minHeightPx(context.resources.displayMetrics.density)

    /** 添加窗口；[initial] 会先被 clamp 到屏幕内。返回是否成功（无权限时 addView 会抛 SecurityException）。 */
    fun show(view: View, initial: WindowGeometry): Boolean {
        if (root === view) return true
        hide()
        val screen = screenSize()
        val clamped = FloatingWindowGeometry.clamp(
            geometry = initial,
            screenWidth = screen.width,
            screenHeight = screen.height,
            minWidth = minWidthPx(),
            minHeight = minHeightPx(),
        )
        val layoutParams = WindowManager.LayoutParams(
            clamped.width,
            clamped.height,
            WINDOW_TYPE,
            // NOT_FOCUSABLE：原型没有输入框，不抢焦点、不弹输入法（P0 接输入框时要去掉，
            // 并自己处理 IME insets——overlay 没有 adjustResize）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clamped.x
            y = clamped.y
            // 覆盖在状态栏/导航栏区域时不做额外避让，位置由几何算法保证在屏内。
            // fitInsetsTypes 是 API 30 才有的，低版本用默认行为（overlay 本来就不吃 insets）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
        return runCatching {
            windowManager.addView(view, layoutParams)
        }.onFailure {
            // 无「显示在其他应用上层」权限时系统会在这里抛 SecurityException；
            // 调用方（FloatingWindowService）负责提前检查权限，这里只兜底不崩。
            Log.e(TAG, "addView failed (missing overlay permission?)", it)
        }.isSuccess.also { success ->
            if (success) {
                root = view
                params = layoutParams
                geometry = clamped
            }
        }
    }

    /** 更新几何（内部会 clamp 到屏幕内）；几何没变时零开销。 */
    fun update(newGeometry: WindowGeometry) {
        val view = root ?: return
        val layoutParams = params ?: return
        val screen = screenSize()
        val clamped = FloatingWindowGeometry.clamp(
            geometry = newGeometry,
            screenWidth = screen.width,
            screenHeight = screen.height,
            minWidth = minWidthPx(),
            minHeight = minHeightPx(),
        )
        if (clamped == geometry) return
        layoutParams.x = clamped.x
        layoutParams.y = clamped.y
        layoutParams.width = clamped.width
        layoutParams.height = clamped.height
        runCatching { windowManager.updateViewLayout(view, layoutParams) }
            .onFailure { Log.w(TAG, "updateViewLayout failed", it) }
        geometry = clamped
    }

    fun hide() {
        val view = root ?: return
        runCatching { windowManager.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
        root = null
        params = null
    }
}
