package app.androidrik.rikkahub.data.files

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** frontmatter 结束围栏（换行 + `---`），YAML 解析与行级工具共用同一判定。 */
private val FRONTMATTER_END_REGEX = Regex("""\r?\n---(?:\r?\n|$)""")

object SkillFrontmatterParser {
    fun parse(content: String): SkillFrontmatter {
        if (!content.startsWith("---")) return SkillFrontmatter.Empty
        val endRange = findFrontmatterEndRange(content) ?: return SkillFrontmatter.Empty
        val yamlContent = content.substring(3, endRange.first).trim()
        if (yamlContent.isEmpty()) return SkillFrontmatter.Empty

        return runCatching {
            val values = createYaml().load<Any?>(yamlContent) as? Map<*, *>
                ?: return SkillFrontmatter.Empty
            SkillFrontmatter(
                values.entries.mapNotNull { (key, value) ->
                    (key as? String)?.let { it to value }
                }.toMap()
            )
        }.getOrDefault(SkillFrontmatter.Empty)
    }

    fun extractBody(content: String): String {
        if (!content.startsWith("---")) return content
        val endRange = findFrontmatterEndRange(content) ?: return content
        return content.substring(endRange.last + 1).trimStart('\r', '\n')
    }

    private fun findFrontmatterEndRange(content: String): IntRange? {
        if (!content.startsWith("---")) return null
        return FRONTMATTER_END_REGEX.find(content, startIndex = 3)?.range
    }

    private fun createYaml(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            codePointLimit = 1_000_000
        }
        return Yaml(SafeConstructor(options))
    }
}

/**
 * #1901：frontmatter 的**行级**条目拆分——把顶层 `key:` 与其续行（缩进行 / `- ` 列表项）
 * 分成一组，保留**原始行文本**。
 *
 * 为什么不用 snakeyaml 重新序列化：那会丢掉注释、打乱键序、把块式列表改写成其它风格——
 * 用户手写的 frontmatter 会被「顺手格式化」，这类静默改写比功能缺失更难排查。
 * 这里只做行的分组，重写时原样搬运。
 *
 * **注释与空行归属于其后的键**（YAML 惯例是一条注释描述它下面那项）。这不是细节：
 * 若归属前一个键，`# 分类说明` 位于 `category:` 上方时会被算进上一个键，
 * 而覆盖保存恰恰会丢掉被排除的键——注释就会连同分类一起消失。
 *
 * @return 顶层条目列表（顺序即原文顺序）；非 `key:` 开头的畸形行不抛异常（见测试）
 */
internal fun splitFrontmatterEntries(content: String): List<FrontmatterEntry> {
    val block = frontmatterBlockOrNull(content) ?: return emptyList()
    val entries = mutableListOf<FrontmatterEntry>()
    var currentKey: String? = null
    var currentLines = mutableListOf<String>()
    // 注释/空行先攒着：遇到下一个键时并入那条目（而非回流给上一个键）
    var pendingLeading = mutableListOf<String>()

    fun flush() {
        currentKey?.let { entries += FrontmatterEntry(it, currentLines.toList()) }
        currentKey = null
        currentLines = mutableListOf()
    }

    block.lineSequence().forEach { line ->
        val key = topLevelKeyOf(line)
        when {
            key != null -> {
                flush()
                currentKey = key
                currentLines = (pendingLeading + line).toMutableList()
                pendingLeading = mutableListOf()
            }
            // 注释与空行：归属**下一个**键
            line.isBlank() || line.trimStart().startsWith("#") -> pendingLeading += line
            currentKey != null -> currentLines += line
            // 首个键之前的孤立畸形行：无归属，丢弃
        }
    }
    flush()
    return entries
}

/** 顶层 `key: ...` 行 → key；不是顶层键返回 null。 */
private fun topLevelKeyOf(line: String): String? {
    if (line.isEmpty() || line[0] == ' ' || line[0] == '\t' || line[0] == '-' || line[0] == '#') return null
    val colon = line.indexOf(':')
    if (colon <= 0) return null
    val key = line.substring(0, colon).trim()
    if (key.isEmpty() || key.any { it.isWhitespace() }) return null
    return key
}

/** frontmatter 块（不含两侧 `---` 围栏）；没有 frontmatter 时返回 null。 */
private fun frontmatterBlockOrNull(content: String): String? {
    if (!content.startsWith("---")) return null
    val endRange = FRONTMATTER_END_REGEX.find(content, startIndex = 3)?.range ?: return null
    return content.substring(3, endRange.first).trim('\r', '\n')
}

/** frontmatter 的一个顶层条目：[key] 与其原始行（首行含 key）。 */
internal data class FrontmatterEntry(val key: String, val lines: List<String>)

/**
 * #1901：取出除 [excludeKeys] 外的 frontmatter 条目行（原文）。
 *
 * 用途：AI 用 `save_skill` 覆盖已有技能时，把 `name`/`description` 之外的键
 * （用户手写的 `category`、`tags`、`compatibility`…）原样带回来，而不是整段丢掉。
 * 没有 frontmatter 时返回空列表。
 */
internal fun otherFrontmatterEntryLines(content: String, excludeKeys: Set<String>): List<String> =
    splitFrontmatterEntries(content)
        .filterNot { it.key in excludeKeys }
        .flatMap { it.lines }

class SkillFrontmatter internal constructor(
    private val values: Map<String, Any?>,
) {
    operator fun get(key: String): String? = values[key] as? String

    companion object {
        internal val Empty = SkillFrontmatter(emptyMap())
    }
}
