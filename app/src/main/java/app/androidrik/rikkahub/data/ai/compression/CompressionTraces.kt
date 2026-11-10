package app.androidrik.rikkahub.data.ai.compression

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.utils.estimateTokens

/**
 * 压缩摘要的「机械痕迹附录」（NMoi D296 判据，本仓落地）。
 *
 * 摘要正文是**模型写的**，而模型看不到被压掉的原文——它漏掉的路径、文件句柄、媒体 URL，
 * 压完就再也回不去（检查点之后模型只能看到摘要）。所以痕迹不交给模型总结，而由代码
 * **从原文确定性抄一份清单**：本段用过哪些工具 + 动过/读过哪些文件 + 带过哪些文档/媒体句柄，
 * 附在摘要尾部随检查点落库。
 *
 * ## 判据（改这里之前必读）
 * - **只搬清单不搬内容**：一条痕迹一行，句柄可回取即可；工具输出正文不抄（那是另一层预算）。
 * - **只认可回取的句柄**：沙箱内绝对路径（[TRACE_PATH_PREFIXES]）与 `file://` / `content://`。
 *   相对路径、`/etc/...` 之类不收——写进摘要只会让下一轮模型去找一个不存在的地方。
 * - **有硬上限**：[TRACE_MAX_ENTRIES] 条 / [TRACE_MAX_CHARS] 字符，超了按出现顺序截断并写
 *   「其余 N 条未列出」；工具名另设 [TRACE_MAX_TOOL_NAMES] 个上限（其余合并成「等 N 种」）。
 * - **预算不够整节不接**：附录 token 超过摘要目标 token 的 [TRACE_BUDGET_RATIO] 就不追加——
 *   摘要正文永远优先，痕迹不能把摘要挤薄。
 * - **重压缩不叠加**：旧检查点里的附录先摘掉，条目去重后接力（本段在前、旧段在后），
 *   任何时刻正文里只有一段痕迹。
 * - **不写「没有」**：本段没有痕迹就返回 null，不往摘要里塞空节。
 *
 * 覆盖率闸的分母（`sourceText`）只含原文正文；附录在闸门**之后**才拼到摘要上，
 * 不参与「硬事实逐字命中」判据（见 [attachTraceSection] 调用点注释）。
 */

/** 附录固定头行。同时是「旧附录」的识别标记（重压缩去重靠它）——改措辞会让旧检查点里的附录无法摘除。 */
internal const val TRACE_SECTION_HEADER = "[这一段用过的痕迹]"

/** 清单最多列几条（文件 + 文档 + 媒体合计）。 */
internal const val TRACE_MAX_ENTRIES = 12

/** 附录总字符上限（含头行、工具行与「未列出」尾巴）。 */
internal const val TRACE_MAX_CHARS = 600

/** 工具名最多列几个（其余合并成「等 N 种」）。 */
internal const val TRACE_MAX_TOOL_NAMES = 8

/** 单条句柄的长度上限：再长就不是句柄而是正文了（截断加省略号，仍能看出是哪个文件）。 */
internal const val TRACE_MAX_HANDLE_CHARS = 160

/**
 * 附录 token 预算占摘要目标 token 的比例：默认目标 2000 token 时约 500 token。
 * 上限 600 字符的清单按中文/CJK 混排通常 150~300 token，正常能进；目标设得极小时整节不接。
 */
internal const val TRACE_BUDGET_RATIO: Double = 0.25

/** 收集期最多留多少条（防 `ls` 之类工具输出里的几千行路径把清单撑爆；第 12 条之后的本来也进不了附录）。 */
private const val TRACE_COLLECT_MAX = 64

/** 沙箱里可回取的路径前缀（含尾斜杠，避免 `/tmpfoo` 这种误判）。 */
internal val TRACE_PATH_PREFIXES: List<String> = listOf(
    "/workspace/",
    "/tmp/",
    "/var/tmp/",
    "/upload/",
    "/skills/",
    "/builtin_skills/",
    "/tool_outputs/",
)

/**
 * 字面扫描用的 token 正则：沙箱路径段，或 `file://` / `content://` URI。
 *
 * 只做**字面扫描**，不解析工具语义：工具输入是 JSON、输出常是命令行/文本，路径就是其中的
 * 一串非分隔字符。宁可多认一个（后面还有长度与条数上限兜底），不要漏。
 *
 * 左界用 `(?<![\w./-])` 卡住：`https://example.com/tmp/a`、`sub/tmp/a`、`./tmp/a` 里的
 * `/tmp/a` 是**别人的路径片段**，不是本仓沙箱路径——误收后写进摘要，续接模型会去宿主上的
 * `/tmp` 找，比漏收更糟。
 */
