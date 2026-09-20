package app.androidrik.rikkahub.ui.components.richtext

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

// ==================== 流式降级渲染（移植自 rikkahub-turbo R3.2/R3.3） ====================
// 流式内容按行分类为 代码块/标题/引用/列表/段落。markdown 是追加式的：
// 已完成的块不再变化，新内容只追加在尾部 → 每帧只需一次 O(n) 行扫描，已渲染块参数不变，
// Compose 智能跳过不重组；生成结束 streaming=false 切完整渲染（data 已后台预热，零解析）。
// 行内格式（加粗/行内代码/链接）仍走 stripStreamingMarkdown 擦除兜底（跨行标记不做行内
// 解析，避免"闪现又消失"）；结束切完整渲染后仅行内格式的细微变化。

// 流式降级渲染专用：把 markdown 源码擦成"近似干净文本"，消除生成中裸标记闪烁。
// 顶层正则只编译一次。擦除是确定性的（同 content → 同结果），故不会"闪现又消失"。
// 流式半截未闭合标记由末尾兜底删除处理；生成结束 streaming=false 切完整渲染，显示真正格式。
private val STREAM_INLINE_CODE = Regex("`([^`\\n]+)`")
private val STREAM_LINK = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
private val STREAM_BOLD = Regex("\\*\\*([\\s\\S]+?)\\*\\*|__([\\s\\S]+?)__")
private val STREAM_ITALIC =
    Regex("(?<![\\*\\w])\\*([^\\*\\n]+?)\\*(?![\\*\\w])|(?<![_\\w])_([^_\\n]+?)_(?![_\\w])")

/**
 * 流式行内文本规范化：仅剥离链接标记（保持原行为），
 * 行内代码/加粗/斜体的标记**保留原文**——闭合的由 [styleStreamingLine] 加样式，
 * 未闭合的以字面量显示（intellij 对未闭合星号/反引号同样按字面量渲染，
 * 因此流式→完整渲染零跳变；旧的 strip 方案会删掉孤立标记，反而制造"字符消失"跳变）。
 */
internal fun normalizeStreamingInline(text: String): String =
    if (text.isEmpty()) text else STREAM_LINK.replace(text) { it.groupValues[1] }

/**
 * 流式行内格式渲染：基于与旧 strip 相同的正则集识别**单行内已闭合**
 * 的行内代码/加粗/斜体并附加样式（等宽 / Bold / Italic，与最终渲染对齐）；
 * 反引号区间优先，加粗/斜体不与行内代码重叠（intellij 语义：code 内标记是字面量）。
 * 未闭合标记保持字面量（同 intellij 字面量渲染，结束切完整渲染时无跳变）。
 */
internal fun styleStreamingLine(text: String): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString(text)
    val spans = mutableListOf<Pair<IntRange, SpanStyle>>()
    val codeRanges = STREAM_INLINE_CODE.findAll(text).map { it.range }.toList()
    codeRanges.forEach {
        spans.add(
            it to SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontFeatureSettings = CODE_FONT_FEATURES,
            )
        )
    }

    fun overlapsCode(range: IntRange): Boolean =
        codeRanges.any { it.first <= range.last && range.first <= it.last }

    STREAM_BOLD.findAll(text).forEach { match ->
        if (!overlapsCode(match.range)) {
            spans.add(match.range to SpanStyle(fontWeight = FontWeight.Bold))
        }
    }
    STREAM_ITALIC.findAll(text).forEach { match ->
        if (!overlapsCode(match.range)) {
            spans.add(match.range to SpanStyle(fontStyle = FontStyle.Italic))
        }
    }
    if (spans.isEmpty()) return AnnotatedString(text)
    return AnnotatedString(
        text = text,
        spanStyles = spans
            .sortedBy { it.first.first }
            .map { (range, style) -> AnnotatedString.Range(style, range.first, range.last + 1) }
    )
}

internal enum class StreamBlockKind { CODE, HEADING, QUOTE, LIST, PARAGRAPH }

internal class StreamBlock(
    val kind: StreamBlockKind,
    val text: String,
    val headingLevel: Int = 1,
    val listOrdered: Boolean = false,
    val listIndex: Int = 0,
)

