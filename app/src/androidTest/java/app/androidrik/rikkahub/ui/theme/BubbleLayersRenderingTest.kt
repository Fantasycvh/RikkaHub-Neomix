package app.androidrik.rikkahub.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.files.*
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.message.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BubbleLayersRenderingTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(target.cacheDir, "bubble-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(target) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }; requireNotNull(activity)
    }
    @After fun cleanup() { directory.deleteRecursively() }

    private fun png(width: Int = 4, height: Int = 4, vertical: Boolean = false): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) for (y in 0 until height) {
            bitmap.setPixel(x, y, if (vertical) when (y % 3) {
                0 -> android.graphics.Color.RED; 1 -> android.graphics.Color.GREEN; else -> android.graphics.Color.BLUE
            } else if (x < width / 2) android.graphics.Color.RED else android.graphics.Color.BLUE)
        }
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }

    @Test fun importBoundsImagesDeduplicatesAndPackagesTexturesWithoutDevicePaths() {
        val store = BubbleTextureStore(context)
        val source = png(2048, 1024)
        val id = store.importImage(source)
        assertEquals(id, store.importImage(source))
        val image = requireNotNull(store.load(id))
        assertTrue(image.width <= BubbleTextureStore.MAX_SIDE && image.height <= BubbleTextureStore.MAX_SIDE)
        val bubble = ChatBubble(stack = BubbleStack(layers = listOf(BubbleLayer(kind = BubbleLayerKind.TEXTURE,
            texture = BubbleTexture(id, BubbleTextureMode.MIRROR, 48f)))))
        val archive = store.exportMaterial(bubble)
        val copyRoot = File(directory, "copy").apply { mkdirs() }
        val copyContext = object : ContextWrapper(context) { override fun getFilesDir() = copyRoot }
        val copy = BubbleTextureStore(copyContext)
        assertEquals(bubble.normalized(), copy.importMaterial(archive))
        assertNotNull(copy.load(id))
        assertNull(store.file("../../bad"))
        assertThrows(IllegalArgumentException::class.java) { store.install("a".repeat(64), png()) }
    }

    @Test fun importedPngRetainsTransparency() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        bitmap.setPixel(1, 1, 0x80800000.toInt())
        val source = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val store = BubbleTextureStore(context)
        val decoded = requireNotNull(store.load(store.importImage(source)))
        assertEquals(0, android.graphics.Color.alpha(decoded.getPixel(0, 0)))
        assertEquals(128, android.graphics.Color.alpha(decoded.getPixel(1, 1)))
    }

    @Test fun layerOrderAndGlobalOpacityApplyToTheComposedGroup() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.background(Color.White).testTag("canvas")) {
                    ChatBubbleSurface(ChatBubble(opacity = 0.5f, stack = BubbleStack(layers = listOf(
                        BubbleLayer(id = "red", colorHex = "#FF0000"),
                        BubbleLayer(id = "blue", colorHex = "#0000FF", opacity = 0.5f)))),
                        Color.Green, Modifier.size(96.dp, 48.dp)) {}
                }
            }
        }
        val p = compose.onNodeWithTag("canvas").captureToImage().toPixelMap()
        val color = p[p.width / 2, p.height / 2]
        assertEquals(0.75f, color.red, 0.04f); assertEquals(0.5f, color.green, 0.04f); assertEquals(0.75f, color.blue, 0.04f)
    }

    @Test fun multiplyAndScreenBlendAgainstTheBubbleBase() {
        compose.setContent {
            MaterialTheme {
                Column {
                    listOf(BubbleBlend.MULTIPLY, BubbleBlend.SCREEN).forEach { blend ->
                        ChatBubbleSurface(ChatBubble(stack = BubbleStack(layers = listOf(
                            BubbleLayer(id = "base", colorHex = "#808080"),
                            BubbleLayer(id = "top", colorHex = "#808080", blend = blend)))),
                            Color.White, Modifier.size(96.dp, 40.dp).testTag(blend.name)) {}
                    }
                }
            }
        }
        val multiply = compose.onNodeWithTag("MULTIPLY").captureToImage().toPixelMap()
        val screen = compose.onNodeWithTag("SCREEN").captureToImage().toPixelMap()
        assertEquals(0.25f, multiply[multiply.width / 2, multiply.height / 2].red, 0.04f)
        assertEquals(0.75f, screen[screen.width / 2, screen.height / 2].red, 0.04f)
    }

    @Test fun textureTileAndMirrorModesUseTheImportedAsset() {
        val store = BubbleTextureStore(context)
        val id = store.importImage(png())
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                MaterialTheme {
                    Column {
                        listOf(BubbleTextureMode.TILE, BubbleTextureMode.MIRROR, BubbleTextureMode.COVER).forEach { mode ->
                            ChatBubbleSurface(ChatBubble(stack = BubbleStack(layers = listOf(
                                BubbleLayer(id = "texture", kind = BubbleLayerKind.TEXTURE, texture = BubbleTexture(id, mode, 16f))))),
                                Color.Green, Modifier.size(96.dp, 32.dp).testTag(mode.name)) {}
                        }
                    }
                }
            }
        }
        compose.waitUntil(5000) { store.cached(id) != null }
        compose.waitForIdle()
        val tile = compose.onNodeWithTag("TILE").captureToImage().toPixelMap()
        val mirror = compose.onNodeWithTag("MIRROR").captureToImage().toPixelMap()
        val cover = compose.onNodeWithTag("COVER").captureToImage().toPixelMap()
        assertTrue(tile[tile.width * 20 / 96, tile.height / 2].red > 0.8f)
        assertTrue(mirror[mirror.width * 20 / 96, mirror.height / 2].blue > 0.8f)
        assertTrue(cover[cover.width / 4, cover.height / 2].red > 0.8f)
        assertTrue(cover[cover.width * 3 / 4, cover.height / 2].blue > 0.8f)
    }

    @Test fun texturePhaseContinuesAcrossLazyBubbleSegments() {
        val store = BubbleTextureStore(context)
        val id = store.importImage(png(2, 3, vertical = true))
        val bitmap = requireNotNull(store.load(id))
        val style = ChatBubble(stack = BubbleStack(layers = listOf(BubbleLayer(id = "texture",
            kind = BubbleLayerKind.TEXTURE, texture = BubbleTexture(id, sizeDp = 16f)))))
        compose.setContent {
            val registry = remember { BubbleCoordinateRegistry() }
            CompositionLocalProvider(LocalBubbleTextureBitmaps provides mapOf(id to bitmap), LocalBubbleCoordinates provides registry) {
                MaterialTheme {
                    Column(Modifier.testTag("parts")) {
                        ChatBubbleSurface(style, Color.White, Modifier.size(96.dp, 20.dp),
                            segment = BubbleSegment(true, false, 0.dp, "message", "one")) {}
                        ChatBubbleSurface(style, Color.White, Modifier.size(96.dp, 20.dp),
                            segment = BubbleSegment(false, true, 0.dp, "message", "two")) {}
                    }
                }
            }
        }
        val p = compose.onNodeWithTag("parts").captureToImage().toPixelMap()
        assertTrue("second segment restarted the texture", p[p.width / 2, p.height / 2 + 2].blue > 0.6f)
    }
}
