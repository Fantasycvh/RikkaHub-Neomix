package app.androidrik.rikkahub.data.export

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 阶段 2（zip 容器 + 附件原字节）的纯逻辑：清单生成、URL 收集与递归改写、
 * 扩展名推断、完整性校验、zip 往返。
 *
 * 见 `docs/design-conversation-import-export.md` §六 阶段 2。
 */
class ConversationArchiveTest {

    private fun imagePart(url: String) = UIMessagePart.Image(url = url)
    private fun docPart(url: String, name: String) =
        UIMessagePart.Document(url = url, fileName = name, mime = "application/pdf")

    private fun toolWithImage(url: String) = UIMessagePart.Tool(
        toolCallId = "call_1",
        toolName = "workspace_present_file",
        input = "{}",
        output = listOf(imagePart(url)),
    )

    private fun conversationOf(vararg parts: UIMessagePart): Conversation = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = "附件测试",
        messageNodes = listOf(
            MessageNode(messages = listOf(UIMessage(role = MessageRole.USER, parts = parts.toList())))
        ),
    )

    // ---- 扩展名推断 ----

    @Test
    fun `extension prefers the file name over the url`() {
        assertEquals("pdf", guessExtension("/x/abc.pdf", "report.pdf", "application/pdf"))
        // 文件名有扩展名时，即使 URL 无扩展名也用文件名
        assertEquals("md", guessExtension("/x/blob", "notes.md", null))
    }

    @Test
    fun `extension falls back to the url then to mime`() {
        assertEquals("png", guessExtension("/x/shot.PNG", null, null))
        assertEquals("png", guessExtension("/x/opaque", null, "image/png"))
        assertEquals("bin", guessExtension("/x/opaque", null, null))
    }

    @Test
    fun `url query string does not confuse the extension`() {
        assertEquals("png", guessExtension("https://x/a.png?token=1", null, null))
    }

    // ---- 条目名（内容哈希 → 去重） ----

    @Test
    fun `entry name is derived from the content hash so duplicates collapse`() {
        val bytes = "hello".toByteArray()
        val sha = sha256Of(bytes)
        val a = attachmentEntryName(sha, "txt")
        val b = attachmentEntryName(sha, "txt")
        assertEquals("相同内容必须得到相同条目名（单次导出内去重的基础）", a, b)
        assertTrue(a.startsWith(ATTACHMENTS_PREFIX))
        assertEquals(16, a.removePrefix(ATTACHMENTS_PREFIX).substringBefore('.').length)
    }

    @Test
    fun `different content yields different entry names`() {
        assertNotEquals(
            attachmentEntryName(sha256Of("a".toByteArray()), "txt"),
            attachmentEntryName(sha256Of("b".toByteArray()), "txt"),
        )
    }

    @Test
    fun `suspicious extension falls back to bin`() {
        // 防路径注入：扩展名只允许字母数字
        val name = attachmentEntryName(sha256Of("x".toByteArray()), "../evil")
        assertFalse(name.contains(".."))
        assertTrue(name.endsWith(".bin"))
    }

    // ---- 完整性校验 ----

    @Test
    fun `content check accepts matching bytes and rejects corrupted ones`() {
        val bytes = "payload".toByteArray()
        val entry = ExportFileEntry(
            originalUrl = "file:///a/b.txt",
            entry = "attachments/x.txt",
            fileName = "b.txt",
            mime = "text/plain",
            size = bytes.size.toLong(),
            sha256 = sha256Of(bytes),
        )
        assertTrue(entry.matchesContent(bytes))
        assertFalse("内容被篡改/损坏必须判为不符", entry.matchesContent("other".toByteArray()))
    }

    // ---- URL 收集 ----

    @Test
    fun `collect finds file urls including nested tool outputs`() {
        val c = conversationOf(
            imagePart("file:///data/a.png"),
            docPart("file:///data/b.pdf", "b.pdf"),
            toolWithImage("file:///data/nested.png"),
        )
        assertEquals(
            listOf("file:///data/a.png", "file:///data/b.pdf", "file:///data/nested.png"),
            c.collectAttachmentUrls(),
        )
    }

    @Test
    fun `collect ignores non-file urls and deduplicates`() {
        val c = conversationOf(
            imagePart("data:image/png;base64,AAAA"),
            imagePart("https://example.com/x.png"),
            imagePart("file:///data/same.png"),
            imagePart("file:///data/same.png"),  // 重复引用 → 只收一次
        )
        assertEquals(listOf("file:///data/same.png"), c.collectAttachmentUrls())
    }

    // ---- URL 改写（含递归） ----

    @Test
    fun `rewrite maps urls recursively including tool outputs`() {
        val c = conversationOf(
            imagePart("file:///old/a.png"),
            toolWithImage("file:///old/nested.png"),
        )
        val mapping = mapOf(
            "file:///old/a.png" to "file:///new/A.png",
            "file:///old/nested.png" to "file:///new/N.png",
        )
        val out = c.rewriteAttachmentUrls(mapping)
        assertEquals(listOf("file:///new/A.png", "file:///new/N.png"), out.collectAttachmentUrls())

        // 工具输出的 URL 确实被改了（而不是只改了顶层 part）
        val tool = out.messageNodes[0].messages[0].parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("file:///new/N.png", (tool.output.single() as UIMessagePart.Image).url)
    }

    @Test
    fun `rewrite leaves unmapped urls untouched`() {
        // 缺失附件就是这种情形：保留原引用比静默清空更可解释
        val c = conversationOf(imagePart("file:///old/missing.png"), imagePart("file:///old/found.png"))
        val out = c.rewriteAttachmentUrls(mapOf("file:///old/found.png" to "file:///new/F.png"))
        assertEquals(
            listOf("file:///old/missing.png", "file:///new/F.png"),
            out.collectAttachmentUrls(),
        )
    }

    @Test
    fun `rewrite does not mutate the original conversation`() {
        // 不可变性：原对象必须保持原 URL（导入失败时不能污染预览对象）
        val c = conversationOf(imagePart("file:///old/a.png"))
        c.rewriteAttachmentUrls(mapOf("file:///old/a.png" to "file:///new/A.png"))
        assertEquals(listOf("file:///old/a.png"), c.collectAttachmentUrls())
    }

    @Test
    fun `rewrite with empty mapping is a no-op`() {
        val c = conversationOf(imagePart("file:///old/a.png"))
        assertEquals(c.collectAttachmentUrls(), c.rewriteAttachmentUrls(emptyMap()).collectAttachmentUrls())
    }

    // ---- zip 往返 ----

    private fun sampleExportData(conversation: Conversation, files: List<ExportFileEntry>) =
        ConversationSerializer.export(conversation).copy(version = 2, files = files)

    @Test
    fun `zip round trip preserves json and attachment bytes`() {
        val conversation = conversationOf(imagePart("file:///old/a.png"))
        val bytes = "PNGDATA".toByteArray()
        val entry = ExportFileEntry(
            originalUrl = "file:///old/a.png",
            entry = attachmentEntryName(sha256Of(bytes), "png"),
            fileName = "a.png",
            mime = "image/png",
            size = bytes.size.toLong(),
            sha256 = sha256Of(bytes),
        )
        val zip = buildZip(
            json = ConversationSerializer.export(conversation).let {
                app.androidrik.rikkahub.utils.JsonInstant.encodeToString(
                    ExportData.serializer(),
                    it.copy(version = 2, files = listOf(entry)),
                )
            },
            attachments = mapOf(entry.entry to bytes),
        )

        val unpacked = unpackConversationArchive(zip)
        assertNotNull(unpacked)
        assertEquals(bytes.toList(), unpacked!!.attachments[entry.entry]?.toList())
        val back = app.androidrik.rikkahub.utils.JsonInstant.decodeFromString(
            ExportData.serializer(),
            unpacked.json,
        )
        assertEquals(2, back.version)
        assertEquals(conversation.id.toString(), back.sourceId)
        assertEquals(listOf(entry), back.files)
    }

    @Test
    fun `unpack rejects a non-zip payload`() {
        assertNull(unpackConversationArchive("not a zip".toByteArray()))
    }

    @Test
    fun `unpack ignores zip-slip style entries`() {
        val zip = buildZip(
            json = """{"version":2,"type":"conversation","data":{}}""",
            attachments = mapOf(
                "attachments/../../etc/passwd" to "evil".toByteArray(),
                "attachments/ok.png" to "fine".toByteArray(),
            ),
        )
        val unpacked = unpackConversationArchive(zip)
        assertNotNull(unpacked)
        assertNull("带 .. 的条目必须被拒", unpacked!!.attachments["attachments/../../etc/passwd"])
        assertNotNull("正常条目仍应保留", unpacked.attachments["attachments/ok.png"])
    }

    @Test
    fun `unpack ignores unknown top-level entries`() {
        val zip = buildZip(
            json = """{"version":2,"type":"conversation","data":{}}""",
            attachments = mapOf("something-else/x" to "x".toByteArray()),
        )
        val unpacked = unpackConversationArchive(zip)
        assertNotNull(unpacked)
        assertTrue(unpacked!!.attachments.isEmpty())
    }

    /** 单测用：直接构造 zip（不走 Context 依赖的 packConversationArchive）。 */
    private fun buildZip(json: String, attachments: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(CONVERSATION_JSON_ENTRY))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            attachments.forEach { (name, bytes) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun assertNotEquals(a: Any?, b: Any?) =
        assertFalse("expected different but both were $a", a == b)
}
