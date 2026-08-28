package app.androidrik.rikkahub.tikz

import android.content.Context
import android.util.Log
import java.io.File

/**
 * TikZ 渲染失败原因分类（docs/tikz-rendering-tectonic-postmortem.md 8.3）：
 * 为方案 B（native TeX 编译管线）立项提供失败分布数据。
 *
 * 分类策略：先按代码特征识别"子集不支持但常见"的结构（detected 优先于兜底），
 * 无法归入已知特征时回退到异常消息判定（尺寸防线 / 无可渲染元素 / 其他）。
 */
enum class TikzFailureCategory(val label: String) {
    PGFPLOTS("pgfplots"),
    POSITIONING("positioning"),
    FOREACH("foreach"),
    TIKZSET_STYLE("tikzset_style"),
    SCOPE("scope"),
    CALC_COORD("calc_coord"),
    FIT("fit"),
    MATRIX("matrix"),
    QUOTES_LABEL("quotes_label"),
    MATH_IN_NODE("math_in_node"),
    /** 尺寸防线拦截（非语法问题，几何溢出） */
    SIZE_GUARD("size_guard"),
    /** 解析完无可渲染元素（空输入或整体无法解析） */
    NO_ELEMENTS("no_elements"),
    OTHER("other"),
}

object TikzTelemetry {

    private const val TAG = "TikzTelemetry"
    private const val MAX_LOG_BYTES = 256L * 1024

    /** 顺序即优先级：前面的特征命中后仍继续收集（一次失败可能踩多个坑），但 classify 按列表顺序输出 */
    private val DETECTORS: List<Pair<TikzFailureCategory, Regex>> = listOf(
        TikzFailureCategory.PGFPLOTS to Regex("""\\begin\{axis\}|\\addplot|pgfplots"""),
        TikzFailureCategory.POSITIONING to Regex("""(?:above|below|left|right)[a-z ]*=\s*[^,\]]*?\bof\b"""),
        TikzFailureCategory.FOREACH to Regex("""\\foreach"""),
        TikzFailureCategory.TIKZSET_STYLE to Regex("""\\tikzset|\\tikzstyle"""),
        TikzFailureCategory.SCOPE to Regex("""\\begin\{scope\}"""),
        TikzFailureCategory.CALC_COORD to Regex("""\(\s*\$"""),
        TikzFailureCategory.FIT to Regex("""\bfit\s*="""),
        TikzFailureCategory.MATRIX to Regex("""\\matrix"""),
        TikzFailureCategory.QUOTES_LABEL to Regex("""(?:to|edge)\s*\[\s*""""),
        TikzFailureCategory.MATH_IN_NODE to Regex("""\{[^{}]*\$[^{}]*\$[^{}]*\}"""),
    )

    /** 纯分类逻辑（无 Android 依赖，可单测）。返回命中的全部类别，稳定排序。 */
    fun classifyFailure(code: String, throwable: Throwable? = null): List<TikzFailureCategory> {
        val message = throwable?.message.orEmpty()
        // 尺寸防线优先：代码本身解析成功，是几何溢出而非"不支持的结构"
        if (message.contains("尺寸异常")) return listOf(TikzFailureCategory.SIZE_GUARD)
        val detected = DETECTORS.filter { it.second.containsMatchIn(code) }.map { it.first }
        if (detected.isNotEmpty()) return detected
        if (message.contains("No renderable")) return listOf(TikzFailureCategory.NO_ELEMENTS)
        return listOf(TikzFailureCategory.OTHER)
    }

    /** 失败记录：logcat + filesDir/tikz_telemetry.log（JSONL，超 256KB 重建） */
    fun record(context: Context, code: String, throwable: Throwable) {
        val categories = classifyFailure(code, throwable)
        Log.w(TAG, "fail[${categories.joinToString(",") { it.label }}] len=${code.length} err=${throwable.message}")
        append(
            context,
            buildString {
                append("""{"ts":""").append(System.currentTimeMillis())
                append(""","ok":false,"cats":[""")
                append(categories.joinToString(",") { "\"${it.label}\"" })
                append("],\"len\":").append(code.length)
                append(""","err":""").append(jsonString(throwable.message?.take(200)))
                append(""","code":""").append(jsonString(code.take(400)))
                append("}\n")
            },
        )
    }

    /** 成功记录（不含代码内容）：提供分母，用于计算失败率与类别占比 */
    fun recordSuccess(context: Context, svgLength: Int) {
        append(
            context,
            buildString {
                append("""{"ts":""").append(System.currentTimeMillis())
                append(""","ok":true,"len":0,"svg":""").append(svgLength)
                append("}\n")
            },
        )
    }

    private fun append(context: Context, line: String) {
        runCatching {
            val file = File(context.filesDir, "tikz_telemetry.log")
            if (file.length() > MAX_LOG_BYTES) file.delete()
            file.appendText(line)
        }.onFailure {
            Log.w(TAG, "telemetry write failed: ${it.message}")
        }
    }

    /** 最小 JSON 字符串转义：反斜杠（TikZ 源码大量存在）→ 引号 → 换行 */
    private fun jsonString(raw: String?): String {
        val s = raw.orEmpty()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        return "\"$s\""
    }
}
