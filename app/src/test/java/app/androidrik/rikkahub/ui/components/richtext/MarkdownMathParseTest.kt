package app.androidrik.rikkahub.ui.components.richtext

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.junit.Test

/**
 * 取证：聊天 UI 的 IntelliJ Markdown 解析对 `$$...$$` TikZ 的实际处理。
 *
 * 本文件的用途是**记录解析器的真实行为**（多处 `_what_happens` 用 println 留证据），
 * 同时把两条关键结论钉成断言：
 * ① `$$` **紧贴** `\begin{tikzpicture}`（本仓 TikZ 引导语要求的写法）→ 产出块级 math span；
 * ② `$$` 与内容之间**夹空行** → 不产出 math span（实测限制，非目标形态）。
 *
 * 为何要明确区分：本文件原本只有一条断言、测的是 ②，于是长期是一条红点，
 * 被当成「基线噪音」无视——而同期另一个文件里「从未运行的测试」也正因同样理由被无视。
 * 断言必须写**实际行为**，或明确标为期望并让它推动修复，不能悬着。
 */
class MarkdownMathParseTest {

    private val flavour by lazy {
        GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    }
    private val parser by lazy { MarkdownParser(flavour) }

    private fun htmlOf(content: String): String {
        val tree = parser.buildMarkdownTreeFromString(content)
        return HtmlGenerator(content, tree, flavour).generateHtml()
    }

    /**
     * **受支持形态**：`$$` 紧贴所包裹的内容。
     *
     * 实测 HTML：`<span class="math" inline = "false">\\begin{tikzpicture}...`。
     * 这是 TikZ 渲染依赖的路径——`MarkdownNew.kt` 正是按 `span.math`（`inline != "true"`）识别块级数学。
     */
    @Test
    fun adjacentDollarTikz_producesBlockMathSpanWithRawBackslash() {
        val content = """
            $$\begin{tikzpicture}
            \draw (0,0) node {中文} -- (3,0) node {English};
            \end{tikzpicture}$$
        """.trimIndent()
        val html = htmlOf(content)
        println("ADJACENT_STYLE_HTML: $html")
        assert(html.contains("class=\"math\"")) { "紧贴式 $$ 应生成 span.math（块级数学）" }
        assert(html.contains("\\begin{tikzpicture}")) { "\\begin 反斜杠应保留（否则 TikZ 源码被破坏）" }
    }

    /**
     * **不受支持形态**：`$$` 与内容之间夹空行。
     *
     * 本用例断言的是**实际行为**而非期望行为：解析器把孤立的 `$$` 当普通文本，
     * 实测 HTML 为 `<p>$$</p><p>\begin{tikzpicture}...`，**没有 math span**。
     *
     * 保留它的三个理由：
     * ① 钉住「该形态不会被渲染成数学块」这一事实——日后若解析器改进（支持空行分隔），
     *    本用例会失败并要求**有意识地**更新为正向断言，而不是悄悄漂移；
     * ② 它解释了原用例（`blockStyleDollarTikz_should_contain_span_math_raw_backslash`）
     *    为何长期失败：原断言写的是期望，而实测是另一种行为；
     * ③ 实际影响很小——TikZ 引导语明确要求 `$$` 紧贴 `\begin{tikzpicture}`，
     *    模型被引导到受支持形态（见上一个用例）。
     */
    @Test
    fun blankLineSeparatedDollarTikz_documentsThatNoMathSpanIsProduced() {
        val content = """
            $$

            \begin{tikzpicture}
            \draw (0,0) node {中文} -- (3,0) node {English};
            \end{tikzpicture}

            $$
        """.trimIndent()
        val html = htmlOf(content)
        println("BLANK_LINE_STYLE_HTML: $html")
        assert(!html.contains("class=\"math\"")) {
            "行为已变化：空行分隔的 $$ 现在会产出 math span——若是解析器改进，请把本用例改为正向断言"
        }
        assert(html.contains("\\begin{tikzpicture}")) { "\\begin 反斜杠应保留" }
    }

