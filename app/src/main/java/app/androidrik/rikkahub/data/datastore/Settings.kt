package app.androidrik.rikkahub.data.datastore

import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.RetrySetting
import app.androidrik.asr.ASRProviderSetting
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.rikkahub.data.ai.DEFAULT_LOREBOOK_BUDGET_TOKENS
import app.androidrik.rikkahub.data.ai.DEFAULT_MEMORY_BUDGET_TOKENS
import app.androidrik.rikkahub.data.ai.MEMORY_CONSOLIDATION_SYSTEM_PROMPT
import app.androidrik.rikkahub.data.ai.compression.CompressPreset
import app.androidrik.rikkahub.data.ai.compression.DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT
import app.androidrik.rikkahub.data.ai.compression.DEFAULT_AUTO_COMPRESS_TARGET_TOKENS
import app.androidrik.rikkahub.data.ai.compression.DEFAULT_AUTO_COMPRESS_THRESHOLD_PERCENT
import app.androidrik.rikkahub.data.ai.mcp.McpServerConfig
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import app.androidrik.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import app.androidrik.rikkahub.data.export.ExportDisplayConfig
import app.androidrik.rikkahub.data.export.ExportDisplayScheme
import app.androidrik.rikkahub.data.files.DEFAULT_EXPORT_RETENTION_DAYS
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.DEFAULT_SUBAGENT_PRESETS
import app.androidrik.rikkahub.data.model.ImportSourceRecord
import app.androidrik.rikkahub.data.model.Lorebook
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.model.PromptPreset
import app.androidrik.rikkahub.data.model.QuickMessage
import app.androidrik.rikkahub.data.model.SubagentPreset
import app.androidrik.rikkahub.data.model.Tag
import app.androidrik.rikkahub.data.model.asSinglePreset
import app.androidrik.rikkahub.data.sync.BackupItem
import app.androidrik.rikkahub.data.sync.s3.S3Config
import app.androidrik.rikkahub.data.model.CustomTheme
import app.androidrik.rikkahub.data.ai.MemoryTriggerConfig
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.search.SearchCommonOptions
import app.androidrik.search.SearchServiceOptions
import app.androidrik.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.koin.core.component.get

// —— Settings 聚合根（批次 4 拆出）：@Serializable 形状逐字保留，字段/默认值一个不动 ——

@Serializable
enum class UsageDisplayMode {
    USED,
    REMAINING,
}

/**
 * 图片 OCR 后端（DeepSeek 适配备料 03 §2.1）。
 *
 * 两种后端计费口径不同（token vs 按页 ¥1.5/千页），混用不可归因——显式二选一，
 * **不做隐式跨后端回退**。默认 [MODEL] = 现有视觉模型 OCR，零行为变化。
 */
@Serializable
enum class OcrBackend {
    /** 配置一个视觉模型看图说话（现状，走 token 计费） */
    MODEL,

    /** DeepSeek 文件 OCR Tool API（文件进、逐页文本出，按页计费） */
    DEEPSEEK,
}

/**
 * DeepSeek OCR 的识别模式（对应服务端 `purpose` 字段）。
 * - [TEXT]：无图 Word/PPT 直接抽文字（**不产生 OCR 费用**），其余逐页识别；
 * - [RICH_LAYOUT]：一律逐页识别，输出含 ref/box 坐标 token。
 */
@Serializable
enum class OcrDeepSeekPurpose {
    TEXT,
    RICH_LAYOUT,
}

/** DeepSeek OCR 的默认端点前缀（UI 与字段默认值共用此常量，防两处漂移） */
const val DEFAULT_OCR_DEEPSEEK_BASE_URL = "https://api.deepseek.com"

