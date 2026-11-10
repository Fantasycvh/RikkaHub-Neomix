package app.androidrik.rikkahub.ui.theme

import android.content.*
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.AppSkin
import app.androidrik.rikkahub.data.files.SkinPackageStore
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.setting.components.SkinPackageManager
import com.dokar.sonner.rememberToasterState
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SkinPackageManagerTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir, "skin-manager-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = directory
    }
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule -> var activity: SketchTestHostActivity? = null; rule.scenario.onActivity { activity = it }; requireNotNull(activity) }
    @After fun cleanup() { directory.deleteRecursively() }
    @Test fun historyControlsSwitchTheInstalledVersionAfterConfirmation() {
        val store = SkinPackageStore(context, AppSkinRegistry())
        store.install(store.exportSkin("fixture", "Fixture", 1, AppSkin()))
        store.install(store.exportSkin("fixture", "Fixture", 2, AppSkin()))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalToaster provides rememberToasterState(),
                LocalActivityResultRegistryOwner provides compose.activity) {
                AppSkinTheme(ResolvedAppSkin(), lightColorScheme()) {
                    SkinPackageManager(AppSkin(), "package:fixture", {}, {}, packageStore = store)
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("历史版本 / 回滚").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("历史版本 / 回滚").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("v1 · Fixture · 切换到此版本").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("v1 · Fixture · 切换到此版本").performScrollTo().performClick()
        compose.onNodeWithText("确认切换").performClick()
        compose.waitUntil(10_000) { store.list().single().version == 1 }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Fixture · v1\nfixture").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(2, 1), store.history("fixture").map { it.version })
    }
    @Test fun metadataRemainsEditableAcrossSkinChangesAndRejectsInvalidIds() {
        var skin by mutableStateOf(AppSkin())
        var dismissed = false
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalToaster provides rememberToasterState(),
                LocalActivityResultRegistryOwner provides compose.activity) {
                AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
                    SkinPackageManager(skin, null, {}, { dismissed = true })
                }
            }
        }
        compose.onNodeWithText("名称").performScrollTo().performTextReplacement("编辑中的皮肤")
        compose.runOnIdle { skin = skin.copy(typography = skin.typography.copy(sizeRatio = 1.2f)) }
        compose.onNodeWithText("名称").assertTextContains("编辑中的皮肤").assertIsFocused()
        compose.onNodeWithText("稳定 ID（升级时保持相同）").performScrollTo().performTextReplacement("../bad")
        compose.onNodeWithText("导出当前皮肤 ZIP").assertIsNotEnabled()
        compose.onNodeWithText("稳定 ID（升级时保持相同）").performTextReplacement("valid-id")
        compose.onNodeWithText("导出当前皮肤 ZIP").assertIsEnabled()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
