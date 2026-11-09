package app.androidrik.rikkahub.ui.theme

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.datastore.ChatFontFamily
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.files.*
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.ui.*
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.setting.components.AppSkinEditor
import com.dokar.sonner.rememberToasterState
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppSkinRenderingTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val handles = mutableListOf<AutoCloseable>()
    private val directory = File(target.cacheDir, "appskin-test-${UUID.randomUUID()}")
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }
    @After fun cleanup() { handles.forEach { it.close() }; directory.deleteRecursively() }

    @Test fun defaultSkinPreservesMaterialTypographyShapesAndGroupedCardPixels() {
        var oldTypography: Typography? = null
        var newTypography: Typography? = null
        var oldShapes: Shapes? = null
        var newShapes: Shapes? = null
        compose.setContent {
            Row {
                MaterialExpressiveTheme {
                    oldTypography = MaterialTheme.typography; oldShapes = MaterialTheme.shapes
                    Column(Modifier.width(160.dp).testTag("old")) { FixtureCard() }
                }
                AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
                    newTypography = MaterialTheme.typography; newShapes = MaterialTheme.shapes
                    Column(Modifier.width(160.dp).testTag("new")) { FixtureCard() }
                }
            }
        }
        compose.runOnIdle { assertEquals(oldTypography, newTypography); assertEquals(oldShapes, newShapes) }
        val old = compose.onNodeWithTag("old").captureToImage().toPixelMap()
        val new = compose.onNodeWithTag("new").captureToImage().toPixelMap()
        assertEquals(old.width, new.width); assertEquals(old.height, new.height)
        for (x in 1..4) for (y in 1..5) {
            val a = old[old.width * x / 5, old.height * y / 6]
            val b = new[new.width * x / 5, new.height * y / 6]
            assertEquals(a.red, b.red, 0.015f); assertEquals(a.alpha, b.alpha, 0.015f)
        }
    }

    @Composable private fun FixtureCard() { CardGroup {
        item(headlineContent = { Text("Title") }, supportingContent = { Text("Body") })
        formItem { AppOutlinedTextField("Input", {}, readOnly = true); AppButton(onClick = {}) { Text("Button") } }
    } }

    @Test fun liveSkinSwitchChangesTypographyAndSpacingWithoutLosingInputOrFocus() {
        var display by mutableStateOf(DisplaySetting())
        var text by mutableStateOf("")
        var fontSize = 0f
        var clicks = 0
        compose.setContent {
            AppSkinTheme(rememberAppSkin(display), lightColorScheme()) {
                fontSize = MaterialTheme.typography.bodyLarge.fontSize.value
                CardGroup(Modifier.testTag("group")) { formItem {
                    AppOutlinedTextField(text, { text = it }, modifier = Modifier.testTag("input"))
                    AppButton(onClick = { clicks++ }, modifier = Modifier.testTag("button")) { Text("Click") }
                } }
            }
        }
        val before = compose.onNodeWithTag("group").fetchSemanticsNode().size.height
        var baseFontSize = 0f
        compose.runOnIdle { baseFontSize = fontSize }
        compose.onNodeWithTag("input").performTextInput("preserve input")
        compose.runOnIdle { display = display.copy(appSkinId = "builtin.reading") }
        compose.onNodeWithTag("input").assertTextContains("preserve input").assertIsFocused()
        compose.runOnIdle { assertEquals(baseFontSize * 1.1f, fontSize) }
        assertTrue(compose.onNodeWithTag("group").fetchSemanticsNode().size.height > before)
        compose.onNodeWithTag("button").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun pluginDisableFallsBackAndReenableUpdatesTheSameSelectionKeepingOverrides() {
        val owner = "test.appskin.${UUID.randomUUID()}"
        val preset = AppSkinPreset("font", "Plugin font", AppSkin(typography = AppSkinTypography(ChatFontFamily.SERIF)))
        val first = AppSkinRegistry.shared.register(owner, listOf(preset)).also { handles.add(it) }
        val display = DisplaySetting(appSkinId = "$owner:font", appSkinOverrides = AppSkinOverrides(shapes = AppSkinShapes(inputDp = 2f)))
        var font: FontFamily? = null
        var radius = 0f
        compose.setContent {
            AppSkinTheme(rememberAppSkin(display), lightColorScheme()) {
                font = MaterialTheme.typography.bodyLarge.fontFamily
                radius = LocalAppSkin.current.skin.shapes.inputDp
                Text("Plugin")
            }
        }
        compose.runOnIdle { assertEquals(FontFamily.Serif, font); first.close() }
        compose.runOnIdle { assertEquals(Typography.bodyLarge.fontFamily, font); assertEquals(2f, radius) }
        compose.runOnIdle { handles.add(AppSkinRegistry.shared.register(owner, listOf(preset.copy(skin = AppSkin(
            typography = AppSkinTypography(ChatFontFamily.MONOSPACE)))))) }
        compose.runOnIdle { assertEquals(FontFamily.Monospace, font); assertEquals(2f, radius) }
    }

    @Test fun buttonTokensChangeCornersWhileExplicitCallerShapesStillWin() {
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(AppSkin(shapes = AppSkinShapes(buttonDp = 24f))), lightColorScheme()) {
                Column {
                    Box(Modifier.background(Color.White).testTag("rounded")) {
                        AppButton(onClick = {}, modifier = Modifier.size(120.dp, 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)) { Text("Round") }
                    }
                    Box(Modifier.background(Color.White).testTag("square")) {
                        AppButton(onClick = {}, modifier = Modifier.size(120.dp, 48.dp), shape = RoundedCornerShape(0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)) { Text("Square") }
                    }
                }
            }
        }
        val rounded = compose.onNodeWithTag("rounded").captureToImage().toPixelMap()
        val square = compose.onNodeWithTag("square").captureToImage().toPixelMap()
        assertTrue(rounded[2, 2].red > 0.9f)
        assertTrue(square[2, 2].blue > 0.9f && square[2, 2].red < 0.1f)
    }

    @Test fun chatFontsInheritGlobalDefaultsAndKeepExplicitOverrides() {
        val display = DisplaySetting(appSkinOverrides = AppSkinOverrides(typography = AppSkinTypography(ChatFontFamily.SERIF)))
        var inherited: FontFamily? = null
        var explicit: FontFamily? = null
        compose.setContent {
            AppSkinTheme(resolveAppSkin(display), lightColorScheme()) {
                inherited = rememberChatFontFamily(display)
                explicit = rememberChatFontFamily(display.copy(chatFontFamily = ChatFontFamily.MONOSPACE))
            }
        }
        compose.runOnIdle {
            assertEquals(FontFamily.Serif, inherited)
            assertEquals(FontFamily.Monospace, explicit)
            assertEquals(FontFamily.Serif, display.resolveChatFontFamily(target))
            assertEquals(FontFamily.Monospace, display.copy(chatFontFamily = ChatFontFamily.MONOSPACE).resolveChatFontFamily(target))
            assertEquals(FontFamily.Default, display.copy(appSkinOverrides = AppSkinOverrides(typography =
                AppSkinTypography(ChatFontFamily.CUSTOM, "../../outside.ttf"))).resolveChatFontFamily(target))
        }
    }

    @Test fun globalEditorKeepsDraftLocalUntilSave() {
        var saved: AppSkin? = null
        var globalRatio = 0f
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
                globalRatio = LocalAppSkin.current.skin.typography.sizeRatio
                CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                    AppSkinEditor(AppSkin(), onSave = { saved = it }, onDismiss = {})
                }
            }
        }
        compose.onNodeWithContentDescription("皮肤 字号比例").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.25f) }
        compose.runOnIdle { assertNull(saved); assertEquals(1f, globalRatio) }
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1.25f, saved!!.typography.sizeRatio); assertEquals(1f, globalRatio) }
    }

    @Test fun globalEditorCancelDoesNotCommitItsDraft() {
        var saved = false
        var dismissed = false
        compose.setContent { CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
            AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
                AppSkinEditor(AppSkin(), onSave = { saved = true }, onDismiss = { dismissed = true })
            }
        } }
        compose.onNodeWithContentDescription("皮肤 卡片外圆角").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(32f) }
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(saved) }
    }

    @Test fun fontDraftImportsPreserveOtherFontsAndRejectOversizedInputs() {
        directory.mkdirs()
        val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
        val store = SkinFontStore(context)
        val font = target.resources.openRawResource(R.font.jetbrains_mono).use { it.readBytes() }
        val first = store.importFont(font.inputStream(), "First.ttf")
        val oldChat = File(directory, "${FileFolders.FONTS}/chat_font.keep.ttf").apply { writeBytes(font) }
        val second = store.importFont(font.inputStream(), "Second.ttf")
        assertNotEquals(first.relativePath, second.relativePath)
        assertTrue(File(directory, first.relativePath).isFile)
        assertTrue(File(directory, second.relativePath).isFile)
        assertTrue(oldChat.isFile)
        assertThrows(IllegalArgumentException::class.java) { store.importFont(ByteArray(SkinFontStore.MAX_BYTES + 1).inputStream(), "Huge.ttf") }
        assertThrows(IllegalArgumentException::class.java) { store.importFont(byteArrayOf().inputStream(), "Empty.ttf") }
    }
}
