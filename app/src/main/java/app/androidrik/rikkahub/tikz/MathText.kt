package app.androidrik.rikkahub.tikz

/**
 * 节点内嵌数学子集排版：把 TikZ node 文本里的 `^` `_` `{组}` `\frac{a}{b}` `\sqrt{x}` `\sum_{lo}^{hi}`
 * 解析成 token 流，再用多个绝对定位的 `<text>`（+ `<line>` 分数横线 / `<path>` 根号折线）堆叠布局，
 * 不依赖 tspan（AndroidSVG 1.4 支持性存疑）。
 *
 * Unicode 数学字符（α β ∑ √ ± ≤ 等）原样输出——系统字体有字形即可。
 */
object MathText {

    private const val BASE_SIZE = 14.0
    private const val SCRIPT_FACTOR = 0.72

    /** 是否需要数学排版（含上标/下标/分数/根号/求和命令） */
    fun needsMath(text: String): Boolean =
        text.contains('^') || text.contains('_') ||
            text.contains("\\frac") || text.contains("\\sqrt") || text.contains("\\sum")

    /** 渲染数学文本为 SVG 元素串；锚点 (cx, cy) 为节点中心 */
    fun render(text: String, cx: Double, cy: Double, color: String): String {
        val tokens = parse(text)
        // 先求总宽用于居中，再以实际起点布局
        val (_, w) = layoutTokens(tokens, 0.0, 0.0, BASE_SIZE, color)
        val x0 = cx - w / 2
        val (svg, _) = layoutTokens(tokens, x0, cy + 5.0, BASE_SIZE, color)
        return svg
    }

    // ---- token ----

    private sealed interface MT {
        data class Txt(val s: String) : MT
        data class Sup(val inner: List<MT>) : MT
        data class Sub(val inner: List<MT>) : MT
        data class Frac(val num: List<MT>, val den: List<MT>) : MT
        data class Sqrt(val inner: List<MT>) : MT
        data class Sum(val lo: List<MT>?, val hi: List<MT>?) : MT
    }

    // ---- 解析 ----

