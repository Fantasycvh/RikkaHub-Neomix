package app.androidrik.rikkahub.ui.components.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import android.content.Intent
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SegmentedPillTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { activityRule ->
        var activity: SketchTestHostActivity? = null
        activityRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test
    fun selectedOptionExposesRadioSelection() {
        rule.setContent {
            MaterialTheme {
                SegmentedPill(listOf("System", "Light", "Dark"), "System", {}, { it })
            }
        }
        rule.onNodeWithText("System").assertIsSelected()
        rule.onNodeWithText("Light").assertIsNotSelected()
    }

    @Test
    fun selectingAnOptionUpdatesSelection() {
        val selected = mutableStateOf("System")
        rule.setContent {
            MaterialTheme {
                SegmentedPill(listOf("System", "Light", "Dark"), selected.value,
                    { selected.value = it }, { it })
            }
        }
        rule.onNodeWithText("Dark").performClick().assertIsSelected()
        rule.onNodeWithText("System").assertIsNotSelected()
    }

    @Test
    fun clickingSelectedOptionDoesNotRepeatCallback() {
        var changes = 0
        rule.setContent {
            MaterialTheme {
                SegmentedPill(listOf("System", "Light", "Dark"), "System", { changes++ }, { it })
            }
        }
        rule.onNodeWithText("System").performClick()
        rule.runOnIdle { assertEquals(0, changes) }
    }

    @Test
    fun optionsHaveAccessibleMinimumHeightAtSettingsWidth() {
        rule.setContent {
            MaterialTheme {
                SegmentedPill(listOf("System", "Light", "Dark"), "System", {}, { it },
                    Modifier.width(186.dp))
            }
        }
        rule.onNodeWithText("System").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Light").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Dark").assertHeightIsAtLeast(48.dp)
    }
}