private val TRACE_HANDLE_TOKEN = Regex(
    "(?<![\\w./-])(?:" + TRACE_PATH_PREFIXES.joinToString("|") { Regex.escape(it) } + ")" +
        "[^\\s\"'`<>,;()\\[\\]{}|&]*" +
        "|(?<![\\w./-])(?:file|content)://[^\\s\"'`<>,;()\\[\\]{}|&]*"
)

/** 句柄尾部常带的标点（`/tmp/a.txt.` 里的句号是句子、不是文件名）。 */
private const val TRACE_TRAILING_PUNCT = ".,;:!?、。，；：）】」』"

internal enum class TraceKind(val marker: String) {
    /** 工具参数/结果里出现的沙箱文件路径 */
    FILE("文件"),

    /** 附件里的文档（PDF/DOCX/...）：句柄是 url，标签是 fileName */
    DOCUMENT("文档"),

    /** 附件里的图片/音频/视频：句柄是 url，标签是类型 */
    MEDIA("媒体"),
}

/**
 * 一条可回取的痕迹。
 *
 * @param handle 可回取的句柄（路径 / url；`data:` 只保留 mime 头，不展开 base64）
 * @param label 人类可读标签（文档的 fileName、媒体的「图片/音频/视频」）；文件类为空
 * @param tools 用过它的工具（按首次出现去重保序）；附件本身没有工具
 */
internal data class TraceEntry(
    val kind: TraceKind,
    val handle: String,
    val label: String = "",
    val tools: List<String> = emptyList(),
)

/** 本段痕迹：工具名（去重保序）+ 可回取条目（按首次出现去重保序）。 */
internal data class TraceBundle(
    val toolNames: List<String> = emptyList(),
    val entries: List<TraceEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = toolNames.isEmpty() && entries.isEmpty()
}

/**
 * 从被压缩的消息里机械收集痕迹。
 *
 * 旧版 `ToolCall` / `ToolResult`（已废弃）不收：它们的序列化形态只在很老的数据里出现，
 * 而压缩段总是近若干轮，实际都是 [UIMessagePart.Tool]；漏收的后果只是附录少几条。
 */
internal fun collectTraceBundle(messages: List<UIMessage>): TraceBundle {
    val toolNames = mutableListOf<String>()
    val entries = mutableListOf<TraceEntry>()

    fun noteTool(name: String) {
        if (name.isNotBlank() && name !in toolNames) toolNames += name
    }

    fun noteHandle(kind: TraceKind, handle: String, label: String = "", tool: String? = null) {
        if (handle.isBlank()) return
        val index = entries.indexOfFirst { it.kind == kind && it.handle == handle }
        if (index >= 0) {
            val existing = entries[index]
            val tools = if (tool.isNullOrBlank() || tool in existing.tools) existing.tools else existing.tools + tool
            entries[index] = existing.copy(label = existing.label.ifBlank { label }, tools = tools)
            return
        }
        if (entries.size >= TRACE_COLLECT_MAX) return
        entries += TraceEntry(kind, handle, label, if (tool.isNullOrBlank()) emptyList() else listOf(tool))
    }

    fun notePaths(text: String, tool: String) {
        if (text.isEmpty()) return
        for (match in TRACE_HANDLE_TOKEN.findAll(text)) {
            val handle = sanitizeHandle(match.value)
            if (handle.isNotEmpty()) noteHandle(TraceKind.FILE, handle, tool = tool)
        }
    }

    for (message in messages) {
        for (part in message.parts) {
            when (part) {
                is UIMessagePart.Tool -> {
                    noteTool(part.toolName)
                    notePaths(part.input, part.toolName)
                    part.output.forEach { output ->
                        if (output is UIMessagePart.Text) notePaths(output.text, part.toolName)
                    }
                }

                is UIMessagePart.ServerTool -> {
                    noteTool(part.toolName)
                    part.input?.toString()?.let { notePaths(it, part.toolName) }
                    part.output?.toString()?.let { notePaths(it, part.toolName) }
                }

                is UIMessagePart.Document -> noteHandle(
                    TraceKind.DOCUMENT,
                    sanitizeHandle(part.url),
                    part.fileName.ifBlank { "" },
                )

                is UIMessagePart.Image -> noteHandle(TraceKind.MEDIA, sanitizeHandle(part.url), "图片")

                is UIMessagePart.Audio -> noteHandle(TraceKind.MEDIA, sanitizeHandle(part.url), "音频")

                is UIMessagePart.Video -> noteHandle(TraceKind.MEDIA, sanitizeHandle(part.url), "视频")

                else -> Unit
            }
        }
    }
    return TraceBundle(toolNames, entries)
}

