package app.androidrik.mediagen.provider.providers.openrouter

import app.androidrik.common.http.KeyRoulette
import app.androidrik.common.http.configureClientWithProxy
import app.androidrik.common.http.mergeCustomBody
import app.androidrik.mediagen.model.MediaGenerationInput
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaGenerationTask
import app.androidrik.mediagen.provider.MediaGenerationProvider
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.mediagen.provider.effectiveMediaApiKeys
import app.androidrik.mediagen.provider.providers.bearerRequest
import app.androidrik.mediagen.provider.providers.executeJson
import app.androidrik.mediagen.provider.providers.openai.OpenAIMediaGenerationProvider
import app.androidrik.mediagen.provider.providers.postJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.io.encoding.Base64

/**
 * OpenRouter 图片适配器（M3a-3 parity，移植旧链路 `editImageWithInputReferences` / 上游 #1993）：
 *
 * OpenRouter 只实现**生成端点** `/images/generations`，没有 `/images/edits`（打过去必 404）——
 * 参考图改由生成接口的 `input_references`（base64 data URL）随 JSON 传入。生成与编辑同端点，
 * 仅请求体不同。视频等异步能力走各自的适配器（veo 等待有厂商接口后再接）。
 */
class OpenRouterMediaGenerationProvider(
    private val client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.OpenRouter> {
    override val id: String = PROVIDER_ID

    // 顺序模式（列表顺序即优先级），与 OpenAI 适配器及旧链路口径一致
    private val keyRoulette = KeyRoulette.default()

    // 响应结构（data[].b64_json/url）与 OpenAI 图像接口一致，解析直接复用
    private val openAIParser = OpenAIMediaGenerationProvider(client)

    private fun apiKey(setting: MediaGenerationProviderSetting.OpenRouter): String =
        keyRoulette.next(setting.effectiveMediaApiKeys, setting.id.toString())

    private fun OkHttpClient.withProxy(setting: MediaGenerationProviderSetting.OpenRouter): OkHttpClient =
        configureClientWithProxy(setting.proxy)

    private fun Request.Builder.modelHeaders(model: MediaGenerationModel): Request.Builder = apply {
        model.customHeaders.filter { it.name.isNotBlank() }.forEach { addHeader(it.name, it.value) }
    }

    override suspend fun create(
        setting: MediaGenerationProviderSetting.OpenRouter,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        require(!request.prompt.isNullOrBlank()) { "OpenRouter image generation requires a non-empty prompt" }
        val url = "${setting.baseUrl.trimEnd('/')}/images/generations"
        val body = if (request.inputs.isEmpty()) buildGenerationBody(model, request) else buildEditBody(model, request)
        val httpRequest = bearerRequest(url, apiKey(setting))
            .modelHeaders(model)
            .postJson(body)
            .build()
        openAIParser.parseTask(client.withProxy(setting).executeJson(httpRequest, id), model.modelId)
    }

    /** 生成请求体：公共字段与 OpenAI 适配器同形；模型级 customBodies 最后合并（custom 覆盖公共字段）。 */
    internal fun buildGenerationBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", request.prompt)
        request.count?.let { put("n", it) }
        if (shouldSendSize(model, request.resolution)) put("size", request.resolution)
    }.mergeCustomBody(model.customBodies)

    /**
     * 编辑请求体：参考图以 base64 data URL 数组放进 `input_references`（OpenRouter 无编辑端点）。
     * 尺寸沿用「auto 不写进请求体」的既有约定。
     */
    internal fun buildEditBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", request.prompt.orEmpty())
        request.count?.let { put("n", it) }
        if (shouldSendSize(model, request.resolution)) put("size", request.resolution)
        putJsonArray("input_references") {
            request.inputs.forEach { input ->
                require(input is MediaGenerationInput.Image) {
                    "OpenRouter image editing does not support ${input::class.simpleName} input"
                }
                val file = File(input.url.removePrefix("file://"))
                require(file.exists()) { "Image file does not exist: ${input.url}" }
                addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") {
                        put("url", "data:${file.mediaType()};base64," + Base64.encode(file.readBytes()))
                    }
                }
            }
        }
    }.mergeCustomBody(model.customBodies)

    // 与生成/编辑两条既有路径同约定：`auto` 不写进请求体；模型级 customBodies 覆盖 size 语义时
    //（骨架键命中）交由用户自担，此处只看字面值
    private fun shouldSendSize(model: MediaGenerationModel, size: String?): Boolean {
        if (size.isNullOrBlank() || size.equals("auto", ignoreCase = true)) return false
        return model.customBodies.none { it.key == "size" }
    }

    private fun File.mediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private companion object {
        const val PROVIDER_ID = "openrouter"
    }
}
