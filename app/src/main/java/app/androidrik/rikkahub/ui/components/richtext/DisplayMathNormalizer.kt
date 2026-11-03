package app.androidrik.rikkahub.ui.components.richtext

/**
 * 折叠显示公式块（`$$…$$` / `\[…\]`）内部的空行。
 *
 * 背景：BetterText 的 Markdown 解析基于 GFM block math，遇到空行会结束公式块。
 * 模型（尤其是 TikZ/pgfplots）经常在 `$$` 块内用空行分隔 `\node` / `\draw`，
 * 于是整块不再被识别成公式，聊天里直接显示源码（「三个 TikZ 图只有最后一个渲染成功」
 * 即此：前两个块内有空行，第三个没有）。
 *
 * 只处理代码围栏之外的显示公式；代码块、行内代码保持原样。公式块内的空行在 TeX
 * 里本来就非法/无意义，折叠掉不影响排版。
 */
internal fun normalizeDisplayMath(content: String): String {
    if ("$$" !in content && "\\[" !in content) return content
    val lines = content.split('\n')
    val out = ArrayList<String>(lines.size)
    var fence: String? = null
    // 当前显示公式块需要匹配的结束定界符（null = 不在公式块内）。
    var closeToken: String? = null
    for (line in lines) {
        val trimmed = line.trimStart()
        if (fence != null) {
            out += line
            if (trimmed.startsWith(fence)) fence = null
            continue
        }
        if (closeToken != null) {
            // 公式块内部的空行会提前终止 GFM block math：直接丢弃。
            if (line.isBlank()) continue
            out += line
            if (line.contains(closeToken)) closeToken = null
            continue
        }
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            fence = if (trimmed.startsWith("```")) "```" else "~~~"
            out += line
            continue
        }
        out += line
        closeToken = detectOpenDisplayMath(line)
    }
    return out.joinToString("\n")
}

/** 若 [line] 开启了一个未在本行闭合的显示公式块，返回其结束定界符。 */
private fun detectOpenDisplayMath(line: String): String? {
    val dollars = countOccurrences(line, "$$")
    if (dollars % 2 == 1) return "$$"
    val openBrackets = countOccurrences(line, "\\[")
    val closeBrackets = countOccurrences(line, "\\]")
    if (openBrackets > closeBrackets) return "\\]"
    return null
}

private fun countOccurrences(text: String, token: String): Int {
    var count = 0
    var index = text.indexOf(token)
    while (index >= 0) {
        count++
        index = text.indexOf(token, index + token.length)
    }
    return count
}
