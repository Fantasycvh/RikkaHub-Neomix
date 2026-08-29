package app.androidrik.rikkahub.tikz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tectonic native 编译链真机验证（方案 B，扩展 bundle 内置）：
 * assets bundle（1598 文件，pgf 3.1.12 + pgfplots 1.18.3）→ libtex_poc.so → PDF。
 * 非 arm64-v8a 设备自动跳过。
 */
@RunWith(AndroidJUnit4::class)
class TectonicNativeTest {

    /** positioning 语法片段：TikzRenderer 子集必然失败，覆盖 wrapDocument 包装路径 */
    private val positioningFragment = """
        \begin{tikzpicture}
        \node[draw, circle] (a) at (0,0) {开始};
        \node[draw, rectangle, right=2cm of a] (b) {处理数据};
        \draw[->, thick] (a) -- (b) node[midway, above] {提交};
        \end{tikzpicture}
    """.trimIndent()

    private val pgfplotsDoc = """
        \documentclass{article}
        \usepackage{pgfplots}
        \pgfplotsset{compat=1.18}
        \pagestyle{empty}
        \begin{document}
        \begin{tikzpicture}
        \begin{axis}[width=8cm, height=5cm]
        \addplot[blue, mark=*] coordinates {(0,1) (1,3) (2,2) (3,5)};
        \end{axis}
        \end{tikzpicture}
        \end{document}
    """.trimIndent()

    @Test
    fun compileFragmentWithPositioningOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("libtex_poc.so not available on this ABI", TectonicNative.available)
        val pdf = TectonicNative.compile(context, positioningFragment)
        assertNotNull("compile returned null, lastError=${TectonicNative.lastErrorText()}", pdf)
        assertTrue("pdf too small: ${pdf!!.size}", pdf.size > 1000)
    }

    @Test
    fun compilePgfplotsAxisOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)
        val pdf = TectonicNative.compile(context, pgfplotsDoc)
        assertNotNull("compile returned null, lastError=${TectonicNative.lastErrorText()}", pdf)
        assertTrue("pdf too small: ${pdf!!.size}", pdf.size > 1000)
    }

    @Test
    fun compileIsDeterministicAcrossRuns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)
        val first = TectonicNative.compile(context, positioningFragment)!!
        val second = TectonicNative.compile(context, positioningFragment)!!
        assertEquals(first.size, second.size)
        assertTrue(first.sliceArray(0..31).contentEquals(second.sliceArray(0..31)))
    }

    /**
     * dvisvgm JNI 矢量转换验证。真机已验证可产出 SVG（argv 悬垂修复后），
     * 但 dvisvgm 与 tectonic 同进程共存存在已知限制：dvisvgm 运行后 tectonic
     * 的 xdvipdfmx 状态被污染，后续 PDF 编译失败（符号隔离不解决，C++ 静态
     * 单例/流状态共享所致；产品化需 isolatedProcess 隔离，见 docs 8.4.4）。
     * 全量套件中本用例与 PDF 用例互斥，故 Ignore；单独跑时验证矢量链路。
     */
    @org.junit.Ignore("dvisvgm/tectonic same-process limitation, run in isolation")
    @Test
    fun xdvToSvgProducesVectorSvg() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)
        val xdv = TectonicNative.compile(context, positioningFragment, "xdv")
        assertNotNull("xdv compile returned null, lastError=${TectonicNative.lastErrorText()}", xdv)
        val svg = TectonicNative.xdvToSvg(context, xdv!!)
        assertNotNull("xdvToSvg returned null", svg)
        assertTrue("not an svg: ${svg!!.take(80)}", svg.contains("<svg"))
        // --no-fonts 下文字轮廓为 <defs><path id=…> + <use> 引用；图形元素可能是 <path>/<use>/<rect> 等任意矢量节点
        val hasVector = svg.contains("<path") || svg.contains("<use") || svg.contains("<rect")
        assertTrue("no vector elements in svg", hasVector)
        assertTrue("svg too small: ${svg.length}", svg.length > 300)
    }

    @Test
    fun invalidTexReturnsNullWithLastError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)
        val bad = "\\documentclass{article}\\begin{document}\\undefinedmacro"
        val result = TectonicNative.compile(context, bad)
        android.util.Log.i(
            "TectonicNativeTest",
            "bad input result=${result?.size ?: "null"} lastError=${TectonicNative.lastErrorText()}",
        )
        org.junit.Assert.assertNull(result)
        assertTrue(!TectonicNative.lastErrorText().isNullOrBlank())
    }
}
