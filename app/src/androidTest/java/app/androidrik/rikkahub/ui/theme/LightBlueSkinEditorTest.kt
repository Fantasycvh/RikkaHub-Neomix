package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.model.AppSkin
import app.androidrik.rikkahub.data.model.AppSkinOverrides
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.setting.components.AppSkinEditor
import app.androidrik.rikkahub.ui.pages.setting.components.AppSkinSettings
import com.dokar.sonner.rememberToasterState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LightBlueSkinEditorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preset = builtInAppSkins.first { it.id == "builtin.premium-dark" }
    private val controller = SkinTrialController.shared

    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @After fun cleanup() {
        controller.cancel()
        controller.dismissUndo()
    }

    @Test fun partialColorSurvivesScrollingAndOnlyAValidDraftCanBeSaved() {
        var saved: AppSkin? = null
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(preset.skin), lightColorScheme()) {
                CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                    AppSkinEditor(preset.skin, onSave = { saved = it }, onDismiss = {}, onTry = {})
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.setting_page_feature_shortcut_title))
            .performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        compose.onNode(hasSetTextAction() and hasText("#D6ECFF"))
            .performScrollTo().performTextReplacement("#E6")
        val save = compose.onNodeWithText("保存")
        save.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("跨页面试穿").assertIsNotEnabled()
        compose.runOnIdle { assertNull(saved) }

        compose.onNode(hasSetTextAction() and hasText("#E6"))
            .performScrollTo().performTextReplacement("#E6F1FF")
        save.performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("#E6F1FF", saved?.hero?.lightGradient?.colors?.first()) }
    }

    @Test fun editingAnActiveTrialKeepsItsSkinIdAndOnlyOverridesTheEditedGroup() {
        val initial = DisplaySetting(appSkinId = "builtin.paper")
        controller.start(initial, SkinSelection(preset.id, AppSkinOverrides()), preset.skin, preset.name)
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(preset.skin), lightColorScheme()) {
                CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                    AppSkinSettings(initial, onSelect = {}, onEdit = { _, _ -> }, onReset = {}, onClearOverrides = {})
                }
            }
        }

        compose.onNodeWithText("编辑全局样式").performClick()
        compose.onNodeWithText(context.getString(R.string.setting_skin_icon_tile)).performScrollTo().performClick()
        compose.onNodeWithText("跨页面试穿").performScrollTo().performClick()
        val trial = compose.runOnIdle { requireNotNull(controller.trial.value) }
        assertEquals(preset.id, trial.target.id)
        assertEquals(AppSkinOverrides(shapes = preset.skin.shapes.copy(iconTile = null)), trial.target.overrides)
        assertEquals("builtin.chat:premium-dark", effectiveChatPresetId(trial.target.applyTo(initial)))

        var persisted = initial
        runBlocking {
            controller.apply({ persisted = it(persisted) })
            assertEquals(trial.target, SkinSelection.from(persisted))
            controller.undo({ persisted = it(persisted) })
        }
        assertEquals(initial, persisted)
    }
}
