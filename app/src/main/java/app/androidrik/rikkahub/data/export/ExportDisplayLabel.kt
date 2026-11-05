package app.androidrik.rikkahub.data.export

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.semanticRole
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 导出时「提供商 / 模型」标签的显示方式（PNG 图片、Markdown、HTML 三种只读导出共用）。
 *
 * JSON 归档（自家往返格式）**不读本配置**：那份文件是给导入回填用的，
 * 显示偏好属于渲染层，写进归档只会增加导入/备份的兼容负担。
 */
@Serializable
enum class ExportModelLabelMode {
    /** 不显示（隐私模式：分享截图不带中转商 / 模型信息）。 */
    HIDDEN,

    /** 只显示模型名（默认，与聊天消息头的口径一致）。 */
    MODEL,

    /** 显示「提供商 · 模型」。 */
    PROVIDER_MODEL,

    /** 自定义文案，支持 [EXPORT_LABEL_PROVIDER_TOKEN] 等占位符。 */
    CUSTOM,
}

/**
 * 标签配置（全局当前值，也是「已保存方案」的内容）。
 *
 * 默认 [ExportModelLabelMode.MODEL] 而不是显示提供商：导出图常用于公开分享，
 * 中转商备注 / 私有地址泄露属于不可逆的隐私损失，想要提供商由用户显式打开。
 */
@Serializable
data class ExportDisplayConfig(
    val mode: ExportModelLabelMode = ExportModelLabelMode.MODEL,
    val customTemplate: String = DEFAULT_EXPORT_LABEL_TEMPLATE,
)

/**
 * 命名的导出显示方案：点行即把 [config] 套到全局当前值，删档**不改**当前值
 * （与 `AvatarFrameScheme` / `ChainOfThoughtScheme` 的「方案不是真相源」取舍一致）。
 */
@Serializable
data class ExportDisplayScheme(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val config: ExportDisplayConfig = ExportDisplayConfig(),
)

/**
 * 内置预设：常见口径的一键配置，用户套用后可以再改、也可以存成自己的命名方案。
 * 只带内容（模式 + 模板），显示名由 UI 层按 [id] 取字符串资源（三语各自翻译）。
 */
data class ExportDisplayPreset(
    val id: String,
    val mode: ExportModelLabelMode,
    val template: String = DEFAULT_EXPORT_LABEL_TEMPLATE,
) {
    val config: ExportDisplayConfig
        get() = ExportDisplayConfig(mode = mode, customTemplate = template)
}

const val EXPORT_PRESET_HIDDEN: String = "hidden"
const val EXPORT_PRESET_MODEL: String = "model"
const val EXPORT_PRESET_PROVIDER_MODEL: String = "provider_model"
const val EXPORT_PRESET_MODEL_PROVIDER: String = "model_provider"
const val EXPORT_PRESET_ASSISTANT_MODEL: String = "assistant_model"
const val EXPORT_PRESET_MODEL_DATE: String = "model_date"

val BUILT_IN_EXPORT_DISPLAY_PRESETS: List<ExportDisplayPreset> = listOf(
    ExportDisplayPreset(EXPORT_PRESET_HIDDEN, ExportModelLabelMode.HIDDEN, ""),
    ExportDisplayPreset(EXPORT_PRESET_MODEL, ExportModelLabelMode.MODEL),
    ExportDisplayPreset(EXPORT_PRESET_PROVIDER_MODEL, ExportModelLabelMode.PROVIDER_MODEL),
    ExportDisplayPreset(
        EXPORT_PRESET_MODEL_PROVIDER,
        ExportModelLabelMode.CUSTOM,
        "$EXPORT_LABEL_MODEL_TOKEN · $EXPORT_LABEL_PROVIDER_TOKEN",
    ),
    ExportDisplayPreset(
        EXPORT_PRESET_ASSISTANT_MODEL,
        ExportModelLabelMode.CUSTOM,
        "$EXPORT_LABEL_ASSISTANT_TOKEN · $EXPORT_LABEL_MODEL_TOKEN",
    ),
    ExportDisplayPreset(
        EXPORT_PRESET_MODEL_DATE,
        ExportModelLabelMode.CUSTOM,
        "$EXPORT_LABEL_MODEL_TOKEN · $EXPORT_LABEL_DATE_TOKEN",
    ),
)

/** 默认自定义文案（与内置的「提供商 · 模型」档同形）。 */
const val DEFAULT_EXPORT_LABEL_TEMPLATE: String = "{provider} · {model}"

const val EXPORT_LABEL_PROVIDER_TOKEN: String = "{provider}"
const val EXPORT_LABEL_MODEL_TOKEN: String = "{model}"
const val EXPORT_LABEL_MODEL_ID_TOKEN: String = "{modelId}"
const val EXPORT_LABEL_ASSISTANT_TOKEN: String = "{assistant}"
const val EXPORT_LABEL_DATE_TOKEN: String = "{date}"
const val EXPORT_LABEL_TIME_TOKEN: String = "{time}"
const val EXPORT_LABEL_TOKENS_TOKEN: String = "{tokens}"

