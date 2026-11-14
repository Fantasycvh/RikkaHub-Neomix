package app.androidrik.ai.provider

import app.androidrik.ai.core.ReasoningOverride
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

/**
 * 模型是否带「搜索类」内置工具（Gemini/GPT 的 [BuiltInTools.Search] 或 DeepSeek 的
 * [BuiltInTools.DeepSeekWebSearch]）。
 *
 * 双计费守卫判据（DeepSeek 适配备料 03 §5.1）：模型任一搜索类内置工具开启时，
 * 助手的外挂搜索（`search_web`/`scrape_web`）不再注册，否则同一问题走两条搜索路径，
 * token 之外按次双重计费（¥5/千次）。[BuiltInTools.DeepSeekReadUrl] 不算搜索——
 * 它只抓取用户消息里的 URL，与外挂搜索不冲突。
 */
val Model.hasBuiltInSearch: Boolean
    get() = BuiltInTools.Search in tools || BuiltInTools.DeepSeekWebSearch in tools



