package app.androidrik.rikkahub.ui.components.ui

import android.view.MotionEvent
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.ui.floating.windowGeometryGesture
import app.androidrik.rikkahub.service.floating.WindowGeometry
import com.petterp.floatingx.core.layout.FxGravity
import com.petterp.floatingx.core.gesture.FxGesture
import com.petterp.floatingx.core.gesture.FxDrag
import org.junit.Assert.assertTrue
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import com.petterp.floatingx.compose.compose
import com.petterp.floatingx.system.systemHost
import com.petterp.floatingx.system.permission.FxPermissionStrategy

/** Regression coverage for the shared TTS floating-window host after FloatingX 3 migration. */
@RunWith(AndroidJUnit4::class)
class FloatingWindowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val composeRule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test
    fun grantedPermissionRendersUpdatesHidesAndDisposes() = withOverlayPermission("allow") {
        val visible = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        val value = mutableStateOf(1)
        val rendered = AtomicInteger()
        composeRule.setContent {
            if (mounted.value) {
                FloatingWindow(tag = "tts-host-regression", visibility = visible.value) {
                    Text("TTS ${value.value}")
                    SideEffect { rendered.set(value.value) }
                }
            }
        }
        composeRule.waitUntil(10_000) { rendered.get() == 1 }
        composeRule.runOnIdle { value.value = 2 }
        composeRule.waitUntil(10_000) { rendered.get() == 2 }
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        composeRule.waitForIdle()
        composeRule.runOnIdle { mounted.value = false }
        composeRule.waitForIdle()
    }

    @Test
    fun deniedPermissionDoesNotCreateWindowOrCrash() = withOverlayPermission("deny") {
        val rendered = AtomicInteger()
        composeRule.setContent {
            FloatingWindow(tag = "tts-denied-regression") {
                SideEffect { rendered.incrementAndGet() }
            }
        }
        composeRule.waitForIdle()
        assertEquals(0, rendered.get())
    }

    @Test
    fun systemOverlayRendersHidesShowsAndCancels() = withOverlayPermission("allow") {
        val rendered = AtomicInteger()
        var control: FxControl? = null
        try {
            composeRule.runOnIdle {
                control = FloatingX.install("chat-system-host-regression") {
                    systemHost(context) { permission(FxPermissionStrategy.skip()) }
                    compose {
                        Text("System overlay")
                        SideEffect { rendered.incrementAndGet() }
                    }
                }.also { it.show() }
            }
            composeRule.waitUntil(10_000) { rendered.get() > 0 }
            composeRule.runOnIdle { control?.hide() }
            composeRule.waitForIdle()
            composeRule.runOnIdle { control?.show() }
            composeRule.waitForIdle()
        } finally {
            composeRule.runOnIdle { control?.cancel() }
        }
    }

    @Test
    fun systemOverlayDragFollowsRawPointerWithoutFeedbackJumps() = withOverlayPermission("allow") {
        var control: FxControl? = null
        val geometry = mutableStateOf(WindowGeometry(30, 100, 500, 500))
        val positions = java.util.Collections.synchronizedList(mutableListOf<Int>())
        try {
            composeRule.runOnIdle {
                control = FloatingX.install("raw-coordinate-drag-regression") {
                    anchor(FxGravity.TOP_START, dx = 30f, dy = 100f)
                    gesture(FxGesture(click = false, longPress = false, drag = FxDrag.DISABLED))
                    systemHost(context) { permission(FxPermissionStrategy.skip()) }
                    compose {
                        val current = geometry.value
                        SideEffect { control?.moveTo(current.x.toFloat(), current.y.toFloat(), false) }
                        Box(Modifier.size(200.dp).windowGeometryGesture(
                            0f, { geometry.value }, false,
                            { origin, _, dx, dy ->
                                positions.add(origin.x + dx)
                                geometry.value = origin.copy(x = origin.x + dx, y = origin.y + dy)
                            }, {},
                        ))
                    }
                }.also { it.show() }
            }
            composeRule.waitForIdle()
            val location = IntArray(2)
            composeRule.runOnIdle { control!!.contentView!!.getLocationOnScreen(location) }
            val startX = location[0] + 100f
            val startY = location[1] + 100f
            val down = SystemClock.uptimeMillis()
            fun inject(action: Int, x: Float, y: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
                finally { event.recycle() }
                instrumentation.waitForIdleSync()
            }
            inject(MotionEvent.ACTION_DOWN, startX, startY)
            for (step in 1..12) inject(MotionEvent.ACTION_MOVE, startX + step * 10f, startY + step * 5f)
            inject(MotionEvent.ACTION_UP, startX + 120f, startY + 60f)
            composeRule.runOnIdle {
                assertTrue("drag callbacks missing", positions.size >= 5)
                assertTrue("window diverged from pointer: ${geometry.value}", kotlin.math.abs(geometry.value.x - 150) <= 3)
                assertTrue("non-monotonic drag: $positions", positions.zipWithNext().all { (a, b) -> b >= a && b - a <= 35 })
            }
        } finally { composeRule.runOnIdle { control?.cancel() } }
    }

    private fun withOverlayPermission(mode: String, block: () -> Unit) {
        val command = "appops get ${context.packageName} SYSTEM_ALERT_WINDOW"
        val original = shell(command)
        val previous = Regex("SYSTEM_ALERT_WINDOW: (\\w+)").find(original)?.groupValues?.get(1) ?: "default"
        try {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode")
            block()
        } finally {
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $previous")
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
