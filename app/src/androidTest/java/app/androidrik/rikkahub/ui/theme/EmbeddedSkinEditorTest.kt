package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.pages.setting.components.EmbeddedSkinEditor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddedSkinEditorTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }

    @Test fun incompleteColorStaysEditableAndValidSaveReturnsOnlyTheDraft() {
        var saved: AppSkinEmbedded? = null
        compose.setContent { AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
            EmbeddedSkinEditor(AppSkin(), onSave = { saved = it }, onDismiss = {})
        } }
        compose.onNodeWithTag("embedded_终端底色").performScrollTo().performTextInput("#12")
        compose.onNodeWithTag("embedded_终端底色").assertTextContains("#12").assertIsFocused()
        compose.onNodeWithTag("embedded_skin_save").assertIsNotEnabled()
        compose.onNodeWithTag("embedded_终端底色").performTextReplacement("#123456")
        compose.onNodeWithTag("embedded_skin_save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("#123456", saved!!.terminal.backgroundHex); assertNull(saved!!.canvas.newPaperHex) }
    }

    @Test fun cancellingEmbeddedEditorDiscardsItsRawDraft() {
        var saved = false
        var open by mutableStateOf(true)
        compose.setContent { AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
            if (open) EmbeddedSkinEditor(AppSkin(), onSave = { saved = true }, onDismiss = { open = false })
        } }
        compose.onNodeWithTag("embedded_网页底色").performScrollTo().performTextInput("#aabbcc")
        compose.onNodeWithTag("embedded_skin_cancel").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(saved); assertFalse(open) }
    }
}
