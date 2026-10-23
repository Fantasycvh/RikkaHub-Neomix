package app.androidrik.mediagen.provider.providers.siliconflow

import app.androidrik.mediagen.model.MediaGenerationError
import app.androidrik.mediagen.model.MediaGenerationInput
import app.androidrik.mediagen.model.MediaGenerationModel
import app.androidrik.mediagen.model.MediaGenerationOutput
import app.androidrik.mediagen.model.MediaGenerationRequest
import app.androidrik.mediagen.model.MediaGenerationStatus
import app.androidrik.mediagen.model.MediaGenerationTask
import app.androidrik.mediagen.provider.MediaGenerationProvider
import app.androidrik.mediagen.provider.MediaModelLister
import app.androidrik.mediagen.provider.MediaGenerationProviderSetting
import app.androidrik.mediagen.provider.providers.bearerRequest
import app.androidrik.mediagen.provider.providers.executeJson
import app.androidrik.mediagen.provider.providers.siliconFlowVideoModels
import app.androidrik.mediagen.provider.providers.obj
import app.androidrik.mediagen.provider.providers.postJson
import app.androidrik.mediagen.provider.providers.string
import app.androidrik.mediagen.provider.providers.toUrlOrDataUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * 硅基流动的视频接口：先 POST `/video/submit` 拿到 requestId，再 POST `/video/status` 轮询。
 *
 * 两个接口都是 POST；status 用 GET 会返回 404。图生视频只接受一张输入图，公网地址和 data URI 原样下发，
 * 本地文件由 [toUrlOrDataUri] 编码成 base64 data URI。
 */
class SiliconFlowMediaGenerationProvider(
    private val client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.SiliconFlow>,
    MediaModelLister<MediaGenerationProviderSetting.SiliconFlow> {
    override val id: String = PROVIDER_ID

    /**
     * `GET {baseUrl}/models?type=video`：文档里的 `type` 参数只返回视频模型。
     * 自建/中转地址可能忽略该参数而返回全部模型，所以解析时仍按 id 特征兜底过滤一次。
     */
    override suspend fun listModels(
        setting: MediaGenerationProviderSetting.SiliconFlow,
    ): Result<List<MediaGenerationModel>> = runCatching {
        val httpRequest = bearerRequest(
            "${setting.baseUrl.trimEnd('/')}/models?type=video",
            setting.apiKey,
        ).get().build()
        siliconFlowVideoModels(client.executeJson(httpRequest, id))
    }

    override suspend fun create(
        setting: MediaGenerationProviderSetting.SiliconFlow,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        // 本地首帧要读出来编码成 data URI
        val body = withContext(Dispatchers.IO) { buildSubmitBody(model, request) }
        val httpRequest = bearerRequest("${setting.baseUrl.trimEnd('/')}/video/submit", setting.apiKey)
            .postJson(body)
            .build()
        val root = client.executeJson(httpRequest, id)
        val requestId = root.string("requestId")
            ?: error("SiliconFlow response does not contain requestId")
        MediaGenerationTask(
            id = requestId,
            provider = id,
            model = model.modelId,
            status = MediaGenerationStatus.QUEUED,
            metadata = root,
        )
    }

    override suspend fun query(
        setting: MediaGenerationProviderSetting.SiliconFlow,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = runCatching {
        val body = buildJsonObject { put("requestId", taskId) }
        val httpRequest = bearerRequest("${setting.baseUrl.trimEnd('/')}/video/status", setting.apiKey)
            .postJson(body)
            .build()
        parseTask(client.executeJson(httpRequest, id), taskId, model.modelId)
    }

    internal fun buildSubmitBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        validate(request)
        // 模型级约束：只有 I2V 接受首帧图，T2V 不接受图片输入。
        // 不给这一层校验的话，选错模型会白白发一次注定 400 的请求。
        val requiresFirstFrame = model.modelId.contains("I2V", ignoreCase = true)
        require(requiresFirstFrame || request.inputs.isEmpty()) {
            "SiliconFlow T2V model does not accept image inputs: ${model.modelId}"
        }
        require(!requiresFirstFrame || request.inputs.size == 1) {
            "SiliconFlow I2V model requires exactly one first-frame image: ${model.modelId}"
        }
        // 供应商私有参数（如 negative_prompt）先写入，公共字段再覆盖同名项
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", request.prompt)
        put("image_size", request.resolution)
        request.inputs.forEach { input ->
            require(input is MediaGenerationInput.Image) {
                "SiliconFlow video generation does not support ${input::class.simpleName} input"
            }
            put("image", input.toUrlOrDataUri())
        }
        request.seed?.let { put("seed", it) }
    }

    internal fun parseTask(
        root: JsonObject,
        taskId: String,
        fallbackModel: String? = null,
    ): MediaGenerationTask {
        val status = mapStatus(root.string("status"))
        val outputs = if (status == MediaGenerationStatus.SUCCEEDED) {
            val videos = root.obj("results")?.get("videos") as? JsonArray ?: JsonArray(emptyList())
            videos.mapNotNull { element ->
                val url = (element as? JsonObject)?.string("url") ?: return@mapNotNull null
                MediaGenerationOutput(url = url, mimeType = "video/mp4")
            }
        } else {
            emptyList()
        }
        return MediaGenerationTask(
            id = root.string("requestId") ?: taskId,
            provider = id,
            model = root.string("model") ?: fallbackModel,
            status = status,
            outputs = outputs,
            error = root.string("reason")?.let { MediaGenerationError(message = it) },
            metadata = root,
        )
    }

    private fun validate(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) {
            "SiliconFlow video generation requires a non-empty prompt"
        }
        require(!request.resolution.isNullOrBlank()) {
            "SiliconFlow video generation requires image_size (resolution)"
        }
        require(request.aspectRatio == null) {
            "SiliconFlow video generation uses image_size instead of aspectRatio"
        }
        require(request.durationSeconds == null) {
            "SiliconFlow video generation does not expose duration"
        }
        require(request.generateAudio == null) {
            "SiliconFlow video generation does not expose generateAudio"
        }
        require(request.watermark == null) {
            "SiliconFlow video generation does not expose watermark"
        }
        require(request.count == null || request.count == 1) {
            "SiliconFlow generates one video per task"
        }
        require(request.promptEnhancement == null) {
            "SiliconFlow video generation does not expose prompt enhancement"
        }
        require(request.callbackUrl == null) {
            "SiliconFlow video generation does not expose callbackUrl"
        }
        require(request.inputs.size <= 1) {
            "SiliconFlow video generation accepts at most one image input"
        }
        request.inputs.forEach { input ->
            require(input is MediaGenerationInput.Image) {
                "SiliconFlow video generation does not support ${input::class.simpleName} input"
            }
        }
    }

    private fun mapStatus(status: String?): MediaGenerationStatus = when (status?.lowercase()) {
        "succeed", "succeeded" -> MediaGenerationStatus.SUCCEEDED
        // 任务在队列里
        "inqueue", "queued", "pending" -> MediaGenerationStatus.QUEUED
        "inprogress", "running" -> MediaGenerationStatus.RUNNING
        "failed" -> MediaGenerationStatus.FAILED
        "cancelled", "canceled" -> MediaGenerationStatus.CANCELLED
        else -> MediaGenerationStatus.UNKNOWN
    }

    private companion object {
        const val PROVIDER_ID = "siliconflow"
    }
}
