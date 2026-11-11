package app.androidrik.rikkahub.data.datastore

import android.content.Context
import app.androidrik.rikkahub.data.ai.account.ensureCatalogSessions
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Storage
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.androidrik.common.settings.refillBuiltIns
import app.androidrik.common.settings.resolveSelected
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.RetrySetting
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.ai.DEFAULT_LOREBOOK_BUDGET_TOKENS
import app.androidrik.rikkahub.data.ai.DEFAULT_MEMORY_BUDGET_TOKENS
import app.androidrik.rikkahub.data.ai.MEMORY_CONSOLIDATION_SYSTEM_PROMPT
import app.androidrik.rikkahub.data.ai.compression.DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT
import app.androidrik.rikkahub.data.files.DEFAULT_EXPORT_RETENTION_DAYS
import app.androidrik.rikkahub.data.export.ExportDisplayConfig
import app.androidrik.rikkahub.data.export.ExportDisplayScheme
import app.androidrik.rikkahub.data.ai.compression.DEFAULT_AUTO_COMPRESS_TARGET_TOKENS
import app.androidrik.rikkahub.data.ai.compression.CompressPreset
import app.androidrik.rikkahub.data.model.ImportSourceRecord
import app.androidrik.rikkahub.data.ai.compression.DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT
import app.androidrik.rikkahub.data.ai.mcp.McpServerConfig
import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import app.androidrik.asr.ASRProviderSetting
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV4Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV5Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV6Migration
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.mergeDefaultAssistantReferences
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.SubagentPreset
import app.androidrik.rikkahub.data.model.DEFAULT_SUBAGENT_PRESETS
import app.androidrik.rikkahub.data.model.asSinglePreset
import app.androidrik.rikkahub.data.model.QuickMessage
import app.androidrik.rikkahub.data.model.Tag
import app.androidrik.rikkahub.data.sync.s3.S3Config
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.toMutableStateFlow
import app.androidrik.search.SearchCommonOptions
import app.androidrik.search.SearchServiceOptions
import app.androidrik.tts.provider.TTSProviderSetting
import app.androidrik.tts.provider.withEnabled
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

/** 收藏既要保留顺序，也必须保证 ID 唯一，否则模型选择器的 LazyColumn 会因重复 key 崩溃。 */
internal fun normalizeFavoriteModelIds(ids: List<Uuid>, providers: List<ProviderSetting>): List<Uuid> {
    val modelIds = providers.flatMap { it.models }.map { it.id }.toSet()
    return ids.distinct().filter { it in modelIds }
}

/** 读取失败的重试上限（退避 100ms × 2^n）；超限后向上抛，绝不回退空配置。 */
private const val READ_MAX_RETRIES = 3

/**
 * 读取失败是否应重试（纯函数，便于单测钉住契约）。
 *
 * - 瞬时 IO 错误：重试到 [READ_MAX_RETRIES] 为止
 * - `CorruptionException`（文件真损坏）：**不重试**，交给 corruptionHandler 走备份+重建
 * - 其余异常：不重试，直接向上抛
 *
 * 关键不变量：**任何失败都不允许退化成「用户数据是空的」**——空 Preferences 会被
 * `update()` 当成用户数据整份写回，抹掉全部设置（上游 `70b382f5` 修的正是这条）。
 */
internal fun shouldRetrySettingsRead(cause: Throwable, attempt: Long): Boolean =
    cause is IOException && cause !is CorruptionException && attempt < READ_MAX_RETRIES

/**
 * 打开设置 DataStore（[file] 由调用方给出，便于 JVM 单测用临时文件驱动）。
 *
 * 相比 `preferencesDataStore` 委托，这里显式传 `corruptionHandler`——**文件真的损坏无法解析**时
 * 才能重建为空，且**先把原文件另存一份**（`.corrupt-<ts>`）供排查/人工抢救。
 * 这正是上游 `70b382f5` 的核心：把「文件损坏」与「瞬时读取失败」分开处理，
 * 后者由 [SettingsStore.settingsFlowRaw] 的重试负责。
 */
internal fun createSettingsDataStore(
    file: File,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    storage: Storage<Preferences>? = null,
    moveAssistantReferences: suspend () -> Unit,
): DataStore<Preferences> {
    val corruptionHandler = ReplaceFileCorruptionHandler<Preferences> { exception ->
        Log.e(TAG, "Settings datastore corrupted, resetting", exception)
        runCatching {
            file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
        }.onFailure {
            Log.e(TAG, "Failed to back up corrupted settings file", it)
        }
        emptyPreferences()
    }
    val migrations = listOf(
        PreferenceStoreV1Migration(),
        PreferenceStoreV2Migration(),
        PreferenceStoreV3Migration(),
        PreferenceStoreV4Migration(),
        PreferenceStoreV5Migration(),
        PreferenceStoreV6Migration(moveAssistantReferences),
    )
    // JVM tests inject a host-compatible real storage; Android retains the default FileStorage backend.
    return if (storage == null) PreferenceDataStoreFactory.create(
        corruptionHandler = corruptionHandler, migrations = migrations, scope = scope, produceFile = { file },
    ) else PreferenceDataStoreFactory.create(
        storage = storage, corruptionHandler = corruptionHandler, migrations = migrations, scope = scope,
    )
}

/**
 * 已退役的搜索提供方多态 `@SerialName`（类型名）。
 *
 * 列入这里 = 该提供方的实现与其 `SearchServiceOptions` 子类**已从 sealed 层删除**，
 * 因此存量 `search_services` JSON 里若还留着它，**必须在多态解码前剔除**（见 [loadSearchServices]）。
 *
 * 当前唯一一项 `rikkahub`：它指向 `https://api.rikka-ai.com/v1/search`，**该服务已不可用**，
 * 故整体移除（与聊天侧 `DefaultProviders.kt` 移除内置 `RikkaHub` 供应商是**同一个理由**，
 * 上游移除同类项也是同一个意思——不再提供该服务；不是「因为它是上游作者的服务」）。
 */
internal val RETIRED_SEARCH_SERVICE_TYPES: Set<String> = setOf("rikkahub")

/** [loadSearchServices] 的结果：过滤+解码后的服务列表，与校正过的选中索引。 */
internal data class SearchServicesLoadResult(
    val services: List<SearchServiceOptions>,
    val selected: Int,
)

