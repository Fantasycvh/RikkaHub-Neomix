package app.androidrik.rikkahub.ui.components.motion

import app.androidrik.rikkahub.ui.components.haze.shouldEnableRecoverableBlur
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动效与玻璃采样门控纯函数：与 Compose / Android 无关，可纯 JVM 验证。 */
class VisualEffectsPolicyTest {

    @Test
    fun `animator scale zero means reduce motion`() {
        assertTrue(isReduceMotionScale(0f))
        assertFalse(isReduceMotionScale(1f))
        assertFalse(isReduceMotionScale(0.5f))
    }

    @Test
    fun `visual work requires page active resumed and focused`() {
        assertTrue(shouldRunVisualWork(pageActive = true, resumed = true, focused = true))
        assertFalse(shouldRunVisualWork(pageActive = false, resumed = true, focused = true))
        assertFalse(shouldRunVisualWork(pageActive = true, resumed = false, focused = true))
        assertFalse(shouldRunVisualWork(pageActive = true, resumed = true, focused = false))
    }

    @Test
    fun `decorative animation gate combines viewport page focus lifecycle and reduce motion`() {
        assertTrue(
            shouldRunDecorativeAnimation(
                inViewport = true,
                pageActive = true,
                resumed = true,
                focused = true,
                reduceMotion = false,
            )
        )
        assertFalse(
            shouldRunDecorativeAnimation(
                inViewport = false,
                pageActive = true,
                resumed = true,
                focused = true,
                reduceMotion = false,
            )
        )
        assertFalse(
            shouldRunDecorativeAnimation(
                inViewport = true,
                pageActive = false,
                resumed = true,
                focused = true,
                reduceMotion = false,
            )
        )
        assertFalse(
            shouldRunDecorativeAnimation(
                inViewport = true,
                pageActive = true,
                resumed = false,
                focused = true,
                reduceMotion = false,
            )
        )
        assertFalse(
            shouldRunDecorativeAnimation(
                inViewport = true,
                pageActive = true,
                resumed = true,
                focused = false,
                reduceMotion = false,
            )
        )
        assertFalse(
            shouldRunDecorativeAnimation(
                inViewport = true,
                pageActive = true,
                resumed = true,
                focused = true,
                reduceMotion = true,
            )
        )
        // 可显式豁免系统减弱动效（例如有限状态反馈），仍受视口/活跃页/焦点约束
        assertTrue(
            shouldRunDecorativeAnimation(
                inViewport = true,
                pageActive = true,
                resumed = true,
                focused = true,
                reduceMotion = true,
                respectSystemReduceMotion = false,
            )
        )
        assertFalse(
            shouldRunDecorativeAnimation(
                inViewport = false,
                pageActive = true,
                resumed = true,
                focused = true,
                reduceMotion = true,
                respectSystemReduceMotion = false,
            )
        )
    }

    @Test
    fun `recoverable blur pauses when backgrounded or covered by another page`() {
        assertTrue(
            shouldEnableRecoverableBlur(
                userEnabled = true,
                isAppInBackground = false,
                isPageVisible = true,
            )
        )
        assertFalse(
            shouldEnableRecoverableBlur(
                userEnabled = true,
                isAppInBackground = false,
                isPageVisible = false,
            )
        )
        assertFalse(
            shouldEnableRecoverableBlur(
                userEnabled = true,
                isAppInBackground = true,
                isPageVisible = true,
            )
        )
        assertFalse(
            shouldEnableRecoverableBlur(
                userEnabled = false,
                isAppInBackground = false,
                isPageVisible = true,
            )
        )
    }
}
