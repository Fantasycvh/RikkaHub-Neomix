package app.androidrik.rikkahub.tikz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * wrapDocument 的 PGF Math 归一化（sin(x rad)→deg）与 #hex 颜色替换单元验证。
 * 纯字符串变换，无 native 依赖，JVM 可跑。
 */
class PgfNormalizeTest {

    private fun norm(s: String) = TectonicNative.normalizePgfMath(s)

    @Test
    fun singleVar_withRad_becomes_deg() {
        assertEquals("sin(deg(x))", norm("sin(x rad)"))
        assertEquals("cos(deg(t))", norm("cos(t r)"))
        assertEquals("tan(deg(theta))", norm("tan(theta rad)"))
    }

    @Test
    fun alreadyCompat_not_touched() {
        assertEquals("sin(deg(x))", norm("sin(deg(x))"))
        assertEquals("sin(x*pi/180)", norm("sin(x*pi/180)"))
        assertEquals("sin(pi/180*x)", norm("sin(pi/180*x)"))
    }

    @Test
    fun no_rad_no_change() {
        assertEquals("sin(x)", norm("sin(x)"))
        assertEquals("sin(30)", norm("sin(30)"))
        assertEquals("x+y", norm("x+y"))
    }

    @Test
    fun full_pgfplots_line() {
        val line = "\\addplot[blue, thick, samples=200] {sin(x rad)*exp(-x^2/8)};"
        assertEquals(
            "\\addplot[blue, thick, samples=200] {sin(deg(x))*exp(-x^2/8)};",
            norm(line)
        )
    }

    @Test
    fun uppercase_and_mixed() {
        assertEquals("sin(deg(x))", norm("SIN(x RAD)"))
        assertEquals("sin(deg(x))", norm("sin( x  rad )"))
    }

    @Test
    fun bareFragment_hexReplaced_withDefinecolorDefs() {
        val code = "\\draw[thick, #4A90D9] (A) -- (B);\\n" +
            "\\fill[#7ED321!30] (1,1) circle (1);\\n" +
            "\\node[#E85D75] at (0,0) {x};"
        val (replaced, defs) = TectonicNative.normalizeHexColors(code)
        assertEquals(
            "\\draw[thick, hex0] (A) -- (B);\\n" +
                "\\fill[hex1!30] (1,1) circle (1);\\n" +
                "\\node[hex2] at (0,0) {x};",
            replaced
        )
        assertEquals(
            listOf(
                "\\definecolor{hex0}{HTML}{4a90d9}",
                "\\definecolor{hex1}{HTML}{7ed321}",
                "\\definecolor{hex2}{HTML}{e85d75}",
            ),
            defs,
        )
    }

    @Test
    fun noHex_noDefs_noChange() {
        val (replaced, defs) = TectonicNative.normalizeHexColors("\\draw[blue] (0,0) -- (1,1);")
        assertEquals("\\draw[blue] (0,0) -- (1,1);", replaced)
        assertEquals(emptyList<String>(), defs)
    }

    @Test
    fun fullDocument_hexKept_radNormalized() {
        // 完整 documentclass 文档：用户自管 preamble，#hex 不替换（避免 Undefined color），
        // 弧度归一化仍生效（原地自洽、无害）—— wrapDocument 修复语义
        val doc = "\\documentclass{article}\\n\\usepackage{tikz}\\n" +
            "\\begin{document}\\n\\draw[#4A90D9] (0,0) -- (1,1);\\n\\end{document}"
        val wrapped = TectonicNative.wrapDocument(doc)
        assertEquals(
            "\\documentclass{article}\\n\\usepackage{tikz}\\n" +
                "\\begin{document}\\n\\draw[#4A90D9] (0,0) -- (1,1);\\n\\end{document}",
            wrapped
        )
    }

    @Test
    fun fullDocument_radStillNormalized() {
        val doc = "\\documentclass{article}\\n\\begin{document}\\n\\addplot {sin(x rad)};\\n\\end{document}"
        val wrapped = TectonicNative.wrapDocument(doc)
        assertTrue("弧度归一化应在完整文档生效，实际: $wrapped", wrapped.contains("sin(deg(x))"))
    }

    @Test
    fun bareFragment_wrapInjectsHexDefsAndFonts() {
        val bare = "\\begin{tikzpicture}\\node[fill=#4A90D9] (a) {x};\\end{tikzpicture}"
        val wrapped = TectonicNative.wrapDocument(bare)
        assertTrue("裸片段应注入 definecolor，实际: ${wrapped.take(200)}", wrapped.contains("definecolor{hex0}"))
        assertTrue("裸片段应注入中文 selectfont 包装", wrapped.contains("zholdselectfont"))
        assertTrue("裸片段不应替换 documentclass（非完整文档）", !wrapped.startsWith("\\documentclass{article}", true) || wrapped.contains("documentclass"))
        assertTrue("替换后代码应含 hex0", wrapped.contains("hex0"))
    }
}