    private fun parse(s: String): List<MT> {
        val out = mutableListOf<MT>()
        val sb = StringBuilder()
        var i = 0
        fun flush() {
            if (sb.isNotEmpty()) { out.add(MT.Txt(sb.toString())); sb.clear() }
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c == '^' -> { flush(); val (g, n) = parseScript(s, i + 1); out.add(MT.Sup(g)); i = n }
                c == '_' -> { flush(); val (g, n) = parseScript(s, i + 1); out.add(MT.Sub(g)); i = n }
                s.startsWith("\\frac", i) -> {
                    flush(); i += 5
                    val (num, n1) = parseGroup(s, i) ?: (listOf(MT.Txt("")) to i)
                    i = n1
                    val (den, n2) = parseGroup(s, i) ?: (listOf(MT.Txt("")) to i)
                    i = n2
                    out.add(MT.Frac(num, den))
                }
                s.startsWith("\\sqrt", i) -> {
                    flush(); i += 5
                    val (inner, n) = parseGroup(s, i) ?: (listOf(MT.Txt("")) to i)
                    i = n
                    out.add(MT.Sqrt(inner))
                }
                s.startsWith("\\sum", i) -> {
                    flush(); i += 4
                    var lo: List<MT>? = null
                    var hi: List<MT>? = null
                    if (i < s.length && s[i] == '_') { val (g, n) = parseScript(s, i + 1); lo = g; i = n }
                    if (i < s.length && s[i] == '^') { val (g, n) = parseScript(s, i + 1); hi = g; i = n }
                    out.add(MT.Sum(lo, hi))
                }
                else -> { sb.append(c); i++ }
            }
        }
        flush()
        return out
    }

    /** ^/_ 后的脚本：`{组}` 或单字符 */
    private fun parseScript(s: String, start: Int): Pair<List<MT>, Int> {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        if (i < s.length && s[i] == '{') {
            return parseGroup(s, i) ?: (listOf(MT.Txt("")) to i + 1)
        }
        if (i < s.length) {
            return listOf(MT.Txt(s[i].toString())) to (i + 1)
        }
        return listOf(MT.Txt("")) to i
    }

    /** 解析 `{..}` 组（支持嵌套），返回组内 token 与结束位置 */
    private fun parseGroup(s: String, start: Int): Pair<List<MT>, Int>? {
        if (start >= s.length || s[start] != '{') return null
        var depth = 1
        var i = start + 1
        val inner = StringBuilder()
        while (i < s.length && depth > 0) {
            when (s[i]) {
                '{' -> { depth++; inner.append('{') }
                '}' -> { depth--; if (depth > 0) inner.append('}') }
                else -> inner.append(s[i])
            }
            i++
        }
        return parse(inner.toString()) to i
    }

    // ---- 盒布局 ----

    private fun layoutTokens(tokens: List<MT>, x0: Double, baseY: Double, size: Double, color: String): Pair<String, Double> {
        val sb = StringBuilder()
        var x = x0
        var w = 0.0
        for (t in tokens) {
            val (svg, tw) = layoutToken(t, x, baseY, size, color)
            sb.append(svg)
            x += tw
            w += tw
        }
        return sb.toString() to w
    }

    private fun layoutToken(t: MT, x: Double, baseY: Double, size: Double, color: String): Pair<String, Double> = when (t) {
        is MT.Txt -> text(x, baseY, size, t.s, color) to estWidth(t.s, size)
        is MT.Sup -> {
            val (svg, w) = layoutTokens(t.inner, x, baseY - size * 0.5, size * SCRIPT_FACTOR, color)
            svg to w
        }
        is MT.Sub -> {
            val (svg, w) = layoutTokens(t.inner, x, baseY + size * 0.42, size * SCRIPT_FACTOR, color)
            svg to w
        }
        is MT.Frac -> {
            val s = size * SCRIPT_FACTOR
            // 分子/分母基线离横线 ±0.55*size，避免字身贴线/重叠（dump 验证 0.32 太紧）
            val (numSvg, numW) = layoutTokens(t.num, x, baseY - size * 0.55, s, color)
            val (denSvg, denW) = layoutTokens(t.den, x, baseY + size * 0.55, s, color)
            val w = maxOf(numW, denW) + 4
            val line = """<line x1="${fmt(x - 1)}" y1="${fmt(baseY)}" x2="${fmt(x + w - 3)}" y2="${fmt(baseY)}" stroke="$color" stroke-width="1"/>"""
            (numSvg + line + denSvg) to w
        }
        is MT.Sqrt -> {
            // 根号折线：√ 形（斜线更斜）+ 上横线覆盖被开方数
            val h = size * 0.9
            val radW = size * 0.5
            val (innerSvg, innerW) = layoutTokens(t.inner, x + radW + 1, baseY, size * 0.95, color)
            val d = "M ${fmt(x)},${fmt(baseY - h)} l ${fmt(radW * 0.4)},${fmt(h)} l ${fmt(radW * 0.6)},${fmt(-h)} " +
                "l ${fmt(radW * 0.4)},${fmt(h * 0.75)} h ${fmt(innerW + 1)}"
            val path = """<path d="$d" fill="none" stroke="$color" stroke-width="1"/>"""
            (path + innerSvg) to (radW + 1 + innerW)
        }
        is MT.Sum -> {
            val sumW = size * 1.15
            val sumSvg = text(x, baseY - 1, size * 1.3, "∑", color)
            var w = sumW
            var extra = ""
            // 上下限远离 ∑ 主体（dump 验证 0.62 重叠）
            t.hi?.let {
                val (s, sw) = layoutTokens(it, x + sumW * 0.55, baseY - size * 0.8, size * 0.58, color)
                extra += s; w = maxOf(w, sumW * 0.55 + sw)
            }
            t.lo?.let {
                val (s, sw) = layoutTokens(it, x + sumW * 0.55, baseY + size * 0.75, size * 0.58, color)
                extra += s; w = maxOf(w, sumW * 0.55 + sw)
            }
            (sumSvg + extra) to w
        }
    }

    /** 文本宽度估算：拉丁/数字 0.68em，Unicode 符号 0.95em，空白 0.3em（0.62 偏窄导致元素重叠） */
    private fun estWidth(s: String, size: Double): Double = s.sumOf { ch ->
        when {
            ch.isWhitespace() -> size * 0.3
            ch.code > 0x2000 -> size * 0.95
            else -> size * 0.68
        }
    }

    private fun text(x: Double, y: Double, size: Double, s: String, color: String): String {
        if (s.isEmpty()) return ""
        return """<text x="${fmt(x)}" y="${fmt(y)}" font-size="${fmt(size)}" fill="$color">${escapeXml(s)}</text>"""
    }

    private fun fmt(v: Double): String {
        val rounded = Math.round(v * 100.0) / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