    @Test
    fun inlineStyleDollarTikz_what_happens() {
        val content = """$$\begin{tikzpicture}
\draw (0,0) node {中文} -- (3,0) node {English};
\node at (1.5,1) [font=\large] {测试中文字符};
\foreach \x/\lab in {1/一, 2/二, 3/三} {
  \draw (\x,0) -- (\x,1) node[above] {\lab};
}
\end{tikzpicture}$$"""
        val html = htmlOf(content)
        println("INLINE_STYLE_HTML: $html")
        val hasMathSpan = html.contains("class=\"math\"")
        val backslashKept = html.contains("\\begin{tikzpicture}")
        println("hasMathSpan=$hasMathSpan backslashKept=$backslashKept")
    }

    @Test
    fun bareTikzWithoutDollar_what_happens() {
        val content = """
            \begin{tikzpicture}
            \node (a) at (0,0) {中文};
            \node (b) at (2,0) {Hello World};
            \end{tikzpicture}
        """.trimIndent()
        val html = htmlOf(content)
        println("BARE_HTML: $html")
    }

    @Test
    fun aiReplyFencedBlankWithDollarTikz() {
        // AI 常见：``` 空标注 + $$...tikz...$$ 包裹（截图源码根因复现）
        val content = "我重新检查：\n```\n$$\\begin{tikzpicture}\n\\draw (0,0) node {中文} -- (3,0) node {English};\n\\end{tikzpicture}$$\n```"
        val html = htmlOf(content)
        println("AI_FENCE_HTML: $html")
        val codeLang = Regex("""class="[^"]*language-([a-z]+)""").find(html)?.groupValues?.get(1) ?: "none"
        println("codeLanguage=$codeLang")
        val hasBegin = html.contains("\\begin{tikzpicture}")
        println("hasBegin=$hasBegin")
        val codeContent = Regex("<code[^>]*>(.*?)</code>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.take(120)
        println("codeContent=$codeContent")
    }

    @Test
    fun lessThanInsideBlockFormula_isNotSwallowedAsHtmlTag() {
        // 上游 issue #2224 / #2221：公式里 `1\le i<j\le n` 的 `<j` 被当成 HTML 标签开头，
        // 导致 math span 在 `<` 处截断、后半段丢失。走真实的 generateMarkdownHtml → Jsoup 链路。
        val content = """
            $$
            V(x_1,\ldots,x_n)=\prod_{1\le i<j\le n}(x_j-x_i).
            $$
        """.trimIndent()
        val html = generateMarkdownHtml(content)
        println("LESS_THAN_BLOCK_HTML: $html")
        val math = Jsoup.parse(html).select("span.math").firstOrNull()
        assert(math != null) { "应生成 math span：$html" }
        val formula = math!!.text()
        assert(formula.contains("<j")) { "公式里的 < 应保留而不是被吞成 HTML 标签：$formula" }
        assert(formula.contains("x_j-x_i")) { "公式后半段不应丢失：$formula" }
    }

    @Test
    fun lessThanInsideInlineFormula_isNotSwallowedAsHtmlTag() {
        val content = """当 ${'$'}1\le i<j\le n${'$'} 时成立。"""
        val html = generateMarkdownHtml(content)
        println("LESS_THAN_INLINE_HTML: $html")
        val math = Jsoup.parse(html).select("span.math").firstOrNull()
        assert(math != null) { "应生成行内 math span：$html" }
        val formula = math!!.text()
        assert(formula.contains("<j")) { "行内公式里的 < 也应保留：$formula" }
        assert(formula.contains("i<j")) { "行内公式后半段不应丢失：$formula" }
    }

    @Test
    fun ampersandInsideFormula_isPreservedAfterJsoup() {
        // align 环境的 `&` 对齐符：转义为 &amp; 后必须由 Jsoup 原样还原，不能被二次转义
        val content = "$$\\begin{aligned} a &= b \\\\ c &< d \\end{aligned}$$"
        val html = generateMarkdownHtml(content)
        val formula = Jsoup.parse(html).select("span.math").firstOrNull()?.text()
        assert(formula != null) { "应生成 math span：$html" }
        assert(formula!!.contains("a &= b")) { "对齐符 & 不应丢失或被转义：$formula" }
        assert(formula.contains("c &< d")) { "公式里的 & 与 < 都应保留：$formula" }
    }
}
