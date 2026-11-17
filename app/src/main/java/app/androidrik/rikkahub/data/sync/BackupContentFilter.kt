package app.androidrik.rikkahub.data.sync

import app.androidrik.rikkahub.data.datastore.Settings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * settings.json 的键级备份过滤（借鉴 Kelivo 的 `backup_content_filter`）：
 * 把 `Settings` 的 JSON 属性名映射到 [BackupCategory]，驱动「打包时剔除被排除类别」
 * 与「恢复时被排除类别保留本机值」两端。
 *
 * **为何在 JSON 层做而不是 typed copy**：`Settings` 上百个字段，typed 级过滤要逐字段
 * copy（每加一个字段漏一处就静默丢数据）；JSON 层按属性名集合过滤，新增字段默认落进
 * [BackupCategory.ABOUT]（兜底类别），无需同步维护过滤代码。
 *
 * **映射原则**（与本仓设置页的分组习惯对齐）：
 * - [BackupCategory.ASSISTANTS]：助手列表/当前助手/墓碑/标签 + 助手行为资产（提示词预设、世界书）；
 * - [BackupCategory.PROVIDERS]：提供商列表/墓碑 + 各用途模型 id（模型 id 引用提供商的模型，
 *   与提供商同进退，避免「只恢复模型 id 却没恢复提供商」的悬空引用）；
 * - [BackupCategory.DISPLAY]：主题/取色/展示样式（换设备最常想要、也最常不想覆盖的一类）；
 * - [BackupCategory.ABOUT]：**默认兜底**——未列入显式清单的字段（远程备份配置、服务器、
 *   各类行为开关等）全部归此类。
 */
internal object BackupContentFilter {
    private val ASSISTANT_KEYS = setOf(
        "assistants",
        "assistantId",
        "removedAssistantIds",
        "assistantTags",
        "legacyDefaultAssistantMerged",
        "promptPresets",
        "lorebooks",
    )

    private val PROVIDER_KEYS = setOf(
        "providers",
        "removedBuiltInProviderIds",
        "catalogAutoRefresh",
        "favoriteModels",
        "chatModelId",
        "fallbackChatModelId",
        "fastModelId",
        "titleModelId",
        "suggestionModelId",
        "ocrModelId",
        "ocrDeepSeekProviderId",
        "imageGenerationModelId",
        "translateModeId",
        "compressModelId",
        "memoryConsolidationModelId",
        "retrySetting",
        "globalProxy",
    )

    private val DISPLAY_KEYS = setOf(
        "dynamicColor",
        "dynamicColorSeedColor",
        "themeId",
        "customThemes",
        "displaySetting",
        "avatarFrameSchemes",
        "cotStyleSchemes",
        "usageDisplayMode",
        "exportDisplayConfig",
        "exportDisplaySchemes",
    )

    /** JSON 属性名 → 类别；未列入显式清单的字段一律 [BackupCategory.ABOUT]（兜底）。 */
    internal fun categoryOfSettingKey(name: String): BackupCategory = when (name) {
        in ASSISTANT_KEYS -> BackupCategory.ASSISTANTS
        in PROVIDER_KEYS -> BackupCategory.PROVIDERS
        in DISPLAY_KEYS -> BackupCategory.DISPLAY
        else -> BackupCategory.ABOUT
    }

    private val BackupCategory.isSettingsCategory: Boolean
        get() = this == BackupCategory.ASSISTANTS ||
            this == BackupCategory.PROVIDERS ||
            this == BackupCategory.DISPLAY ||
            this == BackupCategory.ABOUT

    /**
     * 打包侧：剔除 scope 排除的类别键（纯函数）。
     * 调用点：`BackupArchiveEngine.prepareBackupFileLocked` 序列化 settings.json 之前。
     */
    internal fun filterSettingsJson(settings: JsonObject, scope: BackupScope): JsonObject {
        if (scope.excluded.none { it.isSettingsCategory }) return settings
        return JsonObject(settings.filterKeys { key -> scope.includes(categoryOfSettingKey(key)) })
    }

    /**
     * 恢复侧：scope 排除的类别保留 `current`（本机）值，其余取 `incoming`（纯函数）。
     *
     * 必须在 `settingsStore.update { }` 的 transform 内用 transform 给的 `current` 计算
     * （单向数据流，见 AGENTS.md TextField/状态流规则；禁止读 `settings.value` 锁外改整对象）。
     * incoming 中属于被排除类别的键也一律丢弃——打包侧本不该写出它们，出现即视为陈旧数据。
     */
    internal fun preserveUnselected(
        incoming: JsonObject,
        current: JsonObject,
        scope: BackupScope,
    ): JsonObject {
        if (scope.excluded.none { it.isSettingsCategory }) return incoming
        val merged = LinkedHashMap<String, JsonElement>()
        incoming.forEach { (key, value) ->
            if (scope.includes(categoryOfSettingKey(key))) merged[key] = value
        }
        current.forEach { (key, value) ->
            if (!scope.includes(categoryOfSettingKey(key))) merged[key] = value
        }
        return JsonObject(merged)
    }

    /**
     * merge 模式的 settings 合并：providers/assistants 按 id 幂等合并（本地已有的保留，
     * 备份多出的追加到尾部），其余字段取 incoming（受 scope 的 preserveUnselected 约束，
     * 由调用方先行处理）。纯函数。
     */
    internal fun mergeSettingsById(incoming: Settings, current: Settings): Settings = incoming.copy(
        providers = mergeById(current.providers, incoming.providers) { it.id },
        assistants = mergeById(current.assistants, incoming.assistants) { it.id },
    )

    /** 通用按 id 幂等合并：本地在前（UI 列表顺序语义），备份多出的追加在尾。 */
    internal fun <T> mergeById(current: List<T>, incoming: List<T>, id: (T) -> Uuid): List<T> {
        if (incoming.isEmpty()) return current
        val existingIds = current.mapTo(HashSet()) { id(it) }
        return current + incoming.filter { id(it) !in existingIds }
    }
}
