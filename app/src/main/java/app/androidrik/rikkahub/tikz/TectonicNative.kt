package app.androidrik.rikkahub.tikz

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Native Tectonic 编译引擎（方案 B，docs/tikz-rendering-tectonic-postmortem.md 8.4）。
 *
 * 纯 Kotlin TikzRenderer 的完整性回退：子集渲染失败时用真 TeX 引擎编译，
 * 支持 positioning/pgfplots 等全部 TikZ 语法与中文。引擎为交叉编译的
 * `libtex_poc.so`（仅 arm64-v8a）；扩展 bundle（1598 文件，含 pgf 3.1.12 全部
 * 库 + pgfplots 1.18.3）与中文字体（Droid Sans Fallback，Apache 2.0）内置 assets。
 *
 * 首次编译触发 format 重建（设备约 3-4s），结果持久化到 cacheDir/texfmt。
 */
object TectonicNative {
    private const val TAG = "TectonicNative"

    @Volatile
    private var loaded = false

    /** so 是否可用（仅打包了 arm64-v8a；其他 ABI 或加载失败时为 false，回退链自动降级） */
    val available: Boolean
        get() = runCatching { load() }.isSuccess

    private fun load() {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                System.loadLibrary("tex_poc")
                loaded = true
            }
        }
    }

    // bundle + 字体 ~18MB 常驻内存；后续版本可改磁盘流式读取
    @Volatile private var bundleCache: ByteArray? = null
    @Volatile private var fontCache: ByteArray? = null

    private fun bundle(context: Context): ByteArray {
        bundleCache?.let { return it }
        synchronized(this) {
            if (bundleCache == null) {
                bundleCache = context.assets.open("tex/tectonic-bundle-v2.tikzbundle").use { it.readBytes() }
            }
        }
        return bundleCache!!
    }

    private fun font(context: Context): ByteArray {
        fontCache?.let { return it }
        synchronized(this) {
            if (fontCache == null) {
                fontCache = context.assets.open("tex/droidsans.ttf").use { it.readBytes() }
            }
        }
        return fontCache!!
    }

    /**
     * 编译 TikZ/LaTeX 源码为 PDF；失败返回 null（错误详情 [lastErrorText]）。
     * 阻塞调用，须在 IO 线程执行。
     *
     * 输入若为 TikZ 片段（无 \documentclass）会自动包上完整文档外壳：
     * article + tikz + 常用库（positioning/arrows.meta/calc/shapes）+ 中文字体
     * （经 \normalfont 重定义让全部文本默认可渲染中文；POC 限制：无粗体/斜体
     * 变体，xeCJK 待后续）。
     */
    fun compile(context: Context, texSource: String): ByteArray? {
        return try {
            load()
            val cacheDir = File(context.cacheDir, "texfmt").apply { mkdirs() }.absolutePath
            val tex = wrapDocument(texSource)
            val pdf = compile(bundle(context), font(context), tex.toByteArray(Charsets.UTF_8), cacheDir)
            if (pdf != null) Log.i(TAG, "native compile ok: ${pdf.size} bytes") else Log.w(TAG, "native compile failed: ${lastErrorText()}")
            pdf
        } catch (t: Throwable) {
            Log.w(TAG, "native compile error", t)
            null
        }
    }

    internal fun wrapDocument(code: String): String {
        val trimmed = code.trim()
        if (trimmed.contains("\\documentclass", ignoreCase = true)) return trimmed
        return buildString {
            append("\\documentclass{article}\n")
            append("\\usepackage{tikz}\n")
            append("\\usetikzlibrary{positioning,arrows.meta,calc,shapes.geometric}\n")
            append("\\pagestyle{empty}\n")
            append("\\font\\zhfont=\"[droidsans.ttf]\"\n")
            append("\\let\\tikzoldnormalfont\\normalfont\n")
            append("\\renewcommand{\\normalfont}{\\tikzoldnormalfont\\zhfont}\n")
            append("\\begin{document}\n")
            append(trimmed)
            append("\n\\end{document}")
        }
    }

    fun lastErrorText(): String? = try {
        lastError()
    } catch (t: Throwable) {
        t.message
    }

    private external fun compile(bundle: ByteArray, font: ByteArray, tex: ByteArray, cacheDir: String): ByteArray?
    private external fun lastError(): String?
}
