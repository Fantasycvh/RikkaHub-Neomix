package app.androidrik.rikkahub.perf

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.view.allViews
import androidx.test.core.app.ActivityScenario
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.saltui.SaltUiSpikePreview
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One implementation per instrumentation process, real input and window frames. */
internal class SaltUiPerformanceRun(
    private val instrumentation: Instrumentation,
    private val automation: UiAutomation,
    private val salt: Boolean,
) {
    private val tag = if (salt) "salt" else "appskin"

    fun record(output: File, showLongChat: (SketchTestHostActivity) -> Int) {
        check(!output.exists()) { "Use a fresh reportRun" }
        check(output.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val context = instrumentation.targetContext
        val power = context.getSystemService(PowerManager::class.java)
        val report = JSONObject()
            .put("panel", tag)
            .put("device", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("pid", Process.myPid())
            .put("font_scale", context.resources.configuration.fontScale.toDouble())
            .put("density_dpi", context.resources.displayMetrics.densityDpi)
            .put("thermal_before", power.currentThermalStatus)

        ActivityScenario.launch<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)).use { scenario ->
            lateinit var activity: SketchTestHostActivity
            scenario.onActivity {
                activity = it
                it.window.attributes = it.window.attributes.apply { preferredRefreshRate = 60f }
            }
            val window = activity.window
            report.put("requested_refresh_hz", 60)
            report.put("empty_pss_kib", settledPss())
            LongTextFrameRecorder(window).use { recorder ->
                recorder.start()
                val firstDraw = showPanel(activity)
                report.put("initial_panel", recorder.stop().put("first_draw_ms", firstDraw))
                val taps = switchTaps(window)
                taps(16)
                SystemClock.sleep(900)
                check(count(window) == "点击 0 · 切换 16") { "Warmup switch events did not all arrive" }
                report.put("refresh_hz", window.decorView.display.refreshRate.toDouble())
                report.put("warm_pss_kib", settledPss())

                recorder.start()
                val start = SystemClock.elapsedRealtime()
                taps(100)
                val tapElapsed = SystemClock.elapsedRealtime() - start
                // Collect the full final 800ms SaltUI transition as well.
                SystemClock.sleep(900)
                report.put("rapid_toggles", recorder.stop().put("tap_elapsed_ms", tapElapsed).put("tap_count", 100))
                check(count(window) == "点击 0 · 切换 116") { "Measured switch events did not all arrive" }
                report.put("post_toggles_raw_pss_kib", Debug.getPss())
                report.put("post_toggles_settled_pss_kib", settledPss())
                output.writeText(report.toString(2))

                val nodes = showLongChat(activity)
                recorder.start()
                val returnDraw = showPanel(activity)
                report.put("chat_to_panel", recorder.stop().put("first_draw_ms", returnDraw).put("node_count", nodes))
                check(count(window) == "点击 0 · 切换 0") { "Fresh panel was not rendered after the chat" }
                report.put("post_chat_settled_pss_kib", settledPss())
                report.put("thermal_after", power.currentThermalStatus)
                output.writeText(report.toString(2))
            }
        }
    }

    private fun showPanel(activity: SketchTestHostActivity): Double {
        val drawn = CountDownLatch(1)
        val start = System.nanoTime()
        instrumentation.runOnMainSync {
            activity.setContent {
                SaltUiSpikePreview(
                    modifier = Modifier.drawWithContent { drawContent(); drawn.countDown() },
                    saltPanel = salt,
                )
            }
        }
        check(drawn.await(15, TimeUnit.SECONDS)) { "Panel draw timed out" }
        return (System.nanoTime() - start) / 1e6
    }

    private fun switchTaps(window: Window): (Int) -> Unit {
        val location = IntArray(2)
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            window.decorView.getLocationOnScreen(location)
            val center = node(window, "$tag-switch").boundsInWindow.center
            x = location[0] + center.x
            y = location[1] + center.y
        }
        return { count ->
            val start = SystemClock.uptimeMillis()
            repeat(count) { index ->
                SystemClock.sleep((start + index * 75 - SystemClock.uptimeMillis()).coerceAtLeast(0))
                check(window.decorView.hasWindowFocus()) { "Panel window lost focus" }
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    try {
                        event.source = InputDevice.SOURCE_TOUCHSCREEN
                        check(automation.injectInputEvent(event, true))
                    } finally {
                        event.recycle()
                    }
                }
            }
        }
    }

    private fun count(window: Window): String {
        lateinit var text: String
        instrumentation.runOnMainSync {
            text = node(window, "$tag-events").config[SemanticsProperties.Text].single().text
        }
        return text
    }

    private fun node(window: Window, tag: String): SemanticsNode =
        window.decorView.allViews.filterIsInstance<ViewRootForTest>()
            .flatMap { nodesFrom(it.semanticsOwner.unmergedRootSemanticsNode) }
            .single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun nodesFrom(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(nodesFrom(child))
    }

    private fun settledPss(): Long {
        repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); SystemClock.sleep(100) }
        return Debug.getPss()
    }
}
