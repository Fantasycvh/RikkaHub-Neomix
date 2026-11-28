package app.androidrik.rikkahub.data.ai.tools

import app.androidrik.ai.core.DEFERRED_TOOL_SEARCH_TOOL_NAME
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.TOOL_NO_APPROVAL
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.DeferredToolProtocol
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.DeferredToolSearchResultMetadata
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toMetadata
import app.androidrik.ai.util.ToolSchemaSize
import app.androidrik.rikkahub.utils.getStringContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 原生延迟工具加载的可选作用域。
 *
 * 注意：`Workspace` / `Memory` / `Search` / `Subagent` 及核心交互本地工具恒定常驻，
 * 不在此集合控制范围内，以免高频核心工具多一轮搜索往返。
 */
@Serializable
enum class DeferredToolScope {
    @SerialName("mcp_heavy")
    MCP_HEAVY,

    @SerialName("mcp_all")
    MCP_ALL,

    @SerialName("plugins")
    PLUGINS,

    @SerialName("skills")
    SKILLS,

    @SerialName("optional_local")
    OPTIONAL_LOCAL,
}

val DEFAULT_DEFERRED_TOOL_SCOPES: Set<DeferredToolScope> = setOf(
    DeferredToolScope.MCP_ALL,
    DeferredToolScope.PLUGINS,
    DeferredToolScope.SKILLS,
)

/** 高频核心交互本地工具（始终常驻，不纳入 OPTIONAL_LOCAL 延迟范围）。 */
internal val CORE_EAGER_LOCAL_TOOL_NAMES: Set<String> = setOf(
    "get_time_info",
    "ask_user",
    "todo_write",
    "view_image",
    "await_background_tasks",
)

private const val DEFAULT_SEARCH_LIMIT = 5
private const val MAX_SEARCH_LIMIT = 10
private const val CATALOG_DESCRIPTION_MAX_CHARS = 1_500
private const val CATALOG_NAMES_PER_GROUP = 8

internal data class DeferredToolEntry(
    val source: ToolSource,
    val tool: Tool,
    val paramKeys: List<String> = extractToolParamKeys(tool),
)

internal fun extractToolParamKeys(tool: Tool): List<String> =
    (tool.parameters() as? InputSchema.Obj)?.properties?.keys?.toList().orEmpty()

/**
 * 判断某工具在给定配置与来源下是否应标记为延迟加载。
 */
internal fun shouldDeferTool(
    source: ToolSource,
    tool: Tool,
    schemaTokens: Int,
    scopes: Set<DeferredToolScope>,
    explicitlyAllowedToolNames: Set<String> = emptySet(),
): Boolean {
    if (tool.isDeferredToolSearch) return false
    if (tool.name in explicitlyAllowedToolNames) return false
    return when (source) {
        ToolSource.Memory,
        ToolSource.Search,
        ToolSource.Workspace,
        ToolSource.Subagent,
        ToolSource.McpDynamic,
        ToolSource.DeferredSearch -> false

        is ToolSource.McpServer -> {
            DeferredToolScope.MCP_ALL in scopes ||
                (DeferredToolScope.MCP_HEAVY in scopes && schemaTokens > MCP_SCHEMA_COLLAPSE_TOKENS)
        }

        is ToolSource.Plugin -> DeferredToolScope.PLUGINS in scopes

        ToolSource.Skills,
        ToolSource.SkillInstall -> DeferredToolScope.SKILLS in scopes

        ToolSource.Local -> {
            DeferredToolScope.OPTIONAL_LOCAL in scopes && tool.name !in CORE_EAGER_LOCAL_TOOL_NAMES
        }

        ToolSource.Image,
        ToolSource.TaskManage,
        ToolSource.Conversation,
        ToolSource.Knowledge -> DeferredToolScope.OPTIONAL_LOCAL in scopes
    }
}

/**
 * 对已装配的工具段应用原生延迟加载策略（纯函数）：
 * - 不支持的协议或未开启时原样返回（不改任何工具）；
 * - 命中至少 1 个可延迟工具时，为对应工具置 `deferLoading = true`，并追加 `search_tools` 段；
 * - 未命中任何可延迟工具时，不注入 `search_tools`（零额外开销）。
 */