/**
 * 把痕迹摊成摘要尾部的一节；没有痕迹返回 null。
 *
 * 行序即信息密度序：头行 → 工具名 → 条目（按首次出现顺序）→ 「其余 N 条未列出」。
 * 字符上限在拼行时就判（一旦加上这条超 600 就停），而不是拼完再截——截断会切坏句柄。
 */
internal fun renderTraceSection(bundle: TraceBundle): String? {
    if (bundle.isEmpty) return null
    val lines = mutableListOf(TRACE_SECTION_HEADER)
    var chars = TRACE_SECTION_HEADER.length + 1

    if (bundle.toolNames.isNotEmpty()) {
        val shown = bundle.toolNames.take(TRACE_MAX_TOOL_NAMES)
        val rest = bundle.toolNames.size - shown.size
        val line = buildString {
            append("工具: ")
            append(shown.joinToString(", "))
            if (rest > 0) append(" 等 $rest 种")
        }
        lines += line
        chars += line.length + 1
    }

    var listed = 0
    for (entry in bundle.entries) {
        if (listed >= TRACE_MAX_ENTRIES) break
        val line = renderTraceEntry(entry)
        if (chars + line.length + 1 > TRACE_MAX_CHARS) break
        lines += line
        chars += line.length + 1
        listed++
    }

    val omitted = bundle.entries.size - listed
    if (omitted > 0) {
        val tail = "（其余 $omitted 条未列出）"
        if (chars + tail.length + 1 <= TRACE_MAX_CHARS) lines += tail
    }
    return lines.joinToString("\n")
}

/**
 * 从已经落库的摘要正文里取回旧痕迹（重压缩接力用）。
 *
 * 解析的是本文件自己渲染的格式，所以只求「读得回自己写的那几种行」，不做宽松猜测：
 * 认不出的行直接跳过（宁可少接力，不可把正文误当痕迹）。
 */
internal fun parseTraceBundle(section: String): TraceBundle {
    if (!section.contains(TRACE_SECTION_HEADER)) return TraceBundle()
    val toolNames = mutableListOf<String>()
    val entries = mutableListOf<TraceEntry>()
    var inside = false
    for (rawLine in section.lineSequence()) {
        val line = rawLine.trim()
        when {
            line == TRACE_SECTION_HEADER -> inside = true
            !inside -> continue
            line.startsWith("工具: ") -> {
                val names = line.removePrefix("工具: ")
                    .substringBefore(" 等 ")
                    .split(", ")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                names.forEach { name -> if (name !in toolNames) toolNames += name }
            }

            line.startsWith("- ") -> parseTraceEntry(line.removePrefix("- "))?.let { entry ->
                if (entries.none { it.kind == entry.kind && it.handle == entry.handle }) {
                    entries += entry
                }
            }

            line.startsWith("（其余") -> break
        }
    }
    return TraceBundle(toolNames, entries)
}

/**
 * 摘掉摘要正文里的痕迹附录（重压缩前先摘旧附录）。
 *
 * 判据：附录永远是正文的**最后一段**且以固定头行开始，所以从第一个「行首头行」切到结尾即可。
 * 只认行首（`\n` + 头行），避免正文里恰好引用到这句话时把后文全切掉。
 */
internal fun stripTraceSection(summary: String): String {
    if (summary.isEmpty()) return summary
    val markerIndex = if (summary.startsWith(TRACE_SECTION_HEADER)) {
        0
    } else {
        summary.indexOf("\n$TRACE_SECTION_HEADER")
    }
    if (markerIndex < 0) return summary
    return summary.substring(0, markerIndex).trimEnd('\n', ' ', '\t', '\r')
}

/**
 * 在摘要正文后接上痕迹附录（纯函数，`compressToCheckpoint` 只调这一个入口）。
 *
 * 顺序与判据：
 * 1. 摘掉新摘要里可能残留的旧附录（防御：模型不该看到它，但正文里若出现就替换成新的）；
 * 2. 旧检查点的附录（[priorSummary]）解析出来**接力**：本段条目在前、旧段条目在后，同句柄合并；
 * 3. 渲染新附录；没有痕迹 → 原样返回（不写「没有」）；
 * 4. token 预算：附录 > `targetTokens × [TRACE_BUDGET_RATIO]` → 整节不接，正文一个字不动。
 */
