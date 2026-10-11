package app.androidrik.document

import java.io.File

/**
 * 支持**抽文本**的文档格式（按扩展名识别）。
 *
 * [unitLabel] 是"分页单元"的名字：PDF 是页、PPTX 是幻灯片、EPUB 是章节、DOCX 是**合成块**
 * （docx 文件里没有真实分页，所以按 [DocumentReader.DOCX_PARAGRAPHS_PER_BLOCK] 段落一块）。
 * 工具层把 `offset` / `limit` 解释成这些单元——这样"读第 20–40 页"与文本文件的"读第 20–40 行"
 * 是同一个参数、同一套心智。
 */
enum class DocumentFormat(val extensions: Set<String>, val unitLabel: String) {
    PDF(setOf("pdf"), "page"),
    DOCX(setOf("docx"), "block"),
    PPTX(setOf("pptx"), "slide"),
    EPUB(setOf("epub"), "chapter"),
    ;

    companion object {
        fun of(path: String): DocumentFormat? {
            val extension = path.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) return null
            return entries.firstOrNull { extension in it.extensions }
        }
    }
}

/**
 * 抽出来的一段文本。
 *
 * [firstUnit] / [lastUnit] 是 **1 基、含端点**；空片段时 `lastUnit = firstUnit - 1`
 * （不报成一个不存在的单元号，与文本文件窗口的约定一致）。
 *
 * [totalUnits] 为 **-1 表示未知**：DOCX 要数总块数就得把整篇解析一遍，为了读一块而全解一遍不值当。
 */
data class DocumentSlice(
    val text: String,
    val firstUnit: Int,
    val lastUnit: Int,
    val totalUnits: Int,
) {
    val isEmpty: Boolean get() = text.isBlank()
}

/**
 * 文档抽文本的统一入口（失败**抛异常**，与各解析器旧的 `parse(file): String` 不同——
 * 旧接口把错误当字符串返回，工具层分不清"文档是空的"和"解析失败"，而这两者对模型的意义完全不同）。
 */
object DocumentReader {

    /** DOCX 的合成块大小（段落数）；docx 无真实分页，分块只为让"翻页"可用 */
    const val DOCX_PARAGRAPHS_PER_BLOCK = 100

    /**
     * 抽取 [path] 的第 [startUnit] 个单元起、最多 [unitCount] 个单元。
     *
     * @throws IllegalArgumentException 扩展名不受支持
     * @throws Exception 解析失败（损坏的文件、缺 OPF、加密 PDF 等）
     */
    fun read(file: File, path: String, startUnit: Int, unitCount: Int): DocumentSlice {
        val format = DocumentFormat.of(path)
            ?: throw IllegalArgumentException("unsupported document format: $path")
        val start = startUnit.coerceAtLeast(1)
        val count = unitCount.coerceAtLeast(1)
        return when (format) {
            DocumentFormat.PDF -> PdfParser.parsePages(file, start, count)
            DocumentFormat.DOCX -> DocxParser.parseBlocks(file, start, count)
            DocumentFormat.PPTX -> PptxParser.parseSlides(file, start, count)
            DocumentFormat.EPUB -> EpubParser.parseSections(file, start, count)
        }
    }

    /** 总单元数；未知时 -1（见 [DocumentSlice.totalUnits]） */
    fun totalUnits(file: File, path: String): Int = when (DocumentFormat.of(path)) {
        DocumentFormat.PDF -> PdfParser.pageCount(file)
        DocumentFormat.DOCX -> -1
        DocumentFormat.PPTX -> PptxParser.slideCount(file)
        DocumentFormat.EPUB -> EpubParser.sectionCount(file)
        null -> -1
    }
}
