package app.androidrik.rikkahub.data.files

import app.androidrik.ai.ui.UIMessagePart
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileConversationScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun filesDirWithUpload(vararg files: String): File {
        val root = tmp.newFolder("files")
        val upload = File(root, "upload").apply { mkdirs() }
        files.forEach { File(upload, it).apply { parentFile.mkdirs() }.createNewFile() }
        return root
    }

    /** 与 Android 侧 `file.toUri()` 同构的 URL（正斜杠 + 三斜杠 authority）；Windows 路径也能对上。 */
    private fun fileUrl(root: File, name: String): String =
        "file://" + File(root, name).absolutePath.replace('\\', '/')

    // ---- decodePercentEncoded ----

    @Test
    fun `decode keeps plain path untouched`() {
        assertEquals("/data/user/0/pkg/files/upload/x.png", FileConversationScanner.decodePercentEncoded("/data/user/0/pkg/files/upload/x.png"))
    }

    @Test
    fun `decode resolves percent encoded bytes as utf8`() {
        val encoded = "/upload/%E4%B8%AD%E6%96%87.png"
        assertEquals("/upload/中文.png", FileConversationScanner.decodePercentEncoded(encoded))
    }

    @Test
    fun `decode keeps invalid sequences and plus literal`() {
        assertEquals("/a%2zb+c", FileConversationScanner.decodePercentEncoded("/a%2zb+c"))
    }

    // ---- relativePathOf ----

    @Test
    fun `relativePathOf maps file url under filesDir`() {
        val root = filesDirWithUpload("a.png")
        val url = "file://" + File(root, "upload/a.png").absolutePath
        assertEquals("upload/a.png", FileConversationScanner.relativePathOf(url, root))
    }

    @Test
    fun `relativePathOf rejects path outside filesDir`() {
        val root = filesDirWithUpload("a.png")
        val outside = File(root.parentFile, "evil.png").absolutePath
        assertNull(FileConversationScanner.relativePathOf("file://$outside", root))
    }

    @Test
    fun `relativePathOf rejects traversal`() {
        val root = filesDirWithUpload("a.png")
        assertNull(FileConversationScanner.relativePathOf("file:///data/../secret", root))
    }

    // ---- extractRelativePathsFromJson ----

    @Test
    fun `json extraction handles escaped urls and ignores http and data`() {
        val root = filesDirWithUpload("a.png", "b.jpg")
        val json = """
            [
              {"parts":[
                {"type":"image","url":"${fileUrl(root, "upload/a.png")}"},
                {"type":"text","text":"see https://example.com/x and data:image/png;base64,AAAA"}
              ]}
            ]
        """.trimIndent()
        assertEquals(setOf("upload/a.png"), FileConversationScanner.extractRelativePathsFromJson(json, root))
    }

    @Test
    fun `json extraction collects multiple files`() {
        val root = filesDirWithUpload("a.png", "b.jpg")
        val json = """
            [{"parts":[
                {"type":"image","url":"${fileUrl(root, "upload/a.png")}"},
                {"type":"document","url":"${fileUrl(root, "upload/b.jpg")}"}
            ]}]
        """.trimIndent()
        assertEquals(setOf("upload/a.png", "upload/b.jpg"), FileConversationScanner.extractRelativePathsFromJson(json, root))
    }

    @Test
    fun `json extraction returns empty for json without file refs`() {
        val root = filesDirWithUpload("a.png")
        assertEquals(emptySet<String>(), FileConversationScanner.extractRelativePathsFromJson("""[{"parts":[{"type":"text","text":"hello"}]}]""", root))
    }

    @Test
    fun `json extraction handles percent encoded chinese filename`() {
        val root = filesDirWithUpload("中文 截图.png")
        // URI 路径段编码空格是 %20（URLEncoder 的 + 是表单语义，Android Uri.fromFile 也用 %20）
        val encoded = java.net.URLEncoder.encode("中文 截图.png", "UTF-8").replace("+", "%20")
        val url = "file://" + root.absolutePath.replace('\\', '/') + "/upload/" + encoded
        val json = """[{"parts":[{"type":"image","url":"$url"}]}]"""
        assertEquals(setOf("upload/中文 截图.png"), FileConversationScanner.extractRelativePathsFromJson(json, root))
    }

    // ---- extractRelativePathsFromParts（内存遍历，含 Tool 嵌套）----

    @Test
    fun `parts extraction recurses into tool output`() {
        val root = filesDirWithUpload("shot.png")
        val url = "file://" + File(root, "upload/shot.png").absolutePath
        val parts = listOf(
            UIMessagePart.Text("read the image"),
            UIMessagePart.Tool(
                toolCallId = "t1",
                toolName = "read_image",
                input = "{}",
                output = listOf(
                    UIMessagePart.Text("here"),
                    UIMessagePart.Image(url = url),
                ),
            ),
        )
        assertEquals(setOf("upload/shot.png"), FileConversationScanner.extractRelativePathsFromParts(parts, root))
    }

    @Test
    fun `parts extraction ignores non file urls`() {
        val root = filesDirWithUpload("a.png")
        val parts = listOf(
            UIMessagePart.Image(url = "https://example.com/a.png"),
            UIMessagePart.Image(url = "data:image/png;base64,AAAA"),
            UIMessagePart.Document(url = "content://media/external/1", fileName = "x", mime = "image/png"),
        )
        assertEquals(emptySet<String>(), FileConversationScanner.extractRelativePathsFromParts(parts, root))
    }
}
