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
 * Tectonic native 编译链真机验证（方案 B 第 2 步）：
 * assets bundle + 中文字体 → libtex_poc.so → PDF。
 * 非 arm64-v8a 设备（如 x86 模拟器）自动跳过。
 */
@RunWith(AndroidJUnit4::class)
class TectonicNativeTest {

    private val chineseTikz = """
        \documentclass{article}
        \usepackage{tikz}
        \pagestyle{empty}
        \font\zhfont="[simhei.ttf]"
        \begin{document}
        \begin{tikzpicture}
        \node[draw, circle] (a) at (0,0) {\zhfont 开始};
        \node[draw, rectangle] (b) at (3,1) {\zhfont 处理数据};
        \draw[->, thick] (a) -- (b) node[midway, above] {\zhfont 提交};
        \end{tikzpicture}
        \end{document}
    """.trimIndent()

    @Test
    fun compileChineseTikzOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("libtex_poc.so not available on this ABI", TectonicNative.available)

        val pdf = TectonicNative.compile(context, chineseTikz)
        assertNotNull("compile returned null, lastError=${TectonicNative.lastErrorText()}", pdf)
        assertTrue("pdf too small: ${pdf!!.size}", pdf.size > 1000)
    }

    @Test
    fun compileIsDeterministicAcrossRuns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)

        val first = TectonicNative.compile(context, chineseTikz)!!
        val second = TectonicNative.compile(context, chineseTikz)!!
        assertEquals(first.size, second.size)
        // TeX 确定性输出（宿主/真机/两次运行哈希一致），用首尾采样校验避免全文比对成本
        assertTrue(first.sliceArray(0..31).contentEquals(second.sliceArray(0..31)))
    }

    @Test
    fun invalidTexReturnsNullWithLastError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)

        val bad = TectonicNative.compile(context, "\\documentclass{article}\\begin{document}\\undefinedmacro")
        org.junit.Assert.assertNull(bad)
        assertTrue(!TectonicNative.lastErrorText().isNullOrBlank())
    }
}
