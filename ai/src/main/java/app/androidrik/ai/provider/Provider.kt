package app.androidrik.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.core.Tool
import app.androidrik.ai.ui.ImageGenSize
import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun fetchModelCatalog(providerSetting: T): CatalogFetchResult = CatalogFetchResult.Unsupported()

    suspend fun listModels(providerSetting: T): List<Model> = fetchModelCatalog(providerSetting).candidateModels()

    /**
     * 查询额度（对齐上游 #2186 的余额体系）。返回 null = 该提供商没有额度可查。
     *
     * API Key 类提供商走用户配置的 [QuotaQuery]（见 `runQuotaQuery`）；账号类由各自实现
     * 写死查询地址（P4 落地）。
     */
    suspend fun getQuota(providerSetting: T): ProviderQuota? = null

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported")
    }

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }
}

/**
 * 非流式生成结果（移植自上游 #1648）。
 *
 * 与 [StreamChunk] 体系配套：provider 的 `generateText` 返回完整消息 + 终态信息，
 * 消费端用 `List<UIMessage>.handleTextGenerationResult(...)` 合并进消息列表。
 */
@Serializable
data class TextGenerationResult(
    val id: String = "",
    val model: String = "",
    val message: UIMessage,
    val finishReason: String? = null,
    val usage: TokenUsage? = null,
)

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
    /**
     * 会话 ID（上游 #2186）：作为 Responses 的 `prompt_cache_key`、以及部分网关的会话头，
     * 同一会话的请求能命中服务端提示缓存。
     *
     * ⚠️ 与上游的偏离：上游默认值是 `Uuid.random().toString()`，本仓默认 **null**——
     * 本仓的 `prompt_cache_key` 在 Codex 订阅态下由 `codexPromptCacheKey`（provider+model 稳定键）
     * 兜底，随机默认值会破坏这段既有行为与既有单测；app 层尚未接入会话 ID，等接入后显式传入。
     */
    val sessionId: String? = null,
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val size: String = ImageGenSize.AUTO.value,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
