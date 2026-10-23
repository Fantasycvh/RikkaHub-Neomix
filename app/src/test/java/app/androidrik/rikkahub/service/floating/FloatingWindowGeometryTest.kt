package app.androidrik.rikkahub.service.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗几何的纯逻辑测试。
 *
 * 覆盖真机上最难验证的三类边界：拖到屏幕边缘、拉伸撞最小尺寸、旋转后窗口比屏幕还大。
 * 这些在手动测试里要么很难触发（旋转 + 特定尺寸），要么要反复试错，必须由单测钉住。
 */
class FloatingWindowGeometryTest {

    // 1080×2340 屏、density 2.75（≈393dp 宽）下的最小尺寸
    private val minWidth = FloatingWindowGeometry.minWidthPx(2.75f) // 770
    private val minHeight = FloatingWindowGeometry.minHeightPx(2.75f) // 880

    private fun clamp(g: WindowGeometry, w: Int = 1080, h: Int = 2340) =
        FloatingWindowGeometry.clamp(g, w, h, minWidth, minHeight)

    @Test
    fun `default geometry is centered and fits the screen`() {
        val g = FloatingWindowGeometry.defaultGeometry(1080, 2340, 2.75f)

        assertTrue("宽度不能小于最小值", g.width >= minWidth)
        assertTrue("高度不能小于最小值", g.height >= minHeight)
        assertTrue("必须完整在屏幕内", g.x >= 0 && g.y >= 0 && g.right <= 1080 && g.bottom <= 2340)
        // 居中（允许 1px 取整误差）
        assertTrue("水平方向应大致居中", kotlin.math.abs(g.x - (1080 - g.width) / 2) <= 1)
    }

    @Test
    fun `clamp shrinks a window that is wider than the screen after rotation`() {
        // 横屏 2340×1080 下拖出的大窗，转回竖屏 1080×2340
        val g = clamp(WindowGeometry(x = 0, y = 0, width = 2340, height = 1000))

        assertTrue("宽度必须收进屏幕", g.width <= 1080)
        assertTrue("高度必须不小于最小值", g.height >= minHeight)
        assertTrue(g.right <= 1080)
        assertTrue(g.bottom <= 2340)
    }

    @Test
    fun `clamp pulls an off-screen window back into bounds`() {
        val g = clamp(WindowGeometry(x = 900, y = 2200, width = 900, height = 900))

        assertTrue("右下角必须在屏幕内", g.right <= 1080 && g.bottom <= 2340)
        assertTrue("位置不能为负", g.x >= 0 && g.y >= 0)
    }

    @Test
    fun `clamp enforces minimum size`() {
        val g = clamp(WindowGeometry(x = 100, y = 100, width = 10, height = 10))

        assertEquals(minWidth, g.width)
        assertEquals(minHeight, g.height)
    }

    @Test
    fun `drag stops at every screen edge instead of leaving the screen`() {
        val origin = WindowGeometry(x = 100, y = 100, width = 800, height = 900)

        val toLeftTop = FloatingWindowGeometry.dragged(origin, dx = -500, dy = -500, 1080, 2340)
        assertEquals(0, toLeftTop.x)
        assertEquals(0, toLeftTop.y)

        val toRightBottom = FloatingWindowGeometry.dragged(origin, dx = 5000, dy = 5000, 1080, 2340)
        assertEquals(1080 - origin.width, toRightBottom.x)
        assertEquals(2340 - origin.height, toRightBottom.y)
    }

    @Test
    fun `dragging by zero keeps geometry untouched`() {
        // 用合法几何做基准：x=300 + width=800 会超出 1080 屏幕，clamp 会把它拉回来，
        // 那是 drag 的正确行为，不是「不动」的用例要考的东西。
        val origin = WindowGeometry(x = 200, y = 400, width = 800, height = 900)
        assertEquals(origin, FloatingWindowGeometry.dragged(origin, 0, 0, 1080, 2340))
    }

    @Test
    fun `dragging by zero still pulls an off-screen window back inside`() {
        val origin = WindowGeometry(x = 300, y = 400, width = 800, height = 900)
        val g = FloatingWindowGeometry.dragged(origin, 0, 0, 1080, 2340)
        assertEquals(280, g.x)
    }

    @Test
    fun `dragging the left edge grows the window to the left`() {
        val origin = WindowGeometry(x = 200, y = 300, width = 700, height = 900)

        val g = FloatingWindowGeometry.resized(
            origin = origin, edge = ResizeEdge.LEFT, dx = -100, dy = 0,
            screenWidth = 1080, screenHeight = 2340, minWidth = minWidth, minHeight = minHeight,
        )

        assertEquals(100, g.x)
        assertEquals(800, g.width)
        assertEquals("右边界不动", origin.right, g.right)
    }

    @Test
    fun `dragging the right edge stops at the screen edge`() {
        val origin = WindowGeometry(x = 100, y = 300, width = 700, height = 900)

        val g = FloatingWindowGeometry.resized(
            origin = origin, edge = ResizeEdge.RIGHT, dx = 5000, dy = 0,
            screenWidth = 1080, screenHeight = 2340, minWidth = minWidth, minHeight = minHeight,
        )

        assertEquals(1080, g.right)
        assertEquals(origin.x, g.x)
    }

