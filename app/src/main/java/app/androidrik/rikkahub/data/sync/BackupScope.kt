package app.androidrik.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
@Serializable
data class BackupScope(val excluded: Set<BackupCategory> = emptySet()) {
    /** 该类别是否在备份范围内。 */
    fun includes(category: BackupCategory): Boolean = category !in excluded

    /** 返回调整单个类别后的新 scope（不可变更新）。 */
    fun withCategory(category: BackupCategory, include: Boolean): BackupScope = when {
        include -> copy(excluded = excluded - category)
        else -> copy(excluded = excluded + category)
    }

    /** 与另一 scope 取交集（两者都排除才算排除）——恢复侧「备份 scope ∩ 用户选择」时用。 */
    fun intersect(other: BackupScope): BackupScope =
        copy(excluded = excluded intersect other.excluded)

    /** 排除了所有类别（无内容可备份）。 */
    val isEmpty: Boolean get() = excluded.size == BackupCategory.entries.size

    fun toJson(): JsonElement = buildJsonObject {
        put("excluded", JsonArray(excluded.map { JsonPrimitive(it.name) }))
    }

    companion object {
        /**
         * 从 JSON 解码；未知类别名**按「包含」处理并丢弃**（向前兼容：旧版本读新备份时，
         * 不认识的类别不能当成「已排除」，否则会静默丢数据）。解析失败返回 null。
         */
        fun fromJsonOrNull(element: JsonElement?): BackupScope? = runCatching {
            val names: List<String> = element?.jsonObject?.get("excluded")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
            BackupScope(
                excluded = names.mapNotNull { name ->
                    BackupCategory.entries.firstOrNull { it.name == name }
                }.toSet(),
            )
        }.getOrNull()
    }
}
