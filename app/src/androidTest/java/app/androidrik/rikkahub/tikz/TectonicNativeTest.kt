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
 * Tectonic native 编译链真机验证（方案 B 第 4 步：隔离进程）：
 * TexCompileService（tectonic）isolatedProcess，Messenger 传输。
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
        val pdf = TectonicNative.compileIsolated(context, positioningFragment)
        assertNotNull("compile returned null, lastError=${TectonicNative.lastErrorText()}", pdf)
        assertTrue("pdf too small: ${pdf!!.size}", pdf.size > 1000)
    }

    @Test
    fun compilePgfplotsAxisOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)
        val pdf = TectonicNative.compileIsolated(context, pgfplotsDoc)
        assertNotNull("compile returned null, lastError=${TectonicNative.lastErrorText()}", pdf)
        assertTrue("pdf too small: ${pdf!!.size}", pdf.size > 1000)
    }

    @Test
    fun compileIsDeterministicAcrossRuns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)
        val first = TectonicNative.compileIsolated(context, positioningFragment)!!
        val second = TectonicNative.compileIsolated(context, positioningFragment)!!
        assertEquals(first.size, second.size)
        assertTrue(first.sliceArray(0..31).contentEquals(second.sliceArray(0..31)))
    }

    @Test
    fun invalidTexReturnsNullWithLastError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)
        val bad = "\\documentclass{article}\\begin{document}\\undefinedmacro"
        val result = TectonicNative.compileIsolated(context, bad)
        android.util.Log.i(
            "TectonicNativeTest",
            "bad input result=${result?.size ?: "null"} lastError=${TectonicNative.lastErrorText()}",
        )
        org.junit.Assert.assertNull(result)
        assertTrue(!TectonicNative.lastErrorText().isNullOrBlank())
    }
}
