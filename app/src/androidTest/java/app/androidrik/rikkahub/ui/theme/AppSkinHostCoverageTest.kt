package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.richtext.CodeHighlightText
import app.androidrik.rikkahub.ui.components.richtext.DiffView
import app.androidrik.rikkahub.ui.components.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSkinHostCoverageTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test fun expressivePopupChangesCornersAndPaddingWhileKeepingItsActionsMounted() {
        var skin by mutableStateOf(AppSkin())
        var expanded by mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.White).padding(40.dp)) {
                    ExpressivePopup(expanded, { expanded = false },
                        modifier = Modifier.size(160.dp, 100.dp).testTag("popup"),
                        containerColor = Color.Blue, contentColor = Color.White, shadowElevation = 0.dp) {
                        AppTextButton(onClick = { clicks++; expanded = false }, Modifier.testTag("action")) { Text("Choose") }
                    }
                }
            }
        }
        compose.waitForIdle()
        val before = compose.onNodeWithTag("action").fetchSemanticsNode().boundsInRoot.left -
            compose.onNodeWithTag("popup").fetchSemanticsNode().boundsInRoot.left
        val corner = compose.onNodeWithTag("popup").captureToImage().toPixelMap()[1, 1]
        assertTrue(corner.blue < .9f || corner.alpha < .9f || corner.red > .9f)
        compose.runOnIdle { skin = skin.copy(shapes = skin.shapes.copy(largeDp = 0f),
            spacing = skin.spacing.copy(pageDp = 24f)) }
        val after = compose.onNodeWithTag("action").fetchSemanticsNode().boundsInRoot.left -
            compose.onNodeWithTag("popup").fetchSemanticsNode().boundsInRoot.left
        assertTrue(after > before)
        val square = compose.onNodeWithTag("popup").captureToImage().toPixelMap()[1, 1]
        assertTrue(square.blue > .95f && square.red < .1f)
        compose.onNodeWithTag("action").performClick()
        compose.onNodeWithTag("popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun expressivePopupRetainsBackDismissalAfterSkinChange() {
        var skin by mutableStateOf(AppSkin())
        var expanded by mutableStateOf(true)
        var dismissals = 0
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
                Box(Modifier.padding(32.dp)) {
                    ExpressivePopup(expanded, { dismissals++; expanded = false }, modifier = Modifier.testTag("popup")) {
                        Text("Popup item")
                    }
                }
            }
        }
        compose.onNodeWithText("Popup item").assertIsDisplayed()
        compose.runOnIdle { skin = skin.copy(shapes = skin.shapes.copy(largeDp = 4f)) }
        Espresso.pressBack()
        compose.onNodeWithTag("popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun selectKeepsOpenMenuAndSelectionAcrossSkinSwitch() {
        var skin by mutableStateOf(AppSkin())
        var selected by mutableStateOf("First")
        var changes = 0
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
                Select(listOf("First", "Second"), selected, { selected = it; changes++ },
                    modifier = Modifier.width(240.dp).testTag("select"))
            }
        }
        compose.onNodeWithText("First").performClick()
        compose.onNodeWithText("Second").assertIsDisplayed()
        compose.runOnIdle { skin = skin.copy(shapes = skin.shapes.copy(buttonDp = 0f, extraSmallDp = 0f),
            typography = skin.typography.copy(sizeRatio = 1.2f)) }
        compose.onNodeWithText("Second").performClick()
        compose.runOnIdle { assertEquals("Second", selected); assertEquals(1, changes) }
        compose.onNodeWithText("Second").assertIsDisplayed()
    }

    @Test fun segmentedPillKeepsRadioSemanticsAndMinimumTouchHeight() {
        var skin by mutableStateOf(AppSkin())
        var selected by mutableStateOf("First")
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
                SegmentedPill(listOf("First", "Second"), selected, { selected = it }, { it },
                    modifier = Modifier.width(280.dp).testTag("pill"))
            }
        }
        compose.onNodeWithText("First").assertIsSelected()
        compose.runOnIdle { skin = skin.copy(shapes = skin.shapes.copy(buttonDp = 0f)) }
        compose.onNodeWithText("Second").performClick().assertIsSelected()
        compose.onNodeWithTag("pill").assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { assertEquals("Second", selected) }
    }

    @Test fun diffAndCodeScaleOnceAndRetainMonospaceForStructuredContent() {
        var skin by mutableStateOf(AppSkin())
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
                Column {
                    DiffView("+added", Modifier.testTag("diff"))
                    CodeHighlightText("val x = 1", "kotlin", Modifier.testTag("code"))
                }
            }
        }
        val before = compose.onNodeWithTag("code").fetchSemanticsNode().size.height
        compose.runOnIdle { skin = skin.copy(typography = skin.typography.copy(sizeRatio = 1.5f)) }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("+added").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(16.5f, layouts.single().layoutInput.style.fontSize.value, .01f)
        assertEquals(24f, layouts.single().layoutInput.style.lineHeight.value, .01f)
        assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
        val after = compose.onNodeWithTag("code").fetchSemanticsNode().size.height
        assertTrue("Code text should scale once", after > before * 1.3f && after < before * 1.8f)
    }
}