/** 预览用的示例值（设置页 / 编辑面板里展示「文案长什么样」）。 */
const val EXPORT_LABEL_SAMPLE_PROVIDER: String = "OpenAI"
const val EXPORT_LABEL_SAMPLE_MODEL: String = "GPT-4o"
const val EXPORT_LABEL_SAMPLE_MODEL_ID: String = "gpt-4o"
const val EXPORT_LABEL_SAMPLE_ASSISTANT: String = "Rikka"
const val EXPORT_LABEL_SAMPLE_DATE: String = "2026-01-31"
const val EXPORT_LABEL_SAMPLE_TIME: String = "09:41"
const val EXPORT_LABEL_SAMPLE_TOKENS: String = "1234"

/**
 * 单条消息解析后的标签：
 *
 * - [iconSeed] 只用于品牌图标（`AutoAIIcon` 按名字正则选图标），**不要**用显示文案喂它——
 *   否则「自定义文案」会退化成文字头像，或把图标错认成别的品牌；
 * - [label] 为空 = 不显示标签（不显示档 / 非 assistant / 查不到任何名称）。
 */
data class ResolvedExportModelLabel(
    val iconSeed: String?,
    val label: String,
)

/**
 * 解析一条消息的导出标签。
 *
 * 取名顺序遵循「**实时配置优先、快照兜底**」（与聊天消息头 `ChatMessageAvatar` 一致）：
 * 模型名取 `displayName` → 消息快照 → `modelId`；提供商名取消息快照（历史真实去向）
 * → 按当前提供商列表回找。模型/提供商被删除后靠快照不至于掉成空白
 * （#1991 在聊天侧修过，导出侧此前一直没跟上）。
 *
 * [assistantName] 是会话所属助手的名字（`{assistant}` 占位符用），导出调用方从
 * `settings.assistants` 里按 `conversation.assistantId` 解析后传入；{date}/{time}/{tokens}
 * 直接取消息自身的 `createdAt` / `usage`，不用额外传。
 */
fun resolveExportModelLabel(
    message: UIMessage,
    providers: List<ProviderSetting>,
    config: ExportDisplayConfig,
    assistantName: String? = null,
): ResolvedExportModelLabel {
    if (message.semanticRole != MessageRole.ASSISTANT) return ResolvedExportModelLabel(null, "")
    if (config.mode == ExportModelLabelMode.HIDDEN) return ResolvedExportModelLabel(null, "")

    val model = message.modelId?.let { providers.findModelById(it) }
    val modelName = model?.displayName?.takeIf { it.isNotBlank() }
        ?: message.modelNameSnapshot?.takeIf { it.isNotBlank() }
        ?: model?.modelId?.takeIf { it.isNotBlank() }
    val providerName = message.providerNameSnapshot?.takeIf { it.isNotBlank() }
        ?: model?.findProvider(providers, checkOverwrite = false)?.name?.takeIf { it.isNotBlank() }
    val iconSeed = model?.modelId?.takeIf { it.isNotBlank() }
        ?: message.modelNameSnapshot?.takeIf { it.isNotBlank() }
        ?: message.providerNameSnapshot?.takeIf { it.isNotBlank() }

    val label = when (config.mode) {
        ExportModelLabelMode.HIDDEN -> ""
        ExportModelLabelMode.MODEL -> modelName.orEmpty()
        // 只有一边可解析时退化为那一边，不留悬空分隔符
        ExportModelLabelMode.PROVIDER_MODEL -> listOfNotNull(providerName, modelName)
            .joinToString(" · ")
        ExportModelLabelMode.CUSTOM -> renderExportLabelTemplate(
            template = config.customTemplate,
            providerName = providerName,
            modelName = modelName,
            modelId = model?.modelId,
            assistantName = assistantName,
            date = message.createdAt.toExportLabelDate(),
            time = message.createdAt.toExportLabelTime(),
            tokens = message.usage?.toExportLabelTokens(),
        )
    }
    return ResolvedExportModelLabel(iconSeed = iconSeed, label = label.trim())
}

/**
 * **会话级覆盖优先，否则跟随全局**（会话导出显示的唯一决策点）。
 *
 * 单独抽成函数是为了让「本会话设置 ⇄ 全局设置」的分支可单测，且导出侧三处
 * （Markdown / HTML / PNG）共用同一份语义——不要在调用点各写一次 `?:`。
 */
fun effectiveExportDisplayConfig(
    conversationOverride: ExportDisplayConfig?,
    global: ExportDisplayConfig,
): ExportDisplayConfig = conversationOverride ?: global

