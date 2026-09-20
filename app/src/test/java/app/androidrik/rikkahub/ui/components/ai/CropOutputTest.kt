package app.androidrik.rikkahub.ui.components.ai

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁剪产物「编码格式 ↔ 文件扩展名」一致性（吸收上游 `7c1629d0` / #1892）。
 *
 * 锁的是一个**真实发生过的**缺陷类：文件名写死 `.jpg` 而编码用 PNG，导致产物
 * 「扩展名说 JPEG、内容说 PNG」——真机实证 `files/upload/` 下 5 个 `.jpg` 全以
 * PNG magic 开头。修复方式不是把 PNG 改成 JPEG 就完（那只改了这一次），而是让
 * 扩展名**从格式派生**，再加本测试钉住派生关系与格式选择。
 */
class CropOutputTest {

    @Test
    fun `extension mapping covers the formats we may configure`() {
        assertEquals("jpg", CropOutput.extensionOf(Bitmap.CompressFormat.JPEG))
        assertEquals("png", CropOutput.extensionOf(Bitmap.CompressFormat.PNG))
    }

    @Test
    fun `configured format must be mappable so the name never degrades to bin`() {
        // 换格式（如 WEBP_LOSSY）却没扩映射时会静默产出 .bin —— 这条挡住它
        assertNotEquals("bin", CropOutput.extensionOf(CropOutput.FORMAT))
    }

    @Test
    fun `extension is derived from the format, not hardcoded`() {
        assertEquals(CropOutput.extensionOf(CropOutput.FORMAT), CropOutput.EXTENSION)
    }

    @Test
    fun `output filename carries the derived extension`() {
        val name = CropOutput.fileName(1_700_000_000_000L)
        assertTrue("expected $name to end with .${CropOutput.EXTENSION}", name.endsWith(".${CropOutput.EXTENSION}"))
        assertTrue(name.startsWith("crop_output_1700000000000."))
    }

    @Test
    fun `configured format stays JPEG for crop responsiveness`() {
        // 决策锁定（#1892）：4096² 的 PNG 编码既慢又占内存；JPEG 亦是 UCrop 自身默认值。
        // 已知取舍=透明像素合成成黑色，但仅影响本地显示（请求侧本就统一压 JPEG q85）。
        assertEquals(Bitmap.CompressFormat.JPEG, CropOutput.FORMAT)
    }

    @Test
    fun `quality matches the library default`() {
        assertEquals(90, CropOutput.QUALITY)
    }
}
