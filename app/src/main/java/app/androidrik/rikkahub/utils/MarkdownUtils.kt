package app.androidrik.rikkahub.utils

/**
 * HTML 标签/注释/命名实体。
 *
 * 背景（上游 issue #2222）：模型回复里常带 `<details>` / `<summary>` 等 HTML，
 * 旧的 [stripMarkdown] 只处理 Markdown 标记，于是 TTS 会把标签名逐字读成
 * 「小于 details 大于」。这里先剥掉 HTML，再走 Markdown 清洗。
 *
 * 只匹配**已知 HTML 标签名**，避免把正文里的比较式（`a < b`、`x<y`）误当标签删除；
 * 换行类标签先转换行，防止 `<br>` 删除后两行文字粘在一起。
 */
private val HTML_BREAK_TAG = Regex(
    """(?i)</?(?:br|p|div|li|ul|ol|tr|td|th|h[1-6]|blockquote|pre|section|article|header|footer|figure|figcaption|table|tbody|thead|details|summary|hr)\b[^>]*>"""
)
private val HTML_TAG = Regex(
    """(?i)</?(?:a|abbr|b|bdi|bdo|big|center|cite|code|del|dfn|em|font|i|img|ins|kbd|mark|q|s|samp|small|span|strong|sub|sup|time|u|var|wbr)\b[^>]*>"""
)
private val HTML_COMMENT = Regex("""<!--[\s\S]*?-->""")
private val HTML_ENTITY = Regex("""&(nbsp|amp|lt|gt|quot|#39|apos|#x27);""", RegexOption.IGNORE_CASE)

/**
 * 移除字符串中的 Markdown 格式（以及 HTML 标签，供 TTS 朗读用）
 * @return 移除 Markdown / HTML 后的纯文本
 */
fun String.stripMarkdown(): String {
    return this
        // 先剥 HTML：注释、换行标签、其余标签（保留标签内文字）、常见命名实体
        .replace(HTML_COMMENT, "")
        .replace(HTML_BREAK_TAG, "\n")
        .replace(HTML_TAG, "")
        .replace(HTML_ENTITY) {
            when (it.groupValues[1].lowercase()) {
                "nbsp" -> " "
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                else -> "'"
            }
        }
        // 移除代码块 (```...``` 和 `...`)
        .replace(Regex("```[\\s\\S]*?```|`[^`]*?`"), "")
        // 移除图片和链接，但保留其文本内容
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
        // 移除加粗和斜体 (先处理两个星号的)
        .replace(Regex("\\*\\*([^*]+?)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+?)\\*"), "$1")
        // 移除下划线
        .replace(Regex("__([^_]+?)__"), "$1")
        .replace(Regex("_([^_]+?)_"), "$1")
        // 移除删除线
        .replace(Regex("~~([^~]+?)~~"), "$1")
        // 移除标题标记 (多行模式)
        .replace(Regex("(?m)^#+\\s*"), "")
        // 移除列表标记 (多行模式)
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        // 移除引用标记 (多行模式)
        .replace(Regex("(?m)^>\\s*"), "")
        // 移除水平分割线
        .replace(Regex("(?m)^(\\s*[-*_]){3,}\\s*$"), "")
        // 将多个换行符压缩，以保留段落
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

fun String.extractThinkingTitle(): String? {
    // 按行分割文本
    val lines = this.lines()

    // 从后往前查找最后一个符合条件的加粗文本行
    for (i in lines.indices.reversed()) {
        val line = lines[i].trim()

        // 检查是否为加粗格式且独占一整行
        val boldPattern = Regex("^\\*\\*(.+?)\\*\\*$")
        val match = boldPattern.find(line)

        if (match != null) {
            // 返回加粗标记内的文本内容
            return match.groupValues[1].trim().takeUnless { it.isBlank() }
        }
    }

    return null
}
