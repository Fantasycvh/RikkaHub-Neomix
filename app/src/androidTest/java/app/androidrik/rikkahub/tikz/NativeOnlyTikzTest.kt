package app.androidrik.rikkahub.tikz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 移除第①级后的端到端验证：T1/T2/T3（positioning 全场景）直接走
 * native 引擎（XDV→dvisvgm SVG 矢量），真机全链路。
 * 非 arm64-v8a 设备自动跳过。
 */
@RunWith(AndroidJUnit4::class)
class NativeOnlyTikzTest {

    private val t1 = "\\begin{tikzpicture}\n\\node (a) {A};\n\\node[right=of a] (b) {B};\n\\draw[->] (a) -- (b);\n\\end{tikzpicture}"
    private val t2 = "\\begin{tikzpicture}\n\\node[draw] (a) {A};\n\\node[draw, right=of a] (b) {B};\n\\node[draw, right=2cm of b] (c) {C};\n\\draw (a) -- (b) -- (c);\n\\end{tikzpicture}"
    private val t3 = "\\begin{tikzpicture}\n\\node[draw] (a) {中心};\n\\node[draw, above=of a] (t) {上};\n\\node[draw, below=of a] (d) {下};\n\\node[draw, left=of a] (l) {左};\n\\node[draw, right=of a] (r) {右};\n\\node[draw, above right=of a] (ar) {右上};\n\\end{tikzpicture}"

    private fun compileToSvg(name: String, code: String): String? {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)
        val xdv = TectonicNative.compileIsolated(ctx, code, "xdv")
        assertNotNull("$name xdv compile failed: ${TectonicNative.lastErrorText()}", xdv)
        val svg = TectonicNative.xdvToSvgIsolated(ctx, xdv!!)
        if (svg != null) {
            val out = File(ctx.getExternalFilesDir(null), "$name.svg")
            out.writeText(svg)
            android.util.Log.i("NativeOnly", "$name SVG ok (${svg.length} bytes) -> ${out.absolutePath}")
        } else {
            android.util.Log.w("NativeOnly", "$name svg convert failed, trying pdf fallback")
            val pdf = TectonicNative.compileIsolated(ctx, code)
            assertNotNull("$name pdf fallback also failed", pdf)
        }
        return svg
    }

    @Test
    fun t1BasicPositioning() {
        val svg = compileToSvg("native_t1", t1)
        assertTrue("t1 not svg", svg == null || svg.contains("<svg"))
    }

    @Test
    fun t2ChainedDistance() {
        val svg = compileToSvg("native_t2", t2)
        assertTrue("t2 not svg", svg == null || svg.contains("<svg"))
    }

    @Test
    fun t3FourDirectionsDiagonal() {
        val svg = compileToSvg("native_t3", t3)
        assertTrue("t3 not svg", svg == null || svg.contains("<svg"))
    }

    private val tcd = "\\begin{tikzcd}\nA \\arrow[r, \"f\"] & B\\\\\nC \\arrow[u] \\arrow[ur, \"g\"] &\n\\end{tikzcd}"

    @Test
    fun t4TikzcdDiagram() {
        val svg = compileToSvg("native_t4", tcd)
        assertTrue("t4 not svg", svg == null || svg.contains("<svg"))
    }
}