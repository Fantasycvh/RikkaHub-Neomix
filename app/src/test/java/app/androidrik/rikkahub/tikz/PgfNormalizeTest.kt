package app.androidrik.rikkahub.tikz

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * wrapDocument 的 PGF Math 弧度写法归一化（sin(x rad)→sin(deg(x))）单元验证。
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
}
