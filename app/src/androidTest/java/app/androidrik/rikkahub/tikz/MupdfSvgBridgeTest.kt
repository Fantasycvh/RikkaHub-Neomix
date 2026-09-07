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
        // 中文字符应出现在文本内容里（内嵌 droidsans 经 ToUnicode 映射）
        assertTrue("svg should contain CJK text content", s.contains("\u4e2d\u6587"))
    }

    @Test
    fun mupdfSvgContainsShapePaths() {
        assumeBridgeAvailable()
        val pdf = pdfOf(cnTikz)!!
        val svg = toSvg(pdf)!!
        // 图形（node 边框矩形）必须存在——图形与文字同源，不应因文字模式而丢失
        assertTrue("svg should contain path elements", svg.contains("<path"))
    }
}
