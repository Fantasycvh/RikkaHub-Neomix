package app.androidrik.rikkahub.ui.components.richtext

import android.content.Intent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import me.rerere.bettertext.highlight.HighlightTextColorPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class StreamingHighlightTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test
    fun appendedCodeAndPaletteChangesAreHighlightedWithoutRemounting() {
        val prefix = "{\"value\": 1"
        val code = mutableStateOf(prefix)
        val streaming = mutableStateOf(true)
        val palette = mutableStateOf(HighlightTextColorPalette.AtomOneDark)
        val result = AtomicReference<AnnotatedString?>()
        compose.setContent {
            val highlighted = rememberAsyncHighlightedCode(code.value, "json", palette.value, streaming.value)
            SideEffect { result.set(highlighted) }
        }
        compose.waitUntil(5_000) { result.get()?.spanStyles?.isNotEmpty() == true }
        compose.runOnIdle {
            code.value = prefix + ", \"tail\": true}"
            streaming.value = false
        }
        compose.waitUntil(5_000) {
            result.get()?.spanStyles?.any { it.end > prefix.length } == true
        }
        val darkSpans = result.get()!!.spanStyles
        compose.runOnIdle { palette.value = HighlightTextColorPalette.AtomOneLight }
        compose.waitUntil(5_000) { result.get()!!.spanStyles != darkSpans }
    }
}
