package app.androidrik.rikkahub.data.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 备份类别（借鉴 Kelivo 的 `BackupScope` 组织形式）：把「备份/恢复覆盖哪些数据」从
 * 三档 zip section（[BackupItem]）细化到 settings 内部。
 *
 * - [DATABASE]/[FILES]/[WORKSPACE_FILES]：与 [BackupItem] 一一对应（[BackupItem.toCategory]），
 *   三档语义不变，仅纳入同一类别体系（manifest 记录统一口径）；
 * - [ASSISTANTS]/[PROVIDERS]/[DISPLAY]/[ABOUT]：settings.json 的**键级类别**——被排除的类别
 *   在打包时不写入 settings.json，恢复时保留本机值（见 `BackupContentFilter`）。
 *
 * **为何要类别而非逐字段**：设置项上百，逐字段选择 UI 不可维护；按用户可理解的
 * 「助手 / 提供商 / 外观 / 其他」四类划分，与备份页的分组习惯一致。
 */
@Serializable
enum class BackupCategory {
    DATABASE,
    FILES,
    WORKSPACE_FILES,
    ASSISTANTS,
    PROVIDERS,
    DISPLAY,
    ABOUT,
    // —— v4 细分类别（manifest formatVersion 4 起出现；旧版本解码未知值自动降级为「包含」）——
    SPEECH,
    SEARCH,
    MCP,
    REMOTE_BACKUP,
    TOOLS,
    MEMORY,
    TRANSLATE,
    WEB_SERVER,
}

/** v4 起新增的类别（legacy 语义判定用：v3 包恢复时这些键按 ABOUT 处理，见 BackupContentFilter）。 */
internal val V4_NEW_CATEGORIES: Set<BackupCategory> = setOf(
    BackupCategory.SPEECH,
    BackupCategory.SEARCH,
    BackupCategory.MCP,
    BackupCategory.REMOTE_BACKUP,
    BackupCategory.TOOLS,
    BackupCategory.MEMORY,
    BackupCategory.TRANSLATE,
    BackupCategory.WEB_SERVER,
)

/**
 * FILES（应用文件）的分组（v4）：10 个进备份目录归并成 4 组，组粒度可排除
 * （用户勾选粒度取舍：逐目录 UI 过长）。zip 条目前缀天然带目录名，
 * 打包/恢复两侧按 `BackupFolderRegistry` 的目录→组映射过滤。
 */
@Serializable
enum class BackupFileGroup {
    /** 媒体素材：images、media_creation */
    MEDIA,

    /** 形象装扮：avatars、backgrounds、avatar_frames、bubble_textures、skin_packages */
    PROFILE,

    /** 技能与字体：skills、fonts */
    SKILLS_FONTS,

    /** 其他文件：upload */
    MISC,
}

/** [BackupItem] 三档与归档类别的对应（防两处漂移）。 */
fun BackupItem.toCategory(): BackupCategory = when (this) {
    BackupItem.DATABASE -> BackupCategory.DATABASE
    BackupItem.FILES -> BackupCategory.FILES
    BackupItem.WORKSPACE_FILES -> BackupCategory.WORKSPACE_FILES
}

/**
 * 备份范围：**excluded-set 语义**（借鉴 Kelivo）——记录被排除的类别，未记录即包含。
 *
 * 好处：后续新增类别默认进包，旧版本写出的 JSON（缺字段）解码即「全包含」，天然向后兼容；
 * manifest v2 旧包的 `scope == null` 也按全包含处理。
 */
@Serializable(with = BackupScopeSerializer::class)
data class BackupScope(
    val excluded: Set<BackupCategory> = emptySet(),
    /** v4：被排除的文件分组（excluded-set 同语义，空集 = 全包含；v2/v3 包缺字段解码即空集）。 */
    val excludedFileGroups: Set<BackupFileGroup> = emptySet(),
) {
    /** 该类别是否在备份范围内。 */
    fun includes(category: BackupCategory): Boolean = category !in excluded

    /** 目录名是否在文件备份范围内（目录→组映射见 BackupFolderRegistry）。 */
    fun includesFileFolder(folder: String, groupOf: (String) -> BackupFileGroup?): Boolean {
        val group = groupOf(folder) ?: return true
        return includesFileGroup(group)
    }

    /** 文件分组是否在备份范围内。 */
    fun includesFileGroup(group: BackupFileGroup): Boolean = group !in excludedFileGroups

    /** 返回调整单个类别后的新 scope（不可变更新）。 */
    fun withCategory(category: BackupCategory, include: Boolean): BackupScope = when {
        include -> copy(excluded = excluded - category)
        else -> copy(excluded = excluded + category)
    }

    /** 返回调整单个文件分组后的新 scope。 */
    fun withFileGroup(group: BackupFileGroup, include: Boolean): BackupScope = when {
        include -> copy(excludedFileGroups = excludedFileGroups - group)
        else -> copy(excludedFileGroups = excludedFileGroups + group)
    }

    /** 与另一 scope 取交集（两者都排除才算排除）——恢复侧「备份 scope ∩ 用户选择」时用。 */
    fun intersect(other: BackupScope): BackupScope = copy(
        excluded = excluded intersect other.excluded,
        excludedFileGroups = excludedFileGroups intersect other.excludedFileGroups,
    )

    /** 排除了所有类别（无内容可备份）。 */
    val isEmpty: Boolean get() = excluded.size == BackupCategory.entries.size

    fun toJson(): JsonElement = buildJsonObject {
        put("excluded", JsonArray(excluded.map { JsonPrimitive(it.name) }))
        if (excludedFileGroups.isNotEmpty()) {
            put("excludedFileGroups", JsonArray(excludedFileGroups.map { JsonPrimitive(it.name) }))
        }
    }

    companion object {
        /**
         * 从 JSON 解码；未知类别/分组名**按「包含」处理并丢弃**（向前兼容：旧版本读新备份时，
         * 不认识的类别不能当成「已排除」，否则会静默丢数据）。解析失败返回 null。
         */
        fun fromJsonOrNull(element: JsonElement?): BackupScope? = runCatching {
            val obj = element?.jsonObject ?: return@runCatching BackupScope()
            val names: List<String> = obj["excluded"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
            val groupNames: List<String> = obj["excludedFileGroups"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
            BackupScope(
                excluded = names.mapNotNull { name ->
                    BackupCategory.entries.firstOrNull { it.name == name }
                }.toSet(),
                excludedFileGroups = groupNames.mapNotNull { name ->
                    BackupFileGroup.entries.firstOrNull { it.name == name }
                }.toSet(),
            )
        }.getOrNull()
    }
}

/**
 * [BackupScope] 的容错 JSON 序列化器：未知类别/分组名按「包含」丢弃（[BackupScope.fromJsonOrNull]）。
 *
 * 必须自定义的原因：manifest 是 kotlinx 类型化解码（`decodeFromString<BackupManifest>`），
 * 默认 enum 解码遇到未知枚举名直接抛 `SerializationException`——旧版本 App 读 v4 新包时
 * 会「整个 manifest 解码失败」而不是优雅降级。挂上本序列化器后，新类别/分组对旧版本
 * 表现为「包含」（丢掉排除记录），恢复仍可进行。
 */
object BackupScopeSerializer : KSerializer<BackupScope> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: BackupScope) {
        encoder.encodeSerializableValue(JsonElement.serializer(), value.toJson())
    }

    override fun deserialize(decoder: Decoder): BackupScope =
        BackupScope.fromJsonOrNull(decoder.decodeSerializableValue(JsonElement.serializer()))
            ?: BackupScope()
}
