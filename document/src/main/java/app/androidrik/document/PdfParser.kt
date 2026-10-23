package app.androidrik.document

import app.androidrik.document.pdf.PdfFile
import app.androidrik.document.pdf.PdfTextExtractor
import java.io.File

/**
 * PDF 文本提取（纯 Kotlin 实现，无原生库）。
 *
 * 实现来源：上游 `815e9c20b refactor(document): 用纯 Kotlin 实现的 PDF 文本提取替换 mupdf`，
 * 本仓只做了包名迁移（`me.rerere.document.pdf` → `app.androidrik.document.pdf`）。
 */
object PdfParser {
    fun parserPdf(file: File): String {
        val pdf = PdfFile(file.readBytes())
        val pages = pdf.pages()
        if (pages.isEmpty()) throw IllegalStateException("No pages found in PDF")
        val extractor = PdfTextExtractor(pdf)
        val result = StringBuilder()
        pages.forEachIndexed { i, page ->
            result.append("---")
            result.append("Page ${i + 1}:\n")
            val text = try {
                extractor.extract(page)
            } catch (e: Exception) {
                "[Failed to extract text from this page: ${e.message}]"
            }
            result.append(text)
            result.appendLine()
        }
        return result.toString()
    }

    /** 总页数（打不开文件时抛异常：工具层要能区分"没文本"与"读不了"） */
    fun pageCount(file: File): Int = PdfFile(file.readBytes()).pages().size

    /**
     * 只抽第 [startPage] 页起、最多 [pageCount] 页（1 基）。
     *
     * 为什么需要按页抽：整本抽对几百页的 PDF 是秒级解析 + 大内存，而模型往往只要其中一页；
     * `workspace_read_file` 的 `offset` / `limit` 对 PDF 就是这里的页号与页数。
     */
    fun parsePages(file: File, startPage: Int, pageCount: Int): DocumentSlice {
        val pdf = PdfFile(file.readBytes())
        val pages = pdf.pages()
        val total = pages.size
        val start = startPage.coerceAtLeast(1)
        if (start > total) {
            return DocumentSlice("", start, start - 1, total)
        }
        val end = (start + pageCount - 1).coerceAtMost(total)
        val extractor = PdfTextExtractor(pdf)
        val texts = (start..end).map { pageNumber ->
            // 单页解析失败按空页处理：整本仍可读，且不把"抽不出文本"误判成"读不了文件"
            val text = runCatching { extractor.extract(pages[pageNumber - 1]) }.getOrDefault("")
            "Page $pageNumber:" to text
        }
        return DocumentSlice(
            // 全页无文本 ⇒ 空串（扫描版）：否则只有页头的串会看着"有内容"，把工具层的 notice 吞掉
            text = assembleUnitText(texts),
            firstUnit = start,
            lastUnit = end,
            totalUnits = total,
        )
    }
}
