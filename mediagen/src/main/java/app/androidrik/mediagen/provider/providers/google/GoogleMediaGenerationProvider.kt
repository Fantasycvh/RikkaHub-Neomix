package app.androidrik.mediagen.provider.providers.google

import app.androidrik.common.http.KeyRoulette
import app.androidrik.common.http.configureClientWithProxy
import app.androidrik.common.http.mergeCustomBody
import app.androidrik.mediagen.model.MediaGenerationInput
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationOutput
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaGenerationStatus
import app.androidrik.mediagen.model.MediaGenerationTask
import app.androidrik.mediagen.provider.MediaGenerationProvider
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.mediagen.provider.effectiveMediaApiKeys
import app.androidrik.mediagen.provider.providers.executeJson
import app.androidrik.mediagen.provider.providers.google.vertex.ServiceAccountTokenProvider
import app.androidrik.mediagen.provider.providers.postJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * Google (Gemini) 图片适配器（M3a-4，平移旧链路 `GoogleProvider.generateImage/editImage`，
 * 98ea41fc8）：复用 generateContent 端点与聊天路径的 IMAGE 输出模态请求形态。
 *
 * - 鉴权/代理/Vertex 服务账号：AI Studio 走 `x-goog-api-key`（vertex 走 `?key=` 查询参数），
 *   服务账号走 OAuth Bearer（[ServiceAccountTokenProvider]）；代理按 setting 派生 client。
 * - 张数按 n 语义循环（Gemini 图片模型一次请求只稳定产一张，candidateCount 不稳）。
 * - 显式尺寸映射 aspectRatio（auto/未知值不下发）；safetySettings 全 OFF 与聊天同口径。
 * - 响应收集所有候选的 inlineData（跳过思考草稿图）；无图时把模型文本/拦截原因透传成错误。
 */
