package app.androidrik.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
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
import androidx.datastore.preferences.preferencesDataStoreFile
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
import app.androidrik.rikkahub.data.ai.compression.DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT
import app.androidrik.rikkahub.data.files.DEFAULT_EXPORT_RETENTION_DAYS
import app.androidrik.rikkahub.data.ai.compression.DEFAULT_AUTO_COMPRESS_TARGET_TOKENS
import app.androidrik.rikkahub.data.ai.compression.CompressPreset
import app.androidrik.rikkahub.data.model.ImportSourceRecord
import app.androidrik.rikkahub.data.ai.compression.DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT
import app.androidrik.rikkahub.data.ai.mcp.McpServerConfig
import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import app.androidrik.asr.ASRProviderSetting
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import app.androidrik.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
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
import app.androidrik.rikkahub.ui.theme.CustomTheme
import app.androidrik.rikkahub.ui.theme.PresetThemes
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
internal fun createSettingsDataStore(file: File): DataStore<Preferences> {
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            Log.e(TAG, "Settings datastore corrupted, resetting", exception)
            runCatching {
                file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }.onFailure {
                Log.e(TAG, "Failed to back up corrupted settings file", it)
            }
            emptyPreferences()
        },
        migrations = listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration()
        ),
        produceFile = { file },
    )
}

