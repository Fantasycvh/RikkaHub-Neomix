package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Icon
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.AppSkin
import app.androidrik.rikkahub.data.model.AppSkinHero
import app.androidrik.rikkahub.data.model.AppSkinIconTile
import app.androidrik.rikkahub.data.model.AppSkinShapes
import app.androidrik.rikkahub.data.model.AppearanceGradient
import app.androidrik.rikkahub.ui.components.ui.AppSkinListItemIcon
import app.androidrik.rikkahub.ui.components.ui.CardGroupIcon
import app.androidrik.rikkahub.ui.pages.setting.SettingFeatureShortcut
import com.composables.icons.lucide.BellRing
import com.composables.icons.lucide.Lucide
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSkinHeroRenderingTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test fun iconWrapperMatchesBarePixelsUntilTheSkinEnablesTiles() {
        var shapes by mutableStateOf(AppSkinShapes(continuous = true))
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(AppSkin(shapes = shapes)), lightColorScheme()) {
                Column {
                    Icon(Lucide.BellRing, null, Modifier.testTag("original"))
                    AppSkinListItemIcon(Lucide.BellRing, "Notification", Modifier.testTag("wrapper"))
                    CardGroupIcon(Lucide.BellRing, Modifier.testTag("circle"))
                }
            }
        }

        val original = compose.onNodeWithTag("original").captureToImage().toPixelMap()
        val wrapped = compose.onNodeWithTag("wrapper").captureToImage().toPixelMap()
        assertEquals(original.width, wrapped.width)
        assertEquals(original.height, wrapped.height)
        for (x in 0 until original.width) for (y in 0 until original.height) {
            assertEquals(original[x, y], wrapped[x, y])
        }
        compose.onNodeWithTag("circle").assertWidthIsEqualTo(40.dp)

        compose.runOnIdle { shapes = shapes.copy(iconTile = AppSkinIconTile(sizeDp = 48f)) }
        compose.onNodeWithTag("wrapper").assertWidthIsEqualTo(48.dp)
        compose.onNodeWithTag("circle").assertWidthIsEqualTo(48.dp)
    }

    @Test fun shortcutUsesOneClickTargetAndBothRoutesAtLargeFontInRtl() {
        val light = "#D6ECFF"
        val dark = "#16344F"
        var hero by mutableStateOf(AppSkinHero(
            visible = true,
            lightGradient = AppearanceGradient(listOf(light, light)),
            darkGradient = AppearanceGradient(listOf(dark, dark)),
            decorationOpacity = 0f,
        ))
        var darkMode by mutableStateOf(false)
        var taskCenterEnabled by mutableStateOf(false)
        val routes = mutableListOf<Screen>()
        compose.setContent {
            val density = LocalDensity.current.density
            AppSkinTheme(ResolvedAppSkin(AppSkin(hero = hero)), if (darkMode) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(
                    LocalDarkMode provides darkMode,
                    LocalDensity provides Density(density, fontScale = 2f),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    SettingFeatureShortcut(taskCenterEnabled, { routes.add(it) }, Modifier.testTag("shortcut"))
                }
            }
        }

        val shortcut = compose.onNodeWithTag("shortcut")
        shortcut.assertHeightIsAtLeast(48.dp).assertHasClickAction()
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText(target.getString(R.string.setting_page_feature_shortcut_title)).assertIsDisplayed()
        compose.onNodeWithText(target.getString(R.string.setting_page_feature_shortcut_desc)).assertIsDisplayed()
        val lightPixels = shortcut.captureToImage().toPixelMap()
        assertEquals(Color(0xFFD6ECFF), lightPixels[lightPixels.width / 2, 2])

        compose.runOnIdle { darkMode = true }
        val darkPixels = shortcut.captureToImage().toPixelMap()
        assertEquals(Color(0xFF16344F), darkPixels[darkPixels.width / 2, 2])

        shortcut.performClick()
        compose.runOnIdle { taskCenterEnabled = true }
        shortcut.performClick()
        compose.runOnIdle { assertEquals(listOf(Screen.SettingScheduledTasks, Screen.TaskCenter), routes) }

        compose.runOnIdle { hero = AppSkinHero() }
        shortcut.assertDoesNotExist()
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(0)
    }
}
