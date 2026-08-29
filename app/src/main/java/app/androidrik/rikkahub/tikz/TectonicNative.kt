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
 * 矢量路径：编译出 XDV → `libdvisvgm.so`（PIE 可执行，随包分发于
 * nativeLibraryDir）转 SVG（字形转轮廓，CM 字体由随包 .pfb + fontmap 提供）。
 * dvisvgm 失败时可降级 PDF → PdfRenderer 位图（见 TikzJax 回退链）。
 */
object TectonicNative {
    private const val TAG = "TectonicNative"

    /** 随包分发的 CM Type1 字体（dvisvgm 字形轮廓来源） */
    private val CM_PFB_FONTS = listOf(
        "cmr10.pfb", "cmbx10.pfb", "cmmi10.pfb", "cmsy10.pfb",
        "cmex10.pfb", "cmss10.pfb", "cmssbx10.pfb",
    )

    @Volatile
    private var loaded = false

    /** so 是否可用（仅打包了 arm64-v8a；其他 ABI 或加载失败时为 false，回退链自动降级） */
    val available: Boolean
        get() = runCatching { load() }.isSuccess

    private fun load() {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                System.loadLibrary("tex_poc")
                System.loadLibrary("dvisvgmexec")
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
            val tex = wrapDocument(texSource)
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
        val tex = wrapDocument(texSource)
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
            Log.w(TAG, "isolated compile failed ($format): ${result.error}")
        }
        return result.data
    }

    /** 主进程入口：跨隔离进程（dvisvgm 专属）XDV→SVG。须在 IO 线程（内部阻塞等待）。 */
    fun xdvToSvgIsolated(context: Context, xdv: ByteArray): String? {
        val result = IsoServiceClient.call(context, SvgConvertService::class.java) { replyTo ->
            android.os.Message.obtain(null, IsoProto.MSG_RUN).apply {
                this.replyTo = replyTo
                data = android.os.Bundle().apply { putByteArray(SvgProto.KEY_XDV, xdv) }
            }
        }
        if (result.data != null) {
            Log.i(TAG, "isolated svg convert ok: ${result.data.size} bytes")
        } else {
            Log.w(TAG, "isolated svg convert failed: ${result.error}")
        }
        return result.data?.toString(Charsets.UTF_8)
    }

    /**
     * XDV → SVG（dvisvgm 以共享库形式 JNI 调用，字形转轮廓 + CM 字体走 fontmap）。
     * 失败返回 null；成功返回 SVG 文本。
     *
     * 不做 exec：untrusted_app（targetSdk≥29）对 apk 库目录只有读权限，exec 会被
     * SELinux execute_no_trans 拒绝。改为 System.loadLibrary("dvisvgmexec") 后
     * JNI 直调，规避整个 exec 路径。
     *
     * 注：曾有的 dvisvgmUsed 进程内单次限制已移除——那是双进程隔离前的互污染
     * 补丁；现在 dvisvgm 独占 :svgconvert 进程，tectonic 互不影响，可重复调用。
     */
    fun xdvToSvg(context: Context, xdv: ByteArray): String? {
        return try {
            load()
            val work = File(context.cacheDir, "texwork").apply { deleteRecursively(); mkdirs() }
            File(work, "droidsans.ttf").writeBytes(font(context))
            for (pfb in CM_PFB_FONTS) {
                val bytes = context.assets.open("tex/fonts/$pfb").use { it.readBytes() }
                File(work, pfb).writeBytes(bytes)
                // kpse stub 的 fopen 按原始名字查找；无扩展名副本让 dvisvgm 的 FileFinder
                // 按字体名（cmr10）直接命中（CLI 验证成功路径即此配置，不带 -m）
                File(work, pfb.removeSuffix(".pfb")).writeBytes(bytes)
            }
            File(work, "main.xdv").writeBytes(xdv)

            val rc = dvisvgmMain(
                work.absolutePath,
                arrayOf("--no-fonts", "main.xdv", "-o", "main.svg"),
            )
            if (rc != 0) {
                Log.w(TAG, "dvisvgmMain exit=$rc")
                return null
            }
            val svg = File(work, "main.svg")
            if (svg.isFile && svg.length() > 0) svg.readText() else null
        } catch (t: Throwable) {
            Log.w(TAG, "xdvToSvg error", t)
            null
        }
    }

    internal fun wrapDocument(code: String): String {
        val trimmed = code.trim()
        if (trimmed.contains("\\documentclass", ignoreCase = true)) return trimmed
        return buildString {
            append("\\documentclass{article}\n")
            // XDV 矢量路径必须：pgf 默认 dvipdfm 驱动的 PDF specials dvisvgm 不识别
            //（图形全丢只剩文字），需显式切换 dvisvgm 驱动。必须在 \usepackage{tikz} 前
            append("\\def\\pgfsysdriver{pgfsys-dvisvgm.def}\n")
            append("\\usepackage{tikz}\n")
            append("\\usetikzlibrary{positioning,arrows.meta,calc,shapes.geometric}\n")
            // tikzcd 交换图：需 tikz-cd 宏包（bundle v3+），按内容检测加载
            if (trimmed.contains("\\begin{tikzcd}")) {
                append("\\usepackage{tikz-cd}\n")
            }
            append("\\pagestyle{empty}\n")
            append("\\font\\zhfont=\"[droidsans.ttf]\"\n")
            append("\\let\\tikzoldnormalfont\\normalfont\n")
            append("\\renewcommand{\\normalfont}{\\tikzoldnormalfont\\zhfont}\n")
            append("\\renewcommand{\\rmfamily}{\\zhfont}\n")
            append("\\renewcommand{\\sffamily}{\\zhfont}\n")
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

    private external fun compile(bundle: ByteArray, font: ByteArray, tex: ByteArray, cacheDir: String, format: String): ByteArray?
    private external fun dvisvgmMain(workDir: String, args: Array<String>): Int
    private external fun lastError(): String?
}