class SettingsStore(
    context: Context,
    scope: AppScope,
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
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FALLBACK_CHAT_MODEL = stringPreferencesKey("fallback_chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
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

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // 备份内容项（全局：S3 / WebDAV / 本地共用同一份）
        val BACKUP_ITEMS = stringPreferencesKey("backup_items")

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
        val WORKSPACE_TURN_BUDGET_CHARS = intPreferencesKey("workspace_turn_budget_chars")
        val WORKSPACE_TURN_MAX_STEPS = intPreferencesKey("workspace_turn_max_steps")
        val SUBAGENT_MAX_CONCURRENT_RUNS = intPreferencesKey("subagent_max_concurrent_runs")
        val WORKSPACE_TASK_MAX_DURATION_MINUTES = intPreferencesKey("workspace_task_max_duration_minutes")
        val AUTO_COMPRESS_THRESHOLD_PERCENT = intPreferencesKey("auto_compress_threshold_percent")
        val AUTO_COMPRESS_RETAIN_PERCENT = intPreferencesKey("auto_compress_retain_percent")
        val EXPORT_RETENTION_DAYS = intPreferencesKey("export_retention_days")
        val AUTO_COMPRESS_TARGET_TOKENS = intPreferencesKey("auto_compress_target_tokens")
        // 自定义压缩配置档（用户命名、可增改删；单 key 存 JSON 列表，与 modeInjections 同款）
        val COMPRESS_PRESETS = stringPreferencesKey("compress_presets")
        // 导入来源记录（重复导入提示用；见 docs/design-conversation-import-export.md §十）
        val IMPORTED_SOURCES = stringPreferencesKey("imported_sources")
        // 对话内子代理派发（设计 §十）：开关 + 「按名称调度的模型+提示词」组合列表。
        // **必须进三件套**——它们是 Settings 的**顶层平铺字段**，不写进 DataStore 就只在内存里活着：
        // UI 能改、当前进程内生效，**一重启就回到默认值**（正是 2026-09-11 那条教训的形状）。
        val ENABLE_SUBAGENT_DISPATCH = booleanPreferencesKey("enable_subagent_dispatch")
        val SUBAGENT_PRESETS = stringPreferencesKey("subagent_presets")
        // 全局代理（两层代理设计的全局兜底层）；结构化 JSON 存储
        val GLOBAL_PROXY = stringPreferencesKey("global_proxy")
        // 日志落盘（Log 页「导出到文件」开关；默认关，避免无谓写盘）
        val LOG_TO_FILE_ENABLED = booleanPreferencesKey("log_to_file_enabled")
        // 请求记录（Log 页「记录请求」开关；默认关。**必须持久化**——否则重启后
        // 「导出到文件」虽开着却收不到任何请求日志，是个静默失效的坑）
        val RECORD_REQUESTS_ENABLED = booleanPreferencesKey("record_requests_enabled")

    }

    private val dataStore = createSettingsDataStore(context.preferencesDataStoreFile("settings"))

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
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fallbackChatModelId = preferences[FALLBACK_CHAT_MODEL]?.let { Uuid.parse(it) },
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    // 旧版本「未配置」会落成 AUTO 哨兵（无清除入口时代的默认值），视为未配置
                    ?.takeIf { it != DEFAULT_AUTO_MODEL_ID },
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) },
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
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
                removedBuiltInProviderIds = preferences[REMOVED_BUILTIN_PROVIDERS]?.let {
                    JsonInstant.decodeFromString<List<String>>(it).map { raw -> Uuid.parse(raw) }.toSet()
                } ?: emptySet(),
                removedTtsProviderIds = preferences[REMOVED_TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString<List<String>>(it).map { raw -> Uuid.parse(raw) }.toSet()
                } ?: emptySet(),
                removedAssistantIds = preferences[REMOVED_ASSISTANTS]?.let {
                    JsonInstant.decodeFromString<List<String>>(it).map { raw -> Uuid.parse(raw) }.toSet()
                } ?: emptySet(),
                retrySetting = preferences[RETRY_SETTING]?.let { JsonInstant.decodeFromString(it) }
                    ?: RetrySetting(),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                backupItems = preferences[BACKUP_ITEMS]?.let {
                    JsonInstant.decodeFromString<List<BackupItem>>(it)
                } ?: DEFAULT_BACKUP_ITEMS,
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
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
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
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
                loopGuardSensitivity = preferences[LOOP_GUARD_SENSITIVITY]
                    ?: app.androidrik.rikkahub.data.ai.LoopGuardSensitivity.STANDARD.name,
                workspaceTurnBudgetChars = (preferences[WORKSPACE_TURN_BUDGET_CHARS]
                    ?: DEFAULT_WORKSPACE_TURN_BUDGET_CHARS).coerceIn(32_000, 2_000_000),
                workspaceTurnMaxSteps = (preferences[WORKSPACE_TURN_MAX_STEPS]
                    ?: DEFAULT_WORKSPACE_TURN_MAX_STEPS).coerceIn(8, 512),
                subagentMaxConcurrentRuns = (preferences[SUBAGENT_MAX_CONCURRENT_RUNS]
                    ?: DEFAULT_SUBAGENT_MAX_CONCURRENT_RUNS).coerceIn(1, 8),
                workspaceTaskMaxDurationMinutes = (preferences[WORKSPACE_TASK_MAX_DURATION_MINUTES]
                    ?: DEFAULT_WORKSPACE_TASK_MAX_DURATION_MINUTES).coerceIn(5, 1440),
                logToFileEnabled = preferences[LOG_TO_FILE_ENABLED] ?: false,
                globalProxy = preferences[GLOBAL_PROXY]?.let {
                    JsonInstant.decodeFromString<GlobalProxySetting>(it)
                } ?: GlobalProxySetting(),
                recordRequestsEnabled = preferences[RECORD_REQUESTS_ENABLED] ?: false,
                autoCompressThresholdPercent = preferences[AUTO_COMPRESS_THRESHOLD_PERCENT]
                    ?: DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
                autoCompressRetainPercent = preferences[AUTO_COMPRESS_RETAIN_PERCENT]
                    ?: DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT,
                exportRetentionDays = preferences[EXPORT_RETENTION_DAYS]
                    ?: DEFAULT_EXPORT_RETENTION_DAYS,
                autoCompressTargetTokens = preferences[AUTO_COMPRESS_TARGET_TOKENS]
                    ?: DEFAULT_AUTO_COMPRESS_TARGET_TOKENS,
                compressPresets = preferences[COMPRESS_PRESETS]?.let {
                    JsonInstant.decodeFromString<List<CompressPreset>>(it)
                } ?: emptyList(),
                importedSources = preferences[IMPORTED_SOURCES]?.let {
                    JsonInstant.decodeFromString<Map<String, ImportSourceRecord>>(it)
                } ?: emptyMap(),
                enableSubagentDispatch = preferences[ENABLE_SUBAGENT_DISPATCH] ?: false,
                subagentPresets = preferences[SUBAGENT_PRESETS]?.let {
                    JsonInstant.decodeFromString<List<SubagentPreset>>(it)
                } ?: DEFAULT_SUBAGENT_PRESETS,
            )
        }
        .map {
            var providers = it.providers.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id } &&
                    defaultProvider.id !in it.removedBuiltInProviderIds
                ) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
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
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                promptPresets = settings.promptPresets
                    .distinctBy { it.id }
                    // 预设内的条目也要去重（同 id 重复会让同一注入生效两次）
                    .map { preset -> preset.copy(entries = preset.entries.distinctBy { it.id }) },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }

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
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        settingsFlow.value = settings
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)

            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            settings.fallbackChatModelId?.let {
                preferences[FALLBACK_CHAT_MODEL] = it.toString()
            } ?: preferences.remove(FALLBACK_CHAT_MODEL)
            settings.fastModelId?.let {
                preferences[FAST_MODEL] = it.toString()
            } ?: preferences.remove(FAST_MODEL)
            settings.titleModelId?.let {
                preferences[TITLE_MODEL] = it.toString()
            } ?: preferences.remove(TITLE_MODEL)
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
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

            preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)
            preferences[REMOVED_BUILTIN_PROVIDERS] =
                JsonInstant.encodeToString(settings.removedBuiltInProviderIds.map { it.toString() })
            preferences[REMOVED_ASSISTANTS] =
                JsonInstant.encodeToString(settings.removedAssistantIds.map { it.toString() })
            preferences[REMOVED_TTS_PROVIDERS] =
                JsonInstant.encodeToString(settings.removedTtsProviderIds.map { it.toString() })
            preferences[RETRY_SETTING] = JsonInstant.encodeToString(settings.retrySetting)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)
            preferences[FAVORITE_TAGS] = JsonInstant.encodeToString(settings.favoriteTags)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = sanitizeSearchServiceSelected(settings.searchServiceSelected, settings.searchServices.size)

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            preferences[BACKUP_ITEMS] = JsonInstant.encodeToString(settings.backupItems)
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
            preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[MEMORY_INJECT_BUDGET_TOKENS] = settings.memoryInjectBudgetTokens
            preferences[LOREBOOK_INJECT_BUDGET_TOKENS] = settings.lorebookInjectBudgetTokens
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[LAUNCH_COUNT] = settings.launchCount
            settings.contextLengthOverride?.let {
                preferences[CONTEXT_LENGTH_OVERRIDE] = it
            } ?: preferences.remove(CONTEXT_LENGTH_OVERRIDE)
            preferences[AUTO_COMPRESS_ENABLED] = settings.autoCompressEnabled
            preferences[LOOP_GUARD_SENSITIVITY] = settings.loopGuardSensitivity
            preferences[WORKSPACE_TURN_BUDGET_CHARS] = settings.workspaceTurnBudgetChars
            preferences[WORKSPACE_TURN_MAX_STEPS] = settings.workspaceTurnMaxSteps
            preferences[SUBAGENT_MAX_CONCURRENT_RUNS] = settings.subagentMaxConcurrentRuns
            preferences[WORKSPACE_TASK_MAX_DURATION_MINUTES] = settings.workspaceTaskMaxDurationMinutes
            preferences[AUTO_COMPRESS_THRESHOLD_PERCENT] = settings.autoCompressThresholdPercent
            preferences[AUTO_COMPRESS_RETAIN_PERCENT] = settings.autoCompressRetainPercent
            preferences[EXPORT_RETENTION_DAYS] = settings.exportRetentionDays
            preferences[AUTO_COMPRESS_TARGET_TOKENS] = settings.autoCompressTargetTokens
            preferences[COMPRESS_PRESETS] = JsonInstant.encodeToString(settings.compressPresets)
            preferences[IMPORTED_SOURCES] = JsonInstant.encodeToString(settings.importedSources)
            preferences[LOG_TO_FILE_ENABLED] = settings.logToFileEnabled
            preferences[GLOBAL_PROXY] = JsonInstant.encodeToString(settings.globalProxy)
            preferences[RECORD_REQUESTS_ENABLED] = settings.recordRequestsEnabled
            preferences[ENABLE_SUBAGENT_DISPATCH] = settings.enableSubagentDispatch
            preferences[SUBAGENT_PRESETS] = JsonInstant.encodeToString(settings.subagentPresets)
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        updateMutex.withLock {
            update(fn(settingsFlow.value))
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

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fallbackChatModelId: Uuid? = null, // 主模型整轮失败后切换的备用模型（助手未配 fallback 时生效）
    val fastModelId: Uuid? = null, // 快速模型（标题/建议等后台任务的兜底）；null = 未配置
    val titleModelId: Uuid? = null,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionModelId: Uuid? = null,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    /** 压缩使用的模型 id。默认锁定内置模型；**null = 跟随当前对话模型**（可选，避免单独配置） */
    val compressModelId: Uuid? = DEFAULT_AUTO_MODEL_ID,
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    /** 用户显式删除的内置提供商 id（墓碑）——默认回填逻辑跳过这些 */
    val removedBuiltInProviderIds: Set<Uuid> = emptySet(),
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    /** 用户显式删除的内置助手 id（墓碑）——默认回填逻辑跳过这些（与提供商墓碑同款） */
    val removedAssistantIds: Set<Uuid> = emptySet(),
    /**
     * 已删除的**内置 TTS 提供商** id（墓碑）。
     *
     * 为什么需要：内置 TTS 提供商原本由读路径无条件回填（缺了就补），导致**删不掉**——
     * 与内置助手/内置聊天提供商曾经的问题同款。记下墓碑后回填逻辑跳过它们，
     * 删除才真的生效。与 `removedBuiltInProviderIds` / `removedAssistantIds` 同一模式。
     */
    val removedTtsProviderIds: Set<Uuid> = emptySet(),
    val assistantTags: List<Tag> = emptyList(),

    /**
     * 收藏标签池（**独立于 [assistantTags]**，两者语义不同：前者标签"对话"，后者标签"助手"）。
     *
     * 存 id 清单而非名字，收藏侧只存 tag id → 改名不必批量更新收藏。
     * 与 `assistantTags` 同款写法（单 key JSON 列表）。
     */
    val favoriteTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    /**
     * 备份内容（S3 / WebDAV / 本地导出共用同一份）。
     *
     * **为何全局**：「想备份什么」与「存到哪里」是正交的，不应各自配一份（否则三处要分别维护、且本地无法选）。
     * 需要「云端精简、本地完整」时，在备份前临时切换勾选即可（备份为纯手动、
     * 无自动调度，故“别忘记改回来”的风险低）。
     */
    val backupItems: List<BackupItem> = DEFAULT_BACKUP_ITEMS,
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    /**
     * 预设容器（一组组具名的注入条目）。**这是持久化字段**。
     *
     * 默认值把 [DEFAULT_MODE_INJECTIONS]（学习模式）包成一个预设——与旧数据形态等价。
     */
    val promptPresets: List<PromptPreset> = listOf(DEFAULT_MODE_INJECTIONS.asSinglePreset()),
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    /**
     * 记忆注入预算（token）。注入**哪些**记忆由打分校准决定，**装多少**由此预算决定——
     * 见 `docs/design-memory-scoring.md`。改它会改变 system prompt 前缀，
     * 但只影响「入选集合」本身，不会每次请求漂移（输出顺序按 sortKey 固定）。
     */
    val memoryInjectBudgetTokens: Int = DEFAULT_MEMORY_BUDGET_TOKENS,
    /**
     * 世界书（Lorebook）注入的 token 预算（**全局**，对齐 ST 的 `world_info_budget`）。
     *
     * 0 = 不限制；**默认就是 0**（保持 fork 旧行为）——照搬 ST 的「25% 上下文」会**静默**
     * 改变用户既有的注入量，那是不可接受的默认值变更，所以由用户显式开启。
     * 超预算时按 priority 降序裁剪；常驻条目（constantActive）不计预算。
     *
     * 见 `docs/peer-apps-benchmark.md` §6.2 ③。
     */
    val lorebookInjectBudgetTokens: Int = DEFAULT_LOREBOOK_BUDGET_TOKENS,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    /** 全局代理（provider 未自配时兜底；默认 NONE = 不启用） */
    val globalProxy: GlobalProxySetting = GlobalProxySetting(),
    val retrySetting: RetrySetting = RetrySetting(),
    val launchCount: Int = 0,
    /** 上下文窗口全局默认（模型无 contextLength/customContextLength 时的兜底；null=不覆盖走 256K） */
    val contextLengthOverride: Int? = null,
    /** 自动压缩：占用超过阈值时自动把早期历史摘要为检查点（默认关，保持行为可预期） */
    val autoCompressEnabled: Boolean = false,
    /** LoopGuard 灵敏度档位（strict/standard/loose），见 [app.androidrik.rikkahub.data.ai.guardThresholds] */
    val loopGuardSensitivity: String = "STANDARD",
    /** workspace_* 工具单轮输出预算（字符数），见 [DEFAULT_WORKSPACE_TURN_BUDGET_CHARS] */
    val workspaceTurnBudgetChars: Int = DEFAULT_WORKSPACE_TURN_BUDGET_CHARS,
    /** workspace_* 工具单轮步数上限，见 [DEFAULT_WORKSPACE_TURN_MAX_STEPS] */
    val workspaceTurnMaxSteps: Int = DEFAULT_WORKSPACE_TURN_MAX_STEPS,
    /** 子代理并行运行上限（满了拒绝新派发） */
    val subagentMaxConcurrentRuns: Int = DEFAULT_SUBAGENT_MAX_CONCURRENT_RUNS,
    /** 后台任务最长存活分钟数（到点杀进程树记 TIMED_OUT） */
    val workspaceTaskMaxDurationMinutes: Int = DEFAULT_WORKSPACE_TASK_MAX_DURATION_MINUTES,
    /** 日志落盘到 files/logs（默认关）：取回完整请求体等 logcat 截断看不到的内容 */
    val logToFileEnabled: Boolean = false,
    /** 记录 HTTP 请求日志（默认关）：与落盘开关独立，但落盘要有内容需它同时开启 */
    val recordRequestsEnabled: Boolean = false,
    /** 触发阈值：占用达到模型窗口的百分比时触发（默认 80） */
    val autoCompressThresholdPercent: Int = DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT,
    /** 保留比例：尾部原文占窗口的百分比（默认 40） */
    val autoCompressRetainPercent: Int = DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT,

    /**
     * 导出留存区的保留天数（`files/exports` 的 TTL）。**0 = 不自动清理**。
     *
     * 启动时按此清理过期导出（见 `docs/design-export-retention.md` §4.3）。
     */
    val exportRetentionDays: Int = DEFAULT_EXPORT_RETENTION_DAYS,
    /** 摘要目标 token（默认 2000） */
    val autoCompressTargetTokens: Int = DEFAULT_AUTO_COMPRESS_TARGET_TOKENS,
    /**
     * 自定义压缩配置档（用户命名，可增/改/删）。
     *
     * **不是真相源**：真正决定压缩行为的是上面三个数值，档只是「一键填入保留比例 + 摘要目标」
     * 的快捷方式，故删档不改变行为。内置档是常量、不入此列表。
     * 见 `docs/design-compress-presets.md`。
     */
    val compressPresets: List<CompressPreset> = emptyList(),
    /**
     * 导入来源记录：`sourceId`（导出时的会话 id，见 `ExportData.sourceId`）→ 导入时间与原标题。
     *
     * 用途：**重复导入提示**（同一文件再次导入时告知"已于 x 导入过"）。
     * 之所以放在这里而不是 `ConversationEntity` 加列：零迁移，且它本质是**用户级的来源记录**
     * 而非会话属性（导入总换新 id，故来源与会话不是一对一）。
     * 见 `docs/design-conversation-import-export.md` §十。
     */
    val importedSources: Map<String, ImportSourceRecord> = emptyMap(),
    /**
     * **允许对话中的模型调用子代理**（`dispatch_subagent` 工具，设计见
     * `docs/design-subagent-context.md` §十）。
     *
     * **默认关**，三个理由都是可见成本：①每次请求多一份工具 schema + 一份子代理清单
     * （提示词预算）；②派发是**阻塞**的，模型可以按住本轮对话数分钟；③它是"模型自己决定
     * 再叫一个模型"，不该由更新替用户打开。开启入口在子代理管理页（与预设配置同一处）。
     */
    val enableSubagentDispatch: Boolean = false,
    /**
     * 子代理预设：**「按名称调度的模型 + 提示词」组合**列表。
     *
     * 默认值即内置三个（检索 / 第二意见 / 要点提炼），存量用户读入即得——**零迁移**
     * （新字段 + `JsonInstant` 的 `ignoreUnknownKeys`）。内置预设就存在这个列表里，
     * 用户可改可删；**没有墓碑机制**（与内置提供商不同），删掉不会自动回填。
     */
    val subagentPresets: List<SubagentPreset> = DEFAULT_SUBAGENT_PRESETS,
) {
    /**
     * 全部注入条目的**扁平视图**（预设计算得出，不单独持久化）。
     *
     * 刻意做成计算属性而非存储字段：下游（`PromptInjectionTransformer`、Web API 的
     * id 校验、web-ui 的勾选列表、`ExtensionSelector`）全都按「扁平条目列表」消费，
     * 这样**改容器结构不需要动它们任何一处**。见 `docs/peer-apps-benchmark.md` §6.2 ④。
     */
    val modeInjections: List<PromptInjection.ModeInjection>
        get() = promptPresets.flatMap { it.entries }

    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    /**
     * 输入栏的上下文占用指示器形态：false = 细条 + 百分比（默认），true = 圆环 + 整数百分比。
     *
     * 两种形态只是**展示**不同：数值来源、警戒阈值（`CONTEXT_USAGE_WARNING_RATIO`）、点击行为
     * 完全共用，故切换不会带来口径差异。默认 false = 老样子，旧数据零迁移（缺字段取默认值）。
     */
    val useRingContextIndicator: Boolean = false,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableDiagramRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val enableGlassTheme: Boolean = false,
    val enableTopBarBlur: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_plus_backups",
) {
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

/** 全局代理类型（两层代理设计 §一）。 */
@Serializable
enum class ProxyType { NONE, HTTP, SOCKS5 }

/**
 * 全局代理设置（两层代理设计的「全局兜底层」）。
 *
 * 覆盖面（已盘点，见 docs/design-proxy-two-layer.md §四）：
 * - AI chat：provider 未自配（`ProviderProxy.Inherit`）时走这里；
 * - **只有走 DI 共享 client 的链路才受益**——搜索（`SearchService.init` 注入共享 client）、
 *   ASR（`koinInject<OkHttpClient>`）在列；TTS / MCP 各自 `OkHttpClient.Builder()` 自建、
 *   S3/WebDAV 走独立 Ktor client，**均不受全局层影响**。
 */
@Serializable
data class GlobalProxySetting(
    val type: ProxyType = ProxyType.NONE,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
) {
    /** 是否可用（已选择类型且地址端口填写有效）。 */
    val isConfigured: Boolean
        get() = type != ProxyType.NONE && host.isNotBlank() && port in 1..65535
}

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

/**
 * 解析压缩使用的模型：
 * 1. 显式指定且可解析 -> 用该模型（用户可专门配一个便宜模型做摘要）
 * 2. **未指定（null = 「跟随对话模型」）或指定已失效（模型被删）** -> 用 [followModel]（该会话的生成模型）
 * 3. 都没有 -> 全局当前对话模型
 */
fun Settings.resolveCompressionModel(followModel: Model? = null): Model? =
    compressModelId?.let { findModelById(it) }
        ?: followModel
        ?: getCurrentChatModel()

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    // 停用的提供商不参与选择（见 TTSProviderSetting.enabled）。
    // 与 ASR 同款取舍：**选中项被停用时不静默回退**到别的提供商——用户既然关了它，
    // 就不该悄悄改用另一个可能计费的提供商，而是返回 null 让 TTS 侧如实置「不可用」。
    // 注：「一个启用的都不剩」在读路径已被 ensureFallbackTtsEnabled 拦掉（系统 TTS 兜底），
    // 只有用户把系统 TTS 也删掉时才可能真的没有可用项。
    val selected = selectedTTSProviderId?.let { id -> ttsProviders.find { it.id == id } }
    if (selected != null) return selected.takeIf { it.enabled }
    return ttsProviders.firstOrNull { it.enabled }
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    // 停用的提供商不参与选择（见 ASRProviderSetting.enabled）。
    // **选中项被停用时不静默回退**到别的提供商——用户既然关了它，就不该悄悄改用另一个
    // 可能计费的提供商；返回 null 让消费路径如实提示「请配置语音识别」
    // （听写 asr_error_not_configured / 语音模式 chat_page_voice_configure_asr）。
    val selected = selectedASRProviderId?.let { id -> asrProviders.find { it.id == id } }
    if (selected != null) return selected.takeIf { it.enabled }
    return asrProviders.firstOrNull { it.enabled }
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(proxy = provider.proxy, models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    // 缓存提示：此种子刻意**不含 {{cur_date}}**——system 在请求位置 0，每日变化的内容
    // 会让整段前缀缓存每天作废（#1542 复核 Tier A）。需要日期的用户自行添加并接受该代价。
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}

            ## Hint
            - If the user does not specify a language, reply in the user's primary language.
            - Remember to use Markdown syntax for formatting, and use latex for mathematical expressions.
        """.trimIndent()
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

/**
 * 内置助手回填：缺省（首次安装）或用户删掉了某个内置助手时补回。
 *
 * **跳过墓碑**（[removedIds]）：用户显式删除过的内置助手不再回填——否则读路径每次
 * 都把删掉的加回来，等于删不掉（这正是此前内置助手无法删除的机制）。
 * 与提供商墓碑（`removedBuiltInProviderIds`）同款模式。**纯函数**便于单测。
 */
internal fun refillDefaultAssistants(
    existing: MutableList<Assistant>,
    removedIds: Set<Uuid>,
) {
    DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
        if (existing.none { it.id == defaultAssistant.id } && defaultAssistant.id !in removedIds) {
            existing.add(defaultAssistant.copy())
        }
    }
}

/**
 * 内置 TTS 提供商回填（**纯函数**，与 [refillDefaultAssistants] 同款模式）：
 * 缺了的内置项补回来，**但跳过墓碑**——用户显式删除过的内置项不再回填，
 * 否则读路径每次把删掉的加回来，等于删不掉。
 */
internal fun refillDefaultTtsProviders(
    existing: MutableList<TTSProviderSetting>,
    removedIds: Set<Uuid>,
) {
    DEFAULT_TTS_PROVIDERS.forEach { defaultProvider ->
        if (existing.none { it.id == defaultProvider.id } && defaultProvider.id !in removedIds) {
            existing.add(defaultProvider.copyProvider())
        }
    }
}

/** 该 id 是否属于内置 TTS 提供商（删除时需要记墓碑，否则读路径会补回来）。 */
internal fun isBuiltInTtsProvider(id: Uuid): Boolean =
    DEFAULT_TTS_PROVIDERS.any { it.id == id }

/**
 * 系统 TTS 兜底不变式（**纯函数**）：列表里**一个启用的都没有**时，把系统 TTS 拉回启用。
 *
 * 为什么单给它这个待遇：系统 TTS 走设备引擎、不需要 API key 与网络，是唯一「永远可用」的
 * 选项。允许用户把最后一个也停掉，会让朗读按钮与语音模式直接失效且无人解释原因。
 * 系统 TTS 自身已被删除（墓碑）时不做任何事——那种情况下确实没有兜底可用，
 * 由 `getSelectedTTSProvider()` 返回 null 让 UI 如实置「不可用」。
 */
internal fun List<TTSProviderSetting>.ensureFallbackTtsEnabled(): List<TTSProviderSetting> {
    if (any { it.enabled }) return this
    val fallbackIndex = indexOfFirst { it.id == DEFAULT_SYSTEM_TTS_ID }
    if (fallbackIndex < 0) return this
    return mapIndexed { index, provider ->
        if (index == fallbackIndex) provider.withEnabled(true) else provider
    }
}

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        // 缓存止血（#1542 复核 Tier A）：不用 AFTER_SYSTEM_PROMPT——该位置会**改写 system
        // 消息本体**（applyInjections 把 before+原文+after 拼回 system），而 system 在请求
        // 位置 0，一变整段前缀缓存全废。BOTTOM_OF_CHAT 插在对话尾部、前缀稳定。
        // 只影响新装用户；存量数据的该条目由管理页「一键迁移」覆盖。
        position = InjectionPosition.BOTTOM_OF_CHAT,
        name = "Learning Mode"
    )
)

/**
 * 备份内容默认值：**全部三项**。
 *
 * 旧版默认只有 DATABASE+FILES，但那是当时尚无工作区项的局限，并非用户偏好；
 * 默认全备更符合「防止数据丢失」的意图（嫌大可取消勾选）。
 */
val DEFAULT_BACKUP_ITEMS: List<BackupItem> = BackupItem.entries.toList()

// ---- 护栏与预算的默认值（与原写死常量一致；可配置化不改变默认行为） ----
const val DEFAULT_WORKSPACE_TURN_BUDGET_CHARS = 256 * 1024
const val DEFAULT_WORKSPACE_TURN_MAX_STEPS = 64
const val DEFAULT_SUBAGENT_MAX_CONCURRENT_RUNS = 2
const val DEFAULT_WORKSPACE_TASK_MAX_DURATION_MINUTES = 30
