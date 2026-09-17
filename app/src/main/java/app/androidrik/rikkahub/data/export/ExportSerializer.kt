package app.androidrik.rikkahub.data.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.SelectiveLogic
import app.androidrik.rikkahub.utils.toLocalString
import java.time.LocalDateTime
import kotlin.uuid.Uuid

@Serializable
data class ExportData(
    val version: Int = 1,
    val type: String,
    val data: JsonElement,
    /**
     * 导出时刻（epoch millis）。用于"文件较旧"一类提示；可空以兼容旧文件。
     */
    val exportedAt: Long? = null,
    /**
     * **重复导入检测的幂等锚点**：导出时的 `Conversation.id`。
     *
     * 它随文件固定不变（导入不会改写它），故导入侧可用「该文件是否导入过」来提示重复——
     * 这是必要的，因为导入**总是换新 id**（决策 1），原有的 `existsConversationById` 判据
     * 在换 id 后永不命中（见 `docs/design-conversation-import-export.md` §十）。
     *
     * 可空：旧版导出文件没有该字段 → 无法判定 → **跳过检测**（不是"重复"）。
     */
    val sourceId: String? = null,
    /**
     * **附件清单**（阶段 2）：zip 内 `attachments/` 条目与 part URL 的关联。
     *
     * 空列表 = 无附件（阶段 1 的裸 JSON 也反序列化为此）→ 导入侧跳过附件处理。
     * 见 `docs/design-conversation-import-export.md` §六 阶段 2。
     */
    val files: List<ExportFileEntry> = emptyList(),
)

interface ExportSerializer<T> {
    val type: String

    fun export(data: T): ExportData
    fun import(context: Context, uri: Uri): Result<T>

    // 获取导出文件名
    fun getExportFileName(data: T): String = "${type}.json"

    // 便捷方法：直接导出为 JSON 字符串
    fun exportToJson(data: T, json: Json = DefaultJson): String {
        return json.encodeToString(ExportData.serializer(), export(data))
    }

    // 读取 URI 内容的便捷方法
    fun readUri(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("Failed to read file")
    }

    fun getUriFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) cursor.getString(nameIndex) else null
            } else null
        }
    }

    companion object {
        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
        }
    }
}

object ModeInjectionSerializer : ExportSerializer<PromptInjection.ModeInjection> {
    override val type = "mode_injection"

    override fun getExportFileName(data: PromptInjection.ModeInjection): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: PromptInjection.ModeInjection): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<PromptInjection.ModeInjection> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): PromptInjection.ModeInjection? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<PromptInjection.ModeInjection>(exportData.data)
                .copy(id = Uuid.random())
        }.getOrNull()
    }
}

object LorebookSerializer : ExportSerializer<Lorebook> {
    override val type = "lorebook"

    override fun getExportFileName(data: Lorebook): String {
        return "${data.name.ifEmpty { type }}.json"
    }

    override fun export(data: Lorebook): ExportData {
        return ExportData(
            type = type,
            data = ExportSerializer.DefaultJson.encodeToJsonElement(data)
        )
    }

    override fun import(context: Context, uri: Uri): Result<Lorebook> {
        return runCatching {
            val json = readUri(context, uri)
            // 首先尝试解析为自己的格式
            tryImportNative(json)
            // 然后尝试解析为 SillyTavern 格式
                ?: tryImportSillyTavern(json, getUriFileName(context, uri)?.removeSuffix(".json"))
                ?: throw IllegalArgumentException("Unsupported format")
        }
    }

    private fun tryImportNative(json: String): Lorebook? {
        return runCatching {
            val exportData = ExportSerializer.DefaultJson.decodeFromString(
                ExportData.serializer(),
                json
            )
            if (exportData.type != type) return null
            ExportSerializer.DefaultJson
                .decodeFromJsonElement<Lorebook>(exportData.data)
                .copy(
                    id = Uuid.random(),
                    entries = ExportSerializer.DefaultJson
                        .decodeFromJsonElement<Lorebook>(exportData.data)
                        .entries.map { it.copy(id = Uuid.random()) }
                )
        }.getOrNull()
    }

    private fun tryImportSillyTavern(json: String, fileName: String?): Lorebook? {
        return runCatching {
            val stLorebook = ExportSerializer.DefaultJson.decodeFromString(
                SillyTavernLorebook.serializer(),
                json
            )
            Lorebook(
                id = Uuid.random(),
                name = fileName ?: LocalDateTime.now().toLocalString(),
                description = "",
                enabled = true,
                entries = stLorebook.entries.values.map { it.toRegexInjection() },
            )
        }.getOrNull()
    }
}

/**
 * ST 条目 → fork [PromptInjection.RegexInjection]（**纯函数**，便于单测直接覆盖导入保真）。
 *
 * 三处保真要点（缺一即「导入后行为不等价」，见 `docs/peer-apps-benchmark.md` §6.3）：
 * 1. `key` / `keysecondary` 里的 `/pattern/flags` 都要识别为正则（[parseStKeywords]）；
 * 2. **保留次要键**——这是 ST 抑制误触发的主要机制，丢弃即过度注入；
 * 3. `selective: false` 时 ST 不做二次判定（`world-info.js:4925`）⇒ 次要键整体作废，
 *    且必须在算 `useRegex` 之前作废，否则次要键的正则会连累主键字面量按正则匹配。
 */