/** 用示例值渲染一份预览文案（设置页展示用；[ExportModelLabelMode.HIDDEN] 返回空串）。 */
fun previewExportModelLabel(config: ExportDisplayConfig): String = when (config.mode) {
    ExportModelLabelMode.HIDDEN -> ""
    ExportModelLabelMode.MODEL -> EXPORT_LABEL_SAMPLE_MODEL
    ExportModelLabelMode.PROVIDER_MODEL ->
        "$EXPORT_LABEL_SAMPLE_PROVIDER · $EXPORT_LABEL_SAMPLE_MODEL"
    ExportModelLabelMode.CUSTOM -> renderExportLabelTemplate(
        template = config.customTemplate,
        providerName = EXPORT_LABEL_SAMPLE_PROVIDER,
        modelName = EXPORT_LABEL_SAMPLE_MODEL,
        modelId = EXPORT_LABEL_SAMPLE_MODEL_ID,
        assistantName = EXPORT_LABEL_SAMPLE_ASSISTANT,
        date = EXPORT_LABEL_SAMPLE_DATE,
        time = EXPORT_LABEL_SAMPLE_TIME,
        tokens = EXPORT_LABEL_SAMPLE_TOKENS,
    )
}

/**
 * 渲染自定义文案：占位符替换 + 空值清理。
 *
 * 清理是必要的：`{provider} · {model}` 在提供商缺失时会渲染成 ` · GPT-4o`，
 * 直接显示就是悬空分隔符，故统一收尾（去首尾分隔符、合并重复分隔符、去空括号）。
 *
 * 取不到的字段（无 usage、非 assistant 上下文等）传 `null`，占位符按空串处理，
 * 由 [tidyExportLabel] 把因此产生的悬空分隔符收干净。
 */
fun renderExportLabelTemplate(
    template: String,
    providerName: String?,
    modelName: String?,
    modelId: String?,
    assistantName: String? = null,
    date: String? = null,
    time: String? = null,
    tokens: String? = null,
): String {
    if (template.isBlank()) return ""
    val rendered = template
        .replace(EXPORT_LABEL_MODEL_ID_TOKEN, modelId.orEmpty())
        .replace(EXPORT_LABEL_ASSISTANT_TOKEN, assistantName.orEmpty())
        .replace(EXPORT_LABEL_PROVIDER_TOKEN, providerName.orEmpty())
        .replace(EXPORT_LABEL_MODEL_TOKEN, modelName.orEmpty())
        .replace(EXPORT_LABEL_DATE_TOKEN, date.orEmpty())
        .replace(EXPORT_LABEL_TIME_TOKEN, time.orEmpty())
        .replace(EXPORT_LABEL_TOKENS_TOKEN, tokens.orEmpty())
    return tidyExportLabel(rendered)
}

/** TokenUsage → `{tokens}` 文案；没用量数据或总量为 0 时返回 null（占位符按空处理）。 */
private fun TokenUsage.toExportLabelTokens(): String? {
    val total = if (totalTokens > 0) totalTokens else promptTokens + completionTokens
    return total.takeIf { it > 0 }?.toString()
}

/** `{date}`：ISO 日期（yyyy-MM-dd，`LocalDate.toString()` 即此形）。 */
private fun kotlinx.datetime.LocalDateTime.toExportLabelDate(): String = date.toString()

/** `{time}`：24 小时制 `HH:mm`（时/分补零）。 */
private fun kotlinx.datetime.LocalDateTime.toExportLabelTime(): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

private val EXPORT_LABEL_EMPTY_BRACKETS = Regex("[（(\\[【]\\s*[)）\\]】]")
private val EXPORT_LABEL_EDGE_SEPARATORS =
    Regex("^[\\s·•\\-—–/|,，、:：;；]+|[\\s·•\\-—–/|,，、:：;；]+$")
private val EXPORT_LABEL_REPEATED_SEPARATORS =
    Regex("\\s*([·•\\-—–/|])(?:\\s*[·•\\-—–/|])+\\s*")
private val EXPORT_LABEL_MULTI_SPACE = Regex("[ \\t]{2,}")

/** 空占位符清理（模板替换后收尾）。可见性放宽为 internal 便于单测直接覆盖。 */
internal fun tidyExportLabel(text: String): String {
    if (text.isBlank()) return ""
    var value = text
        .replace('\n', ' ')
        .replace('\r', ' ')
    value = EXPORT_LABEL_EMPTY_BRACKETS.replace(value, "")
    value = EXPORT_LABEL_REPEATED_SEPARATORS.replace(value) { match ->
        " ${match.groupValues[1]} "
    }
    value = EXPORT_LABEL_EDGE_SEPARATORS.replace(value, "")
    value = EXPORT_LABEL_MULTI_SPACE.replace(value, " ")
    return value.trim()
}
