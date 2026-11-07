package app.androidrik.rikkahub.ui.pages.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.tikz.MupdfSvgBridge
import app.androidrik.rikkahub.tikz.TectonicNative
import app.androidrik.rikkahub.tikz.TikzSvgCache

/** 匹配 ```tikz / ```tikzcd 等围栏代码块 */
internal val TIKZ_FENCE_REGEX = Regex("""```(?:tikz|tikzpicture|tikzcd|tikztiming)[^\n]*\n([\s\S]*?)```""")

/** $$...$$ 块内嵌的 TikZ 图表特征（与 MathBlock.isDiagramLatex 一致） */
internal val DIAGRAM_LATEX_PATTERNS = listOf(
    Regex("""\\begin\{tikz\w*\}"""),
    Regex("""\\begin\{axis\}"""),
    Regex("""\\xymatrix\{"""),
    Regex("""\\begin\{CD\}"""),
)

internal val DOLLAR_TIKZ_REGEX = Regex("""\$\$([\s\S]*?)\$\$""")

/**
 * native 编译 TikZ → 内嵌 <svg>（缓存优先；与聊天渲染同链路 mupdf）。
 * 导出与 HTML/PNG 共用：PNG 导出前依赖它把图预编译落入缓存（一次性捕获拿不到异步结果）。
 */
internal suspend fun compileTikzToSvg(context: Context, code: String): String? =
    withContext(Dispatchers.IO) {
        val trimmed = code.trim()
        val cached = TikzSvgCache.get(context, trimmed)
        if (cached != null) {
            cached
        } else if (MupdfSvgBridge.available) {
            // mupdf 链优先：tectonic PDF（内嵌字体）→ <text> 保留型 SVG（与聊天同链路）
            TectonicNative.compileIsolated(context, trimmed)
                ?.let { MupdfSvgBridge.pdfToSvg(it) }
                ?.also { TikzSvgCache.put(context, trimmed, it) }
        } else {
            null
        }
    }

/** 收集消息文本中的 TikZ 代码块（$$ 内嵌图表 + ```tikz 围栏，与 renderMarkdownWithTikz 同口径） */
internal fun collectTikzCodeBlocks(messages: List<UIMessage>): List<String> =
    messages.flatMap { message ->
        message.parts.filterIsInstance<UIMessagePart.Text>().flatMap { part ->
            val result = mutableListOf<String>()
            DOLLAR_TIKZ_REGEX.findAll(part.text).forEach { m ->
                val latex = m.groupValues[1]
                if (DIAGRAM_LATEX_PATTERNS.any { it.containsMatchIn(latex) }) {
                    result.add(latex.trim())
                }
            }
            TIKZ_FENCE_REGEX.findAll(part.text).forEach { m ->
                result.add(m.groupValues[1].trim())
            }
            result
        }
    }
