package app.androidrik.rikkahub.ui.components.richtext

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

// ---- Preprocessing ----

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)

private fun preProcess(content: String): String {
    // 先补裸块级环境的 $$ 定界符并归一环境名：必须早于 $ / \[ \] 改写，
    // 否则已带定界符的环境会被二次包裹成 $$ $$ ... $$ $$（见 LatexEnvironments）。
    val normalized = LatexEnvironments.normalize(content)

    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(normalized).forEach { codeBlocks.add(it.range) }
    fun isInCodeBlock(pos: Int) = codeBlocks.any { pos in it }

    var result = INLINE_LATEX_REGEX.replace(normalized) { m ->
        if (isInCodeBlock(m.range.first)) m.value else "$" + m.groupValues[1] + "$"
    }
    result = BLOCK_LATEX_REGEX.replace(result) { m ->
        if (isInCodeBlock(m.range.first)) m.value else "$$" + m.groupValues[1] + "$$"
    }
    return result
}

// ---- HTML generation (导出 / 预览用；聊天内渲染已迁移到 BetterText) ----

private val flavour by lazy {
    GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
}

private val parser by lazy { MarkdownParser(flavour) }

internal fun generateMarkdownHtml(content: String): String {
    // 与聊天渲染保持一致：显示公式块内的空行会提前结束 GFM block math（TikZ 常见）。
    val preprocessed = preProcess(normalizeDisplayMath(content))
    val tree = parser.buildMarkdownTreeFromString(preprocessed)
    return escapeMathSpanHtml(HtmlGenerator(preprocessed, tree, flavour).generateHtml())
}

/**
 * 转义 math span 内的 `<` / `&`。
 *
 * 背景（上游 issue #2224 / #2221）：markdown 解析器把 `$$…$$` 的内容**原样**写进
 * `<span class="math">`，公式里的 `1\le i<j\le n` 于是让后续 `Jsoup.parse` 把 `<j…`
 * 当成 HTML 标签起点——公式在第一个 `<` 处截断，后半段（乃至后续段落）被吞掉。
 *
 * 只处理 math span：普通文本由 HtmlGenerator 正常转义；span 外不做改动，
 * 避免影响用户自己写的 HTML 与代码块。
 */
private val MATH_SPAN_REGEX = Regex("""(<span class="math"[^>]*>)([\s\S]*?)</span>""")

internal fun escapeMathSpanHtml(html: String): String =
    MATH_SPAN_REGEX.replace(html) { match ->
        val escaped = match.groupValues[2]
            .replace("&", "&amp;")
            .replace("<", "&lt;")
        match.groupValues[1] + escaped + "</span>"
    }
