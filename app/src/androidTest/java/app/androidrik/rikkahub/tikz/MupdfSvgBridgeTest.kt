package app.androidrik.rikkahub.tikz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * mupdf SVG 桥真机验证：tectonic PDF（内嵌字体）→ <text> 保留型 SVG。
 * 决定性断言：输出含 <text> 元素（文字保留，而非轮廓/悬空 use）、中文内容出现。
 */
@RunWith(AndroidJUnit4::class)
class MupdfSvgBridgeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // 真实链路：不含 documentclass 的裸 tikzpicture → TectonicNative.wrapDocument 包装
    //（wrapDocument 已内置 droidsans 三族注入 + every node font 强制中文字体）
    private val cnTikz = """
        \begin{tikzpicture}
        \node[draw] (a) at (0,0) {中文节点 START};
        \end{tikzpicture}
    """.trimIndent()

    private fun pdfOf(code: String): ByteArray? = runBlocking {
        withContext(Dispatchers.IO) { TectonicNative.compileIsolated(context, code) }
    }

    private fun toSvg(pdf: ByteArray): String? = runBlocking {
        withContext(Dispatchers.IO) { MupdfSvgBridge.pdfToSvg(pdf) }
    }

    private fun assumeBridgeAvailable() {
        assertTrue(
            "libpdf2svg_bridge not available (load failed); check jniLibs arm64 packaging",
            MupdfSvgBridge.available,
        )
    }

    @Test
    fun mupdfSvgKeepsTextAsTextElementsAndCjk() {
        assumeBridgeAvailable()
        val pdf = pdfOf(cnTikz) ?: throw AssertionError("tectonic pdf compile failed")
        assertTrue("pdf should not be empty", pdf.isNotEmpty())

        val svg = toSvg(pdf)
        assertNotNull("mupdf pdf->svg failed", svg)
        val s = svg!!

        // 文字保留为 <text> 元素（TEXT_AS_TEXT），而不是悬空 use/轮廓路径
        assertTrue("svg should contain <text elements: ${s.take(300)}", s.contains("<text"))
        // 中转存日志便于诊断——真机 logcat 看实际文字形态
        android.util.Log.i("MupdfSvgBridgeTest", "SVG_DUMP: ${s.length} chars:\\n${s.take(1200)}")
        // 中文字符应出现在文本内容里（mupdf 以 &#x4e2d; HTML 实体形式输出）
        assertTrue(
            "svg should contain CJK text content: ${s.take(400)}",
            s.contains("&#x4e2d;") && s.contains("&#x6587;"),
        )
    }

    @Test
    fun mupdfSvgContainsShapePaths() {
        assumeBridgeAvailable()
        val pdf = pdfOf(cnTikz)!!
        val svg = toSvg(pdf)!!
        // 图形（node 边框矩形）必须存在——图形与文字同源，不应因文字模式而丢失
        assertTrue("svg should contain path elements", svg.contains("<path"))
    }

    // —— 回归复现：foreach 中文 label（用户实测"中文标丢、英文标在"）——
    private val foreachCn = """
        \begin{tikzpicture}
        \foreach \x/\label in {0/A, 1/中, 2/B} {
            \node[draw] at (\x, 0) {\label};
        }
        \end{tikzpicture}
    """.trimIndent()

    @Test
    fun foreachCjkLabelKeptInSvg() {
        assumeBridgeAvailable()
        val pdf = pdfOf(foreachCn) ?: error("foreach compile failed: ${TectonicNative.lastErrorText()}")
        val svg = toSvg(pdf) ?: error("mupdf svg failed")
        android.util.Log.i("MupdfSvgBridgeTest", "FOREACH_SVG: ${svg.take(1200)}")
        // 中文「中」(U+4E2D) 必须出现在文本内容（实体形式 &#x4e2d;）
        assertTrue("foreach CJK label lost in svg: ${svg.take(400)}", svg.contains("&#x4e2d;"))
        // 英文 A/B 也应在
        assertTrue("foreach EN labels lost", svg.contains("A") && svg.contains("B"))
    }

    // —— 回归复现：pgfplots 裸片段（用户实测"整块不渲染输出源码"）——
    private val pgfplotsBare = """
        \begin{tikzpicture}
        \begin{axis}
        \addplot coordinates {(0,1) (1,3) (2,2)};
        \end{axis}
        \end{tikzpicture}
    """.trimIndent()

    @Test
    fun pgfplotsBareFragmentRenders() {
        assumeBridgeAvailable()
        // 裸片段（无 documentclass）→ wrapDocument 应自动加载 pgfplots
        val pdf = pdfOf(pgfplotsBare) ?: error("pgfplots compile failed: ${TectonicNative.lastErrorText()}")
        val svg = toSvg(pdf) ?: error("mupdf svg failed")
        android.util.Log.i("MupdfSvgBridgeTest", "PGFPLOTS_SVG: ${svg.take(800)}")
        assertTrue("pgfplots svg should have shape paths", svg.contains("<path"))
    }
}
