package app.androidrik.rikkahub.ui.theme

import android.content.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.files.BubbleTextureStore
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.ui.*
import app.androidrik.rikkahub.ui.pages.chat.AssistantBackground
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SkinResourceRenderingTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir, "skin-render-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = directory
    }
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule -> var activity: SketchTestHostActivity? = null; rule.scenario.onActivity { activity = it }; requireNotNull(activity) }
    @After fun cleanup() { directory.deleteRecursively() }
    private fun image(color: Int): String {
        val bytes = ByteArrayOutputStream().use { out ->
            val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray()
        }
        return BubbleTextureStore(context).importImage(bytes)
    }
    @Test fun logoHotSwapUsesTheResourceWithoutAdaptiveIconCropping() {
        val blue = image(android.graphics.Color.BLUE); val red = image(android.graphics.Color.RED)
        var logo by mutableStateOf(blue)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) {
            AppSkinTheme(ResolvedAppSkin(AppSkin(resources = AppSkinResources(logo = logo))), lightColorScheme()) {
                Box(Modifier.size(80.dp).testTag("logo").drawAppLogo(rememberAppLogoLayers()))
            }
        } }
        compose.waitUntil(10_000) { compose.onNodeWithTag("logo").captureToImage().toPixelMap()[2, 2].blue > .95f }
        compose.runOnIdle { logo = red }
        compose.waitUntil(10_000) { compose.onNodeWithTag("logo").captureToImage().toPixelMap()[2, 2].red > .95f }
    }
    @Test fun assistantImageTakesPriorityOverTheSkinBackground() {
        val blue = image(android.graphics.Color.BLUE); val red = image(android.graphics.Color.RED)
        var assistant by mutableStateOf(Assistant())
        compose.setContent { CompositionLocalProvider(LocalContext provides context) {
            AppSkinTheme(ResolvedAppSkin(AppSkin(resources = AppSkinResources(chatBackground = blue))), lightColorScheme()) {
                AssistantBackground(Settings(assistantId = assistant.id, assistants = listOf(assistant)), Modifier.size(100.dp).testTag("background"))
            }
        } }
        compose.waitUntil(10_000) {
            val p = compose.onNodeWithTag("background").captureToImage().toPixelMap()[20, 20]; p.blue > p.red + .3f
        }
        compose.runOnIdle { assistant = assistant.copy(background = android.net.Uri.fromFile(BubbleTextureStore(context).file(red)).toString()) }
        compose.waitUntil(10_000) {
            val p = compose.onNodeWithTag("background").captureToImage().toPixelMap()[20, 20]; p.red > p.blue + .3f
        }
    }
}
