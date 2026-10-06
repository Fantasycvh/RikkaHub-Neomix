package app.androidrik.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裸 LaTeX 环境归一化（会话 `Tikz中文支持`「多重公式对齐」渲染成源码的修复）。
 *
 * 关键不变量：
 * - 补的 `$$` 必须独占一行（`$$\begin{align}` 同行会被 remark-math 误判）；
 * - 已有的 `$$` / `\[...\]` / `$...$` 包裹的环境**不得**被二次包裹；
 * - `\\` 换行分隔符原样保留（这条正是「`\\` 被吃掉」的真因所在：没进数学模式才被 markdown 转义）；
 * - 代码块内的 LaTeX 示例不动。
 */
class LatexEnvironmentsTest {

    @Test
    fun `real world bare align block gets display delimiters and inline safe alias`() {
        val content = "- **多重公式对齐**  \n" +
            "  \\begin{align}\n" +
            "      f(x) &= x^2 + 2x + 1 \\\\\n" +
            "      g(x) &= \\int f(x) \\, dx\n" +
            "  \\end{align}\n"

        val expected = "- **多重公式对齐**  \n" +
            "  $$\n" +
            "  \\begin{aligned}\n" +
            "      f(x) &= x^2 + 2x + 1 \\\\\n" +
            "      g(x) &= \\int f(x) \\, dx\n" +
            "  \\end{aligned}\n" +
            "  $$\n"

        assertEquals(expected, LatexEnvironments.normalize(content))
    }

    @Test
    fun `row separator survives normalization`() {
        val result = LatexEnvironments.normalize("\\begin{align} a &= b \\\\ c &= d \\end{align}")

        // `\\` 是 LaTeX 换行分隔符，绝不能被吞成单个 `\`。
        assertTrue(result, result.contains("\\\\"))
        assertFalse(result, result.contains("b \\ c"))
    }

    @Test
    fun `delimiters are on their own lines`() {
        val result = LatexEnvironments.normalize("x\n\\begin{cases}a & b\\end{cases}\n")

        assertFalse("同行 \\$\\$ 会被 remark-math 误判：$result", result.contains("$$\\begin{"))
        assertTrue(result, result.contains("$$\n\\begin{cases}"))
        assertTrue(result, result.contains("\\end{cases}\n$$"))
    }

    @Test
    fun `environment already inside dollar delimiters is not wrapped twice`() {
        val content = "$$\n\\begin{cases}\n x^2 & x \\ge 0 \\\\\n -x^2 & x < 0\n\\end{cases}\n$$"

        val result = LatexEnvironments.normalize(content)

        assertEquals(content, result)
        assertFalse(result, result.contains("$$$$"))
    }

    @Test
    fun `environment inside bracket delimiters is only renamed`() {
        val content = "\\[\n\\begin{align} a &= b \\\\ c &= d \\end{align}\n\\]"

        val result = LatexEnvironments.normalize(content)

        assertFalse("不得在 \\[...\\] 外再包一层：$result", result.contains("$$"))
        assertTrue(result, result.contains("\\begin{aligned}"))
        assertTrue(result, result.contains("\\end{aligned}"))
    }

    @Test
    fun `environment inside inline math is only renamed`() {
        val result = LatexEnvironments.normalize("文本 $\\begin{align} a &= b \\end{align}$ 结尾")

        assertEquals("文本 $\\begin{aligned} a &= b \\end{aligned}$ 结尾", result)
    }

    @Test
    fun `fenced code block is left untouched`() {
        val content = "```latex\n\\begin{align} a &= b \\\\ c &= d \\end{align}\n```"

        assertEquals(content, LatexEnvironments.normalize(content))
    }

    @Test
    fun `inline code span is left untouched`() {
        val content = "用 `\\begin{align}` 起头"

        assertEquals(content, LatexEnvironments.normalize(content))
    }

    @Test
    fun `gather multline and alignat map to supported equivalents`() {
        assertEquals(
            "$$\n\\begin{gathered}\n a \\\\ b\n\\end{gathered}\n$$",
            LatexEnvironments.normalize("\\begin{gather}\n a \\\\ b\n\\end{gather}"),
        )
        assertTrue(
            LatexEnvironments.normalize("\\begin{multline} a \\\\ b \\end{multline}")
                .contains("\\begin{gathered}"),
        )
        assertTrue(
            LatexEnvironments.normalize("\\begin{alignat*}{2} a &= b \\end{alignat*}")
                .contains("\\begin{alignedat}{2}"),
        )
    }

    @Test
    fun `tikz environment is wrapped but never renamed`() {
        val result = LatexEnvironments.normalize("\\begin{tikzpicture}\n\\draw (0,0) -- (1,1);\n\\end{tikzpicture}")

        assertTrue(result, result.contains("$$\n\\begin{tikzpicture}"))
        assertTrue(result, result.contains("\\end{tikzpicture}\n$$"))
        assertFalse("tikz 环境名要原样交给 TikZ 编译器", result.contains("tikzpictured"))
    }

    @Test
    fun `unknown environment is ignored`() {
        val content = "\\begin{itemize}\n\\item a\n\\end{itemize}"

        assertEquals(content, LatexEnvironments.normalize(content))
    }

    @Test
    fun `unclosed environment during streaming keeps its name normalization but gets no delimiters`() {
        val content = "- 对齐\n  \\begin{align}\n      f(x) &= x^2"

        val result = LatexEnvironments.normalize(content)

        // 中途未闭合：补 $$ 只会得到坏公式，故不补定界符。
        assertFalse(result, result.contains("$$"))
        // 环境名照旧归一，闭合后即可直接渲染。
        assertTrue(result, result.contains("\\begin{aligned}"))
    }

    @Test
    fun `nested identical environments pair correctly`() {
        val content = "\\begin{align}\n a \\\\ \\begin{align} b \\end{align}\n\\end{align}"

        val result = LatexEnvironments.normalize(content)

        assertEquals(2, Regex("\\\\begin\\{aligned\\}").findAll(result).count())
        assertEquals(2, Regex("\\\\end\\{aligned\\}").findAll(result).count())
        assertTrue(result, result.startsWith("$$\n"))
        assertTrue(result, result.endsWith("\n$$"))
    }

    @Test
    fun `normalization is idempotent`() {
        val content = "- **多重公式对齐**  \n  \\begin{align}\n a &= b \\\\ c &= d\n  \\end{align}\n"

        val once = LatexEnvironments.normalize(content)

        assertEquals(once, LatexEnvironments.normalize(once))
    }

    @Test
    fun `content without environments is returned unchanged`() {
        val content = "- 普通文本 `$ a $`\n- 行内公式 \\(x^2\\)\n- 块公式 \\[\n y = kx\n\\]\n"

        assertEquals(content, LatexEnvironments.normalize(content))
    }

    @Test
    fun `mid line environment is not wrapped`() {
        val content = "前面有字 \\begin{cases}a & b\\end{cases} 后面有字"

        assertEquals(content, LatexEnvironments.normalize(content))
    }

    @Test
    fun `already inline safe environments keep working`() {
        val content = "$$\n\\begin{aligned}\n a &= b \\\\\n c &= d\n\\end{aligned}\n$$"

        assertEquals(content, LatexEnvironments.normalize(content))
    }
}
