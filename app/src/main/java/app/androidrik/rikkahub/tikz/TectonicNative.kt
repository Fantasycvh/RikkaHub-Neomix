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
        val original = code.trim()
        // 完整文档（用户自管 preamble）：只做无害的弧度归一化（原地自洽），
        // 不碰 #hex——替换了但无注入定义（hexColorDefs 只给裸片段 preamble 注入）
        // 会变成 Undefined color 半途而废；保持原样，让用户自己的 preamble 处理。
        if (original.contains("\\documentclass", ignoreCase = true)) {
            return normalizePgfMath(original)
        }
        // 裸片段：我们写 preamble，可安全注入 #hex 定义 + 弧度归一化
        val (trimmed, hexColorDefs) = normalizeHexColors(normalizePgfMath(original))
        return buildString {
            append("\\documentclass{article}\n")
            if (format == "xdv") {
                append("\\def\\pgfsysdriver{pgfsys-dvisvgm.def}\n")
            }
            append("\\usepackage{tikz}\n")
            // 数学宏包：TikZ node 文本里的 $...$ 走 LaTeX 数学模式，而 \dfrac / \tfrac /
            // \binom / \text / \operatorname / split 等是 **amsmath 宏**（\frac 才是内核宏）。
            // 缺 amsmath 时它们报 "Undefined control sequence" → 整图编译中止 → 回退纯源码
            // （实测：节点里一个 \dfrac 就让整幅斜抛轨迹图变成源码，模型只能改写成 \frac 绕过）。
            // **无条件加载**（不做宏名白名单检测）：amsmath 宏数量大、新宏层出不穷，白名单必然漏，
            // 漏一个就重现本 bug；而漏检代价（整图不渲染）远大于恒定加载的代价
            //（bundle 已内置 amsmath.sty，一次解析开销在图级缓存下可忽略）。
            // amssymb 顺带覆盖 \mathbb / \mathfrak / \checkmark 等常用符号宏（依赖 amsfonts，亦已内置）。
            append("\\usepackage{amsmath}\n")
            append("\\usepackage{amssymb}\n")
            // 裁剪 PDF 到图内容 bbox（preview 包 tightpage）：article 整页(A4 612x792)
            // 不裁会让 SVG 四周大空白、图居中占小块（实测）。preview 纯 TeX 层改页面尺寸，
            // 不依赖外部 pdfcrop（standalone 裁剪需 pdfcrop，设备无此工具故不可行）。
            // preview.sty/prtightpage.def/prauctex.cfg 已放 bundle 根路径（kpse 直查）。
            append("\\usepackage[active,tightpage]{preview}\n")
            append("\\PreviewEnvironment{tikzpicture}\n")
            append("\\PreviewEnvironment{tikzcd}\n")
            hexColorDefs.forEach { append(it + "\n") }
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
            append("\\renewcommand{\\bfdefault}{m}\n")
            append("\\renewcommand{\\itdefault}{m}\n")
            append("\\renewcommand{\\sldefault}{m}\n")
            // \\small/\\textbf/\\footnotesize 等一切字号/字重切换都走 \\selectfont，
            // 而 \\selectfont 按 \\f@family（默认 cmr/lmroman）重设字体族，中文缺字形
            // 整行丢弃（实测：box style font=\\small + \\textbf 的标题行空）。
            // 在 \\selectfont 末尾强制回 \\zhfont：任何切换后中英文仍走 droidsans
            //（Noto Sans SC 含 Latin+CJK），一次覆盖全字号/字重。
            // 注：不能重定义 \\small 等单独命令——会破坏 tikz font= 选项展开；\\selectfont
            // 是它们共同的下游，在这里追加最稳妥。
            append("\\let\\zholdselectfont\\selectfont\n")
            append("\\renewcommand{\\selectfont}{\\zholdselectfont\\zhfont}\n")
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

    /**
     * 运行时归一化 PGF Math 弧度写法：引擎内置 PGF Math 不支持 [rad]/[r] 后缀，
     * `sin(x rad)` / `sin(x r)` 会报 Unknown function `xrad' 编译失败、降级源码。
     * 把常见三角函数弧度参数重写为等价的 deg() 形式（POC 实测兼容）。
     *
     * 处理：sin/cos/tan/sinh/cosh/tanh/log 前紧贴 `(` 的参数若以 ` rad)` 或 ` r)` 结尾
     * → 去掉空格后缀，包裹成 deg(...)。例如 `sin(x rad)` → `sin(deg(x))`。
     * 避免误伤：仅当参数是**单个变量**（如 x/t/theta）时才可安全替换为 deg()；
     * 复杂表达式（如 (x*pi/180)）本就兼容、且替换会改变语义，故限定单 token + 弧度后缀。
     */
    internal fun normalizePgfMath(tex: String): String {
        // 匹配: sin( 空格? 单变量token 空格? rad|r 空格? ) —— 只处理三角函数+单变量+弧度
        val FUNCS = "(?:sin|cos|tan|sinh|cosh|tanh)"
        val re = Regex("""(?i)\b($FUNCS)\(\s*([a-zA-Z]\w*)\s+(rad|r)\s*\)""")
        return re.replace(tex) { m ->
            val fn = m.groupValues[1].lowercase()
            val varName = m.groupValues[2]
            "$fn(deg($varName))"
        }
    }

    /**
     * 把 #RRGGBB / #RGB 十六进制颜色替换为 pgf 可用颜色名（HTML 命名注册）。
     * # 是 TeX 参数 token，pgf options 里 #hex 直接 Illegal parameter number
     * （实测：draw[thick, #4A90D9] 报 Illegal parameter number in \\pgfkeyscurrentkey）。
     * 方案：每个唯一颜色 \\definecolor{hexN}{HTML}{RRGGBB}（xcolor HTML 支持，bundle 可用），
     * 引用处替换为 hexN，保留 !NN 透明度后缀（hex0!30 ⊃ 原 #4A90D9!30 语义）。
     * 支持 3 位简写 #RGB（#f80 → #ff8800，展开为 6 位）与 8 位 #RRGGBBAA（alpha 忽略，取前 6 位）。
     * 限定替换范围：仅在 `[...]` 选项上下文中替换（颜色几乎只出现在 pgfkey options），
     * 避免误伤 node 文本内容里字面的 #hex（盲区 4）。
     * 返回替换后的代码 + 颜色定义行（注入 wrapDocument preamble）。
     */
    internal fun normalizeHexColors(tex: String): Pair<String, List<String>> {
        val hexRe = Regex("""#(?i)([0-9a-f]{3,8})(!\d+)?""")
        val seen = mutableMapOf<String, String>()  // RRGGBB -> hexN
        val defs = mutableListOf<String>()
        var counter = 0
        // 逐字符扫描以跟踪 `[`..`]` 选项上下文；#hex 仅在 options 内替换
        val sb = StringBuilder(tex.length)
        var inOptions = false
        var i = 0
        while (i < tex.length) {
            val ch = tex[i]
            when (ch) {
                '[' -> { inOptions = true; sb.append(ch); i++ }
                ']' -> { inOptions = false; sb.append(ch); i++ }
                '#' -> {
                    val m = hexRe.find(tex, i)
                    if (m != null && m.range.first == i && inOptions) {
                        val raw = m.groupValues[1].lowercase()
                        // 归一：3 位按位展开（#f80→ff8800），6/8 位取前 6（8 位忽略 alpha）
                        val rgb = when (raw.length) {
                            3 -> raw.map { it.toString() + it }.joinToString("")
                            else -> raw.take(6)
                        }
                        val suffix = m.groupValues[2]  // !NN 或不带
                        val name = seen.getOrPut(rgb) {
                            val n = counter++
                            defs.add("\\definecolor{hex$n}{HTML}{$rgb}")
                            "hex$n"
                        }
                        sb.append(name).append(suffix)
                        i = m.range.last + 1
                    } else {
                        sb.append(ch)
                        i++
                    }
                }
                else -> { sb.append(ch); i++ }
            }
        }
        return sb.toString() to defs
    }
}