class GoogleMediaGenerationProvider(
    private val client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.Google> {
    override val id: String = PROVIDER_ID

    // 顺序模式（列表顺序即优先级），与 OpenAI/OpenRouter 适配器及旧链路口径一致
    private val keyRoulette = KeyRoulette.default()

    private val serviceAccountTokenProvider by lazy { ServiceAccountTokenProvider(client) }

    private fun OkHttpClient.withProxy(setting: MediaGenerationProviderSetting.Google): OkHttpClient =
        configureClientWithProxy(setting.proxy)

    /** 供应商级 + 模型级自定义请求头（M3b-1 映射载体 + M3a-2 parity，对齐旧链路两级下发）。 */
    private fun Request.Builder.customHeaders(
        setting: MediaGenerationProviderSetting.Google,
        model: MediaGenerationModel,
    ): Request.Builder = apply {
        setting.customHeaders.filter { it.name.isNotBlank() }.forEach { addHeader(it.name, it.value) }
        model.customHeaders.filter { it.name.isNotBlank() }.forEach { addHeader(it.name, it.value) }
    }

    override suspend fun create(
        setting: MediaGenerationProviderSetting.Google,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        require(!request.prompt.isNullOrBlank()) { "Google image generation requires a non-empty prompt" }
        // Gemini 图片模型一次请求只稳定产一张（nano banana 系）；candidateCount 对图片输出
        // 模型的多候选支持不稳定——按 OpenAI 路径的 n 语义循环补齐张数。
        val outputs = buildList {
            repeat(request.count?.coerceAtLeast(1) ?: 1) {
                val body = if (request.inputs.isEmpty()) buildGenerationBody(model, request)
                else buildEditBody(model, request)
                addAll(requestImage(setting, model, body))
            }
        }
        MediaGenerationTask(
            // 同步接口没有服务端任务 ID，与 OpenAI 适配器同款处理
            id = Uuid.random().toString(),
            provider = id,
            model = model.modelId,
            status = MediaGenerationStatus.SUCCEEDED,
            outputs = outputs,
            metadata = JsonObject(emptyMap()),
        )
    }

    /** 生成请求体：单条 user 文本 + IMAGE 输出模态；模型级 customBody 合并进顶层（与聊天路径同约定）。 */
    internal fun buildGenerationBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", request.prompt) })
                }
            })
        })
        put("generationConfig", imageGenerationConfig(request.resolution))
        put("safetySettings", safetySettingsOff())
    }.mergeCustomBody(model.customBodies)

    /** 编辑请求体：参考图在前、提示词在后（Gemini 官方编辑示例顺序），参考图转 inlineData。 */
    internal suspend fun buildEditBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject {
        val references = withContext(Dispatchers.IO) {
            request.inputs.map { input ->
                require(input is MediaGenerationInput.Image) {
                    "Google image editing does not support ${input::class.simpleName} input"
                }
                referenceImagePart(input.url)
            }
        }
        return buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        references.forEach { add(it) }
                        add(buildJsonObject { put("text", request.prompt) })
                    }
                })
            })
            put("generationConfig", imageGenerationConfig(request.resolution))
            put("safetySettings", safetySettingsOff())
        }.mergeCustomBody(model.customBodies)
    }

    /** ImageGenSize 的 OpenAI 系数值 → Gemini aspectRatio；`auto` 与未知值都不下发（保守口径）。 */
    private fun imageAspectRatio(size: String?): String? = when (size) {
        null, "", "auto" -> null
        "1024x1024", "256x256", "512x512" -> "1:1"
        "1536x1024" -> "3:2"
        "1024x1536" -> "2:3"
        "1792x1024" -> "16:9"
        "1024x1792" -> "9:16"
        else -> null
    }

    private fun imageGenerationConfig(size: String?): JsonObject = buildJsonObject {
        put("responseModalities", buildJsonArray {
            add(JsonPrimitive("TEXT"))
            add(JsonPrimitive("IMAGE"))
        })
        imageAspectRatio(size)?.let {
            put("imageConfig", buildJsonObject { put("aspectRatio", it) })
        }
    }

    /** 全类目 OFF（与聊天路径同一口径）：图片输出模型对安全拦截更敏感，保持与聊天可用性一致。 */
    private fun safetySettingsOff(): JsonArray = buildJsonArray {
        for (category in SAFETY_CATEGORIES) {
            add(buildJsonObject {
                put("category", category)
                put("threshold", "OFF")
            })
        }
    }

    private fun referenceImagePart(path: String): JsonObject {
        val file = File(path.removePrefix("file://"))
        require(file.exists()) { "Image file does not exist: $path" }
        return buildJsonObject {
            put("inlineData", buildJsonObject {
                put("mimeType", file.imageMediaType())
                put("data", Base64.encode(file.readBytes()))
            })
        }
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    // —— 与旧链路 GoogleProvider 同款的 URL/鉴权形态 ——

    private fun buildUrl(setting: MediaGenerationProviderSetting.Google, modelId: String): HttpUrl {
        val url = if (setting.vertexAI && setting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/${setting.projectId}/locations/${setting.location}/publishers/google/models/$modelId:generateContent"
        } else if (setting.vertexAI) {
            "https://aiplatform.googleapis.com/v1/publishers/google/models/$modelId:generateContent"
        } else {
            "${setting.baseUrl.trimEnd('/')}/models/$modelId:generateContent"
        }
        return url.toHttpUrl()
    }

    private suspend fun transformRequest(setting: MediaGenerationProviderSetting.Google, request: Request): Request {
        return if (setting.vertexAI && setting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = setting.serviceAccountEmail.trim(),
                // 服务账号 JSON 里的 private_key 带 \n 转义，与旧链路同款展开
                privateKeyPem = setting.privateKey.trim().replace("\\n", "\n"),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            val key = keyRoulette.next(setting.effectiveMediaApiKeys, setting.id.toString())
            if (setting.vertexAI) {
                request.newBuilder()
                    .url(request.url.newBuilder().addQueryParameter("key", key).build())
                    .build()
            } else {
                request.newBuilder()
                    .addHeader("x-goog-api-key", key)
                    .build()
            }
        }
    }

    private suspend fun requestImage(
        setting: MediaGenerationProviderSetting.Google,
        model: MediaGenerationModel,
        requestBody: JsonObject,
    ): List<MediaGenerationOutput> = withContext(Dispatchers.IO) {
        val request = transformRequest(
            setting = setting,
            request = Request.Builder()
                .url(buildUrl(setting, model.modelId))
                .postJson(requestBody)
                .customHeaders(setting, model)
                .build()
        )
        parseImageOutputs(client.withProxy(setting).executeJson(request, id))
    }

    private fun parseImageOutputs(root: JsonObject): List<MediaGenerationOutput> {
        val candidates = root["candidates"] as? JsonArray
        if (candidates.isNullOrEmpty()) {
            val blockReason = (root["promptFeedback"] as? JsonObject)
                ?.get("blockReason")?.jsonPrimitive?.contentOrNull
            throw IllegalStateException(
                if (blockReason != null) "Prompt blocked: $blockReason" else "No candidates in response"
            )
        }
        val images = mutableListOf<MediaGenerationOutput>()
        val texts = mutableListOf<String>()
        for (candidate in candidates) {
            val parts = ((candidate as? JsonObject)?.get("content") as? JsonObject)
                ?.get("parts") as? JsonArray ?: continue
            for (part in parts) {
                val obj = part.jsonObject
                // 思考过程中的草稿图不进产物（与聊天解析同一口径）
                if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) continue
                val inline = obj["inlineData"] as? JsonObject
                if (inline == null) {
                    obj["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }?.let(texts::add)
                    continue
                }
                val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: ""
                if (mime.startsWith("image/") && data.isNotBlank()) {
                    images.add(MediaGenerationOutput(mimeType = mime, data = Base64.decode(data)))
                }
            }
        }
        if (images.isEmpty()) {
            // 模型可能纯文本拒绝（安全拦截/提示改写）——把文本带回给调用方，别只给一句 "no image"
            throw IllegalStateException(texts.joinToString("\n").ifBlank { "No image in response" })
        }
        return images
    }

    private companion object {
        const val PROVIDER_ID = "google"
        val SAFETY_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
            "HARM_CATEGORY_CIVIC_INTEGRITY",
        )
    }
}
