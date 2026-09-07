package app.androidrik.rikkahub.ui.components.richtext

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Test

/**
 * 取证：聊天 UI 的 IntelliJ Markdown 解析对 $$...$$ 同行式 TikZ 的实际处理。
 * 验证两点：① $$\begin{tikzpicture}...$$ 是否生成 <span class="math">；
 * ② \begin/\end 的反斜杠是否被 markdown 转义吞掉（用户提示的识别问题）。
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

    @Test
    fun blockStyleDollarTikz_should_contain_span_math_raw_backslash() {
        // 换行式 $$（用户系统提示词的推荐形式）
        val content = """
            $$

            \begin{tikzpicture}
            \draw (0,0) node {中文} -- (3,0) node {English};
            \end{tikzpicture}

            $$
        """.trimIndent()
        val html = htmlOf(content)
        println("BLOCK_STYLE_HTML: $html")
        val hasMathSpan = html.contains("class=\"math\"")
        val backslashKept = html.contains("\\begin{tikzpicture}")
        println("hasMathSpan=$hasMathSpan backslashKept=$backslashKept")
        // 断言 MathBlock 走通的前提：生成 math span 且反斜杠保留
        assert(hasMathSpan) { "换行式 $$ 应生成 span.math，实际 HTML 无 math span" }
        assert(backslashKept) { "\\begin 反斜杠应保留，但 HTML 中丢失/被转义" }
    }

    @Test
    fun inlineStyleDollarTikz_what_happens() {
        // 同行式 $$（用户实际输入形式：$$ 紧跟 \begin{tikzpicture}）
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
}
