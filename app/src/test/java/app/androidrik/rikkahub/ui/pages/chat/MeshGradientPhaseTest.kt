package app.androidrik.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * 渐变背景限速时间源的相位纯函数回归。
 *
 * 新实现不再用 `rememberInfiniteTransition`（每 vsync 重组），而是自驱 33ms 时间源 +
 * 绘制期读相位。本测试钉住「数学上与旧动画等价」——旧动画在 `durationMillis * loops` 内
 * 从 0 线性走到 `2π * loops`，等价于 `2π * t / durationMillis`。
 */
class MeshGradientPhaseTest {

    private val twoPi = (2 * PI).toFloat()
    private val delta = 1e-4f

    @Test
    fun `phase follows old linear-repeat formula`() {
        // 5_500ms 一圈，20 圈为一个周期（110 秒）
        assertEquals(0f, phaseAtElapsed(0, 5_500, 20), delta)
        assertEquals(twoPi * 0.25f, phaseAtElapsed(1_375, 5_500, 20), delta)
        assertEquals(twoPi, phaseAtElapsed(5_500, 5_500, 20), delta)
        // 走满一个 period 后回到 0（与 infiniteRepeatable 的重置一致）
        assertEquals(0f, phaseAtElapsed(110_000, 5_500, 20), delta)
        assertEquals(twoPi * 0.25f, phaseAtElapsed(111_375, 5_500, 20), delta)
    }

    @Test
    fun `single loop variant is periodic each duration`() {
        assertEquals(0f, phaseAtElapsed(0, 7_000, 1), delta)
        assertEquals(twoPi * 0.5f, phaseAtElapsed(3_500, 7_000, 1), delta)
        assertEquals(0f, phaseAtElapsed(7_000, 7_000, 1), delta)
    }

    @Test
    fun `degenerate period is safe`() {
        assertTrue(phaseAtElapsed(123, 0, 0) == 0f)
    }
}
