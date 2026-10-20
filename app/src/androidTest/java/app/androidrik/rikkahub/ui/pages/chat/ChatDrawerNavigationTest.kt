package app.androidrik.rikkahub.ui.pages.chat

import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.ui.context.Navigator
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Click the production drawer controls and verify the real Navigator's complete back stack. */
@RunWith(AndroidJUnit4::class)
class ChatDrawerNavigationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backStack = mutableListOf<NavKey>()
    private val navigator = Navigator(backStack)

    // Explicit target-package Intent avoids AndroidX inferring the instrumentation APK as host.
    @get:Rule
    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { activityRule ->
        var activity: SketchTestHostActivity? = null
        activityRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test
    fun historyEntryNavigatesToHistory() {
        showTopActions()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_page_history))
            .clickAndAssertDestination(Screen.History)
    }

    @Test
    fun trailingSearchNavigatesOnlyToMessageSearch() {
        showTopActions()
        // The nested search action must not also activate the containing History Surface.
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_page_search_chats))
            .clickAndAssertDestination(Screen.MessageSearch)
    }

    @Test
    fun mediaEntryNavigatesToMediaCreationSessions() {
        showTopActions()
        rule.onNodeWithText(rule.activity.getString(R.string.media_creation_title))
            .clickAndAssertDestination(Screen.MediaCreationSessions)
    }

    @Test
    fun translatorEntryNavigatesToTranslator() {
        showTopActions()
        rule.onNodeWithText(rule.activity.getString(R.string.chat_page_menu_ai_translator))
            .clickAndAssertDestination(Screen.Translator)
    }

    @Test
    fun assistantActionNavigatesToAssistant() {
        showBottomActions()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.assistant_page_title))
            .clickAndAssertDestination(Screen.Assistant)
    }

    @Test
    fun favoriteActionNavigatesToFavorite() {
        showBottomActions()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.favorite_page_title))
            .clickAndAssertDestination(Screen.Favorite)
    }

    @Test
    fun statsActionNavigatesToStats() {
        showBottomActions()
        rule.onNodeWithContentDescription("统计数据")
            .clickAndAssertDestination(Screen.Stats)
    }

    @Test
    fun settingsActionNavigatesToSetting() {
        showBottomActions()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.settings))
            .clickAndAssertDestination(Screen.Setting)
    }

    private fun showTopActions() {
        rule.setContent {
            MaterialTheme { DrawerActions(navController = navigator) }
        }
    }

    private fun showBottomActions() {
        rule.setContent {
            MaterialTheme { DrawerBottomActions(navController = navigator) }
        }
    }

    private fun SemanticsNodeInteraction.clickAndAssertDestination(destination: Screen) {
        performClick()
        rule.runOnIdle {
            assertEquals(listOf<NavKey>(destination), backStack)
        }
    }
}
