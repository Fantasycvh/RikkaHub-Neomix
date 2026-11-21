package app.androidrik.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.provider.EmbeddingGenerationParams
import app.androidrik.ai.provider.EmbeddingGenerationResult
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.*
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderQuota
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.runQuotaQuery
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.TextGenerationResult
import app.androidrik.ai.provider.providers.openai.ChatCompletionsAPI
import app.androidrik.ai.provider.providers.openai.OpenAIRequestAuthenticator
import app.androidrik.ai.provider.providers.openai.modelsUrl
import app.androidrik.ai.provider.providers.openai.parseCodexModels
import app.androidrik.ai.provider.providers.openai.ResponseAPI
import app.androidrik.ai.provider.providers.openai.toResponsesEndpoint
import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.common.http.KeyRoulette
import app.androidrik.common.http.configureClientWithProxy
import app.androidrik.ai.util.configureReferHeaders
import app.androidrik.ai.util.json
import app.androidrik.common.http.mergeCustomBody
import app.androidrik.ai.util.applyCustomHeaders
import app.androidrik.ai.util.toHeaders
import app.androidrik.common.http.await
import app.androidrik.common.http.evaluateJsonExpr
import app.androidrik.common.http.getByKey
import app.androidrik.common.http.jsonObjectOrNull
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "OpenAIProvider"

/**
 * 该 baseUrl 是否指向 OpenRouter。
 *
 * 为什么按 **host 精确相等**判定（而不是 contains/后缀）：后缀匹配会把
 * `openrouter.ai.evil.com`、`notopenrouter.ai` 一并命中，而前缀匹配会漏掉带端口的写法
 * （`toHttpUrlOrNull().host` 本身已剥掉端口）。与上游 `85af5b91` 的判定一致。
 */
/**
 * 图片文件的 MIME：仅按扩展名推断（三家图片接口都只接受这几类）。
 * 顶层 internal：既供 provider 内的 multipart 上传用，也供 [buildOpenRouterEditBody] 拼 data URL 用。
 */
internal fun File.imageMediaType(): String = when (extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "image/png"
}

internal fun isOpenRouter(baseUrl: String): Boolean =
    baseUrl.toHttpUrlOrNull()?.host?.lowercase() == "openrouter.ai"

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null,
) : Provider<ProviderSetting.OpenAI> {
    // 默认顺序模式（优先级语义：列表顺序即优先级，首个=首选，失败按序切下一个）；
    // LRU 保留为可选（未来配置切换），当前统一走默认顺序
    private val keyRoulette = KeyRoulette.default()

    private val authenticator = OpenAIRequestAuthenticator(keyRoulette)

    private val chatCompletionsAPI =
        ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI =
        ResponseAPI(client = client, keyRoulette = keyRoulette)


    override suspend fun fetchModelCatalog(providerSetting: ProviderSetting.OpenAI): CatalogFetchResult =
        withContext(Dispatchers.IO) {
            val scope = CatalogScope(providerSetting.id.toString(), listOf("openai"))
            collectCatalogPages(scope) {
                val request = Request.Builder().url(providerSetting.modelsUrl()).get()
                    .applyCustomHeaders(providerSetting)
                    .apply { authenticator.authenticateApiKey(this, providerSetting) }.build()
                parseStandardCatalog(client.configureClientWithProxy(providerSetting.proxy).catalogJson(request), cursorPagination = false)
            }
        }

    override suspend fun getQuota(providerSetting: ProviderSetting.OpenAI): ProviderQuota? {
        return client.configureClientWithProxy(providerSetting.proxy).runQuotaQuery(providerSetting) {
            keyRoulette.next(providerSetting.effectiveApiKeys, providerSetting.id.toString())
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            endpoint = providerSetting.toResponsesEndpoint(params, authenticator),
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            endpoint = providerSetting.toResponsesEndpoint(params, authenticator),
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.effectiveApiKeys, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .applyCustomHeaders(providerSetting, params.customHeaders)
            .build()

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }
}
