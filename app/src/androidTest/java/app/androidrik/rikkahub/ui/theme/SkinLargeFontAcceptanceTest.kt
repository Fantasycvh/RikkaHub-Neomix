package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.SkinLargeFontTestHostActivity
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.setting.components.AppSkinEditor
import com.dokar.sonner.rememberToasterState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class SkinLargeFontAcceptanceTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SkinLargeFontTestHostActivity>(Intent(target, SkinLargeFontTestHostActivity::class.java)),
    ) { rule ->
        var activity: SkinLargeFontTestHostActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }

    @Test fun lightEditorKeepsAllActionsAt200PercentAndMaximumSkinFont() = editor(false)
    @Test fun darkEditorKeepsAllActionsAt200PercentAndMaximumSkinFont() = editor(true)

    private fun editor(dark: Boolean) {
        val skin = AppSkin(typography = AppSkinTypography(sizeRatio = 1.5f))
        var saved = false
        var tried = false
        var cancelled = false
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 720.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                        AppSkinTheme(ResolvedAppSkin(skin), if (dark) darkColorScheme() else lightColorScheme()) {
                            AppSkinEditor(skin, { saved = true }, { cancelled = true }, { tried = true })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("保存").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("保存", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, .001f)
        assertTrue(layouts.single().layoutInput.style.fontSize.value >= 20f)
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { assertTrue(saved) }
        compose.onNodeWithText("取消").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
        compose.onNodeWithText("跨页面试穿").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(tried) }
    }
}
