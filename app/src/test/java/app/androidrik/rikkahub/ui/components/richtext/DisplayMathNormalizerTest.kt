package app.androidrik.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayMathNormalizerTest {
    @Test
    fun `drops blank lines inside dollar display math`() {
        val input = "\$\$\\begin{tikzpicture}\n\\node {a};\n\n\\draw (a) -- (b);\n\\end{tikzpicture}\$\$"
        val expected = "\$\$\\begin{tikzpicture}\n\\node {a};\n\\draw (a) -- (b);\n\\end{tikzpicture}\$\$"
        assertEquals(expected, normalizeDisplayMath(input))
    }

    @Test
    fun `keeps multiple display math blocks independent`() {
        val input = "\$\$\na\n\nb\n\$\$\n中间文字\n\$\$\nc\n\$\$"
        val expected = "\$\$\na\nb\n\$\$\n中间文字\n\$\$\nc\n\$\$"
        assertEquals(expected, normalizeDisplayMath(input))
    }

    @Test
    fun `does not touch code fences`() {
        val input = "```latex\n\$\$\na\n\nb\n\$\$\n```\n\n\$\$x\$\$"
        assertEquals(input, normalizeDisplayMath(input))
    }

    @Test
    fun `collapses blank lines inside bracket display math`() {
        val input = "\\[\n\\begin{aligned}\na &= b\n\nc &= d\n\\end{aligned}\n\\]"
        val expected = "\\[\n\\begin{aligned}\na &= b\nc &= d\n\\end{aligned}\n\\]"
        assertEquals(expected, normalizeDisplayMath(input))
    }

    @Test
    fun `leaves inline math untouched`() {
        val input = "行内 \$\$x + y\$\$ 不受影响"
        assertEquals(input, normalizeDisplayMath(input))
    }

    @Test
    fun `content without display math is returned as is`() {
        val input = "普通段落\n\n\$a + b\$"
        assertEquals(input, normalizeDisplayMath(input))
    }
}
