package app.androidrik.rikkahub.data.export

import app.androidrik.ai.core.MessageRole
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

    /** 自定义文案，支持 [EXPORT_LABEL_PROVIDER_TOKEN] / [EXPORT_LABEL_MODEL_TOKEN] / [EXPORT_LABEL_MODEL_ID_TOKEN] 占位符。 */
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

/** 默认自定义文案（与内置的「提供商 · 模型」档同形）。 */
const val DEFAULT_EXPORT_LABEL_TEMPLATE: String = "{provider} · {model}"

const val EXPORT_LABEL_PROVIDER_TOKEN: String = "{provider}"
const val EXPORT_LABEL_MODEL_TOKEN: String = "{model}"
const val EXPORT_LABEL_MODEL_ID_TOKEN: String = "{modelId}"

/** 预览用的示例值（设置页 / 编辑面板里展示「文案长什么样」）。 */
const val EXPORT_LABEL_SAMPLE_PROVIDER: String = "OpenAI"
const val EXPORT_LABEL_SAMPLE_MODEL: String = "GPT-4o"
const val EXPORT_LABEL_SAMPLE_MODEL_ID: String = "gpt-4o"

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
 */
fun resolveExportModelLabel(
    message: UIMessage,
    providers: List<ProviderSetting>,
    config: ExportDisplayConfig,
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
        )
    }
    return ResolvedExportModelLabel(iconSeed = iconSeed, label = label.trim())
}

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
    )
}

/**
 * 渲染自定义文案：占位符替换 + 空值清理。
 *
 * 清理是必要的：`{provider} · {model}` 在提供商缺失时会渲染成 ` · GPT-4o`，
 * 直接显示就是悬空分隔符，故统一收尾（去首尾分隔符、合并重复分隔符、去空括号）。
 */
fun renderExportLabelTemplate(
    template: String,
    providerName: String?,
    modelName: String?,
    modelId: String?,
): String {
    if (template.isBlank()) return ""
    val rendered = template
        .replace(EXPORT_LABEL_MODEL_ID_TOKEN, modelId.orEmpty())
        .replace(EXPORT_LABEL_PROVIDER_TOKEN, providerName.orEmpty())
        .replace(EXPORT_LABEL_MODEL_TOKEN, modelName.orEmpty())
    return tidyExportLabel(rendered)
}

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

