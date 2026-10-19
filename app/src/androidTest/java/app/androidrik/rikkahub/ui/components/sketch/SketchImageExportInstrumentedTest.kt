package app.androidrik.rikkahub.ui.components.sketch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.common.android.Logging
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 画板导出与底图读取：PNG/JPEG 格式与尺寸、橡皮不擦画纸/底图、EXIF 摆正与超大图缩小、
 * 原图不被改写。
 *
 * 全部图片都是测试自己生成在 `cacheDir` 下的临时文件或纯内存位图：不读用户图片、
 * 不联网。
 */
@RunWith(AndroidJUnit4::class)
class SketchImageExportInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun blankPaper_exportsPngWithAStableLongEdge() {
        val state = SketchState(aspectRatio = 16f / 9f)
        state.fit(Size(800f, 800f))
        assertEquals(800f, state.paperSize.width, EPSILON)
        assertEquals(450f, state.paperSize.height, 0.01f)

        val result = state.toResult()
        assertEquals("png", result.extension)
        assertEquals("image/png", result.mimeType)
        // 空白画纸的长边固定为 1536，与设备分辨率无关。
        assertEquals(1536, result.bitmap.width)
        assertEquals(864, result.bitmap.height)
        // 画纸是白色的：空图不能导出成透明图。
        assertEquals(Color.WHITE, result.bitmap.getPixel(0, 0))
        assertEquals(Color.WHITE, result.bitmap.getPixel(1535, 863))

        val encoded = result.encode()
        assertTrue(encoded.size > 8)
        assertEquals(0x89.toByte(), encoded[0])
        assertEquals('P'.code.toByte(), encoded[1])
        assertEquals('N'.code.toByte(), encoded[2])
        assertEquals('G'.code.toByte(), encoded[3])
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
        try {
            assertEquals(1536, decoded.width)
            assertEquals(864, decoded.height)
        } finally {
            decoded.recycle()
        }