internal fun applyNativeDeferredToolPolicy(
    segments: List<ToolSegment>,
    enabled: Boolean,
    protocol: DeferredToolProtocol,
    scopes: Set<DeferredToolScope>,
    explicitlyAllowedToolNames: Set<String>,
    providerType: ProviderSetting?,
    json: Json,
): List<ToolSegment> {
    if (!enabled || protocol == DeferredToolProtocol.NONE || scopes.isEmpty()) {
        return segments
    }
    val deferredEntries = mutableListOf<DeferredToolEntry>()
    val updatedSegments = segments.map { segment ->
        val mappedTools = segment.tools.map { tool ->
            val schemaTokens = if (segment.source is ToolSource.McpServer &&
                DeferredToolScope.MCP_ALL !in scopes &&
                DeferredToolScope.MCP_HEAVY in scopes
            ) {
                ToolSchemaSize.estimate(tool, providerType, json)
            } else {
                0
            }
            if (shouldDeferTool(segment.source, tool, schemaTokens, scopes, explicitlyAllowedToolNames)) {
                val deferredTool = if (tool.deferLoading) tool else tool.copy(deferLoading = true)
                deferredEntries += DeferredToolEntry(segment.source, deferredTool)
                deferredTool
            } else {
                tool
            }
        }
        segment.copy(tools = mappedTools)
    }
    if (deferredEntries.isEmpty()) return segments
    return updatedSegments + ToolSegment(
        source = ToolSource.DeferredSearch,
        tools = listOf(createDeferredToolSearchTool(deferredEntries)),
    )
}

/**
 * 生成确定性、字节稳定的延迟工具目录摘要，注入 `search_tools.description` 以保护 KV Cache 前缀。
 */
internal fun buildDeferredCatalogDescription(
    entries: List<DeferredToolEntry>,
    maxChars: Int = CATALOG_DESCRIPTION_MAX_CHARS,
): String {
    val header = "Search and load deferred tools on demand before calling them."
    val footer = "Call with `query` (keywords or \"*\") or `names` (exact tool names)."
    if (entries.isEmpty()) return "$header\n$footer"

    val grouped = entries
        .groupBy { it.source.segmentName }
        .entries
        .sortedBy { it.key }

    val sb = StringBuilder(header).append('\n').append("Available deferred catalog:")
    for ((segmentName, groupEntries) in grouped) {
        val sortedNames = groupEntries.map { it.tool.name }.distinct().sorted()
        val shown = sortedNames.take(CATALOG_NAMES_PER_GROUP)
        val remaining = sortedNames.size - shown.size
        val line = buildString {
            append("\n- [")
            append(segmentName)
            append("] ")
            append(shown.joinToString(", "))
            if (remaining > 0) {
                append(" (+")
                append(remaining)
                append(" more)")
            }
        }
        if (sb.length + line.length + footer.length + 24 > maxChars) {
            sb.append("\n- ... (more tools available via query)")
            break
        }
        sb.append(line)
    }
    sb.append('\n').append(footer)
    return sb.toString()
}

/**
 * 检索匹配的延迟工具条目（纯函数）。
 * 优先级：精确 `names` > 工具名精确/前缀/子串 > 分词匹配（名/来源/参数/描述）。
 */