internal fun attachTraceSection(
    summary: String,
    priorSummary: String,
    messages: List<UIMessage>,
    targetTokens: Int,
): String {
    val body = stripTraceSection(summary)
    val merged = mergeTraceBundles(
        // 注意：接力读的是**带附录的旧正文**（附录就是要在这一刻被接走的）；摘附录只对
        // 模型可见的 priorContext 做（ChatService 侧 stripTraceSection），两件事不能合。
        carried = parseTraceBundle(priorSummary),
        current = collectTraceBundle(messages),
    )
    val section = renderTraceSection(merged) ?: return body.ifBlank { summary }
    val budget = (targetTokens.coerceAtLeast(0) * TRACE_BUDGET_RATIO).toInt()
    if (budget <= 0 || estimateTokens(section) > budget) return body.ifBlank { summary }
    return if (body.isBlank()) section else body + "\n\n" + section
}

/** 本段优先、旧段接力：同一句柄合并工具列表（本段顺序在前）。 */
private fun mergeTraceBundles(carried: TraceBundle, current: TraceBundle): TraceBundle {
    if (carried.isEmpty) return current
    if (current.isEmpty) return carried
    val entries = mutableListOf<TraceEntry>()
    for (entry in current.entries + carried.entries) {
        val index = entries.indexOfFirst { it.kind == entry.kind && it.handle == entry.handle }
        if (index >= 0) {
            val existing = entries[index]
            entries[index] = existing.copy(
                label = existing.label.ifBlank { entry.label },
                tools = (existing.tools + entry.tools).distinct(),
            )
        } else if (entries.size < TRACE_COLLECT_MAX) {
            entries += entry
        }
    }
    return TraceBundle((current.toolNames + carried.toolNames).distinct(), entries)
}

private fun renderTraceEntry(entry: TraceEntry): String {
    val head = buildString {
        append("- ")
        append(entry.kind.marker)
        append(' ')
        when {
            entry.kind == TraceKind.FILE -> append(entry.handle)
            entry.label.isBlank() -> append(entry.handle)
            entry.handle.isBlank() -> append(entry.label)
            else -> append(entry.label).append(" (").append(entry.handle).append(')')
        }
    }
    if (entry.tools.isEmpty()) return head
    return head + " (" + entry.tools.joinToString(", ") + ")"
}

private fun parseTraceEntry(body: String): TraceEntry? {
    val kind = when {
        body.startsWith("文件 ") -> TraceKind.FILE
        body.startsWith("文档 ") -> TraceKind.DOCUMENT
        body.startsWith("媒体 ") -> TraceKind.MEDIA
        else -> return null
    }
    val rest = body.substringAfter(' ')
    if (kind == TraceKind.FILE) {
        // 文件行末尾可能带工具列表 " (/workspace/a.kt (read_file, edit_file)" 形态)
        val open = rest.lastIndexOf(" (")
        if (open >= 0 && rest.endsWith(")")) {
            val tools = rest.substring(open + 2, rest.length - 1)
                .split(", ")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            return TraceEntry(kind, rest.substring(0, open), "", tools)
        }
        return TraceEntry(kind, rest)
    }
    val open = rest.lastIndexOf(" (")
    if (open >= 0 && rest.endsWith(")")) {
        return TraceEntry(kind, rest.substring(open + 2, rest.length - 1), rest.substring(0, open))
    }
    return TraceEntry(kind, rest)
}

/**
 * 句柄清洗：去尾标点、`data:` 内联内容只留 mime 头、超长句柄截断。
 *
 * `data:` 判据：附件可能是内联 base64（语音/截图快照），展开它等于把整段 base64 抄进摘要；
 * 保留 `data:<mime>` + 「内联，未展开」是诚实的最小信息——回取不了但也没骗模型说能回取。
 */
internal fun sanitizeHandle(raw: String): String {
    var handle = raw.trim().trimEnd { it in TRACE_TRAILING_PUNCT }
    if (handle.isEmpty()) return handle
    if (handle.startsWith("data:")) {
        val cut = handle.indexOfFirst { it == ';' || it == ',' }
        val mime = if (cut >= 0) handle.substring(0, cut) else handle
        return "$mime（内联，未展开）"
    }
    if (handle.length > TRACE_MAX_HANDLE_CHARS) {
        handle = handle.take(TRACE_MAX_HANDLE_CHARS - 1) + "…"
    }
    return handle
}