        // 正方形画纸的长边同样是 1536。
        val square = SketchState(aspectRatio = 1f).apply { fit(Size(100f, 400f)) }
        assertEquals(100f, square.paperSize.width, EPSILON)
        assertEquals(100f, square.paperSize.height, EPSILON)
        val squareResult = square.toResult()
        assertEquals(1536, squareResult.bitmap.width)
        assertEquals(1536, squareResult.bitmap.height)
    }

    @Test
    fun backgroundPaper_exportsJpegAtTheBackgroundResolution() {
        val background = SketchTestImages.solid(300, 200)
        val state = SketchState()
        state.fit(Size(1000f, 1000f))
        state.useBackground(background)
        assertEquals(1000f, state.paperSize.width, EPSILON)
        assertEquals(666.667f, state.paperSize.height, 0.01f)

        val result = state.toResult()
        assertEquals("jpg", result.extension)
        assertEquals("image/jpeg", result.mimeType)
        // 垫图时按底图分辨率出图，底图不被重新缩放。
        assertEquals(300, result.bitmap.width)
        assertEquals(200, result.bitmap.height)
        assertEquals(Color.RED, result.bitmap.getPixel(150, 100))

        // 导出只产生新位图，内存里的底图本身不能被改动。
        assertNotSame(background.asAndroidBitmap(), result.bitmap)
        assertEquals(Color.RED, background.asAndroidBitmap().getPixel(0, 0))

        val encoded = result.encode()
        assertTrue(encoded.size > 4)
        assertEquals(0xFF.toByte(), encoded[0])
        assertEquals(0xD8.toByte(), encoded[1])
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
        try {
            assertEquals(300, decoded.width)
            assertEquals(200, decoded.height)
        } finally {
            decoded.recycle()
        }
    }

    @Test
    fun eraser_doesNotEraseThePaperOrTheBackgroundImage() {
        // 带底图：橡皮只擦笔画层，擦过的位置底图必须还在、而且不透明。
        val state = SketchState()
        state.fit(Size(1000f, 1000f))
        state.useBackground(SketchTestImages.solid(100, 100))
        state.useEraser(true)
        assertTrue(state.erasing)
        val line = state.erase(Offset(-200f, -200f), width = 20f)
        state.extend(line, Offset(200f, 200f))
        state.finish(line)
        assertFalse(state.isBlank)

        val result = state.toResult()
        assertEquals("jpg", result.extension)
        assertEquals(100, result.bitmap.width)
        assertEquals(100, result.bitmap.height)
        // (50,50) 是橡皮擦过的位置，其余是没碰过的底图。
        for ((x, y) in listOf(0 to 0, 50 to 50, 99 to 99)) {
            val pixel = result.bitmap.getPixel(x, y)
            assertEquals("eraser must keep the background opaque at ($x,$y)", 0xFF, Color.alpha(pixel))
            assertEquals("eraser must keep the background red at ($x,$y)", 0xFF, Color.red(pixel))
            assertEquals(0, Color.green(pixel))
            assertEquals(0, Color.blue(pixel))
        }

        // 空白画纸：橡皮也不该在白色画纸上擦出透明洞。
        val blank = SketchState(aspectRatio = 1f)
        blank.fit(Size(500f, 500f))
        blank.useEraser(true)
        val dot = blank.erase(Offset.Zero, width = 10f)
        blank.finish(dot)
        val blankResult = blank.toResult()
        assertEquals("png", blankResult.extension)
        assertEquals(
            Color.WHITE,
            blankResult.bitmap.getPixel(blankResult.bitmap.width / 2, blankResult.bitmap.height / 2),
        )
    }

    @Test
    fun loadBackground_honorsExifDownsamplesLargeImagesAndNeverRewritesTheSource() = runBlocking {
        val source = File(context.cacheDir, "sketch-source-${UUID.randomUUID()}.jpg")
        try {
            val pixels = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
            try {
                pixels.eraseColor(Color.RED)
                FileOutputStream(source).use { pixels.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            } finally {
                pixels.recycle()
            }
            // 像素是 40x60（竖），EXIF 写“旋转 90°”，摆正后应该是 60x40（横）。
            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val bytesBefore = source.readBytes()

            val loaded = requireNotNull(loadSketchBackground(context, Uri.fromFile(source)))
            assertEquals(60, loaded.width)
            assertEquals(40, loaded.height)
            // 读底图不能覆盖原图文件。
            assertArrayEquals(bytesBefore, source.readBytes())
        } finally {
            source.delete()
        }

        // 长边超过 2048 的图片在读入时按 2 的幂采样缩小。
        val large = File(context.cacheDir, "sketch-large-${UUID.randomUUID()}.png")
        try {
            val pixels = Bitmap.createBitmap(3000, 100, Bitmap.Config.ARGB_8888)
            try {
                pixels.eraseColor(Color.RED)
                FileOutputStream(large).use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                pixels.recycle()
            }
            val loaded = requireNotNull(loadSketchBackground(context, Uri.fromFile(large)))
            assertEquals(1500, loaded.width)
            assertEquals(50, loaded.height)
        } finally {
            large.delete()
        }

        // 读不出来时返回 null，不抛给调用方；同时留下应用内 stage=load_background 失败 marker，
        // 这是“无法加载图片”提示的来源，与保存失败（save_chat_file）区分。
        val missing = File(context.cacheDir, "sketch-missing-${UUID.randomUUID()}.jpg")
        assertNull(loadSketchBackground(context, Uri.fromFile(missing)))
        assertTrue(
            "missing load_background failure marker",
            Logging.getTextLogs().any {
                it.tag == "SketchImage" && it.message.contains("stage=load_background outcome=failed")
            },
        )
    }

    @Test
    fun encode_recycledBitmap_throwsInsteadOfReturningAnEmptyImage() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        val result = SketchResult(bitmap, photo = false)
        try {
            result.encode()
            fail("encode() must not silently return an image for a recycled bitmap")
        } catch (_: IllegalStateException) {
            // 预期：编码失败必须显式失败，不能把空内容/半张图当成功返回。
        }
    }

    private companion object {
        const val EPSILON = 0.01f
    }
}
