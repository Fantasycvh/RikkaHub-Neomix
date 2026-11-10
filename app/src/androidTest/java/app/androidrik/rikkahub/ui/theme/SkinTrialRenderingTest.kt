package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkinTrialRenderingTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val controller = SkinTrialController.shared
    private val candidate = AppSkin(typography = AppSkinTypography(sizeRatio = 1.25f), shapes = AppSkinShapes(inputDp = 24f))
    private val selection = SkinSelection(null, AppSkinOverrides.from(candidate))
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule -> var activity: SketchTestHostActivity? = null; rule.scenario.onActivity { activity = it }; requireNotNull(activity) }
    @After fun cleanup() { controller.cancel(); controller.dismissUndo() }

    @Test fun crossPagePreviewKeepsInputAndConfirmationCanBeUndone() {
        var display by mutableStateOf(DisplaySetting())
        var text by mutableStateOf("")
        var page by mutableIntStateOf(0)
        var size = 0f
        compose.setContent {
            AppSkinTheme(rememberAppSkin(display), lightColorScheme()) {
                SkinTrialHost(content = {
                    size = MaterialTheme.typography.bodyLarge.fontSize.value
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        AppOutlinedTextField(text, { text = it }, Modifier.testTag("input"))
                        AppButton(onClick = { page++ }) { Text("换页") }
                        Text("页面 $page")
                    }
                }, update = { display = it(display) }, timeoutMillis = 30_000)
            }
        }
        compose.onNodeWithTag("input").performTextInput("keep draft")
        compose.runOnIdle { controller.start(display, selection, candidate, "fixture") }
        compose.onNodeWithTag("input").assertIsFocused().assertTextContains("keep draft")
        compose.runOnIdle { assertEquals(20f, size); assertEquals(DisplaySetting(), display) }
        compose.onNodeWithText("换页").performClick()
        compose.onNodeWithText("页面 1").assertExists()
        compose.onNodeWithText("确认应用").performClick()
        compose.waitUntil(10_000) { controller.trial.value == null }
        compose.runOnIdle { assertEquals(selection, SkinSelection.from(display)) }
        compose.onNodeWithText("撤销").performClick()
        compose.waitUntil(10_000) { controller.undo.value == null }
        compose.runOnIdle { assertEquals(DisplaySetting(), display); assertEquals(16f, size) }
        compose.onNodeWithTag("input").assertTextContains("keep draft")
    }

    @Test fun expiryRestoresPersistedAppearanceWithoutWritingSettings() {
        var writes = 0
        val display = DisplaySetting()
        compose.setContent {
            AppSkinTheme(rememberAppSkin(display), lightColorScheme()) {
                SkinTrialHost(content = { Text("Page") }, update = { writes++ }, timeoutMillis = 300)
            }
        }
        compose.runOnIdle { controller.start(display, selection, candidate, "expires") }
        compose.waitUntil(10_000) { controller.trial.value == null }
        compose.runOnIdle { assertEquals(0, writes) }
    }
    @Test fun disposingTheRootHostCancelsAnUnconfirmedTrial() {
        var open by mutableStateOf(true)
        var writes = 0
        compose.setContent {
            AppSkinTheme(rememberAppSkin(DisplaySetting()), lightColorScheme()) {
                if (open) SkinTrialHost(content = { Text("Open") }, update = { writes++ }) else Text("Closed")
            }
        }
        compose.runOnIdle { controller.start(DisplaySetting(), selection, candidate, "disposal") }
        compose.runOnIdle { open = false }
        compose.waitUntil(10_000) { controller.trial.value == null }
        compose.runOnIdle { assertEquals(0, writes) }
    }
}
