package app.androidrik.ai.provider
import app.androidrik.common.http.CustomHeader
import app.androidrik.common.http.CustomBody

import app.androidrik.ai.core.ReasoningOverride
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.uuid.Uuid

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    /**
     * 是否在**选择器**中提供该模型。
     *
     * 语义与 [ProviderSetting.enabled] 对齐（同一个取舍）：停用 = **不再出现在模型选择列表**里，
     * 而**不是**「阻断使用」——已经选中它的助手/会话照旧可用，`Settings.findModelById` 也不看本字段。
     * 这样停用不会静默改掉用户既有配置（也就不会有「请求究竟发去了哪」的意外）；
     * 目的只是把不用的模型从长列表里收起来。
     *
     * 默认 true + 序列化缺省 ⇒ **旧数据零迁移**（`JsonInstant` 开了 `ignoreUnknownKeys`）。
     */
    val enabled: Boolean = true,
    /** 模型上下文窗口大小（token 数），拉取模型列表时解析或注册表内置；null=未知 */
    val contextLength: Int? = null,
    /** 用户对该模型手动指定的上下文窗口（覆盖 [contextLength]），用于「已使用上下文」占用显示 */
    val customContextLength: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    /**
     * 思考强度映射的用户覆盖（effort 文本 / budget 数值）。
     * 只影响选中具体档位时发出的值,OFF/AUTO 开关语义不变;null = 跟随提供商兜底与内置映射。
     * 见 [app.androidrik.ai.core.ReasoningOverride]。
     */
    val reasoningOverride: ReasoningOverride? = null,
    val providerOverwrite: ProviderSetting? = null,
)

/** 空格分隔、顺序无关的 AND 搜索，每个关键词可匹配模型 ID 或显示名称。 */
fun List<Model>.filterByKeywords(query: String): List<Model> {
    val keywords = query.split(" ").filter { it.isNotBlank() }
    if (keywords.isEmpty()) return this
    return filter { model ->
        keywords.all { keyword ->
            model.modelId.contains(keyword, ignoreCase = true) ||
                model.displayName.contains(keyword, ignoreCase = true)
        }
    }
}

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()

    // https://api-docs.deepseek.com/zh-cn/guides/web_tools/
    // Chat Completions / Responses 只声明 type；Messages 兼容层还需 name（见 DeepSeek 适配备料 01 §8）
    @Serializable
    @SerialName("deepseek_web_search")
    data object DeepSeekWebSearch : BuiltInTools()

    @Serializable
    @SerialName("deepseek_read_url")
    data object DeepSeekReadUrl : BuiltInTools()
}

const val DEEPSEEK_WEB_TOOLS_MODEL_ID = "deepseek-flash"

val BuiltInTools.isDeepSeekWebTool: Boolean
    get() = this == BuiltInTools.DeepSeekWebSearch || this == BuiltInTools.DeepSeekReadUrl

/** DeepSeek documents these server tools for deepseek-flash on its official API. */
fun Model.supportsDeepSeekWebTools(provider: ProviderSetting?): Boolean =
    provider is ProviderSetting.OpenAI &&
        supportsDeepSeekWebTools(provider.baseUrl.toHttpUrlOrNull())

internal fun Model.supportsDeepSeekWebTools(endpoint: HttpUrl?): Boolean =
    modelId == DEEPSEEK_WEB_TOOLS_MODEL_ID &&
        endpoint?.isHttps == true && endpoint.host == "api.deepseek.com"

fun Model.hasBuiltInSearch(provider: ProviderSetting?): Boolean =
    BuiltInTools.Search in tools ||
        (BuiltInTools.DeepSeekWebSearch in tools && supportsDeepSeekWebTools(provider))

/**
 * 原生延迟工具加载（Deferred Tool Loading）支持的协议形态：
 * - [NONE]：不支持（Chat Completions、`deepseek-v4-pro`、启用 DeepSeek 内置联网工具、或旧版模型）
 * - [ANTHROPIC_DEFER_LOADING]：Messages API 的 `defer_loading: true` + `tool_reference`
 * - [RESPONSES_TOOL_SEARCH]：Responses API 的 `type="tool_search", execution="client"` + `tool_search_call`/`tool_search_output`
 */
enum class DeferredToolProtocol {
    NONE,
    ANTHROPIC_DEFER_LOADING,
    RESPONSES_TOOL_SEARCH,
}

// Anthropic 官方支持 defer_loading 的模型系列（Claude 4.5+ / 5 系列；4.1 及更早型号不支持会报 400）
private val ANTHROPIC_DEFER_LOADING_MODEL_REGEX =
    Regex("""claude-(?:fable|mythos|sonnet|opus|haiku)-(?:[5-9]|4-[5-9])""", RegexOption.IGNORE_CASE)

/** 判定当前模型在指定提供商下可使用的原生延迟加载协议。 */
fun Model.deferredToolProtocol(provider: ProviderSetting?): DeferredToolProtocol {
    if (ModelAbility.TOOL !in abilities) return DeferredToolProtocol.NONE
    return when (provider) {
        is ProviderSetting.OpenAI -> deferredToolProtocol(
            endpoint = provider.baseUrl.toHttpUrlOrNull(),
            isOpenAIResponses = provider.useResponseApi,
            isClaude = false,
        )
        is ProviderSetting.Claude -> deferredToolProtocol(
            endpoint = provider.baseUrl.toHttpUrlOrNull(),
            isOpenAIResponses = false,
            isClaude = true,
        )
        else -> DeferredToolProtocol.NONE
    }
}

internal fun Model.deferredToolProtocol(
    endpoint: HttpUrl?,
    isOpenAIResponses: Boolean,
    isClaude: Boolean,
): DeferredToolProtocol {
    if (ModelAbility.TOOL !in abilities) return DeferredToolProtocol.NONE
    if (endpoint == null || !endpoint.isHttps) return DeferredToolProtocol.NONE
    val host = endpoint.host.lowercase()
    if (host == "api.deepseek.com") {
        if (modelId != DEEPSEEK_WEB_TOOLS_MODEL_ID) return DeferredToolProtocol.NONE
        // 硬互斥：DeepSeek 延迟加载不可与 deepseek_web_search / deepseek_read_url 共存
        if (tools.any { it.isDeepSeekWebTool }) return DeferredToolProtocol.NONE
        return when {
            isOpenAIResponses -> DeferredToolProtocol.RESPONSES_TOOL_SEARCH
            isClaude -> DeferredToolProtocol.ANTHROPIC_DEFER_LOADING
            else -> DeferredToolProtocol.NONE
        }
    }
    if (host == "api.anthropic.com" && isClaude) {
        return if (ANTHROPIC_DEFER_LOADING_MODEL_REGEX.containsMatchIn(modelId)) {
            DeferredToolProtocol.ANTHROPIC_DEFER_LOADING
        } else {
            DeferredToolProtocol.NONE
        }
    }
    return DeferredToolProtocol.NONE
}



