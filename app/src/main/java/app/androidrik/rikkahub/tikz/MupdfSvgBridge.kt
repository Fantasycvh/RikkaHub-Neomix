package app.androidrik.rikkahub.tikz

import android.util.Log

/**
 * PDF→SVG JNI 桥（链接 document 模块内置的 libmupdf_java.so）。
 *
 * 背景：dvisvgm 链路的文字轮廓依赖随包 TeX 字体资产（pfb/OTF 集合长期不全），
 * 文字在 SVG 里悬空丢失；mupdf 的 SVG writer 从 tectonic PDF 的**内嵌字体** +
 * ToUnicode CMap 取字形，用 FZ_SVG_TEXT_AS_TEXT 模式直接产出 <text> 元素，
 * 文字交给前端字体栈渲染——彻底绕开字体资产问题。tectonic PDF 必内嵌字体
 * （已验证：LMRoman OTF + ToUnicode CMap 均在 PDF 内）。
 *
 * 仅 arm64-v8a（与 libtex_poc.so 一致；x86_64 模拟器上 fallback 到 PDF 位图链）。
 */
object MupdfSvgBridge {
    private const val TAG = "MupdfSvgBridge"

    @Volatile
    private var loaded = false

    /** so 是否可用（仅打包 arm64-v8a；加载失败时为 false，调用方回退 PDF 位图链） */
    val available: Boolean
        get() = runCatching { load() }.isSuccess

    private fun load() {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                // 桥的 fz_* 符号链接自 document 模块的 libmupdf_java.so，
                // 必须先加载 mupdf（动态链接器按依赖解析），再加载桥本身。
                System.loadLibrary("mupdf_java")
                System.loadLibrary("pdf2svg_bridge")
                loaded = true
            }
        }
    }

    /**
     * PDF → SVG（文字保留为 <text>，FZ_SVG_TEXT_AS_TEXT）。
     * 失败返回 null。阻塞调用，须在 IO 线程执行。
     * 只转第一页（tectonic 输出单页图表）。
     */
    fun pdfToSvg(pdf: ByteArray): String? {
        return try {
            load()
            val svg = nativePdfToSvg(pdf)
            if (svg != null) Log.i(TAG, "pdf->svg ok: ${svg.length} chars") else Log.w(TAG, "pdf->svg failed")
            svg
        } catch (t: Throwable) {
            Log.w(TAG, "pdfToSvg error", t)
            null
        }
    }

    private external fun nativePdfToSvg(pdf: ByteArray): String?
}
