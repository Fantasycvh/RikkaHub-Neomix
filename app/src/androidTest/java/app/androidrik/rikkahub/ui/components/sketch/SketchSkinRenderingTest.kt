package app.androidrik.rikkahub.ui.components.sketch

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SketchSkinRenderingTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }

    @Test fun skinInitializesPaperButDoesNotRecolorAnOpenDocumentOrItsExport() {
        var skin by mutableStateOf(AppSkin(embedded = AppSkinEmbedded(canvas = AppSkinCanvas(newPaperHex = "#112233"))))
        var document by mutableIntStateOf(0)
        var state: SketchState? = null
        compose.setContent { AppSkinTheme(ResolvedAppSkin(skin), lightColorScheme()) {
            key(document) {
                val current = rememberSkinSketchState(1f)
                state = current
                SketchCanvas(current, Modifier.size(200.dp).testTag("canvas"))
            }
        } }
        val first = requireNotNull(state)
        compose.runOnIdle { assertEquals(Color.White, first.color); first.usePreset(Color.Red) }
        compose.runOnIdle { skin = skin.copy(embedded = skin.embedded.copy(canvas = AppSkinCanvas(newPaperHex = "#445566"))) }
        compose.runOnIdle { assertSame(first, state); assertEquals(Color.Red, first.color) }
        val pixels = compose.onNodeWithTag("canvas").captureToImage().toPixelMap()
        assertEquals(0xff112233.toInt(), pixels[pixels.width / 2, pixels.height / 2].toArgb())
        compose.runOnIdle {
            val result = first.toResult()
            try { assertEquals(0xff112233.toInt(), result.bitmap.getPixel(0, 0)) } finally { result.bitmap.recycle() }
            document++
        }
        compose.runOnIdle { assertNotSame(first, state); assertEquals(Color(0xff445566), state!!.paperColor) }
    }

    @Test fun opaqueImportedContentAndBrushChoiceKeepTheirDocumentColors() {
        val state = SketchState(1f, Color(0xff112233)).apply { fit(Size(100f, 100f)); usePreset(Color.Red) }
        val bitmap = android.graphics.Bitmap.createBitmap(20, 20, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        state.useBackground(bitmap.asImageBitmap())
        val result = state.toResult()
        try {
            assertEquals(android.graphics.Color.BLUE, result.bitmap.getPixel(10, 10))
            assertEquals(Color.Red, state.color)
        } finally { result.bitmap.recycle(); bitmap.recycle() }
    }
}
