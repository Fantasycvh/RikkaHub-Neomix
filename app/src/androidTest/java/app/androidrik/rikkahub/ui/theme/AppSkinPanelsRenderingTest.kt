package app.androidrik.rikkahub.ui.theme

import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.datastore.*
import app.androidrik.rikkahub.data.files.*
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.ai.ContextUsage
import app.androidrik.rikkahub.ui.components.message.ChatMessageToolStep
import app.androidrik.rikkahub.ui.components.message.tools.*
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField
import app.androidrik.rikkahub.ui.components.ui.ChainOfThought
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.chat.DrawerEntry
import app.androidrik.rikkahub.ui.pages.chat.TopBar
import app.androidrik.rikkahub.ui.pages.setting.components.SkinPanelEditor
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.Lucide
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.HazeState
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppSkinPanelsRenderingTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir, "skin-panels-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
    private val handles = mutableListOf<AutoCloseable>()
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }
    @After fun cleanup() { handles.forEach { it.close() }; directory.deleteRecursively() }

    private fun panel(color: String = "#112233") = AppSkinPanel(background = ChatBubble(colorHex = color),
        radiusDp = 16f, contentColorHex = "#FFFFFF", borderWidthDp = 1f, paddingHorizontalDp = 12f, paddingVerticalDp = 8f)

    @Test fun defaultPanelSurfacePreservesOriginalMaterialPixelsAndSize() {
        compose.setContent { MaterialTheme { Row {
            Surface(Modifier.size(120.dp, 60.dp).testTag("old"), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow) { Text("Body") }
            AppSkinPanelSurface(AppSkinPanel(), Modifier.size(120.dp, 60.dp).testTag("new"),
                defaultShape = RoundedCornerShape(12.dp)) { Text("Body") }
        } } }
        val old = compose.onNodeWithTag("old").captureToImage().toPixelMap()
        val new = compose.onNodeWithTag("new").captureToImage().toPixelMap()
        assertEquals(old.width, new.width); assertEquals(old.height, new.height)
        for (x in 1..4) for (y in 1..4) {
            val a = old[old.width * x / 5, old.height * y / 5]
            val b = new[new.width * x / 5, new.height * y / 5]
            assertEquals(a.red, b.red, 0.015f); assertEquals(a.alpha, b.alpha, 0.015f)
        }
    }

    @Test fun topBarModesKeepDrawerAndMoreMenuActions() {
        var mode by mutableStateOf(TopBarChromeStyle.FULL_BAR)
        var drawerClicks = 0
        var newChatClicks = 0
        val assistant = Assistant(name = "Test")
        val settings = Settings(init = true, providers = emptyList(), assistants = listOf(assistant))
        compose.setContent {
            CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                AppSkinTheme(ResolvedAppSkin(AppSkin(topBar = AppSkinTopBar(mode, panel()))), lightColorScheme()) {
                    TopBar(settings, Conversation(assistantId = assistant.id, title = "Skin title", messageNodes = emptyList()), ContextUsage(32, 4096),
                        drawerOpen = false, onOpenDrawer = { drawerClicks++ }, bigScreen = false, previewMode = false,
                        hazeState = remember { HazeState() }, glassHazeState = remember { HazeState() }, showCapsuleBackground = true,
                        onClickMenu = {}, onNewChat = { newChatClicks++ }, onExport = {}, onUpdateTitle = {}, onShowTreeView = {})
                }
            }
        }
        compose.onNodeWithContentDescription("Messages").performClick()
        compose.runOnIdle { mode = TopBarChromeStyle.CAPSULES }
        compose.onNodeWithText("Skin title").assertIsDisplayed()
        compose.onNodeWithContentDescription("Messages").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText(target.getString(R.string.chat_page_new_chat)).performClick()
        compose.runOnIdle { assertEquals(2, drawerClicks); assertEquals(1, newChatClicks) }
    }

    @Test fun styledDrawerEntryKeepsTrailingSearchIndependentFromParent() {
        var parent = 0; var trailing = 0
        compose.setContent {
            AppSkinTheme(ResolvedAppSkin(AppSkin(drawer = AppSkinDrawer(item = panel()))), lightColorScheme()) {
                DrawerEntry(Lucide.ListTree, "History", { parent++ }, Modifier.width(240.dp),
                    trailingIcon = Lucide.ListTree, trailingDescription = "Search", onTrailingClick = { trailing++ })
            }
        }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.runOnIdle { assertEquals(0, parent); assertEquals(1, trailing) }
        compose.onNodeWithText("History").performClick()
        compose.runOnIdle { assertEquals(1, parent) }
    }

    @Test fun styledReasoningKeepsGroupCollapseAndExpandedStepAcrossSkinChanges() {
        var skin by mutableStateOf(AppSkin(cards = AppSkinCards(reasoning = panel())))
        compose.setContent { AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
            ChainOfThought(steps = listOf(1, 2, 3, 4), animateSizeChanges = false) { step ->
                ChainOfThoughtStep(label = { Text("Step $step") }, content = { Text("Detail $step") })
            }
        } }
        compose.onNodeWithText("Step 1").assertDoesNotExist()
        compose.onNodeWithText(target.getString(R.string.chain_of_thought_show_more_steps, 2)).performClick()
        compose.onNodeWithText("Step 1").assertIsDisplayed()
        compose.onNodeWithText("Step 4").performClick()
        compose.onNodeWithText("Detail 4").assertIsDisplayed()
        compose.runOnIdle { skin = skin.copy(cards = AppSkinCards(reasoning = panel("#334455"))) }
        compose.onNodeWithText("Detail 4").assertIsDisplayed()
        compose.onNodeWithText(target.getString(R.string.chain_of_thought_collapse)).performClick()
        compose.onNodeWithText("Step 1").assertDoesNotExist()
    }

    @Test fun reasoningMinimalAndCustomModesRetainTheirExplicitBackgrounds() {
        compose.setContent { AppSkinTheme(ResolvedAppSkin(AppSkin(cards = AppSkinCards(reasoning = panel("#0000FF")))), lightColorScheme()) {
            Column {
                listOf(ChainOfThoughtStyle.CARD, ChainOfThoughtStyle.MINIMAL, ChainOfThoughtStyle.CUSTOM).forEach { style ->
                    Box(Modifier.background(Color.White).testTag(style.name)) {
                        ChainOfThought(modifier = Modifier.width(200.dp), steps = listOf(1), style = style,
                            custom = if (style == ChainOfThoughtStyle.CUSTOM) ChainOfThoughtCustomStyle(containerColorHex = "#00FF00", cornerRadiusDp = 0f) else null,
                            animateSizeChanges = false) { ChainOfThoughtStep(label = { Text("Step") }) }
                    }
                }
            }
        } }
        fun color(tag: String): Color {
            val p = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            return p[p.width * 3 / 4, p.height / 2]
        }
        assertTrue(color("CARD").blue > 0.9f && color("CARD").red < 0.1f)
        assertTrue(color("MINIMAL").red > 0.9f)
        assertTrue(color("CUSTOM").green > 0.9f && color("CUSTOM").red < 0.1f)
    }

    @Test fun toolSkinKeepsApprovalSkipAndLiveSummaryInput() {
        val name = "test.skin.${UUID.randomUUID()}"
        handles.add(ToolUIRegistry.register(object : ToolUIRenderer {
            override val toolName = name
            @Composable override fun title(context: ToolUIContext) = "Test tool"
            override fun hasSummary(context: ToolUIContext) = true
            @Composable override fun Summary(context: ToolUIContext) {
                var text by remember { mutableStateOf("") }
                AppOutlinedTextField(text, { text = it }, Modifier.testTag("tool-input"))
            }
        }))
        var skin by mutableStateOf(AppSkin(cards = AppSkinCards(tool = panel())))
        var approved: String? = null; var skipped: String? = null
        val tool = UIMessagePart.Tool("call", name, "{}", approvalState = ToolApprovalState.Pending)
        compose.setContent { AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
            ChainOfThought(steps = listOf(tool), animateSizeChanges = false) { value ->
                ChatMessageToolStep(value, onToolApproval = { id, allow, _ -> if (allow) approved = id }, onToolSkip = { skipped = it })
            }
        } }
        compose.onNodeWithTag("tool-input").performTextInput("keep this")
        compose.runOnIdle { skin = skin.copy(cards = AppSkinCards(tool = panel("#445566"))) }
        compose.onNodeWithTag("tool-input").assertTextContains("keep this").assertIsFocused()
        compose.onNodeWithContentDescription(target.getString(R.string.chat_message_tool_approve)).performClick()
        compose.onNodeWithContentDescription(target.getString(R.string.chat_message_tool_skip)).performClick()
        compose.runOnIdle { assertEquals("call", approved); assertEquals("call", skipped) }
    }

    @Test fun panelEditorRetainsIncompleteColorsAndCommitsOnlyValidDrafts() {
        var saved: AppSkinPanel? = null
        compose.setContent { CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
            MaterialTheme { SkinPanelEditor("Test panel", AppSkinPanel(), onSave = { saved = it }, onDismiss = {}) }
        } }
        val field = compose.onNode(hasSetTextAction() and hasText("文字颜色（留空自动）"))
        field.performScrollTo().performTextReplacement("#12")
        field.assertTextContains("#12")
        compose.onNodeWithText("保存").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertNull(saved) }
        field.performScrollTo().performTextReplacement("#123456")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("#123456", saved!!.contentColorHex) }
    }

    @Test fun panelEditorCancelDoesNotCommit() {
        var saved = false; var dismissed = false
        compose.setContent { CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
            MaterialTheme { SkinPanelEditor("Test panel", AppSkinPanel(), onSave = { saved = true }, onDismiss = { dismissed = true }) }
        } }
        compose.onNode(hasSetTextAction() and hasText("文字颜色（留空自动）")).performScrollTo().performTextReplacement("#123456")
        compose.onNodeWithText("取消").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(saved) }
    }

    @Test fun themePackagesRestoreAllPanelTexturesAndRejectOversizedStacksBeforeInstallingAssets() {
        val store = BubbleTextureStore(context)
        var skin = AppSkin()
        AppSkinPanelSlot.entries.forEachIndexed { index, slot ->
            val image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF000000.toInt() or (index * 0x113355)) }
            val png = ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }; image.recycle()
            val id = store.importImage(png)
            skin = skin.withPanel(slot, panel().copy(background = ChatBubble(stack = BubbleStack(layers = listOf(
                BubbleLayer(id = "texture", kind = BubbleLayerKind.TEXTURE, texture = BubbleTexture(id)))))))
        }
        val snapshot = ThemeSnapshot(theme = CustomTheme(id = "test"), appearance =
            DisplaySetting(appSkinOverrides = AppSkinOverrides.from(skin)).toAppearanceSnapshot())
        val pack = store.exportTheme(snapshot)
        val copyRoot = File(directory, "copy").apply { mkdirs() }
        val copy = BubbleTextureStore(object : ContextWrapper(context) { override fun getFilesDir() = copyRoot })
        assertEquals(snapshot, copy.importTheme(pack))
        assertEquals(5, snapshot.textureIds().size)
        snapshot.textureIds().forEach { assertNotNull(copy.load(it)) }
        val bad = snapshot.copy(appearance = snapshot.appearance!!.copy(appSkin = AppSkin(cards = AppSkinCards(tool = AppSkinPanel(
            background = ChatBubble(stack = BubbleStack(layers = List(5) { BubbleLayer(id = "layer-$it") })))))))
        val archive = AppearanceArchive(Json.encodeToString(ThemeSnapshot.serializer(), bad), emptyMap()).encode()
        assertThrows(IllegalArgumentException::class.java) { copy.importTheme(archive) }
    }
}
