package app.androidrik.rikkahub.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFileUrlTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val filesDir get() = tempFolder.root

    @Test
    fun `跨包 upload 路径重写到当前包 upload 目录`() {
        val url = "file:///data/user/0/app.androidrik.rikkahub.debug/files/upload/avatar.png"
        val expected = "file://" + File(filesDir, "upload/avatar.png").absolutePath
        assertEquals(expected, normalizeLocalFileUrl(filesDir, url))
    }

    @Test
    fun `跨包 upload 路径带子路径时保留文件名段`() {
        val url = "file:///data/user/0/com.other.pkg/files/upload/docs/a.pdf"
        val expected = "file://" + File(filesDir, "upload/docs/a.pdf").absolutePath
        assertEquals(expected, normalizeLocalFileUrl(filesDir, url))
    }

    @Test
    fun `非 upload 的 file 路径原样返回`() {
        val url = "file:///data/user/0/app.androidrik.rikkahub/files/workspace/notes.txt"
        assertEquals(url, normalizeLocalFileUrl(filesDir, url))
    }

    @Test
    fun `非 file 协议原样返回`() {
        val urls = listOf(
            "https://example.com/a.png",
            "http://localhost:8080/img.png",
            "data:image/png;base64,AAAA",
            "relative/path.png",
        )
        urls.forEach { url ->
            assertEquals(url, normalizeLocalFileUrl(filesDir, url))
        }
    }

    @Test
    fun `空串与空白串原样返回`() {
        assertEquals("", normalizeLocalFileUrl(filesDir, ""))
    }

    // 真机现场（2026-10-20）：release 包的备份恢复到 debug 安装后，消息里的生图路径仍是
    // `/data/user/0/app.androidrik.rikkahub/files/images/<name>.png`，而文件已经被恢复到
    // 当前包的 files/images 下 —— 生图工具卡因此显示「已失效或无法读取」。
    @Test
    fun `跨包生图绝对路径按当前包 images 目录重写`() {
        val path = "/data/user/0/app.androidrik.rikkahub/files/images/1792124295694_model_0.png"
        val expected = File(filesDir, "images/1792124295694_model_0.png").absolutePath
        assertEquals(expected, normalizeGeneratedImagePath(filesDir, path))
    }

    @Test
    fun `跨包生图路径的旧子目录结构保留`() {
        // 2026-10-04 之前含 "/" 的模型名会把产物落进 images/<vendor>/ 子目录
        val path = "/data/user/0/app.androidrik.rikkahub/files/images/vendor/model_0.png"
        val expected = File(filesDir, "images/vendor/model_0.png").absolutePath
        assertEquals(expected, normalizeGeneratedImagePath(filesDir, path))
    }

    @Test
    fun `当前包的生图路径保持不变`() {
        val path = File(filesDir, "images/a.png").absolutePath
        assertEquals(path, normalizeGeneratedImagePath(filesDir, path))
    }

    @Test
    fun `生图记录表里的相对路径不被改写`() {
        // gen_media.path 存的是相对路径，由 GeneratedImage 读取侧拼接，这里不能动
        assertEquals("images/a.png", normalizeGeneratedImagePath(filesDir, "images/a.png"))
    }

    @Test
    fun `生图路径归一化不会越过目标目录`() {
        val attack = "/data/user/0/app.androidrik.rikkahub/files/images/../../databases/rikka_hub"
        assertEquals(attack, normalizeGeneratedImagePath(filesDir, attack))
    }

    @Test
    fun `生图路径归一化不碰远程与 content uri`() {
        listOf(
            "https://example.com/files/images/a.png",
            "content://media/external/images/media/42",
        ).forEach { url ->
            assertEquals(url, normalizeGeneratedImagePath(filesDir, url))
        }
    }
}
