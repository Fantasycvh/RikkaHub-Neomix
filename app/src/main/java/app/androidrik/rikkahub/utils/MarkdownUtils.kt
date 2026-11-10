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

/** 围栏代码块：多行代码不该被朗读。 */
private val FENCED_CODE = Regex("```[\\s\\S]*?```")

/**
 * 行内代码：只去反引号、保留文本（上游 Miffan `6efec385a`）。
 *
 * 单行模式 `[^`\n]`：跨行的落单反引号不吞掉整段正文。
 */
private val INLINE_CODE = Regex("`([^`\\n]*?)`")

/**
 * 行内代码占位符（Unicode 私有区 U+E000/U+E001，正常回复里不会出现）。
 *
 * **为什么需要占位**：行内代码的文本必须原样保留，但后续的 `*`/`_`/`~` 规则会把它当
 * Markdown 标记继续改写——`my_var_name` 会被洗成 `myvar_name`、`a*b` 会被洗成 `ab`，
 * TTS 念出来就是错的。故先抽成占位符，其余规则跑完再原样放回。
 */
private val INLINE_CODE_PLACEHOLDER = Regex("\uE000(\\d+)\uE001")

/**
 * 移除字符串中的 Markdown 格式（以及 HTML 标签，供 TTS 朗读用）
 * @return 移除 Markdown / HTML 后的纯文本（行内代码保留其文本，围栏代码块整段丢弃）
 */
fun String.stripMarkdown(): String {
    // 第一步：剥 HTML（注释、换行标签、其余标签、常见命名实体）
    val withoutHtml = this
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
    // 第二步：围栏代码块整段删（长代码念出来没有意义）
    val withoutFenced = withoutHtml.replace(FENCED_CODE, "")
    // 第三步：行内代码抽成占位符，避免被后面的格式规则二次改写
    val inlineCodes = mutableListOf<String>()
    val protected = INLINE_CODE.replace(withoutFenced) { match ->
        inlineCodes += match.groupValues[1]
        "\uE000${inlineCodes.lastIndex}\uE001"
    }
    // 第四步：清洗其余 Markdown 标记
    val stripped = protected
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
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
    // 第五步：放回行内代码原文
    return INLINE_CODE_PLACEHOLDER.replace(stripped) { inlineCodes[it.groupValues[1].toInt()] }
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
