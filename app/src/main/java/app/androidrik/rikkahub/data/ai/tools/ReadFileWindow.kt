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
    val kept = mutableListOf<String>()
    var bytes = 0
    var byteLimited = false
    for (line in lines) {
        val cost = line.toByteArray(Charsets.UTF_8).size + if (kept.isEmpty()) 0 else 1
        if (kept.isNotEmpty() && bytes + cost > maxBytes) {
            byteLimited = true
            break
        }
        kept += line
        bytes += cost
    }
    return ReadWindowResult(
        text = kept.joinToString("\n"),
        startLine = window.startLine,
        endLine = window.startLine + kept.size - 1,
        truncated = byteLimited || moreLines,
        bytes = bytes,
    )
}
