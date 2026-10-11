package app.androidrik.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 文档"按单元抽"的单测（真实 zip 夹具，DOCX / PPTX / EPUB）。
 *
 * 为什么值得为它造夹具：这三条解析路径此前只有"整本抽"，**没有测试**；按单元抽一旦把索引算错，
 * 表现是"模型拿到第 2 章的内容却以为是第 1 章"——它能读、能答，只是答案对应错了页，用户很难发现。
 *
 * **PDF 不在这里测**：`PdfParser` 走 MuPDF 的 native 库（Android 侧才有），JVM 单测跑不了；
 * 它只在真机路径上验证（见 docs/roadmap.md）。所以这里至少钉住"扩展名判定"这条不含 native 的逻辑。
 */
class DocumentReaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---------- 扩展名判定 ----------

    @Test
    fun `formats are detected by extension case-insensitively`() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.of("/upload/report.PDF"))
        assertEquals(DocumentFormat.DOCX, DocumentFormat.of("/workspace/notes.docx"))
        assertEquals(DocumentFormat.PPTX, DocumentFormat.of("slides.pptx"))
        assertEquals(DocumentFormat.EPUB, DocumentFormat.of("/upload/book.epub"))
        assertEquals(null, DocumentFormat.of("/workspace/main.kt"))
        assertEquals("没有扩展名就是普通文本，不该被当文档", null, DocumentFormat.of("/workspace/somefile"))
    }

    @Test
    fun `an unsupported document format fails instead of silently returning text`() {
        val file = tempFolder.newFile("legacy.doc")
        val error = assertThrows(IllegalArgumentException::class.java) {
            DocumentReader.read(file, "legacy.doc", startUnit = 1, unitCount = 1)
        }
        assertTrue(error.message!!.contains("unsupported document format"))
    }

    // ---------- DOCX ----------

    @Test
    fun `docx blocks are sliced by paragraph count`() {
        // 250 段 ⇒ 3 块（100 / 100 / 50）：第 2 块必须正好是第 101–200 段
        val paragraphs = (1..250).map { "para$it" }
        val file = docx(paragraphs)

        val first = DocxParser.parseBlocks(file, startBlock = 1, blockCount = 1)
        val second = DocxParser.parseBlocks(file, startBlock = 2, blockCount = 1)
        val third = DocxParser.parseBlocks(file, startBlock = 3, blockCount = 5)

        assertEquals(1, first.firstUnit)
        assertEquals(1, first.lastUnit)
        assertTrue(first.text.contains("para1"))
        assertFalse("第 1 块不该漏进第 101 段", first.text.contains("para101"))

        assertEquals(2, second.firstUnit)
        assertEquals(2, second.lastUnit)
        assertTrue(second.text.contains("para101"))
        assertTrue(second.text.contains("para200"))
        assertFalse(second.text.contains("para201"))

        assertEquals("最后一块只有 50 段，读 5 块也只到第 3 块", 3, third.lastUnit)
        assertTrue(third.text.contains("para250"))
    }

    @Test
    fun `docx reports an unknown total because it has no real pagination`() {
        val file = docx(listOf("only one paragraph"))

        val slice = DocxParser.parseBlocks(file, startBlock = 1, blockCount = 1)

        assertEquals("DOCX 没有真实分页 ⇒ 总数报 -1（未知），不假装知道", -1, slice.totalUnits)
        assertEquals("only one paragraph", slice.text)
    }

    @Test
    fun `an out of range docx block yields an empty slice`() {
        val file = docx(listOf("a", "b"))

        val slice = DocxParser.parseBlocks(file, startBlock = 99, blockCount = 1)

        assertEquals("", slice.text)
        assertEquals("空片段：lastUnit 必须小于 firstUnit", 98, slice.lastUnit)
    }

    // ---------- PPTX ----------

    @Test
    fun `pptx slides are addressed by number and sorted numerically`() {
        // slide2 / slide10 一起放：按字符串排序会把 slide10 排到 slide2 前面
        val file = pptx(mapOf(2 to "second slide", 10 to "tenth slide", 1 to "first slide"))

        assertEquals(3, PptxParser.slideCount(file))

        val second = PptxParser.parseSlides(file, startSlide = 2, slideCount = 1)
        assertEquals("按幻灯片号排序，不是按文件名排序", 2, second.firstUnit)
        assertTrue(second.text.contains("second slide"))
        assertFalse(second.text.contains("tenth slide"))

        val third = PptxParser.parseSlides(file, startSlide = 3, slideCount = 1)
        assertTrue("第 3 张应当是 slide10 的内容", third.text.contains("tenth slide"))
    }

    @Test
    fun `an out of range pptx slide yields an empty slice with the real total`() {
        val file = pptx(mapOf(1 to "only slide"))

        val slice = PptxParser.parseSlides(file, startSlide = 5, slideCount = 2)

        assertEquals("", slice.text)
        assertEquals(1, slice.totalUnits)
        assertEquals(4, slice.lastUnit)
    }

    // ---------- EPUB ----------

    @Test
    fun `epub sections follow the spine order`() {
        val file = epub(listOf("chapter one", "chapter two", "chapter three"))

        assertEquals(3, EpubParser.sectionCount(file))

        val second = EpubParser.parseSections(file, startSection = 2, sectionCount = 1)
        assertEquals(2, second.firstUnit)
        assertEquals(2, second.lastUnit)
        assertTrue(second.text.contains("chapter two"))
        assertFalse(second.text.contains("chapter one"))
        assertEquals(3, second.totalUnits)
    }

    @Test
    fun `an epub without an opf fails loudly`() {
        val file = zip("broken.epub", mapOf("mimetype" to "application/epub+zip"))

        assertThrows(IllegalStateException::class.java) {
            EpubParser.parseSections(file, startSection = 1, sectionCount = 1)
        }
    }

    // ---------- 夹具 ----------

    private fun docx(paragraphs: List<String>): File {
        val body = paragraphs.joinToString("") { """<w:p><w:r><w:t>${escape(it)}</w:t></w:r></w:p>""" }
        val xml = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="x"><w:body>$body</w:body></w:document>"""
        return zip("sample.docx", mapOf("word/document.xml" to xml))
    }

    private fun pptx(slides: Map<Int, String>): File {
        val entries = slides.entries.associate { (number, text) ->
            "ppt/slides/slide$number.xml" to
                """<?xml version="1.0" encoding="UTF-8"?><p:sld xmlns:a="a" xmlns:p="p"><p:cSld><p:sp><p:txBody><a:p><a:r><a:t>${escape(text)}</a:t></a:r></a:p></p:txBody></p:sp></p:cSld></p:sld>"""
        }
        return zip("sample.pptx", entries)
    }

    private fun epub(chapters: List<String>): File {
        val manifest = chapters.indices.joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }
        val spine = chapters.indices.joinToString("") { """<itemref idref="c$it"/>""" }
        val opf = """<?xml version="1.0" encoding="UTF-8"?><package><manifest>$manifest</manifest><spine>$spine</spine></package>"""
        val container = """<?xml version="1.0" encoding="UTF-8"?><container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
        val entries = buildMap {
            put("META-INF/container.xml", container)
            put("OEBPS/content.opf", opf)
            chapters.forEachIndexed { index, text ->
                put("OEBPS/c$index.xhtml", "<html><body><p>${escape(text)}</p></body></html>")
            }
        }
        return zip("sample.epub", entries)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")

    private fun zip(name: String, entries: Map<String, String>): File {
        val file = File(tempFolder.newFolder(name.substringBeforeLast('.')), name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}
