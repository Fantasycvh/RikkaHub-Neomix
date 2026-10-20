package app.androidrik.rikkahub.service.floating

import kotlin.math.roundToInt

/** 悬浮窗几何：`x`/`y` 是窗口左上角，全部为屏幕像素。 */
data class WindowGeometry(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/** 8 个拉伸方向（4 边 + 4 角）。 */
enum class ResizeEdge {
    LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

    val affectsLeft: Boolean get() = this == LEFT || this == TOP_LEFT || this == BOTTOM_LEFT
    val affectsRight: Boolean get() = this == RIGHT || this == TOP_RIGHT || this == BOTTOM_RIGHT
    val affectsTop: Boolean get() = this == TOP || this == TOP_LEFT || this == TOP_RIGHT
    val affectsBottom: Boolean get() = this == BOTTOM || this == BOTTOM_LEFT || this == BOTTOM_RIGHT
}

/**
 * 悬浮窗的几何计算，**纯函数、不碰 WindowManager**。
 *
 * 为什么单独抽出来：拖到屏幕边缘、拉伸撞最小尺寸、旋转后越界这三类边界在真机上最难调，
 * 而它们全是坐标算术。放在 [OverlayWindowController] 里就只能靠手点验证，抽成纯函数才能用
 * JVM 单测钉住（见 `FloatingWindowGeometryTest`）。
 *
 * 约定：所有函数都不假设调用方传入合法几何，**返回的一定是合法几何**（在屏幕内、不小于最小值）。
 */
object FloatingWindowGeometry {
    /** 窗口最小尺寸（dp）：再小就放不下标题栏 + 一条消息了 */
    const val MIN_WIDTH_DP = 280
    const val MIN_HEIGHT_DP = 320

    /** 松手时距屏幕左/右边缘多少 dp 内自动贴边 */
    const val EDGE_SNAP_DP = 16

    /** 默认/还原时窗口最大占屏比（给边缘留出可抓的余量，避免窗口顶满屏幕无法再拖） */
    const val MAX_WIDTH_RATIO = 0.95f
    const val MAX_HEIGHT_RATIO = 0.90f

    fun minWidthPx(density: Float): Int = (MIN_WIDTH_DP * density).roundToInt()

    fun minHeightPx(density: Float): Int = (MIN_HEIGHT_DP * density).roundToInt()

    fun edgeSnapPx(density: Float): Int = (EDGE_SNAP_DP * density).roundToInt()

    /** 首次打开 / 还原时的默认几何：居中偏右，留出屏幕边缘。 */
    fun defaultGeometry(
        screenWidth: Int,
        screenHeight: Int,
        density: Float,
    ): WindowGeometry {
        val minWidth = minWidthPx(density)
        val minHeight = minHeightPx(density)
        val width = minOf((360 * density).roundToInt(), (screenWidth * 0.9f).roundToInt())
            .coerceAtLeast(minWidth)
        val height = minOf((560 * density).roundToInt(), (screenHeight * 0.72f).roundToInt())
            .coerceAtLeast(minHeight)
        return clamp(
            geometry = WindowGeometry(
                x = ((screenWidth - width) / 2f).roundToInt(),
                y = ((screenHeight - height) / 2f).roundToInt(),
                width = width,
                height = height,
            ),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            minWidth = minWidth,
            minHeight = minHeight,
        )
    }

    /**
     * 把几何收进屏幕：尺寸先按最小/最大夹紧，再把位置夹到「窗口完整可见」。
     *
     * 旋转屏幕后必调（横屏窗口在竖屏里可能比屏幕还宽）：只夹位置不夹尺寸会留下
     * 一个超出屏幕的窗口，右侧/底部永远拖不回来。
     */
    fun clamp(
        geometry: WindowGeometry,
        screenWidth: Int,
        screenHeight: Int,
        minWidth: Int,
        minHeight: Int,
    ): WindowGeometry {
        val safeMinWidth = minWidth.coerceAtMost(screenWidth).coerceAtLeast(1)
        val safeMinHeight = minHeight.coerceAtMost(screenHeight).coerceAtLeast(1)
        val maxWidth = maxWidthPx(screenWidth, safeMinWidth)
        val maxHeight = maxHeightPx(screenHeight, safeMinHeight)
        val width = geometry.width.coerceIn(safeMinWidth, maxWidth)
        val height = geometry.height.coerceIn(safeMinHeight, maxHeight)
        val x = geometry.x.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        val y = geometry.y.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        return WindowGeometry(x = x, y = y, width = width, height = height)
    }

    /** 拖动：从 [origin] 位移 `(dx, dy)`，结果是窗口完整可见（不允许拖出屏幕）。 */
    fun dragged(
        origin: WindowGeometry,
        dx: Int,
        dy: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): WindowGeometry = origin.copy(
        x = (origin.x + dx).coerceIn(0, (screenWidth - origin.width).coerceAtLeast(0)),
        y = (origin.y + dy).coerceIn(0, (screenHeight - origin.height).coerceAtLeast(0)),
    )

    /**
     * 8 向拉伸：从 [origin]（本次拖动的起点）位移 `(dx, dy)`。
     *
     * **传起点 + 累计位移而不是逐帧增量**：增量会累积浮点误差，且拖动中途改几何时
     * （例如撞到最小尺寸）下一次增量基于已夹紧的值会「粘住」拉不回来。
     * 左/上边拖动时同时改位置与尺寸，右/下边只改尺寸。
     */
    fun resized(
        origin: WindowGeometry,
        edge: ResizeEdge,
        dx: Int,
        dy: Int,
        screenWidth: Int,
        screenHeight: Int,
        minWidth: Int,
        minHeight: Int,
    ): WindowGeometry {
        val safeMinWidth = minWidth.coerceAtLeast(1)
        val safeMinHeight = minHeight.coerceAtLeast(1)
        var left = origin.x
        var top = origin.y
        var right = origin.right
        var bottom = origin.bottom

        if (edge.affectsLeft) {
            // 左边界最多推到「右边界 - 最小宽度」，且不能出屏幕左缘
            left = (origin.x + dx).coerceIn(0, (origin.right - safeMinWidth).coerceAtLeast(0))
        }
        if (edge.affectsRight) {
            // 右边界最多到屏幕右缘，最少回到「左边界 + 最小宽度」
            right = (origin.right + dx).coerceIn(
                (left + safeMinWidth).coerceAtLeast(0),
                maxOf(screenWidth, left + safeMinWidth),
            )
        }
        if (edge.affectsTop) {
            top = (origin.y + dy).coerceIn(0, (origin.bottom - safeMinHeight).coerceAtLeast(0))
        }
        if (edge.affectsBottom) {
            bottom = (origin.bottom + dy).coerceIn(
                (top + safeMinHeight).coerceAtLeast(0),
                maxOf(screenHeight, top + safeMinHeight),
            )
        }
        return clamp(
            geometry = WindowGeometry(left, top, right - left, bottom - top),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            minWidth = safeMinWidth,
            minHeight = safeMinHeight,
        )
    }

    /** 松手贴边：距左/右边缘 [threshold] 内吸到边上（只做水平方向，上下吸附容易误触）。 */
    fun snapped(
        geometry: WindowGeometry,
        screenWidth: Int,
        threshold: Int,
    ): WindowGeometry {
        val x = when {
            geometry.x <= threshold -> 0
            screenWidth - geometry.right <= threshold -> (screenWidth - geometry.width).coerceAtLeast(0)
            else -> geometry.x
        }
        return if (x == geometry.x) geometry else geometry.copy(x = x)
    }

    /** 最大化：铺满可用屏幕（不含状态栏区域由调用方给的尺寸决定）。 */
    /**
     * 最大化后的尺寸上限：屏幕的 [MAX_WIDTH_RATIO] / [MAX_HEIGHT_RATIO]。
     *
     * **必须**和 [clamp] 用同一个口径——最大化请求会经过 [clamp]，如果这里按整屏算，
     * `isMaximized` 就永远判 false，还原按钮点了没反应（真机踩过）。
     */
    fun maxWidthPx(screenWidth: Int, minWidth: Int = 1): Int =
        maxOf(
            (screenWidth * MAX_WIDTH_RATIO).roundToInt(),
            minWidth.coerceAtMost(screenWidth).coerceAtLeast(1),
        )

    fun maxHeightPx(screenHeight: Int, minHeight: Int = 1): Int =
        maxOf(
            (screenHeight * MAX_HEIGHT_RATIO).roundToInt(),
            minHeight.coerceAtMost(screenHeight).coerceAtLeast(1),
        )

    /** 最大化：贴左上角、占满尺寸上限。 */
    fun maximized(screenWidth: Int, screenHeight: Int): WindowGeometry =
        WindowGeometry(
            x = 0,
            y = 0,
            width = maxWidthPx(screenWidth),
            height = maxHeightPx(screenHeight),
        )

    /** 是否已经是最大化状态（还原按钮据此显示）。 */
    fun isMaximized(geometry: WindowGeometry, screenWidth: Int, screenHeight: Int): Boolean =
        geometry.x == 0 && geometry.y == 0 &&
            geometry.width >= maxWidthPx(screenWidth) && geometry.height >= maxHeightPx(screenHeight)
}
