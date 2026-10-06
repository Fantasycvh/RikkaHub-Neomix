package app.androidrik.rikkahub.ui.components.richtext

/**
 * 裸 LaTeX 环境归一化。
 *
 * 背景（2026-10-06 实测，会话 `Tikz中文支持`）：模型有时会输出**不带任何数学定界符**的
 * 块级环境，例如
 *
 * ```
 * - **多重公式对齐**
 *   \begin{align}
 *       f(x) &= x^2 + 2x + 1 \\
 *       g(x) &= \int f(x) \, dx
 *   \end{align}
 * ```
 *
 * 相邻条目都规矩地写了 `\[ ... \]`，只有这一条漏了。没有定界符就**不会进入任何数学渲染器**，
 * 于是被当作普通段落文本处理，出现三个连带症状：
 *
 * 1. `\\` 被 markdown 当作转义 → 退化成单个 `\`（换行分隔符消失）；
 * 2. 行尾软换行被折叠成空格 → 多行挤成一行；
 * 3. `&` / `\,` 被转义（web 成 `&amp;`、`\,` 成 `,`）。
 *
 * 这正是 App 与 Web「表现完全一致」的原因——两端都没把它当公式，跟各自的数学渲染器无关。
 * 同一批内容里 `\[ ... \]` 包裹的分段函数在两端都渲染正常，可作对照。
 *
 * 处理两件事：
 *
 * 1. **补定界符**：裸的块级环境两侧补上独占一行的 `$$`。
 *    实测 `$$` 必须独占一行：`$$\begin{align}` 同行会被 remark-math 误判（数学节点内容从
 *    `\begin` 之后才开始），`$$\n\begin{align}...\end{align}\n$$` 才是正确的 display math。
 * 2. **归一环境名**：`align` 等环境在 KaTeX 里**只能用于 display 模式**，在行内 `$...$` 中会
 *    直接报 `{align} can be used only in display mode`；`multline` / `flalign` 则 KaTeX 根本不支持。
 *    统一改写成两端渲染器都认的内联安全等价物（`align`→`aligned`、`gather`→`gathered` 等）。
 */
internal object LatexEnvironments {

    /**
     * 需要自动补 `$$` 定界符的环境：AMS 块级公式环境 + 矩阵/分段家族 + 图表环境。
     *
     * 图表环境（tikzpicture/tikzcd/axis）一并纳入：App 侧的图表渲染入口
     * （`MathBlock.isDiagramLatex`）要求内容位于 `$$...$$` 或代码围栏内，`PromptAssembly`
     * 里也已要求模型必须裹 `$$`——模型漏写时在这里兜住。
     */
    val DISPLAY_MATH_ENVIRONMENTS: Set<String> = setOf(
        // AMS 多行/对齐
        "align", "align*", "aligned",
        "alignat", "alignat*", "alignedat",
        "gather", "gather*", "gathered",
        "multline", "multline*",
        "equation", "equation*",
        "flalign", "flalign*",
        "split", "CD",
        // 分段/矩阵家族（通常行内出现，漏写定界符时同样补上）
        "cases", "dcases", "rcases",
        "array", "darray", "subarray",
        "matrix", "pmatrix", "bmatrix", "Bmatrix", "vmatrix", "Vmatrix", "smallmatrix",
        // 图表
        "tikzpicture", "tikzcd",
        "axis", "semilogyaxis", "loglogaxis", "polaraxis",
    )

    /**
     * 环境名重写表：只保留 KaTeX 与 jlatexmath 都能吃下的等价环境。
     *
     * 不改写 tikzpicture / tikzcd / axis —— 它们要原样交给 TikZ 编译器。
     */
    val LATEX_ENVIRONMENT_ALIASES: Map<String, String> = mapOf(
        "align" to "aligned",
        "align*" to "aligned",
        "flalign" to "aligned",
        "flalign*" to "aligned",
        "equation" to "aligned",
        "equation*" to "aligned",
        "alignat" to "alignedat",
        "alignat*" to "alignedat",
        "gather" to "gathered",
        "gather*" to "gathered",
        "multline" to "gathered",
        "multline*" to "gathered",
    )

    private val BEGIN_REGEX = Regex("""\\begin\{([A-Za-z*]+)\}""")
    private val NAME_REGEX = Regex("""\\(begin|end)\{([A-Za-z*]+)\}""")

