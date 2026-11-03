package app.androidrik.rikkahub.ui.components.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 动效门控纯函数：与 Compose / Android 无关，可纯 JVM 验证。 */
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
}
