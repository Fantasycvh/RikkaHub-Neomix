package app.androidrik.rikkahub.tikz

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模拟 MarkdownNew.kt preProcess（`\(...\)`/`\[...\]` → `$`/`$$`，跳过代码块；内容 trim 供 IntelliJ 识别） */
private fun preProcess(content: String): String {
    val codeBlocks = mutableListOf<IntRange>()
    Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL).findAll(content).forEach { codeBlocks.add(it.range) }
    fun isInCodeBlock(pos: Int) = codeBlocks.any { pos in it }
    var result = Regex("\\\\\\((.+?)\\\\\\)").replace(content) { m ->
        if (isInCodeBlock(m.range.first)) m.value else "$" + m.groupValues[1].trim() + "$"
    }
    result = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL).replace(result) { m ->
        if (isInCodeBlock(m.range.first)) m.value else "$$" + m.groupValues[1].trim() + "$$"
    }
    return result
}

/**
 * 诊断：IntelliJ markdown 对 $$...$$ 内嵌 TikZ 消息的提取结果（定位真机「No renderable」根因）。
 * 模拟 Markdown.kt preProcess + parser.buildMarkdownTreeFromString 链路。
 */
class MarkdownMathExtractTest {

    private fun parse(msg: String): Pair<ASTNode, String> {
        val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))
        val tree = parser.buildMarkdownTreeFromString(msg)
        return tree to msg
    }

    private fun walk(n: ASTNode, src: String, depth: Int, out: StringBuilder) {
        val text = if (n.children.isEmpty()) src.substring(n.startOffset, n.endOffset) else ""
        out.append("  ${"  ".repeat(depth)}${n.type} [${text.replace("\n", "\\n")}]\n")
        n.children.forEach { walk(it, src, depth + 1, out) }
    }

    private fun collectMath(n: ASTNode, src: String, out: MutableList<String>) {
        if (n.type == GFMElementTypes.BLOCK_MATH || n.type == GFMElementTypes.INLINE_MATH) {
            out.add(src.substring(n.startOffset, n.endOffset))
        }
        n.children.forEach { collectMath(it, src, out) }
    }

    @Test
    fun `diag - dollar math block extraction for tikz single variant`() {
        val msgs = listOf(
            """$$\begin{tikzpicture}
\draw[thick, rounded corners] (0,0) -- (4,0);
\end{tikzpicture}$$""",
            """$$\begin{tikzpicture}
\draw[rounded corners=5, thick] (0,0) -- (4,0);
\end{tikzpicture}$$""",
            """$$\begin{tikzpicture}
\draw[rounded corners, thick] (0,0) -- (4,0) -- (4,3) -- (0,3) -- (0,6);
\end{tikzpicture}$$""",
        )
        msgs.forEach { msg ->
            val (tree, src) = parse(msg)
            println("=== input: ${msg.replace("\n", "\\n")}")
            val sb = StringBuilder()
            walk(tree, src, 0, sb)
            println(sb.toString())
            val math = mutableListOf<String>()
            collectMath(tree, src, math)
            println("  MATH: ${math.map { it.replace("\n", "\\n") }}")
        }
    }

    @Test
    fun `diag - html generator math rendering for tikz block`() {
        val msgs = listOf(
            """$$\begin{tikzpicture}
\draw[rounded corners=5, thick] (0,0) -- (4,0);
\end{tikzpicture}$$""",
            // 无结尾 $$（用户贴的格式：开头 $$ 结尾无）
            """$$\begin{tikzpicture}
\draw[rounded corners=5, thick] (0,0) -- (4,0);
\end{tikzpicture}""",
            // 整段用户消息（含标题/注释/多个变体）
            """## 三个快速变体

$$\begin{tikzpicture}
\draw[thick, rounded corners] (0,0) -- (4,0);
\end{tikzpicture}$$

*（顺序反转）*

$$\begin{tikzpicture}
\draw[rounded corners=5, thick] (0,0) -- (4,0);
\end{tikzpicture}$$""",
        )
        msgs.forEach { msg ->
            val preprocessed = preProcess(msg)
            val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))
            val tree = parser.buildMarkdownTreeFromString(preprocessed)
            val html = HtmlGenerator(preprocessed, tree, GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)).generateHtml()
            println("=== html gen for: ${msg.replace("\n", "\\n")}")
            println("  HTML: ${html.replace("\n", "\\n")}")
            // jsoup 提取 math 元素 text（模拟 MarkdownNew HtmlMathBlock）
            val doc = Jsoup.parse(html)
            doc.select("span.math, .math, mjx-container").forEach { el ->
                println("  MATH-ELEM[${el.tagName()} class=${el.className()} inline=${el.attr("inline")}] text=${el.text().replace("\n", "\\n")}")
            }
        }
    }

    @Test
    fun `diag - user exact message format with unclosed dollars`() {
        // 用户贴的格式：$$ 开头，\end{tikzpicture} 结尾无闭合 $$ —— 模拟 IntelliJ 配对 + strip + render 全链路
        val msg = """$$\begin{tikzpicture}
\draw[rounded corners, thick] (0,0) -- (4,0) -- (4,3) -- (0,3) -- (0,6);
\end{tikzpicture}

## three quick variants

$$\begin{tikzpicture}
\draw[thick, rounded corners] (0,0) -- (4,0);
\end{tikzpicture}

*（reversed order）*

$$\begin{tikzpicture}
\draw[rounded corners, dashed] (0,0) -- (4,0);
\end{tikzpicture}

*（second option）*

$$\begin{tikzpicture}
\draw[rounded corners=5, thick] (0,0) -- (4,0);
\end{tikzpicture}

none of these work"""
        val preprocessed = preProcess(msg)
        val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))
        val tree = parser.buildMarkdownTreeFromString(preprocessed)
        val math = mutableListOf<String>()
        collectMath(tree, preprocessed, math)
        println("=== user msg (unclosed dollars), len=${msg.length}, MATH count=${math.size}")
        math.forEachIndexed { i, m ->
            val stripped = stripDollarWrappers(m)
            val r = TikzRenderer.render(stripped)
            println("  MATH[$i] len=${m.length} strippedHead=${stripped.take(60).replace("\n", "\\n")} -> ${if (r.isSuccess) "OK len=${r.getOrThrow().length}" else "FAIL ${r.exceptionOrNull()}"}")
        }
        // 完整 AST 树（找普通段落里的 \draw 文本）
        val sb = StringBuilder()
        walk(tree, preprocessed, 0, sb)
        println(sb.toString())
    }

    private fun stripDollarWrappers(latex: String): String {
        val t = latex.trim()
        return when {
            t.startsWith("$$") && t.endsWith("$$") && t.length > 4 -> t.substring(2, t.length - 2).trim()
            t.startsWith("\\[") && t.endsWith("\\]") && t.length > 4 -> t.substring(2, t.length - 2).trim()
            else -> t
        }
    }

    @Test
    fun `diag - inline latex paren in text vs code block`() {
        // 普通文本里的 \(...\)：preProcess 应替换为 $...$ → INLINE_MATH
        val msg1 = """公式：\( \frac{a}{b} \) 测试 \( \sqrt[3]{x} \)"""
        val p1 = preProcess(msg1)
        println("=== text: ${p1.replace("\n", "\\n")}")
        val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))
        val tree1 = parser.buildMarkdownTreeFromString(p1)
        val math1 = mutableListOf<String>()
        collectMath(tree1, p1, math1)
        println("  MATH count=${math1.size}")
        math1.forEachIndexed { i, m -> println("  MATH[$i]: ${m.replace("\n", "\\n")}") }

        // 代码块里的 \(...\)：preProcess 应跳过（isInCodeBlock）→ 原样保留在代码块
        val msg2 = """```latex
\( \frac{a}{b} \)
```"""
        val p2 = preProcess(msg2)
        println("=== codeblock: ${p2.replace("\n", "\\n")}")
        val tree2 = parser.buildMarkdownTreeFromString(p2)
        val math2 = mutableListOf<String>()
        collectMath(tree2, p2, math2)
        println("  MATH count=${math2.size}")
        val sb = StringBuilder()
        walk(tree2, p2, 0, sb)
        println(sb.toString())
    }

    @Test
    fun `diag - inline math recognition variants`() {
        val D = "${'$'}"
        val variants = listOf(
            """公式：${D}\frac{a}{b}${D} 测试""",
            """公式：${D}\sqrt[3]{x}${D} 测试""",
            """公式：${D}x^2${D} 测试""",
            """公式：${D} \frac{a}{b} ${D} 测试""",
            """公式：$D$D\frac{a}{b}$D$D 测试""".replace("$D$D", "$$"),
        )
        val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))
        variants.forEach { msg ->
            val tree = parser.buildMarkdownTreeFromString(msg)
            val math = mutableListOf<String>()
            collectMath(tree, msg, math)
            println("=== [$msg] MATH count=${math.size}")
            math.forEachIndexed { i, m -> println("  MATH[$i]: ${m.replace("\n", "\\n")}") }
        }
    }

    @Test
    fun `inline latex paren with spaces is trimmed and recognized`() {
        // AI 常见格式 \( \frac{a}{b} \)（括号内带空格）→ preProcess 应 trim 成 $\frac{a}{b}$ → INLINE_MATH
        val msg = """公式：\( \frac{a}{b} \) 和 \( \sqrt[3]{x} \)"""
        val preprocessed = preProcess(msg)
        assertTrue("should be converted to dollar form: $preprocessed", preprocessed.contains("$\\frac{a}{b}$"))
        val parser = MarkdownParser(GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true))
        val tree = parser.buildMarkdownTreeFromString(preprocessed)
        val math = mutableListOf<String>()
        collectMath(tree, preprocessed, math)
        println("  MATH count=${math.size}")
        math.forEachIndexed { i, m -> println("  MATH[$i]: ${m.replace("\n", "\\n")}") }
        assertTrue("expected 2 inline math blocks, got ${math.size}", math.size >= 2)
        assertTrue("frac missing", math.joinToString().contains("\\frac{a}{b}"))
        assertTrue("sqrt3 missing", math.joinToString().contains("\\sqrt[3]{x}"))
    }

    @Test
    fun `diag - full user message with multiple variants and prose`() {
        val msg = """## 三个快速变体

$$\begin{tikzpicture}
\draw[thick, rounded corners] (0,0) -- (4,0);
\end{tikzpicture}$$

*（顺序反转）*

$$\begin{tikzpicture}
\draw[rounded corners, dashed] (0,0) -- (4,0);
\end{tikzpicture}$$

*（换选项）*

$$\begin{tikzpicture}
\draw[rounded corners=5, thick] (0,0) -- (4,0);
\end{tikzpicture}"""
        val (tree, src) = parse(msg)
        println("=== full message, len=${msg.length}")
        val sb = StringBuilder()
        walk(tree, src, 0, sb)
        println(sb.toString())
        val math = mutableListOf<String>()
        collectMath(tree, src, math)
        println("  MATH count=${math.size}")
        math.forEachIndexed { i, m -> println("  MATH[$i]: ${m.replace("\n", "\\n")}") }
    }
}
