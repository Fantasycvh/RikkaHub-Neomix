package app.androidrik.rikkahub.data.ai.transformers

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 上下文图片淘汰（`ContextImageEvictionTransformer`，借鉴 NMoi D248/D272）。
 *
 * 钉住四件事：
 * 1. 限额内零成本直通、超限保留**最新**的 N 张；
 * 2. 占位文本带原始路径（view_image 的寻址约定）；
 * 3. 工具输出里的图片同样计数（image gen / view_image 的结果也是上下文）；
 * 4. `limit = 0` 关闭 + 幂等（占位是 Text，重跑不二次处理）。
 */
class ContextImageEvictionTransformerTest {

    private fun imgMsg(urls: List<String>, role: MessageRole = MessageRole.USER) =
        UIMessage(role = role, parts = urls.map { UIMessagePart.Image(it) })

    private fun textMsg(text: String, role: MessageRole = MessageRole.USER) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `within limit returns messages untouched`() {
        val messages = listOf(imgMsg(listOf("file:///a.png")), textMsg("hi"))
        assertEquals(messages, evictOldImages(messages, limit = 20))
    }

    @Test
    fun `keeps the most recent images and evicts the oldest`() {
        val messages = listOf(
            imgMsg(listOf("file:///old1.png")),
            textMsg("middle"),
            imgMsg(listOf("file:///old2.png", "file:///old3.png")),
            imgMsg(listOf("file:///new1.png"), role = MessageRole.ASSISTANT),
        )
        val evicted = evictOldImages(messages, limit = 2)
        // 4 张图留最新 2 张：old1/old2 淘汰，old3/new1 保留
        val remainingUrls = evicted.flatMap { it.parts }.filterIsInstance<UIMessagePart.Image>().map { it.url }
        assertEquals(listOf("file:///old3.png", "file:///new1.png"), remainingUrls)

        val placeholder = evicted[0].parts.single() as UIMessagePart.Text
        assertTrue(placeholder.text.contains("/old1.png"))
        assertTrue(placeholder.text.contains("view_image"))

        // 同消息内两张图只淘汰一张（old3 是更靠后的，应保留）
        val parts2 = evicted[2].parts
        assertTrue(parts2[0] is UIMessagePart.Text)
        assertTrue(parts2[1] is UIMessagePart.Image)
    }

    @Test
    fun `counts tool output images toward the limit`() {
        val toolResult = UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(
                UIMessagePart.Text("result"),
                UIMessagePart.Image("file:///toolgen.png"),
            ),
        )
        val messages = listOf(
            imgMsg(listOf("file:///old.png")),
            toolResult,
            imgMsg(listOf("file:///recent.png")),
        )
        val evicted = evictOldImages(messages, limit = 2)
        val remaining = evicted.flatMap { it.parts }.filterIsInstance<UIMessagePart.Image>().map { it.url }
        // 3 张留 2 张：最旧的用户图被淘汰，工具输出图与最新图保留
        assertEquals(listOf("file:///toolgen.png", "file:///recent.png"), remaining)
    }

    @Test
    fun `inline data images are evicted with a generic placeholder`() {
        val messages = listOf(
            imgMsg(listOf("data:image/png;base64,AAAA")),
            imgMsg(listOf("file:///new.png")),
        )
        val evicted = evictOldImages(messages, limit = 1)
        val placeholder = evicted[0].parts.single() as UIMessagePart.Text
        assertTrue(placeholder.text.contains("内联图片"))
        assertFalse(placeholder.text.contains("AAAA"))
    }

    @Test
    fun `limit zero disables eviction and transform is idempotent`() {
        val messages = listOf(
            imgMsg(listOf("file:///a.png", "file:///b.png", "file:///c.png")),
        )
        assertEquals(messages, evictOldImages(messages, limit = 0))

        val once = evictOldImages(messages, limit = 2)
        val twice = evictOldImages(once, limit = 2)
        assertEquals(once, twice)
    }

    @Test
    fun `placeholder survives re-transform as text`() {
        val once = evictOldImages(
            listOf(imgMsg(listOf("file:///a.png")), imgMsg(listOf("file:///b.png"))),
            limit = 1,
        )
        val placeholder = once[0].parts.single() as UIMessagePart.Text
        val again = evictOldImages(once, limit = 2)
        assertEquals(placeholder, again[0].parts.single())
    }

    @Test
    fun `path resolution allows only existing files under filesDir`() {
        val root = File(System.getProperty("java.io.tmpdir"), "viewimage-test-${System.nanoTime()}")
        root.mkdirs()
        try {
            val img = File(root, "upload/photo.png").apply { parentFile.mkdirs(); writeText("x") }
            // file:// 前缀与 URL 转义都能解析
            assertEquals(img.canonicalPath, resolveViewableImagePath("file://${img.canonicalPath}", root.canonicalPath))
            assertEquals(img.canonicalPath, resolveViewableImagePath(img.canonicalPath, root.canonicalPath))
            assertEquals(img.canonicalPath, resolveViewableImagePath("file://${root.canonicalPath}/upload/ph%6Fto.png", root.canonicalPath))
            // 越界 / 不存在 / 空串一律拒绝
            assertNull(resolveViewableImagePath("/etc/passwd", root.canonicalPath))
            assertNull(resolveViewableImagePath("${root.canonicalPath}/../outside.png", root.canonicalPath))
            assertNull(resolveViewableImagePath("${root.canonicalPath}/upload/missing.png", root.canonicalPath))
            assertNull(resolveViewableImagePath("", root.canonicalPath))
        } finally {
            root.deleteRecursively()
        }
    }
}
