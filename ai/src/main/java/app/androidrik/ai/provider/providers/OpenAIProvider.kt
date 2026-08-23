package app.androidrik.ai.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import app.androidrik.ai.provider.EmbeddingGenerationParams
import app.androidrik.ai.provider.EmbeddingGenerationResult
import app.androidrik.ai.provider.ImageEditParams
import app.androidrik.ai.provider.ImageGenerationParams
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.providers.openai.ChatCompletionsAPI
import app.androidrik.ai.provider.providers.openai.ResponseAPI
import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.ai.ui.MessageChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.util.KeyRoulette
import app.androidrik.ai.util.configureClientWithProxy
import app.androidrik.ai.util.configureReferHeaders
import app.androidrik.ai.util.json
import app.androidrik.ai.util.mergeCustomBody
import app.androidrik.ai.util.applyCustomHeaders
import app.androidrik.ai.util.toHeaders
import app.androidrik.common.http.await
import app.androidrik.common.http.evaluateJsonExpr
import app.androidrik.common.http.getByKey
import app.androidrik.common.http.jsonObjectOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * New API 的管理接口（/api/...）挂在站点根路径，不在 OpenAI 兼容的 /v1 下。
 * 因此当 balance 路径以 /api/ 开头且 baseUrl 以 /v1 结尾时，先剥掉 /v1 再拼接。
 */
private fun balanceApiUrl(baseUrl: String, path: String): String {
    if (path.startsWith("http")) return path
    val trimmed = baseUrl.trimEnd('/')
    val root = if (path.startsWith("/api/") && trimmed.endsWith("/v1")) {
        trimmed.removeSuffix("/v1")
    } else {
        trimmed
    }
    return "$root$path"
}

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .addHeader("Authorization", "Bearer $key")
                .get()
                .applyCustomHeaders(providerSetting.customHeaders)
                .build()

            val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                Model(
                    modelId = id,
                    displayName = id,
                )
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val balanceOption = providerSetting.balanceOption
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        if (balanceOption.sources.isEmpty()) {
            // 旧逻辑：单接口模式
            val url = balanceApiUrl(providerSetting.baseUrl, balanceOption.apiPath)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .get()
                .applyCustomHeaders(providerSetting.customHeaders)
                .build()
            val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to get balance: ${response.code} ${response.body?.string()}")
            }

            val bodyStr = response.body.string()
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObjectOrNull
                ?: error("Balance API response is not a JSON object: ${bodyStr.take(200)}")
            val value = bodyJson.getByKey(balanceOption.resultPath)
            val digitalValue = value.toFloatOrNull()
            if (digitalValue != null) {
                "%.2f".format(digitalValue)
            } else {
                value
            }
        } else {
            // 新逻辑：多接口模式 — 并行调用各 source，合并为单一 JSON，求值表达式
            val merged = coroutineScope {
                val results = balanceOption.sources.map { source ->
                    async {
                        val url = balanceApiUrl(providerSetting.baseUrl, source.apiPath)
                        val request = Request.Builder()
                            .url(url)
                            .addHeader("Authorization", "Bearer $key")
                            .get()
                            .applyCustomHeaders(providerSetting.customHeaders)
                            .build()
                        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
                        if (!response.isSuccessful) {
                            error("Failed to get balance source '${source.name}': ${response.code} ${response.body?.string()}")
                        }
                        val bodyStr = response.body.string()
                        val bodyJson = json.parseToJsonElement(bodyStr).jsonObjectOrNull
                            ?: error("Balance API response for source '${source.name}' is not a JSON object: ${bodyStr.take(200)}")
                        val rawValue = bodyJson.getByKey(source.resultPath)
                        source.name to rawValue
                    }
                }
                val pairs = results.awaitAll()
                buildJsonObject {
                    pairs.forEach { (name, rawValue) ->
                        val num = rawValue.toFloatOrNull()
                        if (num != null) {
                            put(name, JsonPrimitive(num))
                        } else {
                            put(name, JsonPrimitive(rawValue))
                        }
                    }
                }
            }
            val result = evaluateJsonExpr(balanceOption.expression, merged)
            val digitalValue = result.toFloatOrNull()
            if (digitalValue != null) {
                "%.2f".format(digitalValue)
            } else {
                result
            }
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
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
    ): MessageChunk = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
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

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
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
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .applyCustomHeaders(providerSetting.customHeaders)
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

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                
                val isGrok = providerSetting.baseUrl.contains("x.ai", ignoreCase = true) || 
                    params.model.modelId.contains("grok", ignoreCase = true)
                
                if (params.size.isNotBlank() && !isGrok) {
                    put("size", params.size)
                }
            }
                .mergeCustomBody(params.customBody)
        )

        Log.i(TAG, "generateImage: $requestBody")

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .applyCustomHeaders(providerSetting.customHeaders)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        if (params.size.isNotBlank()) {
            bodyBuilder.addFormDataPart("size", params.size)
        }

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/edits")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
            .applyCustomHeaders(providerSetting.customHeaders)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to edit image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    private suspend fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(
                    data = b64Json,
                    mimeType = outputFormat.toImageMimeType(),
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        val base64 = Base64.encode(body.bytes())

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType
        )
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
