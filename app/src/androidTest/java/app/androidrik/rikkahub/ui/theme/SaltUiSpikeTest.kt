package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.saltui.SALT_SPIKE_ITEM_DETAIL
import app.androidrik.rikkahub.saltui.SALT_SPIKE_SWITCH_DETAIL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SaltUiSpikeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scale = object : MotionDurationScale {
        @Volatile override var scaleFactor = 1f
    }

    @get:Rule val compose = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<SketchTestHostActivity>(
            Intent(context, SketchTestHostActivity::class.java).putExtra("saltUiSpike", true),
        ),
        effectContext = scale,
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test fun saltRowsExposeOneActionAndIgnoreDisabledTouches() {
        val item = compose.onNodeWithTag("salt-item")
        val toggle = compose.onNodeWithTag("salt-switch")
        item.performScrollTo().assertHeightIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
        toggle.performScrollTo().assertHeightIsAtLeast(48.dp).assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        toggle.assertIsOn()
        for (tag in listOf("salt-item", "salt-switch")) {
            val withinRow = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))
            compose.onAllNodes(withinRow and hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        }
        for (tag in listOf("salt-disabled-item", "salt-disabled-switch")) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        }
        compose.onNodeWithTag("salt-events").performScrollTo().assertTextEquals("点击 1 · 切换 1")
        compose.onNodeWithTag("appskin-events").assertTextEquals("点击 0 · 切换 0")
        compose.onNodeWithTag("appskin-switch").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("appskin-events").performScrollTo().assertTextEquals("点击 0 · 切换 1")
        compose.onNodeWithTag("salt-events").assertTextEquals("点击 1 · 切换 1")
    }

    @Test fun bothPanelsWrapLargeTextInRtlAcrossSkinAndColorModes() {
        compose.onNodeWithText("200% 字体").performClick()
        compose.onNodeWithText("RTL").performScrollTo().performClick()
        assertDetailsFit()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        assertDetailsFit()
        compose.onNodeWithText("浅蓝").performScrollTo().performClick()
        assertDetailsFit()
        compose.onNodeWithText("深色").performScrollTo().performClick()
        assertDetailsFit()
    }

    @Test fun recreatedHostRetainsIndependentPanelState() {
        compose.onNodeWithTag("salt-switch").performScrollTo().performClick()
        compose.onNodeWithTag("appskin-item").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("salt-switch").performScrollTo().assertIsOn()
        compose.onNodeWithTag("salt-events").performScrollTo().assertTextEquals("点击 0 · 切换 1")
        compose.onNodeWithTag("appskin-switch").performScrollTo().assertIsOff()
        compose.onNodeWithTag("appskin-events").performScrollTo().assertTextEquals("点击 1 · 切换 0")
    }

    @Test fun zeroDurationScaleSettlesTheSwitchOnTheNextFrames() {
        val toggle = compose.onNodeWithTag("salt-switch").performScrollTo()
        val before = toggle.captureToImage()
        compose.runOnIdle { scale.scaleFactor = 0f }
        compose.mainClock.autoAdvance = false
        try {
            toggle.performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
            compose.mainClock.advanceTimeBy(64)
            toggle.assertIsOn()
            val early = toggle.captureToImage()
            compose.mainClock.advanceTimeBy(1000)
            val settled = toggle.captureToImage()
            assertFalse(before.pixels().contentEquals(settled.pixels()))
            assertTrue(early.pixels().contentEquals(settled.pixels()))
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun assertDetailsFit() {
        for (group in listOf("appskin-group", "salt-group")) {
            for (detail in listOf(SALT_SPIKE_ITEM_DETAIL, SALT_SPIKE_SWITCH_DETAIL)) {
                val results = mutableListOf<TextLayoutResult>()
                compose.onNode(hasText(detail) and hasAnyAncestor(hasTestTag(group)), useUnmergedTree = true)
                    .performScrollTo()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
                        assertTrue(read(results))
                    }
                val layout = results.single()
                assertEquals(LayoutDirection.Rtl, layout.layoutInput.layoutDirection)
                assertFalse(detail, layout.hasVisualOverflow)
            }
        }
    }

    private fun ImageBitmap.pixels(): IntArray = IntArray(width * height).also { readPixels(it) }
}