// 分类规则与完整解析器（intellij markdown）对齐，避免结束时块类型跳变：
// - ATX 标题：行首 1-6 个 # 后必须跟空格（"#x" 不是标题，保持段落）
// - 引用：> 后可选一个空格（">text" 也是引用；">> " 嵌套退化为单级，内层 > 由 strip 擦除）
// - 无序列表：- * + 后必须跟空格（"-x" 不是列表，保持段落）
// - 有序列表：1-9 位数字 + . 或 ) + 空格
private val STREAM_HEADING_RE = Regex("^(#{1,6}) ")
private val STREAM_QUOTE_RE = Regex("^> ?")
private val STREAM_UNORDERED_RE = Regex("^[-*+] ")
private val STREAM_ORDERED_RE = Regex("^(\\d{1,9})[.)] ")

internal fun splitStreamingBlocks(content: String): List<StreamBlock> {
    if (content.isEmpty()) return emptyList()
    val blocks = mutableListOf<StreamBlock>()
    val lines = content.split('\n')
    var inCode = false
    var codeBuf = StringBuilder()
    var paraBuf = StringBuilder()
    var paraDirty = false

    fun flushParagraph() {
        if (!paraDirty) return
        val text = normalizeStreamingInline(paraBuf.toString()).trim()
        if (text.isNotEmpty()) blocks.add(StreamBlock(StreamBlockKind.PARAGRAPH, text))
        paraBuf = StringBuilder()
        paraDirty = false
    }

    for (line in lines) {
        val lt = line.trimStart()
        if (inCode) {
            if (lt.startsWith("```")) {
                // 闭合围栏：围栏行本身（含语言标记）不渲染进代码体
                inCode = false
                val text = codeBuf.toString().trimEnd('\n')
                if (text.isNotEmpty()) blocks.add(StreamBlock(StreamBlockKind.CODE, text))
                codeBuf = StringBuilder()
            } else {
                codeBuf.append(line).append('\n')
            }
            continue
        }
        if (lt.startsWith("```")) {
            // 开启围栏（``` 后的语言标记随围栏行丢弃）
            flushParagraph()
            inCode = true
            continue
        }
        if (line.isBlank()) {
            flushParagraph()
            continue
        }
        val heading = STREAM_HEADING_RE.find(lt)
        if (heading != null) {
            flushParagraph()
            val text = normalizeStreamingInline(lt.substring(heading.value.length)).trim()
            if (text.isNotEmpty()) {
                blocks.add(
                    StreamBlock(
                        kind = StreamBlockKind.HEADING,
                        text = text,
                        headingLevel = heading.groupValues[1].length,
                    )
                )
            }
            continue
        }
        val quote = STREAM_QUOTE_RE.find(lt)
        if (quote != null) {
            flushParagraph()
            val text = normalizeStreamingInline(lt.substring(quote.value.length)).trim()
            if (text.isNotEmpty()) blocks.add(StreamBlock(StreamBlockKind.QUOTE, text))
            continue
        }
        val unordered = STREAM_UNORDERED_RE.find(lt)
        if (unordered != null) {
            flushParagraph()
            val text = normalizeStreamingInline(lt.substring(unordered.value.length)).trim()
            if (text.isNotEmpty()) blocks.add(StreamBlock(StreamBlockKind.LIST, text))
            continue
        }
        val ordered = STREAM_ORDERED_RE.find(lt)
        if (ordered != null) {
            flushParagraph()
            val text = normalizeStreamingInline(lt.substring(ordered.value.length)).trim()
            if (text.isNotEmpty()) {
                blocks.add(
                    StreamBlock(
                        kind = StreamBlockKind.LIST,
                        text = text,
                        listOrdered = true,
                        listIndex = ordered.groupValues[1].toIntOrNull() ?: 1,
                    )
                )
            }
            continue
        }
        // 普通段落行：累积（markdown soft line break 语义，段落内换行不新开块）
        paraDirty = true
        if (paraBuf.isNotEmpty()) paraBuf.append('\n')
        paraBuf.append(normalizeStreamingInline(line))
    }
    flushParagraph()
    // 未闭合代码块（生成中尾巴）：按代码块渲染，代码生成中可见；结束切完整渲染恢复
    if (inCode) {
        val text = codeBuf.toString().trimEnd('\n')
        if (text.isNotEmpty()) blocks.add(StreamBlock(StreamBlockKind.CODE, text))
    }
    return blocks
}
