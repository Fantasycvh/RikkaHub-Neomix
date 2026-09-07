package app.androidrik.rikkahub.tikz

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Native Tectonic 编译引擎（方案 B，docs/tikz-rendering-tectonic-postmortem.md 8.4）。
 *
 * 纯 Kotlin TikzRenderer 的完整性回退：子集渲染失败时用真 TeX 引擎编译，
 * 支持 positioning/pgfplots 等全部 TikZ 语法与中文。引擎为交叉编译的
 * `libtex_poc.so`（仅 arm64-v8a）；扩展 bundle（1598 文件，pgf 3.1.12 +
 * pgfplots 1.18.3）与中文字体（Droid Sans Fallback）内置 assets。
 *
 * 矢量路径：编译 PDF（tectonic 必内嵌字体）→ MupdfSvgBridge（libmupdf_java.so）
 * 转 SVG（FZ_SVG_TEXT_AS_TEXT，文字保留为 <text>，不依赖随包字体资产）。
 * SVG 失败时降级 PDF → PdfRenderer 位图（见 TikzJax 回退链）。
 */
object TectonicNative {
    private const val TAG = "TectonicNative"

    @Volatile
    private var loaded = false

    /** 最近一次隔离进程（:texcompile）返回的错误文本。
     * 隔离进程内 native 的 lastError 只存在于该进程，跨进程回传后在此缓存，
     * 供主进程 [lastErrorText] 统一读取。 */
    @Volatile
    private var lastIsolatedError: String? = null

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
                bundleCache = context.assets.open("tex/tectonic-bundle-v3.tikzbundle").use { it.readBytes() }
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
     * 编译 TikZ/LaTeX 源码；[format] = "pdf" 或 "xdv"。
     * 失败返回 null（错误详情 [lastErrorText]）。阻塞调用，须在 IO 线程执行。
     *
     * TikZ 片段（无 \documentclass）自动包上完整文档外壳：article + tikz +
     * 常用库 + 中文字体（\normalfont 重定义使全文默认可渲染中文；POC 限制：
     * 无粗体/斜体变体，xeCJK 待后续）。
     *
     * 主进程走隔离进程（TexCompileService）编译——根治 dvisvgm/tectonic 同进程
     * 互污染；隔离进程自身（service 侧）直接调本方法内的 native 路径。
     */
    fun compile(context: Context, texSource: String, format: String = "pdf"): ByteArray? {
        return try {
            load()
            val cacheDir = File(context.cacheDir, "texfmt").apply { mkdirs() }.absolutePath
            val tex = wrapDocument(texSource, format)
            val data = compile(bundle(context), font(context), tex.toByteArray(Charsets.UTF_8), cacheDir, format)
            if (data != null) Log.i(TAG, "native compile ok ($format): ${data.size} bytes") else Log.w(TAG, "native compile failed ($format): ${lastErrorText()}")
            data
        } catch (t: Throwable) {
            Log.w(TAG, "native compile error", t)
            null
        }
    }

    /** 主进程入口：跨隔离进程（tectonic 专属）编译。须在 IO 线程（内部阻塞等待）。 */
    fun compileIsolated(context: Context, texSource: String, format: String = "pdf"): ByteArray? {
        val tex = wrapDocument(texSource, format)
        val result = IsoServiceClient.call(context, TexCompileService::class.java) { replyTo ->
            android.os.Message.obtain(null, IsoProto.MSG_RUN).apply {
                arg1 = if (format == "xdv") 1 else 0
                this.replyTo = replyTo
                data = android.os.Bundle().apply { putString(TexProto.KEY_TEX, tex) }
            }
        }
        if (result.data != null) {
            Log.i(TAG, "isolated compile ok ($format): ${result.data.size} bytes")
        } else {
            lastIsolatedError = result.error
            Log.w(TAG, "isolated compile failed ($format): ${result.error}")
        }
        return result.data
    }

    /**
     * 包装裸 TikZ 片段为完整文档。format 决定 pgf 驱动注入：
     * - "xdv"（dvisvgm 链路）：必须注入 pgfsys-dvisvgm.def——pgf 默认 dvipdfm
     *   驱动的 PDF specials dvisvgm 不识别（图形全丢只剩文字）
     * - "pdf"（mupdf/位图链路）：保持默认 dvipdfm 驱动——注入 dvisvgm 驱动会让
     *   xdvipdfmx 不识别 specials、图形全丢（实测"大部分空"）。必须在 \usepackage{tikz} 前。
     */
    internal fun wrapDocument(code: String, format: String = "pdf"): String {
        val trimmed = code.trim()
        if (trimmed.contains("\\documentclass", ignoreCase = true)) return trimmed
        return buildString {
            append("\\documentclass{article}\n")
            if (format == "xdv") {
                append("\\def\\pgfsysdriver{pgfsys-dvisvgm.def}\n")
            }
            append("\\usepackage{tikz}\n")
            append("\\usetikzlibrary{positioning,arrows.meta,calc,shapes.geometric}\n")
            // 按内容检测加载额外宏包（bundle v3+ 均内置）：
            // - tikzcd 交换图：tikz-cd
            // - pgfplots：\begin{axis}（曲线/散点/饼图），缺则 axis 未注册编译失败
            if (trimmed.contains("\\begin{tikzcd}")) {
                append("\\usepackage{tikz-cd}\n")
            }
            if (trimmed.contains("\\begin{axis}") || trimmed.contains("\\begin{semilogyaxis}") ||
                trimmed.contains("\\begin{loglogaxis}") || trimmed.contains("\\begin{polaraxis}")
            ) {
                append("\\usepackage{pgfplots}\n")
                append("\\pgfplotsset{compat=1.18}\n")
            }
            append("\\pagestyle{empty}\n")
            append("\\font\\zhfont=\"[droidsans.ttf]\"\n")
            append("\\let\\tikzoldnormalfont\\normalfont\n")
            append("\\renewcommand{\\normalfont}{\\tikzoldnormalfont\\zhfont}\n")
            append("\\renewcommand{\\rmfamily}{\\zhfont}\n")
            append("\\renewcommand{\\sffamily}{\\zhfont}\n")
            // node 文本的字体走 TeX 字体机制内部路径（\rmfamily 重定义不覆盖），
            // 必须强制 every node 用 zhfont，否则中文进不了 PDF（实测缺失字符）。
            // 用户 node 自身 font 选项优先级高于此默认样式，可正常覆盖。
            append("\\tikzset{every node/.style={font=\\zhfont}}\n")
            append("\\begin{document}\n")
            append(trimmed)
            append("\n\\end{document}")
        }
    }

    fun lastErrorText(): String? = try {
        // 隔离进程回传缓存（compileIsolated/xdvToSvgIsolated 主路径）优先，
        // 无则回退主进程 native 槽（直接 compile 用）。
        // 注意 native 槽失败时返回 "unknown error" 而非 null，故不能先取它。
        lastIsolatedError ?: lastError()
    } catch (t: Throwable) {
        t.message
    }

    private external fun compile(bundle: ByteArray, font: ByteArray, tex: ByteArray, cacheDir: String, format: String): ByteArray?
    private external fun lastError(): String?
}