/**
 * 加载 `search_services` 原始 JSON（**纯函数**：无 Android / DataStore 依赖，可直接单测）。
 *
 * **为什么必须先过滤再解码**：搜索提供方与聊天 provider 的持久化方式不同——聊天内置项
 * 根本不进 `providers` 键，每次加载由 `refillBuiltIns(existing, DEFAULT_PROVIDERS, 墓碑)`
 * 回填，所以删默认项不影响已存数据；而搜索提供方是**整份多态列表落盘**在 `search_services`
 * 单 key 里，直接 `decodeFromString`。已退役子类的 `@SerialName` 一旦从 sealed 层删掉，
 * 存量 JSON 会在解码时抛 `SerializationException` ⇒ **整份 settings 加载失败**。
 * 所以先按原始 JSON 把退役类型剔掉，再对剩余内容做多态解码。
 *
 * **索引语义**：[storedSelected] 是**完整列表索引**。剔除一个元素后，它之后的元素全部左移，
 * 因此被剔项在选中项**之前**时必须把索引减一，否则会静默切到**另一个**提供方
 * （而不是像天真的实现那样「少一个位置」）。最后统一 clamp 到合法范围
 * （[SettingsStore.sanitizeSearchServiceSelected]），同时覆盖「选中项本身被剔除」与越界。
 *
 * **失败即回退，绝不抛出**：畸形 JSON、剔除后为空、剩余内容解码失败，一律返回
 * `listOf(SearchServiceOptions.DEFAULT)` + 索引 0，与「该键不存在」时的行为一致。
 * 让异常冒出去正是本函数要修的问题（整份 settings 加载失败），不能再引入一次。
 */
internal fun loadSearchServices(raw: String?, storedSelected: Int): SearchServicesLoadResult {
    val fallback = SearchServicesLoadResult(listOf(SearchServiceOptions.DEFAULT), 0)
    if (raw == null) return fallback
    val filtered = runCatching { filterRetiredSearchServices(raw, storedSelected) }.getOrNull()
        ?: return fallback
    if (filtered.isEmpty) return fallback
    val decoded = runCatching {
        JsonInstant.decodeFromString<List<SearchServiceOptions>>(filtered.json)
    }.getOrNull() ?: return fallback
    if (decoded.isEmpty()) return fallback
    return SearchServicesLoadResult(
        services = decoded,
        selected = SettingsStore.sanitizeSearchServiceSelected(filtered.selected, decoded.size),
    )
}

/** [filterRetiredSearchServices] 的产物：剩余元素的 JSON 文本 + 左移后的索引。 */
private data class RetainedSearchServices(
    val json: String,
    val selected: Int,
    val isEmpty: Boolean,
)

/**
 * 从原始 JSON 里剔除 [RETIRED_SEARCH_SERVICE_TYPES] 的元素。
 * 解析失败（非 JSON 数组）返回 null，由 [loadSearchServices] 回退默认值。
 */
private fun filterRetiredSearchServices(raw: String, storedSelected: Int): RetainedSearchServices? {
    val array = JsonInstant.parseToJsonElement(raw) as? JsonArray ?: return null
    var removedBeforeSelected = 0
    val kept = buildList {
        array.forEachIndexed { index, element ->
            if (element.isRetiredSearchService()) {
                if (index < storedSelected) removedBeforeSelected++
            } else {
                add(element)
            }
        }
    }
    return RetainedSearchServices(
        json = JsonArray(kept).toString(),
        selected = storedSelected - removedBeforeSelected,
        isEmpty = kept.isEmpty(),
    )
}

/**
 * 多态 `type` 是否属于退役类型；非对象 / 缺 `type` / `type` 非字符串一律交给解码去判定。
 * `internal` 是因为备份恢复侧的 [app.androidrik.rikkahub.data.datastore.migration.SettingsJsonMigrator]
 * 也要剔除同一批类型（旧备份里可能还带着它们），两处共用同一份判定，避免各写一遍走偏。
 */
internal fun JsonElement.isRetiredSearchService(): Boolean {
    val type = (this as? JsonObject)
        ?.get("type")
        ?.let { (it as? JsonPrimitive)?.contentOrNull }
        ?: return false
    return type in RETIRED_SEARCH_SERVICE_TYPES
}

