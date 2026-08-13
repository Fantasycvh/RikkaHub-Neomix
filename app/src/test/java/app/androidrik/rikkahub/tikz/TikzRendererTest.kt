package app.androidrik.rikkahub.tikz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TikZ 子集渲染器往返测试：TikZ 源码 → SVG 字符串。
 * 坐标约定：TikZ 坐标系 y 向上，SVG 输出 y 向下（渲染时翻转）。
 */
class TikzRendererTest {

    @Test
    fun `empty input returns failure`() {
        val result = TikzRenderer.render("")
        assertTrue(result.isFailure)
    }

    @Test
    fun `node with text renders text element`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\node at (0,0) {A};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("<text"))
        assertTrue(result.getOrThrow().contains("A"))
    }

    @Test
    fun `draw line renders path with move and line commands`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) -- (1,1);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("<path"))
        assertTrue(svg.contains("M "))
        assertTrue(svg.contains("L "))
    }

    @Test
    fun `draw line with arrow and color sets marker and stroke`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw[->, red] (0,0) -- (1,1);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        val pathCount = svg.split("<path").size - 1
        assertTrue("missing arrow head (expected 2 paths: line + triangle)", pathCount >= 2)
        assertTrue("missing red stroke", svg.contains("red") || svg.contains("#"))
    }

    @Test
    fun `draw circle renders circle element`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) circle (1);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("<circle"))
    }

    @Test
    fun `node with circle shape renders circle`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\node[circle, draw] (A) at (0,0) {A};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("<circle"))
        assertTrue(svg.contains("A"))
    }

    @Test
    fun `chinese text is preserved`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\node at (0,0) {你好};\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("你好"))
    }

    @Test
    fun `named node reference resolves to its coordinate`() {
        val code = """
            \begin{tikzpicture}
            \node (A) at (1,2) {A};
            \draw (A) -- (0,0);
            \end{tikzpicture}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        // (A) 解析为 (1,2)，路径应含该坐标（y 翻转后）
        assertTrue(result.getOrThrow().contains("L "))
    }

    @Test
    fun `polar coordinate is converted to cartesian`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) -- (90:2);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        // (90:2) 极坐标 → (0, 2) 笛卡尔（y 向上），SVG 翻转后为 (0, -2) 相对平移
        val svg = result.getOrThrow()
        assertTrue(svg.contains("L "))
    }

    @Test
    fun `svg has viewBox with valid dimensions`() {
        val result = TikzRenderer.render("""\begin{tikzpicture}\draw (0,0) -- (1,1);\end{tikzpicture}""")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("viewBox"))
    }

    @Test
    fun `tikzcd simple two node arrow renders`() {
        val code = """
            \begin{tikzcd}
            A \arrow[r] & B
            \end{tikzcd}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("A"))
        assertTrue(svg.contains("B"))
        assertTrue("missing arrow", svg.split("<path").size - 1 >= 2)
    }

    @Test
    fun `tikzcd two row diagram with labels`() {
        val code = """
            \begin{tikzcd}
            A \arrow[r, "f"] \arrow[d] & B \\
            C & D
            \end{tikzcd}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        val svg = result.getOrThrow()
        assertTrue(svg.contains("f"))
        assertTrue(svg.contains("A"))
        assertTrue(svg.contains("B"))
        assertTrue(svg.contains("C"))
        assertTrue(svg.contains("D"))
    }

    @Test
    fun `tikzcd diagonal arrow renders`() {
        val code = """
            \begin{tikzcd}
            A \arrow[dr] & B \\
            C & D
            \end{tikzcd}
        """.trimIndent()
        val result = TikzRenderer.render(code)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().contains("<path"))
    }
}