internal fun SillyTavernEntry.toRegexInjection(): PromptInjection.RegexInjection {
    val parsedPrimary = parseStKeywords(key)
    val effectiveSecondary = if (selective) parseStKeywords(keySecondary) else null
    return PromptInjection.RegexInjection(
        id = Uuid.random(),
        name = comment.orEmpty().ifEmpty { key.firstOrNull().orEmpty() },
        enabled = !disable,
        priority = order,
        position = mapSillyTavernPosition(position),
        injectDepth = depth,
        content = content,
        keywords = parsedPrimary.keywords,
        // useRegex 是**整个条目共用**的开关（fork 限制）：主/次键任一侧出现正则形态都要置位，
        // 否则次要键里的正则会被当字面量，二次判定结果整体失真。
        useRegex = parsedPrimary.useRegex || (effectiveSecondary?.useRegex == true),
        caseSensitive = caseSensitive ?: false,
        scanDepth = scanDepth ?: 4,
        constantActive = constant,
        secondaryKeywords = effectiveSecondary?.keywords.orEmpty(),
        selectiveLogic = mapSillyTavernSelectiveLogic(selectiveLogic),
        // ST 的 6 个 match* 开关（角色描述/性格/场景/深度提示/作者注记/用户人设）
        // 在 fork 里无对应字段（内容已压进 systemPrompt）⇒ 并成一个「扫人设」开关。
        matchAssistantPrompt = matchCharacterDescription ||
            matchCharacterPersonality ||
            matchScenario ||
            matchCharacterDepthPrompt ||
            matchCreatorNotes ||
            matchPersonaDescription,
    )
}

/**
 * 解析 ST 世界书的 `key`（或 `keysecondary`）列表。
 *
 * ST 允许两种形态混用（`world-info.js` 的 `parseRegexFromString` + `matchKeys`）：
 * - 普通字面量关键词
 * - `/pattern/flags` 形式的正则（**优先于**其它匹配选项）
 *
 * fork 的 `RegexInjection` 只有**一个** `useRegex` 开关作用于整个关键词列表，
 * 无法表达「混合」。因此：只要出现任一正则形态就置 `useRegex = true` **并保留全部关键词**
 * ——宁可让纯字面量关键词按正则语义匹配（字面量本身在正则里通常等价，除非含元字符），
 * 也不要丢掉正则语义（后者会让条目的触发条件整体改变，是更严重的失真）。
 */
internal fun parseStKeywords(raw: List<String>): ParsedStKeywords {
    val isRegex = raw.any { isStRegexKeyword(it) }
    return ParsedStKeywords(
        // 正则形态去掉 `/.../flags` 包裹与斜杠转义，交给 Regex 语义使用
        keywords = if (isRegex) raw.map { unwrapStRegexKeyword(it) } else raw,
        useRegex = isRegex,
    )
}

/** `/pattern/flags` 形态判定（对齐 ST `parseRegexFromString` 的前置条件）。 */
private fun isStRegexKeyword(value: String): Boolean {
    val match = ST_KEY_REGEX.matchEntire(value.trim()) ?: return false
    // 未转义的分隔斜杠会让其它正则引擎失败，ST 同样拒绝
    return !match.groupValues[1].contains(UNESCAPED_SLASH_REGEX)
}

/** 去掉正则包裹，得到裸 pattern（`\/` 还原为 `/`，对齐 ST 的处理）。 */
private fun unwrapStRegexKeyword(value: String): String {
    val match = ST_KEY_REGEX.matchEntire(value.trim()) ?: return value
    return match.groupValues[1].replace("\\/", "/")
}

/**
 * ST 条目位置 → fork [InjectionPosition]。
 *
 * ST 的位置（`world_info_position`，`world-info.js:855-864`）：
 * `before(0)` / `after(1)` / `ANTop(2)` / `ANBottom(3)` / `atDepth(4)` / `EMTop(5)` / `EMBottom(6)` / `outlet(7)`。
 *
 * **ANTop/ANBottom 修正（2026-09-17）**：原实现把两者都映射为 `TOP_OF_CHAT`
 * （且注释写成「After Examples」，与 ST 的 AN 无关）。核实 ST 的 AN 语义：
 * ANTop/ANBottom 并非「聊天最开头/最末尾」，而是**包在「作者注释」前后**
 * （`world-info.js:5270` 把两者拼进 `originalAN` 两侧 → `setExtensionPrompt(..., position, depth, ...)`），
 * 而作者注释默认是 **IN_CHAT + depth 4**（`authors-note.js:273` DEFAULT_POSITION=1=IN_CHAT、
 * `:272` DEFAULT_DEPTH=4）。即两者实际都落在**对话中、距末尾若干条**处。
 *
 * fork 没有「作者注释」这个锚点，无法表达「在注释前/后」；映射到 `TOP_OF_CHAT` 会把内容
 * 挪到**对话最开头**（与真实位置相反的一端），是比丢信息更糟的失真。故改映射为
 * [InjectionPosition.AT_DEPTH]（配合 `entry.depth`），位置语义与 ST 默认一致；
 * 代价是 ANTop/ANBottom/atDepth 三者不再可区分——这是 fork 无 AN 概念下的**诚实损失**。
 *
 * `EMTop/EMBottom`（示例消息锚点）与 `outlet` fork 无对应概念，归入 `AFTER_SYSTEM_PROMPT`
 * （既有行为，未变）。
 */
