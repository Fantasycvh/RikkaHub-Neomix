package app.androidrik.rikkahub.ui.components.ui

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