    @Test
    fun `dragging the top edge grows upward and keeps the bottom fixed`() {
        val origin = WindowGeometry(x = 200, y = 300, width = 700, height = 900)

        val g = FloatingWindowGeometry.resized(
            origin = origin, edge = ResizeEdge.TOP, dx = 0, dy = -150,
            screenWidth = 1080, screenHeight = 2340, minWidth = minWidth, minHeight = minHeight,
        )

        assertEquals(150, g.y)
        assertEquals(1050, g.height)
        assertEquals(origin.bottom, g.bottom)
    }

    @Test
    fun `resize cannot shrink below the minimum size`() {
        val origin = WindowGeometry(x = 100, y = 100, width = 800, height = 900)

        val left = FloatingWindowGeometry.resized(
            origin = origin, edge = ResizeEdge.LEFT, dx = 5000, dy = 0,
            screenWidth = 1080, screenHeight = 2340, minWidth = minWidth, minHeight = minHeight,
        )
        assertEquals("左边界最多推到右边界减最小宽度", minWidth, left.width)
        assertEquals(origin.right - minWidth, left.x)

        val bottom = FloatingWindowGeometry.resized(
            origin = origin, edge = ResizeEdge.BOTTOM, dx = 0, dy = -5000,
            screenWidth = 1080, screenHeight = 2340, minWidth = minWidth, minHeight = minHeight,
        )
        assertEquals(minHeight, bottom.height)
    }

    @Test
    fun `corner resize changes both dimensions and keeps the opposite corner fixed`() {
        val origin = WindowGeometry(x = 200, y = 300, width = 700, height = 900)

        val g = FloatingWindowGeometry.resized(
            origin = origin, edge = ResizeEdge.BOTTOM_RIGHT, dx = 100, dy = 200,
            screenWidth = 1080, screenHeight = 2340, minWidth = minWidth, minHeight = minHeight,
        )

        assertEquals(origin.x, g.x)
        assertEquals(origin.y, g.y)
        assertEquals(800, g.width)
        assertEquals(1100, g.height)
    }

    @Test
    fun `corner resize pulls back when dragged past the opposing edge`() {
        val origin = WindowGeometry(x = 200, y = 300, width = 700, height = 900)

        // 把左上角拖到右下角的右下方：宽高不能变负，必须停在最小尺寸
        val g = FloatingWindowGeometry.resized(
            origin = origin, edge = ResizeEdge.TOP_LEFT, dx = 5000, dy = 5000,
            screenWidth = 1080, screenHeight = 2340, minWidth = minWidth, minHeight = minHeight,
        )

        assertEquals(minWidth, g.width)
        assertEquals(minHeight, g.height)
        assertEquals(origin.right - minWidth, g.x)
        assertEquals(origin.bottom - minHeight, g.y)
    }

    @Test
    fun `snap pulls the window to the left edge when close`() {
        val g = FloatingWindowGeometry.snapped(
            WindowGeometry(x = 10, y = 500, width = 700, height = 900),
            screenWidth = 1080,
            threshold = 40,
        )
        assertEquals(0, g.x)
    }

    @Test
    fun `snap pulls the window to the right edge when close`() {
        val g = FloatingWindowGeometry.snapped(
            WindowGeometry(x = 1080 - 700 - 12, y = 500, width = 700, height = 900),
            screenWidth = 1080,
            threshold = 40,
        )
        assertEquals(1080 - 700, g.x)
    }

    @Test
    fun `snap leaves the window alone in the middle`() {
        val origin = WindowGeometry(x = 200, y = 500, width = 700, height = 900)
        assertEquals(origin, FloatingWindowGeometry.snapped(origin, screenWidth = 1080, threshold = 40))
    }

    @Test
    fun `maximize stops at the size cap and is detected as maximized`() {
        val g = FloatingWindowGeometry.maximized(1080, 2340)

        // 95% / 90% 上限：最大化后仍留一圈边距，不是整屏
        assertEquals(WindowGeometry(0, 0, 1026, 2106), g)
        assertTrue(FloatingWindowGeometry.isMaximized(g, 1080, 2340))
        // clamp 之后的几何也必须被认成最大化（否则还原按钮点了没反应）
        val clamped = FloatingWindowGeometry.clamp(g, 1080, 2340, minWidth = 770, minHeight = 880)
        assertTrue(FloatingWindowGeometry.isMaximized(clamped, 1080, 2340))
        assertFalse(
            "还原后的窗口不该再被认成最大化",
            FloatingWindowGeometry.isMaximized(WindowGeometry(0, 0, 800, 900), 1080, 2340),
        )
        assertFalse(
            "普通窗口不应被认成最大化",
            FloatingWindowGeometry.isMaximized(WindowGeometry(0, 0, 800, 900), 1080, 2340),
        )
    }
}
