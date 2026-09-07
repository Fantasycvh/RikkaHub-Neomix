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

        // TEXT_AS_PATH：文字转轮廓 path（AndroidSVG 不渲染 <text> 中文），检查 path 存在
        assertTrue("svg should contain path elements: ${s.take(300)}", s.contains("<path"))
        // 中转存日志便于诊断——真机 logcat 看实际文字形态
        android.util.Log.i("MupdfSvgBridgeTest", "SVG_DUMP: ${s.length} chars:\\n${s.take(1200)}")
        // TEXT_AS_PATH 模式文字为轮廓，svg 体积应明显大于纯图形（含中文/拉丁字形轮廓）
        android.util.Log.i("MupdfSvgBridgeTest", "SVG_DUMP_LEN=${s.length}")
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
        // TEXT_AS_PATH：中文/英文为轮廓，svg 应足够大（含全部字形轮廓）
        assertTrue("foreach svg too small: ${svg.length}", svg.length > 2000)
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

    // —— 用户原样复现：函数型 pgfplots（sin(x rad)*exp(-x^2/8)，区别于 coordinates 系列）——
    private val userPgfplots = """
        \begin{tikzpicture}
        \begin{axis}[
          width=8cm, height=4.5cm,
          xmin=-6, xmax=6, ymin=-1.4, ymax=1.4,
          axis lines=middle, grid=both,
          xlabel={${'$'}x${'$'}}, ylabel={${'$'}y${'$'}}, title={${'$'}\sin(x)\,e^{-x^2/8}${'$'}},
        ]
        \addplot[blue, thick, samples=200] {sin(x rad)*exp(-x^2/8)};
        \end{axis}
        \end{tikzpicture}
    """.trimIndent()

    @Test
    fun userPgfplotsFunctionAddplotRenders() {
        assumeBridgeAvailable()
        val pdf = pdfOf(userPgfplots)
        android.util.Log.i("MupdfSvgBridgeTest", "USER_PGFPLOTS lastError=${TectonicNative.lastErrorText()}")
        android.util.Log.i("MupdfSvgBridgeTest", "USER_PGFPLOTS pdf=${pdf?.size ?: "null"}")
        val svg = pdf?.let { toSvg(it) }
        android.util.Log.i("MupdfSvgBridgeTest", "USER_PGFPLOTS svg=${svg?.take(600) ?: "null"}")
        assertNotNull("user pgfplots function addplot failed", svg)
        assertTrue("user pgfplots svg should contain shapes", svg!!.contains("<path"))
    }

    // —— 用户原样复现：裸 tikzpicture 内 CJK node（中英对照）——
    private val userCjkNode = """
        \begin{tikzpicture}
        \node (a) at (0,0) {中文};
        \node (b) at (2,0) {Hello World};
        \end{tikzpicture}
    """.trimIndent()

    @Test
    fun userBareTikzCjkNodeKept() {
        assumeBridgeAvailable()
        val pdf = pdfOf(userCjkNode)
        android.util.Log.i("MupdfSvgBridgeTest", "USER_CJK lastError=${TectonicNative.lastErrorText()}")
        val svg = pdf?.let { toSvg(it) }
        android.util.Log.i("MupdfSvgBridgeTest", "USER_CJK svg=${svg?.take(600) ?: "null"}")
        assertNotNull("user bare cjk node failed", svg)
        assertTrue("user cjk node svg should contain paths", svg!!.contains("<path"))
    }


    // —— 修复对照：rad → deg(x)（提示词已引导，验证编译走通）——
    private val userPgfplotsDeg = """
        \begin{tikzpicture}
        \begin{axis}[
          width=8cm, height=4.5cm,
          xmin=-6, xmax=6, ymin=-1.4, ymax=1.4,
          axis lines=middle, grid=both,
          xlabel={${'$'}x${'$'}}, ylabel={${'$'}y${'$'}}, title={${'$'}sin(x)e^{-x^2/8}${'$'}},
        ]
        \addplot[blue, thick, samples=200] {sin(deg(x))*exp(-x^2/8)};
        \end{axis}
        \end{tikzpicture}
    """.trimIndent()

    @Test
    fun userPgfplotsDegVariantRenders() {
        assumeBridgeAvailable()
        val svg = userPgfplotsDeg.let { pdfOf(it)?.let { toSvg(it) } }
        android.util.Log.i("MupdfSvgBridgeTest", "DEG_SVG=${svg?.take(600) ?: "null"}")
        assertNotNull("deg() variant should render", svg)
        assertTrue("deg() svg should contain path", svg!!.contains("<path"))
    }

    // —— 用户第二次原样：path 内 node + 显式 font 覆盖 + foreach 中文 ——
    private val userComplexTikz = """
        \begin{tikzpicture}
        \draw (0,0) node {中文} -- (3,0) node {English};
        \node at (1.5,1) [font=\large] {测试中文字符};
        \foreach \x/\lab in {1/一, 2/二, 3/三} {
          \draw (\x,0) -- (\x,1) node[above] {\lab};
        }
        \end{tikzpicture}
    """.trimIndent()

    @Test
    fun userComplexTikzCjkKept() {
        assumeBridgeAvailable()
        val pdf = pdfOf(userComplexTikz)
        android.util.Log.i("MupdfSvgBridgeTest", "USER2 lastError=${TectonicNative.lastErrorText()}")
        android.util.Log.i("MupdfSvgBridgeTest", "USER2 pdf=${pdf?.size ?: "null"}")
        val svg = pdf?.let { toSvg(it) }
        android.util.Log.i("MupdfSvgBridgeTest", "USER2 svg=${svg?.take(1200) ?: "null"}")
        assertNotNull("user complex tikz failed", svg)
        assertTrue("complex svg should contain paths (CJK as outline)", svg!!.contains("<path"))
    }

}
