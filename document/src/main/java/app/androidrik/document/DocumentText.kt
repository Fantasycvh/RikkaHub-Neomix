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
enum class DocumentFormat(val extensions: Set<String>, val unitLabel: String, val mimes: Set<String> = emptySet()) {
    PDF(setOf("pdf"), "page", setOf("application/pdf")),
    DOCX(
        setOf("docx"), "block",
        setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    ),
    PPTX(
        setOf("pptx"), "slide",
        setOf("application/vnd.openxmlformats-officedocument.presentationml.presentation"),
    ),
    EPUB(setOf("epub"), "chapter", setOf("application/epub+zip")),
    ;

    companion object {
        fun of(path: String): DocumentFormat? {
            val extension = path.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) return null
            return entries.firstOrNull { extension in it.extensions }
        }

        /** 按 MIME 识别；调用方手里有比文件名更权威的格式信息（附件 mime）时用它。 */
        fun fromMime(mime: String): DocumentFormat? {
            val normalized = mime.substringBefore(';').trim().lowercase()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { normalized in it.mimes }
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
    fun totalUnits(file: File, path: String): Int =
        DocumentFormat.of(path)?.let { totalUnits(it, file) } ?: -1

    /** 总单元数（[DocumentFormat.fromMime] 等显式指定格式时用）；未知时 -1 */
    fun totalUnits(format: DocumentFormat, file: File): Int = when (format) {
        DocumentFormat.PDF -> PdfParser.pageCount(file)
        DocumentFormat.DOCX -> -1
        DocumentFormat.PPTX -> PptxParser.slideCount(file)
        DocumentFormat.EPUB -> EpubParser.sectionCount(file)
    }

    /**
     * 全文抽取（聊天附件转提示词、知识库索引用）。失败**抛异常**——上层据此区分
     * 「空文档」与「解析失败」，不再像旧全文 API 那样把错误当正文返回（错误串会被
     * 当成内容切块嵌入，检索时召回垃圾）。
     *
     * @param path 仅用于扩展名识别；调用方已有显式 [DocumentFormat] 时走重载。
     */
    fun readAllText(file: File, path: String): String =
        readAllText(
            DocumentFormat.of(path)
                ?: throw IllegalArgumentException("unsupported document format: $path"),
            file,
        )

    fun readAllText(format: DocumentFormat, file: File): String = when (format) {
        DocumentFormat.PDF -> PdfParser.parserPdf(file)
        DocumentFormat.DOCX -> DocxParser.parse(file)
        DocumentFormat.PPTX -> PptxParser.parse(file)
        DocumentFormat.EPUB -> EpubParser.parse(file)
    }
}

/**
 * 把"带标签的单元"拼成抽取文本，并处理**全空**的情形。
 *
 * 为什么必须在这里处理：给每个单元加标题（`Page 1:` / `## Slide 1`）之后，即使**一个字的文本都没抽到**，
 * 拼出来的串也非空。上层据此判断"这份文档有内容"⇒ **扫描版 PDF 的提示就被吞掉了**（真机上实测到：
 * 纯图像 PDF 的回执是 `"text":"Page 1:\n\nPage 2:"`，看着有内容、其实没有文本层）。
 * 所以判据是"**所有单元正文都为空**"⇒ 返回空串，让上层如实说"抽不出文本"。
 *
 * @param units 单元标签与正文（PDF 用页码、PPTX 用幻灯片号）
 */
internal fun assembleUnitText(units: List<Pair<String, String>>): String {
    if (units.all { it.second.isBlank() }) return ""
    return units.joinToString("\n") { (label, body) ->
        if (body.isBlank()) label else "$label\n$body"
    }.trim()
}