    /** 代码块（围栏 + 行内）：内部一律不动。 */
    private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`")

    /**
     * 已有的数学定界符区间：`$$...$$`、`\[...\]`、`\(...\)`、行内 `$...$`。
     * 落在这些区间里的裸环境**不补**定界符（否则会套娃成 `$$ $$ ... $$ $$`）。
     */
    private val MATH_SPAN_REGEX =
        Regex("""\$\$[\s\S]*?\$\$|\\\[[\s\S]*?\\\]|\\\([\s\S]*?\\\)|\$(?!\$)[^\n$]*?\$""")

    /**
     * 归一化入口：统一重写环境名，并给裸的块级环境补 `$$` 定界符。
     *
     * 幂等：补过 `$$` 的内容再次进入时环境已被 [MATH_SPAN_REGEX] 保护，不会重复包裹；
     * 重命名是定长替换，`align` 不会二次命中。
     */
    fun normalize(content: String): String {
        if (!content.contains("\\begin{")) return content

        val codeBlocks = CODE_BLOCK_REGEX.findAll(content).map { it.range }.toList()
        val mathSpans = MATH_SPAN_REGEX.findAll(content).map { it.range }.toList()
        val protectedRanges = codeBlocks + mathSpans
        fun isProtected(position: Int) = protectedRanges.any { position in it }

        // 重命名是定长替换，位置在重命名后的文本上依然一一对应，故先做重命名，
        // 再基于同一坐标系补充定界符。
        val renamed = renameEnvironments(content) { position -> codeBlocks.any { position in it } }

        val edits = mutableListOf<Pair<IntRange, String>>()
        var searchFrom = 0
        while (true) {
            val match = BEGIN_REGEX.find(renamed, searchFrom) ?: break
            searchFrom = match.range.last + 1

            val environment = match.groupValues[1]
            if (environment !in DISPLAY_MATH_ENVIRONMENTS) continue
            val beginPos = match.range.first
            if (isProtected(beginPos)) continue

            // 只处理「独占行首」的环境：保证插入的 $$ 落在行首，remark-math 才认。
            val lineStart = renamed.lastIndexOf('\n', beginPos - 1) + 1
            val indent = renamed.substring(lineStart, beginPos)
            if (indent.any { it != ' ' && it != '\t' }) continue

            val endRange = findEnvironmentEnd(renamed, environment, match.range.last + 1) ?: continue
            if (isProtected(endRange.first)) continue

            // 列表项内缩进必须保留：$$ 掉到列 0 会让数学块脱离列表项（实测 remark-math
            // 只认「$$ 与正文同缩进且独占一行」的形态）。
            edits += (lineStart..endRange.last) to
                (indent + "$$\n" + indent + renamed.substring(beginPos, endRange.last + 1) +
                    "\n" + indent + "$$")
            searchFrom = endRange.last + 1
        }

        if (edits.isEmpty()) return renamed

        val builder = StringBuilder(renamed.length + edits.size * 8)
        var cursor = 0
        for ((range, replacement) in edits) {
            builder.append(renamed, cursor, range.first)
            builder.append(replacement)
            cursor = range.last + 1
        }
        builder.append(renamed, cursor, renamed.length)
        return builder.toString()
    }

    /**
     * 重写环境名：`\begin{align}` 与其配对的 `\end{align}` 必须成对改写。
     *
     * @param isInCodeBlock 代码块内的 LaTeX 示例保持原样（用户可能就是在展示源码）。
     */
    fun renameEnvironments(content: String, isInCodeBlock: (Int) -> Boolean = { false }): String {
        if (LATEX_ENVIRONMENT_ALIASES.keys.none { content.contains("{$it}") }) return content
        return NAME_REGEX.replace(content) { match ->
            val alias = LATEX_ENVIRONMENT_ALIASES[match.groupValues[2]] ?: return@replace match.value
            if (isInCodeBlock(match.range.first)) return@replace match.value
            "\\" + match.groupValues[1] + "{" + alias + "}"
        }
    }

    /**
     * 从 [searchStart] 起找与已开启环境配对的 `\end{environment}`，支持同名环境嵌套。
     * 找不到闭合时返回 null —— 流式生成中途常见，此时保持原样不做改动。
     */
    private fun findEnvironmentEnd(content: String, environment: String, searchStart: Int): IntRange? {
        val open = "\\begin{$environment}"
        val close = "\\end{$environment}"
        var depth = 0
        var index = searchStart
        while (index < content.length) {
            val nextOpen = content.indexOf(open, index)
            val nextClose = content.indexOf(close, index)
            if (nextClose < 0) return null
            if (nextOpen in 0 until nextClose) {
                depth++
                index = nextOpen + open.length
                continue
            }
            if (depth == 0) return nextClose until nextClose + close.length
            depth--
            index = nextClose + close.length
        }
        return null
    }
}
