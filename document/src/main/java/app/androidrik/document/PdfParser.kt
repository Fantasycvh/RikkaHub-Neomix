package app.androidrik.document

import com.artifex.mupdf.fitz.PDFDocument
import java.io.File

object PdfParser {
    fun parserPdf(file: File): String {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        val pages = document.countPages()
        val result = StringBuilder()
        for (i in 0 until pages) {
            val page = document.loadPage(i).toStructuredText()
            result.append("---")
            result.append("Page ${i + 1}:\n")
            result.append(page.asText())
            result.appendLine()
        }
        return result.toString()
    }

    /** 总页数（打不开文件时抛异常：工具层要能区分"没文本"与"读不了"） */
    fun pageCount(file: File): Int =
        PDFDocument.openDocument(file.absolutePath).asPDF().countPages()

    /**
     * 只抽第 [startPage] 页起、最多 [pageCount] 页（1 基）。
     *
     * 为什么需要按页抽：整本抽对几百页的 PDF 是几秒级的原生解析 + 大内存，而模型往往只要其中一页；
     * `workspace_read_file` 的 `offset` / `limit` 对 PDF 就是这里的页号与页数。
     */
    fun parsePages(file: File, startPage: Int, pageCount: Int): DocumentSlice {
        val document = PDFDocument.openDocument(file.absolutePath).asPDF()
        val total = document.countPages()
        val start = startPage.coerceAtLeast(1)
        if (start > total) {
            return DocumentSlice("", start, start - 1, total)
        }
        val end = (start + pageCount - 1).coerceAtMost(total)
        val pages = (start..end).map { page ->
            "Page $page:" to document.loadPage(page - 1).toStructuredText().asText()
        }
        return DocumentSlice(
            // 全页无文本 ⇒ 空串（扫描版）：否则只有页头的串会看着"有内容"，把工具层的 notice 吞掉
            text = assembleUnitText(pages),
            firstUnit = start,
            lastUnit = end,
            totalUnits = total,
        )
    }
}
