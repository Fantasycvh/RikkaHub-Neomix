package app.androidrik.rikkahub.tikz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TikZ 失败分类器测试：验证 AI 常见输出特征能落入预期类别，
 * 为 docs/tikz-rendering-tectonic-postmortem.md 8.3 埋点提供可靠分布数据。
 */
class TikzTelemetryTest {

    @Test
    fun `pgfplots axis environment detected`() {
        val cats = TikzTelemetry.classifyFailure("""\begin{axis}\addplot {x^2};\end{axis}""")
        assertEquals(listOf(TikzFailureCategory.PGFPLOTS), cats)
    }

    @Test
    fun `positioning right=of detected`() {
        val code = """\node[draw] (a) {A}; \node[draw, right=of a] (b) {B};"""
        val cats = TikzTelemetry.classifyFailure(code)
        assertTrue(cats.contains(TikzFailureCategory.POSITIONING))
    }

    @Test
    fun `positioning with distance and compound anchor detected`() {
        val code = """\node[above right=2cm and 1cm of a] (b) {B};"""
        val cats = TikzTelemetry.classifyFailure(code)
        assertTrue(cats.contains(TikzFailureCategory.POSITIONING))
    }

    @Test
    fun `foreach detected`() {
        val cats = TikzTelemetry.classifyFailure("""\foreach \i in {1,...,5} {\node at (\i,0) {\i};}""")
        assertEquals(listOf(TikzFailureCategory.FOREACH), cats)
    }

    @Test
    fun `tikzset style definition detected`() {
        val cats = TikzTelemetry.classifyFailure("""\tikzset{mystyle/.style={draw, red}}""")
        assertEquals(listOf(TikzFailureCategory.TIKZSET_STYLE), cats)
    }

    @Test
    fun `scope environment detected`() {
        val cats = TikzTelemetry.classifyFailure("""\begin{scope}[shift={(1,1)}] \draw (0,0) -- (1,1); \end{scope}""")
        assertEquals(listOf(TikzFailureCategory.SCOPE), cats)
    }

    @Test
    fun `calc coordinate expression detected`() {
        val cats = TikzTelemetry.classifyFailure("""\draw ($ (a)!0.5!(b) $) circle (1);""")
        assertEquals(listOf(TikzFailureCategory.CALC_COORD), cats)
    }

    @Test
    fun `fit option detected`() {
        val code = """\node[draw, fit=(a) (b), inner sep=2pt] {};"""
        val cats = TikzTelemetry.classifyFailure(code)
        assertTrue(cats.contains(TikzFailureCategory.FIT))
    }

    @Test
    fun `matrix detected`() {
        val cats = TikzTelemetry.classifyFailure("""\matrix[draw] at (0,0) { \node {A}; \\ \node {B}; \\ };""")
        assertEquals(listOf(TikzFailureCategory.MATRIX), cats)
    }

    @Test
    fun `quotes edge label detected`() {
        val cats = TikzTelemetry.classifyFailure("""\draw (a) to["label"] (b);""")
        assertEquals(listOf(TikzFailureCategory.QUOTES_LABEL), cats)
    }

    @Test
    fun `math inside node text detected`() {
        val cats = TikzTelemetry.classifyFailure("\\node at (0,0) {\$x^2 + y^2\$};")
        assertEquals(listOf(TikzFailureCategory.MATH_IN_NODE), cats)
    }

    @Test
    fun `size guard exception takes precedence over detected constructs`() {
        val code = "\\node[right=of a] {\$x\$};"
        val e = IllegalArgumentException("TikZ 图表尺寸异常 (1.0e12 x 20)，已拒绝渲染")
        val cats = TikzTelemetry.classifyFailure(code, e)
        assertEquals(listOf(TikzFailureCategory.SIZE_GUARD), cats)
    }

    @Test
    fun `no renderable elements exception maps to no_elements when no known constructs`() {
        val e = IllegalArgumentException("No renderable TikZ elements found")
        val cats = TikzTelemetry.classifyFailure("""\draw[myunknownstyle] (0,0);""", e)
        assertEquals(listOf(TikzFailureCategory.NO_ELEMENTS), cats)
    }

    @Test
    fun `unsupported construct without known signature falls back to other`() {
        val cats = TikzTelemetry.classifyFailure("""\draw (0,0) pic {somepic};""")
        assertEquals(listOf(TikzFailureCategory.OTHER), cats)
    }

    @Test
    fun `multiple constructs all reported in detector order`() {
        val code = """\foreach \i in {1,2} {\node[right=of a] {$\i$};}"""
        val cats = TikzTelemetry.classifyFailure(code)
        assertEquals(
            listOf(TikzFailureCategory.POSITIONING, TikzFailureCategory.FOREACH, TikzFailureCategory.MATH_IN_NODE),
            cats,
        )
    }
}
