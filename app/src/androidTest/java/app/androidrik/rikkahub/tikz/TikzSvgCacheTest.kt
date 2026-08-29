package app.androidrik.rikkahub.tikz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TikzSvgCache 真机验证：native 渲染 → 落盘 → 二次命中（免编译）。
 * 缓存键为内容寻址：同源码必命中，不同源码必未命中。
 */
@RunWith(AndroidJUnit4::class)
class TikzSvgCacheTest {

    private val code = "\\begin{tikzpicture}\n\\node (a) {A};\n\\node[right=of a] (b) {B};\n\\draw[->] (a) -- (b);\n\\end{tikzpicture}"

    @Test
    fun missThenHit() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(TectonicNative.available)

        // 清场
        java.io.File(ctx.filesDir, "tikz_cache").deleteRecursively()
        assertNull("cache should be empty initially", TikzSvgCache.get(ctx, code))

        // native 渲染并落盘（与 TikzJax 相同的链路）
        val xdv = TectonicNative.compileIsolated(ctx, code, "xdv")
        assertNotNull("xdv compile failed: ${TectonicNative.lastErrorText()}", xdv)
        val svg = TectonicNative.xdvToSvgIsolated(ctx, xdv!!)
        assertNotNull("svg convert failed", svg)
        TikzSvgCache.put(ctx, code, svg!!)

        // 二次读取：命中且内容一致
        val cached = TikzSvgCache.get(ctx, code)
        assertNotNull("cache should hit after put", cached)
        assertEquals("cached svg should equal original", svg, cached)

        // 内容寻址：不同源码不命中
        assertNull("different code should miss", TikzSvgCache.get(ctx, code + "\n% other"))

        // 缓存文件可直接定位（导出复用路径）
        val f = TikzSvgCache.file(ctx, code)
        assertNotNull("cache file should exist", f)
        assertTrue(f!!.isFile && f.length() > 0)
    }
}