/** DeepSeek OCR 的 PDF 页数本地预检上限（服务端上限 1000 页；UI 输入钳制同口径） */
const val OCR_DEEPSEEK_MAX_PAGES_LIMIT = 1000

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    /** 动态取色种子色（ARGB）；null = 跟随壁纸。仅 dynamicColor 且 Android 12+ 时生效。 */
    val dynamicColorSeedColor: Long? = null,
    val themeId: String = DEFAULT_THEME_ID,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    /** 头像框自定义参数方案（CUSTOM_PARAMS 档的命名预设）。 */
    val avatarFrameSchemes: List<AvatarFrameScheme> = emptyList(),
    /** 思维链自定义样式方案（CUSTOM 档的命名预设）。 */
    val cotStyleSchemes: List<ChainOfThoughtScheme> = emptyList(),
    val catalogAutoRefresh: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fallbackChatModelId: Uuid? = null, // 主模型整轮失败后切换的备用模型（助手未配 fallback 时生效）
    val fastModelId: Uuid? = null, // 快速模型（标题/建议等后台任务的兜底）；null = 未配置
    /** 做梦（记忆整理）专用模型；未配置时依次回落到快速模型、助手聊天模型。 */
    val memoryConsolidationModelId: Uuid? = null,
    /**
     * 整理任务的推理强度（上游 72d419171 增量移植）；null = 跟随默认（AUTO，原行为）。
     * 对整理实际用上的那个模型生效（做梦模型或回落链上的模型）。
     */
    val memoryConsolidationReasoningLevel: ReasoningLevel? = null,
    val memoryConsolidationPrompt: String = MEMORY_CONSOLIDATION_SYSTEM_PROMPT,
    /**
     * 记忆整理的触发方式（`docs/design/design-memory-consolidation-trigger.md`）：单选互斥，
     * 默认 IDLE = 历史行为（轮末空闲 2 分钟 + 攒 6 轮）。范围空集 = 全部助手。
     */
    val memoryTriggerConfig: MemoryTriggerConfig = MemoryTriggerConfig(),
    val titleModelId: Uuid? = null,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,

    /**
     * 自动生成标题（新对话第一轮之后）。
     *
     * 只关**自动**：会话列表/抽屉里的「重新生成标题」仍然可用（`force = true`）——
     * 与记忆整理的「整理一下」同一条原则：开关管的是后台自动行为，不是用户显式动作。
     */
    val enableAutoTitle: Boolean = true,
    val suggestionModelId: Uuid? = null,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    // —— DeepSeek OCR 可选后端（默认 MODEL = 现状，零行为变化；配料 03 §2.2）——
    val ocrBackend: OcrBackend = OcrBackend.MODEL,
    /** DeepSeek OCR 用的提供商 id；null = 复用内置 DeepSeek provider（共享 Key） */
    val ocrDeepSeekProviderId: Uuid? = null,
    /** DeepSeek Tool API 端点前缀（OCR 走 `{base}/v1/tools/ocr`） */
    val ocrDeepSeekBaseUrl: String = DEFAULT_OCR_DEEPSEEK_BASE_URL,
    val ocrDeepSeekPurpose: OcrDeepSeekPurpose = OcrDeepSeekPurpose.TEXT,
    /** 是否在 OCR 结果里保留 ref/box 坐标 token（本仓暂无消费位，默认剥掉） */
    val ocrDeepSeekIncludeLayout: Boolean = false,
    /** PDF 页数本地预检上限，防白发请求（服务端上限 1000 页） */
    val ocrDeepSeekMaxPages: Int = OCR_DEEPSEEK_MAX_PAGES_LIMIT,
    /** 压缩使用的模型 id。默认锁定内置模型；**null = 跟随当前对话模型**（可选，避免单独配置） */
    val compressModelId: Uuid? = DEFAULT_AUTO_MODEL_ID,
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    /**
     * 工具 schema 压缩档位（批次4 ToolSchemaCompactor）：0=OFF（默认），1=BALANCED，2=AGGRESSIVE。
     * 只裁描述文本不改参数/required；默认关闭——压缩改变模型看到的内容，属用户显式选择。
     */
    val toolSchemaCompactLevel: Int = 0,
    /**
     * 订阅用量（ChatGPT/Grok 的额度窗口）展示口径：
     * [UsageDisplayMode.USED] = 已用百分比（默认，与后端 `used_percent` 同义）；
     * [UsageDisplayMode.REMAINING] = 剩余百分比（100 - 已用）。
     * 仅影响展示，ai 层的 [app.androidrik.ai.provider.UsageWindow.usedPercent] 语义不变。
     */
    val usageDisplayMode: UsageDisplayMode = UsageDisplayMode.USED,
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    /** 用户显式删除的内置提供商 id（墓碑）——默认回填逻辑跳过这些 */
    val removedBuiltInProviderIds: Set<Uuid> = emptySet(),
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    /** 用户显式删除的内置助手 id（墓碑）——默认回填逻辑跳过这些（与提供商墓碑同款） */
    val removedAssistantIds: Set<Uuid> = emptySet(),
    /** Replay reference migration after a staged backup database is installed on restart. */
    val legacyDefaultAssistantMerged: Boolean = false,
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
    /**
     * MCP schema 动态分层加载（peer⑤③ 缩水版）：单工具 schema 超过阈值的重型工具不进
     * provider schema，由 `get_tool_map` + `mcp_tool_call` 元工具替代（provider schema 恒定，
     * token 成本 O(小工具数)）。默认开；弱模型走桥传参质量差时可关（关=全部原生 schema）。
     */
    val mcpDynamicSchemaLoading: Boolean = true,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    // RemoteFileStore 上传临时素材用的桶：与备份 [s3Config] 相互独立，互不覆盖
    val uploadS3Config: S3Config = S3Config(),
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
     * 媒体生成供应商（图片/视频）。空列表 = 未配置：媒体创作页提示去设置，不静默失败。
     * 与聊天 [providers] 分开：媒体接口的鉴权、可用模型与能力声明都不同。
     */
    val mediaGenerationProviders: List<MediaGenerationProviderSetting> = emptyList(),
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
    /** 安全策略：Web 鉴权恒开（不提供不鉴权模式）。读路径同样强制为 true。 */
    val webServerJwtEnabled: Boolean = true,
    val webServerAccessPassword: String = "",
    /** 安全策略：默认只听回环，需用户显式切换才暴露到局域网/mDNS。 */
    val webServerLocalhostOnly: Boolean = true,
    /**
     * 记忆注入预算（token）。注入**哪些**记忆由打分校准决定，**装多少**由此预算决定——
     * 见 `docs/design/design-memory-scoring.md`。改它会改变 system prompt 前缀，
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
    /**
     * LoopGuard「成功重复阈值」**自定义覆盖**（2026-10-31 用户决议，推翻早前「只做档位不做
     * 自由数字」的取舍）：0 = 跟随档位默认；>0 = 覆盖成功阈值（窗口/失败阈值仍随档位）。
     * 上限 20：护栏名存实亡的下限由调用侧 coerce 保证（不设下限 1——那等于关掉护栏）。
     */
    val loopGuardRepeatLimit: Int = 0,
    /**
     * 规则提醒（探索/落地守卫）：连续多次只读/搜索类工具调用且零落地产出时，在**对话尾部**
     * 注入一条自查提醒（默认开）。见 `docs/design/design-rule-engine.md`。
     */
    val ruleReminderEnabled: Boolean = true,
    /** 规则提醒灵敏度档位（strict/standard/loose），与 LoopGuard 同款：只做档位、不做自由数字 */
    val ruleReminderSensitivity: String = "STANDARD",
    /** workspace_* 工具单轮输出预算（字符数），见 [DEFAULT_WORKSPACE_TURN_BUDGET_CHARS] */
    val workspaceTurnBudgetChars: Int = DEFAULT_WORKSPACE_TURN_BUDGET_CHARS,
    /** workspace_* 工具单轮步数上限，见 [DEFAULT_WORKSPACE_TURN_MAX_STEPS] */
    val workspaceTurnMaxSteps: Int = DEFAULT_WORKSPACE_TURN_MAX_STEPS,
    /** Phone Use 工具单轮步数上限，见 [DEFAULT_PHONE_USE_TURN_MAX_STEPS] */
    val phoneUseTurnMaxSteps: Int = DEFAULT_PHONE_USE_TURN_MAX_STEPS,
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
    /**
     * 上下文图片清单上限（默认 20，0 = 关闭）：请求窗口只保留最近 N 张图片，更旧的换成
     * 占位文本、模型可用 view_image 按路径取回（借鉴 NMoi D248/D272，只影响请求不影响落库）。
     */
    val contextImageLimit: Int = DEFAULT_CONTEXT_IMAGE_LIMIT,
    /** 保留比例：尾部原文占窗口的百分比（默认 40） */
    val autoCompressRetainPercent: Int = DEFAULT_AUTO_COMPRESS_RETAIN_PERCENT,

    /**
     * 导出留存区的保留天数（`files/exports` 的 TTL）。**0 = 不自动清理**。
     *
     * 启动时按此清理过期导出（见 `docs/design/design-export-retention.md` §4.3）。
     */
    val exportRetentionDays: Int = DEFAULT_EXPORT_RETENTION_DAYS,
    /**
     * 导出时「提供商 / 模型」标签的**全局配置**（PNG 图片、Markdown、HTML 三种只读导出共用；
     * JSON 归档不读它）。见 `data/export/ExportDisplayLabel.kt`。
     */
    val exportDisplayConfig: ExportDisplayConfig = ExportDisplayConfig(),
    /** 用户命名的导出显示方案（一键套用到 [exportDisplayConfig]；删档不改当前值）。 */
    val exportDisplaySchemes: List<ExportDisplayScheme> = emptyList(),
    /** 摘要目标 token（默认 2000） */
    val autoCompressTargetTokens: Int = DEFAULT_AUTO_COMPRESS_TARGET_TOKENS,
    /**
     * 自定义压缩配置档（用户命名，可增/改/删）。
     *
     * **不是真相源**：真正决定压缩行为的是上面三个数值，档只是「一键填入保留比例 + 摘要目标」
     * 的快捷方式，故删档不改变行为。内置档是常量、不入此列表。
     * 见 `docs/design/design-compress-presets.md`。
     */
    val compressPresets: List<CompressPreset> = emptyList(),
    /**
     * 导入来源记录：`sourceId`（导出时的会话 id，见 `ExportData.sourceId`）→ 导入时间与原标题。
     *
     * 用途：**重复导入提示**（同一文件再次导入时告知"已于 x 导入过"）。
     * 之所以放在这里而不是 `ConversationEntity` 加列：零迁移，且它本质是**用户级的来源记录**
     * 而非会话属性（导入总换新 id，故来源与会话不是一对一）。
     * 见 `docs/design/design-conversation-import-export.md` §十。
     */
    val importedSources: Map<String, ImportSourceRecord> = emptyMap(),
    /**
     * **允许对话中的模型调用子代理**（`dispatch_subagent` 工具，设计见
     * `docs/design/design-subagent-context.md` §十）。
     *
     * **默认关**，三个理由都是可见成本：①每次请求多一份工具 schema + 一份子代理清单
     * （提示词预算）；②派发是**阻塞**的，模型可以按住本轮对话数分钟；③它是"模型自己决定
     * 再叫一个模型"，不该由更新替用户打开。开启入口在子代理管理页（与预设配置同一处）。
     */
    val noticeCarrier: app.androidrik.rikkahub.data.task.NoticeCarrier = app.androidrik.rikkahub.data.task.NoticeCarrier.SYSTEM,
    val enableBackgroundSubagent: Boolean = false,
    /** Resume idle chats after background commands/subagents finish; may make additional model requests. */
    val workspaceTaskNoticeEnabled: Boolean = false,
    val enableSubagentDispatch: Boolean = false,
    /** P0 回滚开关（附录 C，默认开）：关 = 隐藏任务中心入口，旧 Agent 运行列表仍可进。 */
    val taskCenterEnabled: Boolean = true,
    /** P0 回滚开关：关 = 子代理审批通知回旧固定 4242/4243/4244。 */
    val namespacedNotifications: Boolean = true,
    /** P0 回滚开关：关 = 并发闸上限冻结在首读，改设置需重启。 */
    val dynamicRunGate: Boolean = true,
    /** P0 回滚开关：关 = run 取消/超时不再级联 kill 它起的 shell task。 */
    val killShellTasksOnRunCancel: Boolean = true,
    /**
     * P4b 可选执行路径：开启且后台子代理可用时，定时任务不再注入目标会话，而是派发一个
     * 独立 headless `agent_run`；结果由 notice 投递泵写回目标会话 + 完成通知。**默认关**
     * ——关闭时行为与历史完全一致（会话注入），也便于按设置整体回退。
     */
    val scheduledHeadlessEnabled: Boolean = false,
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
