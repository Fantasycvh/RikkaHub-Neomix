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
}
