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
        // 聊天辅助资产，与提示词预设/世界书同域（v4 起从 ABOUT 移入）
        "quickMessages",
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

    /** v4 起从 ABOUT 兜底拆出的细分类别键集（用户数据域）。 */
    private val SPEECH_KEYS = setOf(
        "ttsProviders",
        "removedTtsProviderIds",
        "selectedTTSProviderId",
        "defaultTTSPlaybackSpeed",
        "asrProviders",
        "selectedASRProviderId",
    )

    private val SEARCH_KEYS = setOf(
        "searchServices",
        "searchCommonOptions",
        "searchServiceSelected",
    )

    private val MCP_KEYS = setOf(
        "mcpServers",
        "mcpDynamicSchemaLoading",
    )

    private val REMOTE_BACKUP_KEYS = setOf(
        "webDavConfig",
        "s3Config",
        "uploadS3Config",
    )

    /** v4 起从 ABOUT 兜底拆出的细分类别键集（行为/服务域）。 */
    private val TOOLS_KEYS = setOf(
        "toolSchemaCompactLevel",
        "workspaceTurnBudgetChars",
        "workspaceTurnMaxSteps",
        "workspaceTaskMaxDurationMinutes",
        "phoneUseTurnMaxSteps",
        "subagentMaxConcurrentRuns",
        "loopGuardSensitivity",
        "loopGuardRepeatLimit",
        "ruleReminderEnabled",
        "ruleReminderSensitivity",
        "logToFileEnabled",
        "recordRequestsEnabled",
        "contextLengthOverride",
        "contextImageLimit",
    )

    private val MEMORY_KEYS = setOf(
        "memoryTriggerConfig",
        "memoryInjectBudgetTokens",
        "lorebookInjectBudgetTokens",
        "memoryConsolidationPrompt",
        "memoryConsolidationReasoningLevel",
    )

    private val TRANSLATE_KEYS = setOf(
        "translatePrompt",
        "translateThinkingBudget",
        "ocrPrompt",
        "ocrBackend",
        "ocrDeepSeekBaseUrl",
        "ocrDeepSeekPurpose",
        "ocrDeepSeekIncludeLayout",
        "ocrDeepSeekMaxPages",
    )

    private val WEB_SERVER_KEYS = setOf(
        "webServerEnabled",
        "webServerPort",
        "webServerJwtEnabled",
        "webServerAccessPassword",
        "webServerLocalhostOnly",
    )

    /**
     * JSON 属性名 → 类别；未列入显式清单的字段一律 [BackupCategory.ABOUT]（兜底）。
     *
     * @param legacy v3 及更早的包恢复时置 true：v4 拆出的细分类别键按 ABOUT 处理——
     *   旧包的「排除 ABOUT」语义下这些键本就该保留本机值，不能因新映射被恢复覆盖。
     *   打包侧恒为 false（新版按新语义写出）。
     */
    internal fun categoryOfSettingKey(name: String, legacy: Boolean = false): BackupCategory {
        val category = when (name) {
            in ASSISTANT_KEYS -> BackupCategory.ASSISTANTS
            in PROVIDER_KEYS -> BackupCategory.PROVIDERS
            in DISPLAY_KEYS -> BackupCategory.DISPLAY
            in SPEECH_KEYS -> BackupCategory.SPEECH
            in SEARCH_KEYS -> BackupCategory.SEARCH
            in MCP_KEYS -> BackupCategory.MCP
            in REMOTE_BACKUP_KEYS -> BackupCategory.REMOTE_BACKUP
            in TOOLS_KEYS -> BackupCategory.TOOLS
            in MEMORY_KEYS -> BackupCategory.MEMORY
            in TRANSLATE_KEYS -> BackupCategory.TRANSLATE
            in WEB_SERVER_KEYS -> BackupCategory.WEB_SERVER
            else -> BackupCategory.ABOUT
        }
        return if (legacy && category in V4_NEW_CATEGORIES) BackupCategory.ABOUT else category
    }

    private val BackupCategory.isSettingsCategory: Boolean
        get() = when (this) {
            BackupCategory.ASSISTANTS,
            BackupCategory.PROVIDERS,
            BackupCategory.DISPLAY,
            BackupCategory.SPEECH,
            BackupCategory.SEARCH,
            BackupCategory.MCP,
            BackupCategory.REMOTE_BACKUP,
            BackupCategory.TOOLS,
            BackupCategory.MEMORY,
            BackupCategory.TRANSLATE,
            BackupCategory.WEB_SERVER,
            BackupCategory.ABOUT,
            -> true
            else -> false
        }

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
        legacy: Boolean = false,
    ): JsonObject {
        if (scope.excluded.none { it.isSettingsCategory }) return incoming
        val merged = LinkedHashMap<String, JsonElement>()
        incoming.forEach { (key, value) ->
            if (scope.includes(categoryOfSettingKey(key, legacy))) merged[key] = value
        }
        current.forEach { (key, value) ->
            if (!scope.includes(categoryOfSettingKey(key, legacy))) merged[key] = value
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