class SettingsStore(
    context: Context,
    scope: AppScope,
    /** Explicit store file for isolated test profiles; normal callers retain the existing settings file. */
    dataStoreFile: File = context.preferencesDataStoreFile("settings"),
) : KoinComponent {
    /**
     * 串行化 update(fn) 的读-改-写（D5）：并发调用基于同一快照做 fn 会 lost update
     * （MCP syncTools 并发丢工具列表根因）。注意：只锁 update 方法，勿动 settingsFlowRaw
     * 流链（turbo 曾把 Mutex 插进流链导致 205 个编译错误）。
     */
    private val updateMutex = Mutex()

    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        // 动态取色种子色(ARGB Long);null = 跟随壁纸。空串表示显式清除
        val DYNAMIC_COLOR_SEED = stringPreferencesKey("dynamic_color_seed")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        // 轻量个性化命名方案（分立：头像框参数 / 思维链样式各一份列表）
        val AVATAR_FRAME_SCHEMES = stringPreferencesKey("avatar_frame_schemes")
        val COT_STYLE_SCHEMES = stringPreferencesKey("cot_style_schemes")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val CATALOG_AUTO_REFRESH = booleanPreferencesKey("catalog_auto_refresh")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FALLBACK_CHAT_MODEL = stringPreferencesKey("fallback_chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val MEMORY_CONSOLIDATION_MODEL = stringPreferencesKey("memory_consolidation_model")
        val MEMORY_CONSOLIDATION_REASONING = stringPreferencesKey("memory_consolidation_reasoning")
        val MEMORY_CONSOLIDATION_PROMPT = stringPreferencesKey("memory_consolidation_prompt")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")

        /**
         * 自动生成标题（默认开）。
         *
         * 为什么需要这个开关：标题模型解析是 `titleModelId` → **回落 `fastModelId`**，
         * 所以想「关掉自动标题」在今天做不到——清空标题模型会回落到快速模型，
         * 把快速模型也清空会连带影响其它后台任务（建议、记忆整理）。
         */
        val ENABLE_AUTO_TITLE = booleanPreferencesKey("enable_auto_title")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 生成重试（全局默认，per-provider retryOption 覆盖；#1812 半截保留三字段同存）
        val RETRY_SETTING = stringPreferencesKey("retry_setting")
        // 已删除的内置提供商（墓碑：默认回填跳过）
        val REMOVED_BUILTIN_PROVIDERS = stringPreferencesKey("removed_builtin_providers")
        val REMOVED_ASSISTANTS = stringPreferencesKey("removed_assistants")
        val LEGACY_DEFAULT_ASSISTANT_MERGED = booleanPreferencesKey("legacy_default_assistant_merged")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")
        val FAVORITE_TAGS = stringPreferencesKey("favorite_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        /**
         * 搜索服务选中索引清洗：searchServices 为空（用户删光所有搜索服务）时
         * size-1 = -1，coerceIn(0, -1) 会抛 IllegalArgumentException，导致此后
         * 所有设置保存在磁盘写入前崩溃（内存已更新、磁盘没写 → 不一致）。
         */
        fun sanitizeSearchServiceSelected(index: Int, searchServiceCount: Int): Int =
            index.coerceIn(0, (searchServiceCount - 1).coerceAtLeast(0))

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")
        val MCP_DYNAMIC_SCHEMA_LOADING = booleanPreferencesKey("mcp_dynamic_schema_loading")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // 素材上传用桶：与备份桶完全独立，避免上传临时素材时覆盖/污染备份配置
        val UPLOAD_S3_CONFIG = stringPreferencesKey("upload_s3_config")

        // 备份内容项（全局：S3 / WebDAV / 本地共用同一份）
        val BACKUP_ITEMS = stringPreferencesKey("backup_items")

        // 媒体生成供应商（图片/视频；与聊天 providers 分开存：鉴权与能力枚举不同）
        val MEDIA_GENERATION_PROVIDERS = stringPreferencesKey("media_generation_providers")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        // 内置 TTS 提供商墓碑（用户显式删除过的不再回填；与聊天提供商同款）
        val REMOVED_TTS_PROVIDERS = stringPreferencesKey("removed_tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 记忆注入预算（token）：注入哪些记忆由打分校准决定，装多少由此预算决定
        val MEMORY_INJECT_BUDGET_TOKENS = intPreferencesKey("memory_inject_budget_tokens")

        // 世界书注入预算（token）：0 = 不限制（默认，保持旧行为）
        val LOREBOOK_INJECT_BUDGET_TOKENS = intPreferencesKey("lorebook_inject_budget_tokens")

        // 提示词注入
        /** 预设容器（新结构）。旧 key [MODE_INJECTIONS] 仅作**读取回退**，写入只落这里。 */
        val PROMPT_PRESETS = stringPreferencesKey("prompt_presets")
        /** @deprecated 旧扁平结构；仅用于读取回退，勿写入。 */
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 上下文窗口全局默认（模型无 contextLength/customContextLength 时的兜底，null=不覆盖走 256K）
        val CONTEXT_LENGTH_OVERRIDE = intPreferencesKey("context_length_override")

        // 自动压缩（超过阈值时把早期历史摘要为检查点，design-auto-compaction Phase 2）
        val AUTO_COMPRESS_ENABLED = booleanPreferencesKey("auto_compress_enabled")
        // peer ⑤ Tier 1 可配置化：护栏与预算（写死常量的可配置化）
        val LOOP_GUARD_SENSITIVITY = stringPreferencesKey("loop_guard_sensitivity")
        val LOOP_GUARD_REPEAT_LIMIT = intPreferencesKey("loop_guard_repeat_limit")
        // 规则提醒（探索/落地守卫）：默认开 + 三档，与 LoopGuard 同款形态
        val RULE_REMINDER_ENABLED = booleanPreferencesKey("rule_reminder_enabled")
        val RULE_REMINDER_SENSITIVITY = stringPreferencesKey("rule_reminder_sensitivity")
        val WORKSPACE_TURN_BUDGET_CHARS = intPreferencesKey("workspace_turn_budget_chars")
        val WORKSPACE_TURN_MAX_STEPS = intPreferencesKey("workspace_turn_max_steps")
        val PHONE_USE_TURN_MAX_STEPS = intPreferencesKey("phone_use_turn_max_steps")
        val SUBAGENT_MAX_CONCURRENT_RUNS = intPreferencesKey("subagent_max_concurrent_runs")
        val WORKSPACE_TASK_MAX_DURATION_MINUTES = intPreferencesKey("workspace_task_max_duration_minutes")
        val AUTO_COMPRESS_THRESHOLD_PERCENT = intPreferencesKey("auto_compress_threshold_percent")
        val AUTO_COMPRESS_RETAIN_PERCENT = intPreferencesKey("auto_compress_retain_percent")
        val EXPORT_RETENTION_DAYS = intPreferencesKey("export_retention_days")
        // 导出「提供商 / 模型」标签的全局配置与命名方案（设计见 data/export/ExportDisplayLabel.kt）
        val EXPORT_DISPLAY_CONFIG = stringPreferencesKey("export_display_config")
        val EXPORT_DISPLAY_SCHEMES = stringPreferencesKey("export_display_schemes")
        val AUTO_COMPRESS_TARGET_TOKENS = intPreferencesKey("auto_compress_target_tokens")
        val CONTEXT_IMAGE_LIMIT = intPreferencesKey("context_image_limit")
        // 自定义压缩配置档（用户命名、可增改删；单 key 存 JSON 列表，与 modeInjections 同款）
        val COMPRESS_PRESETS = stringPreferencesKey("compress_presets")
        // 导入来源记录（重复导入提示用；见 docs/design/design-conversation-import-export.md §十）
        val IMPORTED_SOURCES = stringPreferencesKey("imported_sources")
        // 对话内子代理派发（设计 §十）：开关 + 「按名称调度的模型+提示词」组合列表。
        // **必须进三件套**——它们是 Settings 的**顶层平铺字段**，不写进 DataStore 就只在内存里活着：
        // UI 能改、当前进程内生效，**一重启就回到默认值**（正是 2026-09-11 那条教训的形状）。
        val NOTICE_CARRIER = stringPreferencesKey("subagent_notice_carrier")
        val ENABLE_BACKGROUND_SUBAGENT = booleanPreferencesKey("enable_background_subagent")
        val WORKSPACE_TASK_NOTICE_ENABLED = booleanPreferencesKey("workspace_task_notice_enabled")
        /** P0 回滚开关（附录 C）：关 = 隐藏任务中心页，旧运行列表保留。 */
        val TASK_CENTER_ENABLED = booleanPreferencesKey("task_center_enabled")
        /** P0 回滚开关：关 = 子代理审批通知回旧固定 id/requestCode。 */
        val NAMESPACED_NOTIFICATIONS = booleanPreferencesKey("namespaced_notifications")
        /** P0 回滚开关：关 = 并发闸回到 `by lazy` 首读冻结。 */
        val DYNAMIC_RUN_GATE = booleanPreferencesKey("dynamic_run_gate")
        /** P0 回滚开关：关 = run 取消/超时不再级联收它起的 shell task。 */
        val KILL_SHELL_TASKS_ON_RUN_CANCEL = booleanPreferencesKey("kill_shell_tasks_on_run_cancel")
        /** P4b 可选执行路径：定时任务改为独立 headless run（默认关，回退为会话注入）。 */
        val SCHEDULED_HEADLESS_ENABLED = booleanPreferencesKey("scheduled_headless_enabled")
        val ENABLE_SUBAGENT_DISPATCH = booleanPreferencesKey("enable_subagent_dispatch")
        val SUBAGENT_PRESETS = stringPreferencesKey("subagent_presets")
        // 全局代理（两层代理设计的全局兜底层）；结构化 JSON 存储
        val GLOBAL_PROXY = stringPreferencesKey("global_proxy")
        // 订阅用量展示口径（已用 / 剩余）；只是展示层偏好，不改 ai 层语义
        val USAGE_DISPLAY_MODE = stringPreferencesKey("usage_display_mode")
        // 日志落盘（Log 页「导出到文件」开关；默认关，避免无谓写盘）
        val LOG_TO_FILE_ENABLED = booleanPreferencesKey("log_to_file_enabled")
        // 请求记录（Log 页「记录请求」开关；默认关。**必须持久化**——否则重启后
        // 「导出到文件」虽开着却收不到任何请求日志，是个静默失效的坑）
        val RECORD_REQUESTS_ENABLED = booleanPreferencesKey("record_requests_enabled")

    }

    @Volatile
    private var legacyReferencesMigrated = false

    private val dataStore = createSettingsDataStore(dataStoreFile) {
        get<AppDatabase>().mergeDefaultAssistantReferences()
    }

    // 读取失败**绝不能**回退为空配置：空 Preferences 随后会被 update() 当成用户数据写回，
    // 把全部设置抹成默认值（上游 70b382f5 修的就是这个，用户表现为「升级后设置全丢」）。
    // 瞬时 IO 错误退避重试，仍失败则向上抛；文件损坏由 corruptionHandler 单独兜底。
    // 注意：勿把 Mutex 插进本流链（turbo 曾如此导致 205 个编译错误）——retryWhen 无锁语义，安全。
    val settingsFlowRaw = dataStore.data
        .retryWhen { cause, attempt ->
            val shouldRetry = shouldRetrySettingsRead(cause, attempt)
            if (shouldRetry) {
                Log.w(TAG, "Failed to read settings, retrying (${attempt + 1}/$READ_MAX_RETRIES)", cause)
                delay((100L shl attempt.toInt()).milliseconds)
            }
            shouldRetry
        }
        .map { preferences ->
            // 搜索提供方是**持久化**的多态列表（`search_services` 单 key 存整份列表），
            // 已退役的类型必须在多态解码前剔除，否则存量 JSON 会让整份 settings 加载抛异常。
            // 详见 loadSearchServices 的 kdoc。
            val searchServicesLoad = loadSearchServices(
                raw = preferences[SEARCH_SERVICES],
                storedSelected = preferences[SEARCH_SELECTED] ?: 0,
            )
            Settings(
                catalogAutoRefresh = preferences[CATALOG_AUTO_REFRESH] ?: false,
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fallbackChatModelId = preferences[FALLBACK_CHAT_MODEL]?.let { Uuid.parse(it) },
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    // 旧版本「未配置」会落成 AUTO 哨兵（无清除入口时代的默认值），视为未配置
                    ?.takeIf { it != DEFAULT_AUTO_MODEL_ID },
                memoryConsolidationModelId = preferences[MEMORY_CONSOLIDATION_MODEL]?.let { Uuid.parse(it) },
                memoryConsolidationReasoningLevel = preferences[MEMORY_CONSOLIDATION_REASONING]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { JsonInstant.decodeFromString<ReasoningLevel>(it) }.getOrNull() },
                memoryConsolidationPrompt = preferences[MEMORY_CONSOLIDATION_PROMPT]
                    ?: MEMORY_CONSOLIDATION_SYSTEM_PROMPT,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) },
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                // 缺键 = 开（老数据零变化）
                enableAutoTitle = preferences[ENABLE_AUTO_TITLE] != false,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) },
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                // 空串哨兵 = 用户显式选择「跟随对话模型」；key 不存在 = 默认显式模型
                compressModelId = preferences[COMPRESS_MODEL]
                    ?.let { raw -> if (raw.isBlank()) null else Uuid.parse(raw) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                favoriteTags = preferences[FAVORITE_TAGS]?.let {
                    JsonInstant.decodeFromString<List<Tag>>(it)
                } ?: emptyList(),
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                removedBuiltInProviderIds = preferences.readRemovedIds(REMOVED_BUILTIN_PROVIDERS),
                removedTtsProviderIds = preferences.readRemovedIds(REMOVED_TTS_PROVIDERS),
                removedAssistantIds = preferences.readRemovedIds(REMOVED_ASSISTANTS),
                legacyDefaultAssistantMerged = preferences[LEGACY_DEFAULT_ASSISTANT_MERGED] ?: false,
                retrySetting = preferences[RETRY_SETTING]?.let { JsonInstant.decodeFromString(it) }
                    ?: RetrySetting(),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                dynamicColorSeedColor = preferences[DYNAMIC_COLOR_SEED]?.takeIf { it.isNotBlank() }?.toLongOrNull(),
                themeId = preferences[THEME_ID] ?: DEFAULT_THEME_ID,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                avatarFrameSchemes = preferences[AVATAR_FRAME_SCHEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                cotStyleSchemes = preferences[COT_STYLE_SCHEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                searchServices = searchServicesLoad.services,
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                // 索引随剔除元素左移并 clamp（见 loadSearchServices）；不能直接读原始值
                searchServiceSelected = searchServicesLoad.selected,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                mcpDynamicSchemaLoading = preferences[MCP_DYNAMIC_SCHEMA_LOADING] != false,
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                uploadS3Config = preferences[UPLOAD_S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                backupItems = preferences[BACKUP_ITEMS]?.let {
                    JsonInstant.decodeFromString<List<BackupItem>>(it)
                } ?: DEFAULT_BACKUP_ITEMS,
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                mediaGenerationProviders = preferences[MEDIA_GENERATION_PROVIDERS]?.let {
                    JsonInstant.decodeFromString<List<MediaGenerationProviderSetting>>(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                // 预设容器：新 key 优先；**旧 key 作回退**（把旧扁平列表包成一个预设），
                // 老用户原地可用、无需迁移器。写入只落新 key。
                // 解码后跑种子迁移（peer ⑤①）：内置条目的未改动旧副本自动升级到最新版。
                promptPresets = run {
                    val decodedPresets = preferences[PROMPT_PRESETS]?.let {
                        JsonInstant.decodeFromString<List<PromptPreset>>(it)
                    } ?: preferences[MODE_INJECTIONS]?.let {
                        listOf(
                            JsonInstant.decodeFromString<List<PromptInjection.ModeInjection>>(it)
                                .asSinglePreset()
                        )
                    } ?: listOf(DEFAULT_MODE_INJECTIONS.asSinglePreset())
                    migrateSeedPresetEntries(decodedPresets) ?: decodedPresets
                },
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                // 安全策略：Web 鉴权**恒开**，不再提供「不鉴权」模式。存量偏好里
                // `web_server_jwt_enabled=false`（旧版本默认值）也在这里被强制纠正，
                // 否则老用户升级后仍会以无鉴权方式暴露整个 Web API。
                webServerJwtEnabled = true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                // 安全策略：默认只听回环（`localhostOnly=true`）。用户仍可显式开启
                // 局域网模式，但旧偏好里缺失该 key 时不再默认暴露到 LAN。
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] ?: true,
                memoryInjectBudgetTokens = preferences[MEMORY_INJECT_BUDGET_TOKENS]
                    ?: DEFAULT_MEMORY_BUDGET_TOKENS,
                lorebookInjectBudgetTokens = preferences[LOREBOOK_INJECT_BUDGET_TOKENS]
                    ?: DEFAULT_LOREBOOK_BUDGET_TOKENS,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                contextLengthOverride = preferences[CONTEXT_LENGTH_OVERRIDE],
                autoCompressEnabled = preferences[AUTO_COMPRESS_ENABLED] ?: false,
                contextImageLimit = (preferences[CONTEXT_IMAGE_LIMIT] ?: DEFAULT_CONTEXT_IMAGE_LIMIT)
                    .coerceIn(0, 500),
                loopGuardSensitivity = preferences[LOOP_GUARD_SENSITIVITY]
                    ?: app.androidrik.rikkahub.data.ai.LoopGuardSensitivity.STANDARD.name,
                loopGuardRepeatLimit = (preferences[LOOP_GUARD_REPEAT_LIMIT] ?: 0).coerceIn(0, 20),
                ruleReminderEnabled = preferences[RULE_REMINDER_ENABLED] ?: true,
                ruleReminderSensitivity = preferences[RULE_REMINDER_SENSITIVITY]
                    ?: app.androidrik.rikkahub.data.ai.RuleReminderSensitivity.STANDARD.name,
                workspaceTurnBudgetChars = (preferences[WORKSPACE_TURN_BUDGET_CHARS]
                    ?: DEFAULT_WORKSPACE_TURN_BUDGET_CHARS).coerceIn(32_000, 2_000_000),
                workspaceTurnMaxSteps = (preferences[WORKSPACE_TURN_MAX_STEPS]
                    ?: DEFAULT_WORKSPACE_TURN_MAX_STEPS).coerceIn(8, 512),
                phoneUseTurnMaxSteps = (preferences[PHONE_USE_TURN_MAX_STEPS]
                    ?: DEFAULT_PHONE_USE_TURN_MAX_STEPS).coerceIn(5, 200),
                subagentMaxConcurrentRuns = (preferences[SUBAGENT_MAX_CONCURRENT_RUNS]
                    ?: DEFAULT_SUBAGENT_MAX_CONCURRENT_RUNS).coerceIn(1, 8),
                workspaceTaskMaxDurationMinutes = (preferences[WORKSPACE_TASK_MAX_DURATION_MINUTES]
                    ?: DEFAULT_WORKSPACE_TASK_MAX_DURATION_MINUTES).coerceIn(5, 1440),
                logToFileEnabled = preferences[LOG_TO_FILE_ENABLED] ?: false,
                globalProxy = preferences[GLOBAL_PROXY]?.let {
                    JsonInstant.decodeFromString<GlobalProxySetting>(it)
                } ?: GlobalProxySetting(),
                usageDisplayMode = preferences[USAGE_DISPLAY_MODE]?.let {
                    runCatching { UsageDisplayMode.valueOf(it) }.getOrNull()
                } ?: UsageDisplayMode.USED,
                recordRequestsEnabled = preferences[RECORD_REQUESTS_ENABLED] ?: false,
                autoCompressThresholdPercent = preferences[AUTO_COMPRESS_THRESHOLD_PERCENT]
                    ?: DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
                autoCompressRetainPercent = preferences[AUTO_COMPRESS_RETAIN_PERCENT]
                    ?: DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT,
                exportRetentionDays = preferences[EXPORT_RETENTION_DAYS]
                    ?: DEFAULT_EXPORT_RETENTION_DAYS,
                exportDisplayConfig = preferences[EXPORT_DISPLAY_CONFIG]?.let {
                    JsonInstant.decodeFromString<ExportDisplayConfig>(it)
                } ?: ExportDisplayConfig(),
                exportDisplaySchemes = preferences[EXPORT_DISPLAY_SCHEMES]?.let {
                    JsonInstant.decodeFromString<List<ExportDisplayScheme>>(it)
                } ?: emptyList(),
                autoCompressTargetTokens = preferences[AUTO_COMPRESS_TARGET_TOKENS]
                    ?: DEFAULT_AUTO_COMPRESS_TARGET_TOKENS,
                compressPresets = preferences[COMPRESS_PRESETS]?.let {
                    JsonInstant.decodeFromString<List<CompressPreset>>(it)
                } ?: emptyList(),
                importedSources = preferences[IMPORTED_SOURCES]?.let {
                    JsonInstant.decodeFromString<Map<String, ImportSourceRecord>>(it)
                } ?: emptyMap(),
                noticeCarrier = app.androidrik.rikkahub.data.task.NoticeCarrier.fromStorage(preferences[NOTICE_CARRIER]),
                enableBackgroundSubagent = preferences[ENABLE_BACKGROUND_SUBAGENT] ?: false,
                workspaceTaskNoticeEnabled = preferences[WORKSPACE_TASK_NOTICE_ENABLED] ?: false,
                enableSubagentDispatch = preferences[ENABLE_SUBAGENT_DISPATCH] ?: false,
                taskCenterEnabled = preferences[TASK_CENTER_ENABLED] ?: true,
                namespacedNotifications = preferences[NAMESPACED_NOTIFICATIONS] ?: true,
                dynamicRunGate = preferences[DYNAMIC_RUN_GATE] ?: true,
                killShellTasksOnRunCancel = preferences[KILL_SHELL_TASKS_ON_RUN_CANCEL] ?: true,
                scheduledHeadlessEnabled = preferences[SCHEDULED_HEADLESS_ENABLED] ?: false,
                subagentPresets = preferences[SUBAGENT_PRESETS]?.let {
                    JsonInstant.decodeFromString<List<SubagentPreset>>(it)
                } ?: DEFAULT_SUBAGENT_PRESETS,
            )
        }
        .map {
            // 内置提供商回填（墓碑感知）——收编进共享 refillBuiltIns（原为内联 forEach，
            // 与 assistants/TTS 两套形态不一致；`ifEmpty { DEFAULT }` 复活陷阱见
            // Enableable.refillBuiltIns 的 kdoc，此处从无 ifEmpty、保持如此）。
            val providers = refillBuiltIns(
                it.providers,
                DEFAULT_PROVIDERS,
                it.removedBuiltInProviderIds,
            ) { provider -> provider.copyProvider() }
                .map { provider ->
                    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                    if (defaultProvider != null) {
                        provider.copyProvider(
                            builtIn = defaultProvider.builtIn,
                            description = defaultProvider.description,
                            shortDescription = defaultProvider.shortDescription,
                        )
                    } else provider
                }
            // 内置助手回填——**不能再写 `ifEmpty { DEFAULT_ASSISTANTS }`**：
            // 内置项被删光（墓碑齐全）时列表本就该空，ifEmpty 会把墓碑项整体复活。
            val assistants = it.assistants.toMutableList()
            refillDefaultAssistants(assistants, it.removedAssistantIds)
            // 内置 TTS 提供商回填（墓碑感知）——注意**不能再写 `ifEmpty { DEFAULT }`**：
            // 用户把内置项全删光后列表本就为空，那样会整体复活，删除等于没生效。
            val ttsProviders = it.ttsProviders.toMutableList()
            refillDefaultTtsProviders(ttsProviders, it.removedTtsProviderIds)
            // 系统 TTS 兜底：不允许「一个启用的都不剩」
            val normalizedTtsProviders = ttsProviders.ensureFallbackTtsEnabled()
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = normalizedTtsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Account -> provider.copyProvider(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = normalizeFavoriteModelIds(settings.favoriteModels, settings.providers),
                promptPresets = settings.promptPresets
                    .distinctBy { it.id }
                    // 预设内的条目也要去重（同 id 重复会让同一注入生效两次）
                    .map { preset -> preset.copy(entries = preset.entries.distinctBy { it.id }) },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        .map { settings ->
            // Backup databases are staged until restart, after settings have already been restored.
            // Replay the idempotent reference migration on the first read of each process as well.
            if (settings.legacyDefaultAssistantMerged && !legacyReferencesMigrated) {
                get<AppDatabase>().mergeDefaultAssistantReferences()
                legacyReferencesMigrated = true
            }
            settings.copy(providers = settings.providers.ensureCatalogSessions(legacy = true))
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }
        // 把上面**两段 map**（Preferences→Settings 的逐字段解码/JSON 反序列化 + 内置回填 +
        // 对 providers/assistants/注入/预设等逐个 `distinctBy` 规整）移出主线程。
        //
        // 为什么需要：`SettingsStore` 的 scope 是 `AppScope` = **Dispatchers.Main**，
        // `settingsFlow` 用 `toMutableStateFlow(scope, …)` 在里面 `collect`（见 CoroutineUtils）
        // ⇒ 不指定 dispatcher 时**整条上游映射链都在主线程跑**，冷启动与每次设置变化都要付一次。
        // fork 的映射比上游更重（多组 distinctBy 是 fork 自己的悬空引用过滤定制）。
        //
        // 放这里（而不是每条流各加）：`settingsFlowRaw` 的所有消费者（`settingsFlow` 的收集、
        // 各处的 `settingsFlowRaw.first()`、两处独立 `collect`）都只是**接收**映射结果，
        // 把上游计算移到 Default 不改变它们的语义；`settingsFlow` 自己的
        // `distinctUntilChanged()` 与 `MutableStateFlow.value =` 仍在收集方上下文执行。
        //
        // ⚠️ 数据流的既有约束别破：**勿把 Mutex 插进本流链**（turbo 前车之鉴，见上方注释）。
        .flowOn(Dispatchers.Default)

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    /**
     * 启动次数 +1，**只原子地改一个 key**。
     *
     * 不能用 `update(settingsFlowRaw.first().copy(launchCount = …))`：那会读整份快照再写回
     * 全部 key——启动时机极早（`Application.onCreate`），此时读链若尚未产出真实数据
     * （或曾因读失败而给出空配置），整份写回就把用户设置抹成默认值（上游 `70b382f5`）。
     * `edit` 是 DataStore 的原子读-改-写，只碰这一个 key，与其余设置无关。
     *
     * @return 自增后的启动次数（供日志）
     */
    suspend fun incrementLaunchCount(): Int {
        var count = 0
        dataStore.edit { preferences ->
            count = (preferences[LAUNCH_COUNT] ?: 0) + 1
            preferences[LAUNCH_COUNT] = count
        }
        return count
    }

    suspend fun update(settings: Settings) {
        updateMutex.withLock {
            updateLocked(settings)
        }
    }

    private suspend fun updateLocked(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        // 安全策略：Web 鉴权**恒开**。备份恢复 / 同步 / 增量更新都可能把存量
        // `webServerJwtEnabled=false` 带进来，这里在广播与落盘前统一纠正——否则
        // settingsFlow 会短暂出现「不鉴权」快照，而 WebApiModule 只在服务启动时读一次。
        val corrected = settings.mergeLegacyDefaultAssistant {
            get<AppDatabase>().mergeDefaultAssistantReferences()
        }.copy(
            webServerJwtEnabled = true,
            providers = settings.providers.ensureCatalogSessions(),
            favoriteModels = normalizeFavoriteModelIds(settings.favoriteModels, settings.providers),
        )
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[DYNAMIC_COLOR_SEED] = settings.dynamicColorSeedColor?.toString() ?: ""
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)
            preferences[AVATAR_FRAME_SCHEMES] = JsonInstant.encodeToString(settings.avatarFrameSchemes)
            preferences[COT_STYLE_SCHEMES] = JsonInstant.encodeToString(settings.cotStyleSchemes)

            preferences[CATALOG_AUTO_REFRESH] = settings.catalogAutoRefresh
            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(corrected.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            settings.fallbackChatModelId?.let {
                preferences[FALLBACK_CHAT_MODEL] = it.toString()
            } ?: preferences.remove(FALLBACK_CHAT_MODEL)
            settings.fastModelId?.let {
                preferences[FAST_MODEL] = it.toString()
            } ?: preferences.remove(FAST_MODEL)
            settings.memoryConsolidationModelId?.let {
                preferences[MEMORY_CONSOLIDATION_MODEL] = it.toString()
            } ?: preferences.remove(MEMORY_CONSOLIDATION_MODEL)
            settings.memoryConsolidationReasoningLevel?.let {
                preferences[MEMORY_CONSOLIDATION_REASONING] = JsonInstant.encodeToString(it)
            } ?: preferences.remove(MEMORY_CONSOLIDATION_REASONING)
            preferences[MEMORY_CONSOLIDATION_PROMPT] = settings.memoryConsolidationPrompt
            settings.titleModelId?.let {
                preferences[TITLE_MODEL] = it.toString()
            } ?: preferences.remove(TITLE_MODEL)
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
            preferences[ENABLE_AUTO_TITLE] = settings.enableAutoTitle
            settings.suggestionModelId?.let {
                preferences[SUGGESTION_MODEL] = it.toString()
            } ?: preferences.remove(SUGGESTION_MODEL)
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId.toString()
            preferences[OCR_PROMPT] = settings.ocrPrompt
            preferences[COMPRESS_MODEL] = settings.compressModelId?.toString() ?: ""
            preferences[COMPRESS_PROMPT] = settings.compressPrompt

            preferences[PROVIDERS] = JsonInstant.encodeToString(corrected.providers)
            preferences.writeRemovedIds(REMOVED_BUILTIN_PROVIDERS, settings.removedBuiltInProviderIds)
            preferences.writeRemovedIds(REMOVED_ASSISTANTS, corrected.removedAssistantIds)
            preferences[LEGACY_DEFAULT_ASSISTANT_MERGED] = corrected.legacyDefaultAssistantMerged
            preferences.writeRemovedIds(REMOVED_TTS_PROVIDERS, settings.removedTtsProviderIds)
            preferences[RETRY_SETTING] = JsonInstant.encodeToString(settings.retrySetting)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(corrected.assistants)
            preferences[SELECT_ASSISTANT] = corrected.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)
            preferences[FAVORITE_TAGS] = JsonInstant.encodeToString(settings.favoriteTags)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = sanitizeSearchServiceSelected(settings.searchServiceSelected, settings.searchServices.size)

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            preferences[MCP_DYNAMIC_SCHEMA_LOADING] = settings.mcpDynamicSchemaLoading
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            preferences[UPLOAD_S3_CONFIG] = JsonInstant.encodeToString(settings.uploadS3Config)
            preferences[BACKUP_ITEMS] = JsonInstant.encodeToString(settings.backupItems)
            preferences[MEDIA_GENERATION_PROVIDERS] =
                JsonInstant.encodeToString(settings.mediaGenerationProviders)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            settings.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)
            preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
            preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
            settings.selectedASRProviderId?.let {
                preferences[SELECTED_ASR_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_ASR_PROVIDER)
            preferences[PROMPT_PRESETS] = JsonInstant.encodeToString(settings.promptPresets)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            // 安全策略：Web 鉴权恒开，不落盘 false（读路径也会强制纠正）。
            preferences[WEB_SERVER_JWT_ENABLED] = true
            preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[MEMORY_INJECT_BUDGET_TOKENS] = settings.memoryInjectBudgetTokens
            preferences[LOREBOOK_INJECT_BUDGET_TOKENS] = settings.lorebookInjectBudgetTokens
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            // 防回退：整份快照可能早于 incrementLaunchCount()，取盘内现值与新值的较大者
            preferences[LAUNCH_COUNT] = maxOf(settings.launchCount, preferences[LAUNCH_COUNT] ?: 0)
            settings.contextLengthOverride?.let {
                preferences[CONTEXT_LENGTH_OVERRIDE] = it
            } ?: preferences.remove(CONTEXT_LENGTH_OVERRIDE)
            preferences[AUTO_COMPRESS_ENABLED] = settings.autoCompressEnabled
            preferences[CONTEXT_IMAGE_LIMIT] = settings.contextImageLimit
            preferences[LOOP_GUARD_SENSITIVITY] = settings.loopGuardSensitivity
            preferences[LOOP_GUARD_REPEAT_LIMIT] = settings.loopGuardRepeatLimit.coerceIn(0, 20)
            preferences[RULE_REMINDER_ENABLED] = settings.ruleReminderEnabled
            preferences[RULE_REMINDER_SENSITIVITY] = settings.ruleReminderSensitivity
            preferences[WORKSPACE_TURN_BUDGET_CHARS] = settings.workspaceTurnBudgetChars
            preferences[WORKSPACE_TURN_MAX_STEPS] = settings.workspaceTurnMaxSteps
            preferences[PHONE_USE_TURN_MAX_STEPS] = settings.phoneUseTurnMaxSteps
            preferences[SUBAGENT_MAX_CONCURRENT_RUNS] = settings.subagentMaxConcurrentRuns
            preferences[WORKSPACE_TASK_MAX_DURATION_MINUTES] = settings.workspaceTaskMaxDurationMinutes
            preferences[AUTO_COMPRESS_THRESHOLD_PERCENT] = settings.autoCompressThresholdPercent
            preferences[AUTO_COMPRESS_RETAIN_PERCENT] = settings.autoCompressRetainPercent
            preferences[EXPORT_RETENTION_DAYS] = settings.exportRetentionDays
            preferences[EXPORT_DISPLAY_CONFIG] = JsonInstant.encodeToString(settings.exportDisplayConfig)
            preferences[EXPORT_DISPLAY_SCHEMES] = JsonInstant.encodeToString(settings.exportDisplaySchemes)
            preferences[AUTO_COMPRESS_TARGET_TOKENS] = settings.autoCompressTargetTokens
            preferences[COMPRESS_PRESETS] = JsonInstant.encodeToString(settings.compressPresets)
            preferences[IMPORTED_SOURCES] = JsonInstant.encodeToString(settings.importedSources)
            preferences[LOG_TO_FILE_ENABLED] = settings.logToFileEnabled
            preferences[GLOBAL_PROXY] = JsonInstant.encodeToString(settings.globalProxy)
            preferences[USAGE_DISPLAY_MODE] = settings.usageDisplayMode.name
            preferences[RECORD_REQUESTS_ENABLED] = settings.recordRequestsEnabled
            preferences[NOTICE_CARRIER] = settings.noticeCarrier.storageValue
            preferences[ENABLE_BACKGROUND_SUBAGENT] = settings.enableBackgroundSubagent
            preferences[WORKSPACE_TASK_NOTICE_ENABLED] = settings.workspaceTaskNoticeEnabled
            preferences[ENABLE_SUBAGENT_DISPATCH] = settings.enableSubagentDispatch
            preferences[TASK_CENTER_ENABLED] = settings.taskCenterEnabled
            preferences[NAMESPACED_NOTIFICATIONS] = settings.namespacedNotifications
            preferences[DYNAMIC_RUN_GATE] = settings.dynamicRunGate
            preferences[KILL_SHELL_TASKS_ON_RUN_CANCEL] = settings.killShellTasksOnRunCancel
            preferences[SCHEDULED_HEADLESS_ENABLED] = settings.scheduledHeadlessEnabled
            preferences[SUBAGENT_PRESETS] = JsonInstant.encodeToString(settings.subagentPresets)
        }
        // 落盘成功后再广播内存态：edit 抛异常（序列化失败等）时内存与磁盘不分叉
        settingsFlow.value = corrected
    }

    suspend fun update(fn: (Settings) -> Settings) {
        updateMutex.withLock {
            updateLocked(fn(settingsFlow.value))
        }
    }

    /**
     * **定点改写头像引用**（`file://` 旧地址 → 新地址），不动整份 `Settings`。
     *
     * ## 为什么不能用 `update { }`
     *
     * `update(Settings)` 开头有一道守卫：`if (settings.init) return`（不许把 `Settings.dummy()`
     * 写回）。而头像迁移（[app.androidrik.rikkahub.data.files.AvatarMigration]）在**启动极早期**运行，
     * 此时 `settingsFlow.value` 仍是 `Settings.dummy()`（`init = true`）⇒ 走 `update { }` 的改写会被
     * **静默丢弃**（只留一行 "Cannot update dummy settings" 日志）。
     *
     * 真机实证（2026-10-13）：迁移把 5 个头像从 `upload/` 搬到了 `avatars/`，但引用一条都没改，
     * 于是头像引用全部悬空、助手头像显示不出来。诊断日志当时打出 `init=true` 才定位到这条。
     *
     * 这里改用 `dataStore.edit`（**原子读-改-写**，与 [incrementLaunchCount] 同一思路）：
     * 只碰 `assistants` 与 `display_setting` 两个键，且**以盘上的当前值为基准**做替换，
     * 因此既不依赖 `settingsFlow` 是否已就绪，也不会把别的设置覆盖成旧值。
     *
     * 与增量更新（[updateIncremental]）的分工：那个走整份 `Settings` 快照、有守卫；
     * 这个只做字符串级替换，专门服务「读链未就绪但必须落盘」的迁移场景。
     */
    suspend fun rewriteAvatarReferences(urlMap: Map<String, String>) {
        if (urlMap.isEmpty()) return
        updateMutex.withLock {
            dataStore.edit { preferences ->
                // 助手头像
                preferences[ASSISTANTS]?.let { raw ->
                    runCatching {
                        val assistants = JsonInstant.decodeFromString<List<Assistant>>(raw)
                        val rewritten = assistants.map { assistant ->
                            val img = assistant.avatar as? Avatar.Image ?: return@map assistant
                            urlMap[img.url]?.let { assistant.copy(avatar = Avatar.Image(it)) } ?: assistant
                        }
                        if (rewritten != assistants) {
                            preferences[ASSISTANTS] = JsonInstant.encodeToString(rewritten)
                        }
                    }.onFailure { Log.w(TAG, "rewriteAvatarReferences: assistants parse failed", it) }
                }
                // 用户头像
                preferences[DISPLAY_SETTING]?.let { raw ->
                    runCatching {
                        val display = JsonInstant.decodeFromString<DisplaySetting>(raw)
                        val img = display.userAvatar as? Avatar.Image
                        val newUrl = img?.let { urlMap[it.url] }
                        if (newUrl != null) {
                            preferences[DISPLAY_SETTING] =
                                JsonInstant.encodeToString(display.copy(userAvatar = Avatar.Image(newUrl)))
                        }
                    }.onFailure { Log.w(TAG, "rewriteAvatarReferences: displaySetting parse failed", it) }
                }
            }
        }
    }

    /**
     * 定点改写各助手的**聊天背景** `file://` 引用（[BackgroundsMigration] 用）。
     * 与 [rewriteAvatarReferences] 同款：字符串级替换，不走 `update { }` 的快照守卫
     * （迁移在启动极早期跑，快照还是 dummy）。
     */
    suspend fun rewriteAssistantBackgroundReferences(urlMap: Map<String, String>) {
        if (urlMap.isEmpty()) return
        updateMutex.withLock {
            dataStore.edit { preferences ->
                preferences[ASSISTANTS]?.let { raw ->
                    runCatching {
                        val assistants = JsonInstant.decodeFromString<List<Assistant>>(raw)
                        val rewritten = assistants.map { assistant ->
                            val newUrl = assistant.background?.let { urlMap[it] }
                            if (newUrl != null) assistant.copy(background = newUrl) else assistant
                        }
                        if (rewritten != assistants) {
                            preferences[ASSISTANTS] = JsonInstant.encodeToString(rewritten)
                        }
                    }.onFailure { Log.w(TAG, "rewriteAssistantBackgroundReferences: assistants parse failed", it) }
                }
            }
        }
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

/** 订阅用量窗口的展示口径，见 [Settings.usageDisplayMode]。 */
/** 墓碑集合读：JSON 字符串数组 → Uuid 集（与 [writeRemovedIds] 成对；**pref key 与 JSON 结构一个都不能动**——存量数据兼容硬约束）。 */
private fun Preferences.readRemovedIds(key: Preferences.Key<String>): Set<Uuid> =
    this[key]?.let {
        JsonInstant.decodeFromString<List<String>>(it).map { raw -> Uuid.parse(raw) }.toSet()
    } ?: emptySet()

/** 墓碑集合写：Uuid 集 → JSON 字符串数组。 */
private fun MutablePreferences.writeRemovedIds(key: Preferences.Key<String>, ids: Set<Uuid>) {
    this[key] = JsonInstant.encodeToString(ids.map { it.toString() })
}
