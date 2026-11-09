package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.files.BubbleTextureStore
import app.androidrik.rikkahub.data.files.exportMaterial
import app.androidrik.rikkahub.data.files.importMaterial
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.message.*
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.pages.setting.components.BubbleMaterialEditor
import com.dokar.sonner.rememberToasterState
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BubbleShaderRenderingTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }

    @Test fun nativeProgramsCompileAndUniformEditsReuseTheProgram() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val renderer = BubbleShaderRenderer()
        val first = renderer.prepare(BubbleShaderStyle(), 2f, Offset.Zero, hardware = true)
        assertTrue("AGSL program silently fell back", first is RuntimeShader)
        BubbleShaderTemplate.entries.forEach { template ->
            val next = renderer.prepare(BubbleShaderStyle.defaults(template).copy(phase = 0.4f, warp = 1.2f), 2f, Offset(2f, 37f), true)
            assertSame(first, next)
        }
        assertTrue(renderer.prepare(BubbleShaderStyle(), 2f, Offset.Zero, hardware = false) is BitmapShader)
    }

    @Test fun hardwareAndSoftwareTemplatesRenderTheSameColorsAndTransforms() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val runtimeDrawn = mutableSetOf<BubbleShaderTemplate>()
        compose.setContent {
            Column {
                BubbleShaderTemplate.entries.forEach { template ->
                    val style = BubbleShaderStyle.defaults(template).copy(scaleDp = 160f, angleDegrees = 31f,
                        phase = 0.23f, warp = 1.1f, contrast = 1.2f)
                    Row {
                        listOf(true, false).forEach { hardware ->
                            val renderer = remember { BubbleShaderRenderer() }
                            val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true } }
                            Box(Modifier.size(96.dp, 48.dp).testTag("$template-$hardware").drawBehind {
                                drawIntoCanvas {
                                    val native = it.nativeCanvas
                                    paint.shader = renderer.prepare(style, density, Offset(13f, 71f), hardware && native.isHardwareAccelerated)
                                    if (paint.shader is RuntimeShader) runtimeDrawn.add(template)
                                    native.drawRect(0f, 0f, size.width, size.height, paint)
                                }
                            })
                        }
                    }
                }
            }
        }
        BubbleShaderTemplate.entries.forEach { template ->
            val gpu = compose.onNodeWithTag("$template-true").captureToImage().toPixelMap()
            val cpu = compose.onNodeWithTag("$template-false").captureToImage().toPixelMap()
            for (x in 1..4) for (y in 1..3) {
                val a = gpu[gpu.width * x / 5, gpu.height * y / 4]
                val b = cpu[cpu.width * x / 5, cpu.height * y / 4]
                assertColorClose("$template ($x,$y)", a, b, 0.045f)
            }
        }
        compose.runOnIdle { assertEquals(BubbleShaderTemplate.entries.toSet(), runtimeDrawn) }
    }

    @Test fun softwareCanvasUsesARealMaterialAndKeepsGlobalPhase() {
        BubbleShaderTemplate.entries.forEach { template ->
            val style = BubbleShaderStyle.defaults(template).copy(scaleDp = 96f, angleDegrees = 47f, phase = 0.31f)
            val image = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888)
            val split = Bitmap.createBitmap(64, 80, Bitmap.Config.ARGB_8888)
            val painter = BubbleShaderRenderer()
            val paint = Paint().apply { isFilterBitmap = true }
            paint.shader = painter.prepare(style, 1f, Offset.Zero, false)
            android.graphics.Canvas(image).drawRect(0f, 0f, 64f, 80f, paint)
            val canvas = android.graphics.Canvas(split)
            canvas.save(); canvas.clipRect(0, 0, 64, 37)
            canvas.drawRect(0f, 0f, 64f, 37f, paint); canvas.restore()
            canvas.save(); canvas.translate(0f, 37f)
            paint.shader = painter.prepare(style, 1f, Offset(0f, 37f), false)
            canvas.drawRect(0f, 0f, 64f, 43f, paint); canvas.restore()
            val colors = mutableSetOf<Int>()
            for (x in 0 until 64) for (y in 0 until 80) {
                assertEquals("$template restarted on the second slice", image.getPixel(x, y), split.getPixel(x, y))
                colors.add(image.getPixel(x, y))
            }
            assertTrue(colors.size > 100)
            image.recycle(); split.recycle()
        }
    }

    @Test fun shaderLayersShareCoordinatesAcrossLazyMessageSlices() {
        val style = ChatBubble(geometry = ChatBubbleGeometry(radiusDp = 0f, paddingDp = 0f),
            stack = BubbleStack(layers = listOf(BubbleLayer(id = "shader", kind = BubbleLayerKind.SHADER,
                shader = BubbleShaderStyle(template = BubbleShaderTemplate.MARBLE, angleDegrees = 29f, phase = 0.2f)))))
        compose.setContent {
            val registry = remember { BubbleCoordinateRegistry() }
            CompositionLocalProvider(LocalBubbleCoordinates provides registry) {
                MaterialTheme {
                    Row {
                        ChatBubbleSurface(style, Color.White, Modifier.size(96.dp, 80.dp).testTag("whole")) {}
                        Column(Modifier.testTag("parts")) {
                            ChatBubbleSurface(style, Color.White, Modifier.size(96.dp, 37.dp),
                                segment = BubbleSegment(true, false, 0.dp, "shader", "one")) {}
                            ChatBubbleSurface(style, Color.White, Modifier.size(96.dp, 43.dp),
                                segment = BubbleSegment(false, true, 0.dp, "shader", "two")) {}
                        }
                    }
                }
            }
        }
        val whole = compose.onNodeWithTag("whole").captureToImage().toPixelMap()
        val parts = compose.onNodeWithTag("parts").captureToImage().toPixelMap()
        for (x in 1..4) for (y in 1..7) assertColorClose("slice ($x,$y)",
            whole[whole.width * x / 5, whole.height * y / 8], parts[parts.width * x / 5, parts.height * y / 8], 0.025f)
    }

    @Test fun shaderMaterialAndThemePackagesRoundTripWithoutTextureAssets() {
        val store = BubbleTextureStore(target)
        val bubble = ChatBubble(stack = BubbleStack(layers = listOf(BubbleLayer(id = "shader", kind = BubbleLayerKind.SHADER,
            blend = BubbleBlend.OVERLAY, opacity = 0.7f,
            shader = BubbleShaderStyle.defaults(BubbleShaderTemplate.RIPPLES).copy(scaleDp = 200f, phase = 0.6f)))))
        assertEquals(bubble, store.importMaterial(store.exportMaterial(bubble)))
        val snapshot = ThemeSnapshot(theme = CustomTheme(name = "Shader"), appearance =
            app.androidrik.rikkahub.data.datastore.DisplaySetting().withChatAppearance(ChatAppearance(userBubble = bubble)).toAppearanceSnapshot())
        assertEquals(snapshot, store.importTheme(store.exportTheme(snapshot)))
        assertTrue(snapshot.textureIds().isEmpty())
    }

    @Test fun shaderLayersHonorBlendGlobalOpacityAndBubbleClipping() {
        compose.setContent {
            MaterialTheme {
                Column {
                    listOf(BubbleBlend.MULTIPLY, BubbleBlend.SCREEN).forEach { blend ->
                        Box(Modifier.background(Color.White).testTag("blend-$blend")) {
                            ChatBubbleSurface(ChatBubble(opacity = 0.5f, stack = BubbleStack(layers = listOf(
                                BubbleLayer(id = "base", colorHex = "#808080"),
                                BubbleLayer(id = "shader", kind = BubbleLayerKind.SHADER, opacity = 0.5f, blend = blend,
                                    shader = BubbleShaderStyle(colors = List(3) { "#808080" }))))),
                                Color.White, Modifier.size(96.dp, 48.dp)) {}
                        }
                    }
                }
            }
        }
        val multiply = if (Build.VERSION.SDK_INT >= 29) 0.6875f else 0.8125f // legacy PorterDuff modulates alpha too
        listOf(BubbleBlend.MULTIPLY to multiply, BubbleBlend.SCREEN to 0.8125f).forEach { (blend, expected) ->
            val p = compose.onNodeWithTag("blend-$blend").captureToImage().toPixelMap()
            val center = p[p.width / 2, p.height / 2]
            assertColorClose("$blend", Color(expected, expected, expected), center, 0.03f)
            assertColorClose("corner was not clipped", Color.White, p[0, 0], 0.01f)
        }
    }

    @Test fun shaderEditorRetainsIncompleteColorInputAndOnlyCommitsOnSave() {
        val initial = ChatBubble(stack = BubbleStack(layers = listOf(BubbleLayer(id = "shader", kind = BubbleLayerKind.SHADER))))
        var saved: ChatBubble? = null
        compose.setContent {
            CompositionLocalProvider(LocalToaster provides rememberToasterState()) {
                MaterialTheme { BubbleMaterialEditor("Shader test", initial, onSave = { saved = it }, onDismiss = {}) }
            }
        }
        val color = compose.onNode(hasSetTextAction() and hasText("Shader 颜色 1"))
        color.performScrollTo().performTextReplacement("#12")
        color.assertTextContains("#12")
        compose.onNodeWithText("保存").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertNull(saved) }
        color.performScrollTo().performTextReplacement("#123456")
        compose.onNodeWithContentDescription("Shader 相位").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        compose.onNodeWithText("保存").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("#123456", saved!!.stack!!.layers.single().shader.colors[0])
            assertEquals(0.6f, saved!!.stack!!.layers.single().shader.phase)
            assertEquals(BubbleShaderStyle(), initial.stack!!.layers.single().shader)
        }
    }

    private fun assertColorClose(label: String, a: Color, b: Color, tolerance: Float) {
        assertEquals("$label red", a.red, b.red, tolerance)
        assertEquals("$label green", a.green, b.green, tolerance)
        assertEquals("$label blue", a.blue, b.blue, tolerance)
    }
}