internal fun searchDeferredTools(
    entries: List<DeferredToolEntry>,
    query: String? = null,
    names: List<String>? = null,
    limit: Int = DEFAULT_SEARCH_LIMIT,
): List<DeferredToolEntry> {
    if (entries.isEmpty()) return emptyList()
    val effectiveLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
    val byLowerName = entries
        .sortedBy { it.tool.name }
        .associateBy { it.tool.name.lowercase() }

    val exactMatches = names.orEmpty()
        .mapNotNull { raw -> byLowerName[raw.trim().lowercase()] }
        .distinctBy { it.tool.name }

    val remainingBudget = effectiveLimit - exactMatches.size
    if (remainingBudget <= 0) return exactMatches.take(effectiveLimit)

    val exactMatchedNames = exactMatches.mapTo(mutableSetOf()) { it.tool.name }
    val candidates = entries
        .filter { it.tool.name !in exactMatchedNames }
        .distinctBy { it.tool.name }

    val trimmedQuery = query?.trim().orEmpty()
    if (trimmedQuery.isEmpty() || trimmedQuery == "*") {
        return if (names.isNullOrEmpty() || trimmedQuery == "*") {
            (exactMatches + candidates.sortedBy { it.tool.name }.take(remainingBudget))
                .take(effectiveLimit)
        } else {
            exactMatches
        }
    }

    val normalizedQuery = trimmedQuery.lowercase()
    val tokens = tokenizeSearchQuery(trimmedQuery)
    val scored = candidates.mapNotNull { entry ->
        val score = scoreDeferredToolEntry(entry, normalizedQuery, tokens)
        if (score > 0) entry to score else null
    }.sortedWith(
        compareByDescending<Pair<DeferredToolEntry, Int>> { it.second }
            .thenBy { it.first.tool.name }
    ).map { it.first }

    return (exactMatches + scored.take(remainingBudget)).take(effectiveLimit)
}

private fun scoreDeferredToolEntry(
    entry: DeferredToolEntry,
    normalizedQuery: String,
    tokens: List<String>,
): Int {
    val nameLower = entry.tool.name.lowercase()
    val sourceLower = entry.source.segmentName.lowercase()
    val descLower = entry.tool.description.lowercase()
    val paramsLower = entry.paramKeys.map { it.lowercase() }

    var score = 0
    when {
        nameLower == normalizedQuery -> score += 100
        nameLower.startsWith(normalizedQuery) -> score += 60
        nameLower.contains(normalizedQuery) -> score += 40
    }
    for (token in tokens) {
        if (token.isEmpty()) continue
        if (nameLower.contains(token)) score += 20
        if (sourceLower.contains(token)) score += 15
        if (paramsLower.any { it.contains(token) }) score += 10
        if (descLower.contains(token)) score += 6
    }
    return score
}

private fun tokenizeSearchQuery(query: String): List<String> {
    val camelSplit = query.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    return camelSplit
        .split(Regex("[\\s_\\-./,:;!?()\\[\\]{}\"']+"))
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
}

internal fun createDeferredToolSearchTool(entries: List<DeferredToolEntry>): Tool {
    return Tool(
        name = DEFERRED_TOOL_SEARCH_TOOL_NAME,
        description = buildDeferredCatalogDescription(entries),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Search keywords matching tool name, source, or capability (use \"*\" to list overview).",
                        )
                    })
                    put("names", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Optional exact tool names to load directly.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max number of tools to load (1-$MAX_SEARCH_LIMIT, default $DEFAULT_SEARCH_LIMIT).")
                    })
                },
            )
        },
        needsApproval = TOOL_NO_APPROVAL,
        concurrentSafe = true,
        executionTimeoutMillis = 5_000L,
        isDeferredToolSearch = true,
        deferLoading = false,
        execute = { args ->
            val obj = args as? JsonObject
            val query = args.getStringContent("query")
            val names = (obj?.get("names") as? JsonArray)?.mapNotNull {
                it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
            }
            val limit = obj?.get("limit")?.jsonPrimitive?.intOrNull ?: DEFAULT_SEARCH_LIMIT
            val matched = searchDeferredTools(
                entries = entries,
                query = query,
                names = names,
                limit = limit,
            )
            val matchedNames = matched.map { it.tool.name }
            val text = if (matched.isEmpty()) {
                "No matching deferred tools found for query=${query.orEmpty()}."
            } else {
                buildString {
                    append("Loaded ")
                    append(matched.size)
                    append(" tool(s): ")
                    appendLine(matchedNames.joinToString(", "))
                    matched.forEach { entry ->
                        append("- ")
                        append(entry.tool.name)
                        append(" [")
                        append(entry.source.segmentName)
                        append("]: ")
                        appendLine(entry.tool.description.lineSequence().firstOrNull().orEmpty().take(160))
                    }
                }.trimEnd()
            }
            listOf(
                UIMessagePart.Text(
                    text = text,
                    metadata = DeferredToolSearchResultMetadata(loadedToolNames = matchedNames).toMetadata(),
                )
            )
        },
    )
}
