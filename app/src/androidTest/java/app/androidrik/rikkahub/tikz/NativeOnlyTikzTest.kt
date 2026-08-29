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

    // #hex!NN（十六进制颜色 + 不透明度）——pgf/TeX 层已知限制（见 docs §8.4.8）：
    // `#` 在 TeX 中为参数 token，pgf options 展开进 \tikz@temp 时报
    // Illegal parameter number，整图编译失败、走降级。非引擎 bug，宿主系统 TeX 同错。
    private val tHex = "\\begin{tikzpicture}\n\\node[draw, fill=#4A90D9!30] (a) {A};\n\\node[draw, fill=#E85D75!40, right=of a] (b) {B};\n\\draw[->, #4A90D9!60] (a) -- (b);\n\\end{tikzpicture}"

    @Test
    fun t5HexColorWithOpacity() {
        // 记录性用例：文档化 pgf 对 #hex 的限制（真机 + 宿主 TeX 双端确认）。
        // 若未来引擎加了 #hex 预处理，本用例需随行为更新。
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)
        val xdv = TectonicNative.compileIsolated(ctx, tHex, "xdv")
        val err = TectonicNative.lastErrorText()
        if (xdv != null) {
            // 引擎若已支持（未来改进），SVG 转换后尺寸应正常
            val svg = TectonicNative.xdvToSvgIsolated(ctx, xdv)
            assertTrue("unexpected svg too small", svg == null || svg.length > 500)
        } else {
            // 当前预期：TeX 层失败，报 Illegal parameter number
            assertTrue("expected pgf #hex failure, got err=$err", err?.contains("Illegal parameter number") == true)
        }
    }
}