internal fun mapSillyTavernPosition(position: Int): InjectionPosition {
    return when (position) {
        0 -> InjectionPosition.BEFORE_SYSTEM_PROMPT
        1 -> InjectionPosition.AFTER_SYSTEM_PROMPT
        2, 3 -> InjectionPosition.AT_DEPTH         // ANTop / ANBottom：作者注释前后（默认 in-chat depth 4）
        4 -> InjectionPosition.AT_DEPTH            // @Depth
        else -> InjectionPosition.AFTER_SYSTEM_PROMPT
    }
}

/** [parseStKeywords] 的结果。 */
internal data class ParsedStKeywords(
    val keywords: List<String>,
    val useRegex: Boolean,
)

/** ST 正则关键词形态：`/pattern/flags`（flags 取值对齐 `parseRegexFromString`）。 */
private val ST_KEY_REGEX = Regex("""^/([\w\W]+?)/([gimsuy]*)$""")

/** 未转义的分隔斜杠（ST 拒绝这类 pattern，因为别的正则引擎会解析失败）。 */
private val UNESCAPED_SLASH_REGEX = Regex("""(^|[^\\])/""")


@Serializable
private data class SillyTavernLorebook(
    val entries: Map<String, SillyTavernEntry> = emptyMap(),
)

@Serializable
internal data class SillyTavernEntry(
    val key: List<String> = emptyList(),
    val content: String = "",
    val comment: String? = null,
    val constant: Boolean = false,
    val position: Int = 0,
    val order: Int = 100,
    val disable: Boolean = false,
    val depth: Int = 4,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
    /**
     * 次要关键词（ST 的 `keysecondary`）。
     *
     * ⚠️ 字段名必须是 `keySecondary`（驼峰）——ST 导出的是 JSON 里的 `keysecondary`，
     * 靠 `@SerialName` 对齐；`JsonInstant`/`DefaultJson` 开了 `ignoreUnknownKeys`，
     * 故此前漏读该字段时不会报错、只会**静默丢弃**（这正是过度触发的成因）。
     */
    @SerialName("keysecondary")
    val keySecondary: List<String> = emptyList(),
    /**
     * 次键判定逻辑（ST 的 `selectiveLogic`，0/1/2/3）。
     *
     * 同样必须 `@SerialName` 对齐小写名。缺省 0 = AND_ANY（与 ST 一致）。
     */
    @SerialName("selectiveLogic")
    val selectiveLogic: Int = 0,
    /**
     * 是否启用次要键判定（ST 的 `selective`，默认 true）。
     *
     * ST 的判据是 `selective && keysecondary.length`（`world-info.js:4925`）——
     * 即 `selective: false` 时**即使有次要键也不做二次判定**。漏读它会让这类条目
     * 比在 ST 里更难触发（方向相反的失真）。
     */
    val selective: Boolean = true,
    // ST 的 match* 扫描源开关（默认全 false）。fork 无对应字段 → 并成一个 matchAssistantPrompt。
    @SerialName("matchPersonaDescription")
    val matchPersonaDescription: Boolean = false,
    @SerialName("matchCharacterDescription")
    val matchCharacterDescription: Boolean = false,
    @SerialName("matchCharacterPersonality")
    val matchCharacterPersonality: Boolean = false,
    @SerialName("matchCharacterDepthPrompt")
    val matchCharacterDepthPrompt: Boolean = false,
    @SerialName("matchScenario")
    val matchScenario: Boolean = false,
    @SerialName("matchCreatorNotes")
    val matchCreatorNotes: Boolean = false,
)

/**
 * ST 的 `selectiveLogic` 整数 → [SelectiveLogic]。
 *
 * 取值来自 ST `world_info_logic`（`world-info.js:33-38`）：
 * `AND_ANY(0)` / `NOT_ALL(1)` / `NOT_ANY(2)` / `AND_ALL(3)`。
 * 未知值回退 `AND_ANY`（ST 默认，也是 SAFE 的一侧：不会让条目意外恒不触发）。
 */
internal fun mapSillyTavernSelectiveLogic(value: Int): SelectiveLogic = when (value) {
    1 -> SelectiveLogic.NOT_ALL
    2 -> SelectiveLogic.NOT_ANY
    3 -> SelectiveLogic.AND_ALL
    else -> SelectiveLogic.AND_ANY
}
