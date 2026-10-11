package app.androidrik.rikkahub.data.ai.tools

/**
 * `workspace_read_file` 行窗口的**纯策略**（不碰 IO，便于单测）。
 *
 * 为什么要单独抽出来：窗口的边界判定全是"差一错误"的高发区（1 基还是 0 基、`limit` 是行数还是结束行、
 * 到底还有没有更多行），而这些错误的表现是**模型读到错位的行却看不出哪里错**——比抛异常难查得多。
 */
internal data class ReadWindow(
    /** 1 基起始行 */
    val startLine: Long,
    /** 最多返回多少行 */
    val limit: Int,
)

/**
 * 归一化 `offset` / `limit`。
 *
 * - 两者都缺（或都非正）⇒ `null` = 整文件读（走老路径，受单次读取上限约束）；
 * - 只给 `limit` ⇒ 从第 1 行开始读；
 * - 只给 `offset` ⇒ 从该行读到文件末（仍受 [MAX_READ_LINES] 与字节上限约束）；
 * - `limit` 超过 [MAX_READ_LINES] ⇒ 夹到上限（挡住 `limit=10_000_000`）。
 */
internal fun normalizeReadWindow(offset: Int?, limit: Int?): ReadWindow? {
    val hasOffset = offset != null && offset > 0
    val hasLimit = limit != null && limit > 0
    if (!hasOffset && !hasLimit) return null
    return ReadWindow(
        startLine = if (hasOffset) offset!!.toLong() else 1L,
        limit = if (hasLimit) limit!!.coerceAtMost(MAX_READ_LINES) else MAX_READ_LINES,
    )
}

internal data class ReadWindowResult(
    val text: String,
    /** 实际返回的起始行（与请求相同，保留字段是为了回执自证） */
    val startLine: Long,
    /** 实际返回的结束行；空窗口时为 `startLine - 1` */
    val endLine: Long,
    /** 窗口之外还有内容：行没读完（[moreLines]）或字节被裁到上限 */
    val truncated: Boolean,
    val bytes: Int,
)

/**
 * 把读到的行拼成窗口结果。
 *
 * @param lines 从 [window].startLine 开始的连续行（IO 层负责跳过起始行；可能少于 limit，也可能为空）
 * @param moreLines 窗口之后还有行（IO 层多探一行得到）
 * @param maxBytes 窗口自身的字节上限（**不是**文件上限：窗口读的意义就是读大文件的局部）
 *
 * 字节裁剪按行进行（不会把一行切成半截——半截行会让模型以为文件就长这样），
 * 且**至少保留第一行**：否则"一行的长度就超上限"会退化成空窗口，比截断更难理解。
 */
internal fun buildWindowResult(
    window: ReadWindow,
    lines: List<String>,
    moreLines: Boolean,
    maxBytes: Int,
): ReadWindowResult {
    // 裁剪口径与文档抽取共用一处（trimLinesToBytes），避免两条路径各写一份"按整行裁"的规则后走偏
    val trimmed = trimLinesToBytes(lines, maxBytes)
    val bytes = trimmed.lines.sumOf { it.toByteArray(Charsets.UTF_8).size } +
        (trimmed.lines.size - 1).coerceAtLeast(0)
    return ReadWindowResult(
        text = trimmed.lines.joinToString("\n"),
        startLine = window.startLine,
        endLine = window.startLine + trimmed.lines.size - 1,
        truncated = trimmed.trimmed || moreLines,
        bytes = bytes,
    )
}

/** 文档不给 offset/limit 时默认读前多少个单元（页/张/章/块） */
internal const val DEFAULT_DOC_UNITS_PER_READ = 10

/** 单次文档抽取返回的字符上限（比文本窗口保守：抽取结果通常更"密"） */
internal const val MAX_DOC_CHARS_PER_READ = 100_000

/** 文档抽取的返回结果（单元号 1 基、含端点；空片段时 `lastUnit = firstUnit - 1`） */
internal data class DocumentReadResult(
    val text: String,
    val firstUnit: Int,
    val lastUnit: Int,
    /** -1 = 未知（DOCX 无真实分页）；0 = 空文档 */
    val totalUnits: Int,
    /** 还有更多单元没读，或返回文本被字符上限裁过 */
    val truncated: Boolean,
    /** 抽不出文本时的说明（扫描版 PDF 之类），供工具层原样告诉模型 */
    val notice: String?,
)

/**
 * 把抽取结果裁成"能交给模型的一页"。
 *
 * 两条判据与文本窗口一致：按**整行**裁（不切半行）、至少保留一行；`truncated` 要么是单元没读完，
 * 要么是字符被裁——两者对模型的意义一样："还有东西没给你"。
 *
 * 空文本单独给 [DocumentReadResult.notice]：**返回空串而不解释是最糟的**（模型会以为文件是空的），
 * 而扫描版 PDF 恰恰很常见。
 */
internal fun buildDocumentResult(
    text: String,
    firstUnit: Int,
    lastUnit: Int,
    totalUnits: Int,
    maxBytes: Int = MAX_DOC_CHARS_PER_READ,
): DocumentReadResult {
    val trimmed = trimLinesToBytes(text.lines(), maxBytes)
    val moreUnits = totalUnits > 0 && lastUnit < totalUnits
    return DocumentReadResult(
        text = trimmed.lines.joinToString("\n"),
        firstUnit = firstUnit,
        lastUnit = lastUnit,
        totalUnits = totalUnits,
        truncated = trimmed.trimmed || moreUnits,
        notice = if (text.isBlank()) {
            "No extractable text in units $firstUnit..${lastUnit.coerceAtLeast(firstUnit)}. " +
                "The document may be a scanned image without a text layer, or empty."
        } else {
            null
        },
    )
}

/** 按字节上限裁剪行集合：返回保留的行与"是否被裁" */
internal data class TrimmedLines(val lines: List<String>, val trimmed: Boolean)

internal fun trimLinesToBytes(lines: List<String>, maxBytes: Int): TrimmedLines {
    val kept = mutableListOf<String>()
    var bytes = 0
    for (line in lines) {
        val cost = line.toByteArray(Charsets.UTF_8).size + if (kept.isEmpty()) 0 else 1
        if (kept.isNotEmpty() && bytes + cost > maxBytes) return TrimmedLines(kept, true)
        kept += line
        bytes += cost
    }
    return TrimmedLines(kept, false)
}
