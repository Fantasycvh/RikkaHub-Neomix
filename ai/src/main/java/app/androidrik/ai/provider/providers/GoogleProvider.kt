package app.androidrik.ai.provider.providers

import app.androidrik.ai.util.checkAttachmentLimit
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.resolveReasoningOverride
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.*
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.common.http.CustomHeader
import app.androidrik.ai.provider.ModelType
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderQuota
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.runQuotaQuery
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.TextGenerationResult
import app.androidrik.ai.provider.providers.google.GoogleStreamDecoder
import app.androidrik.ai.provider.providers.google.InteractionsAPI
import app.androidrik.ai.provider.providers.google.googleServerTool
import app.androidrik.ai.provider.providers.google.googleThoughtMetadata
import app.androidrik.ai.provider.providers.google.stringOrNull
import app.androidrik.ai.provider.providers.google.withGoogleSignature
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.ImageGenSize
import app.androidrik.ai.ui.ImageGenerationItem
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.provider.providers.vertex.ServiceAccountTokenProvider
import app.androidrik.ai.registry.ModelRegistry
import app.androidrik.ai.ui.GoogleThoughtMetadata
import app.androidrik.ai.ui.ServerToolMetadata
import app.androidrik.ai.ui.ServerToolProtocol
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.mergeMetadata
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.ui.toMetadata
import app.androidrik.common.http.KeyRoulette
import app.androidrik.common.http.configureClientWithProxy
import app.androidrik.ai.util.configureReferHeaders
import app.androidrik.ai.util.configureSessionHeaders
import app.androidrik.ai.util.encodeBase64
import app.androidrik.ai.util.ensureArrayItems
import app.androidrik.ai.util.json
import app.androidrik.ai.util.mergeCustomBody
import app.androidrik.ai.util.removeElements
import app.androidrik.ai.util.stringSafe
import app.androidrik.ai.util.applyCustomHeaders
import app.androidrik.ai.util.toHeaders
import app.androidrik.common.http.await
import app.androidrik.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.uuid.Uuid
import app.androidrik.ai.util.summarizeRequestBody
import app.androidrik.ai.util.summarizeErrorBody
import app.androidrik.ai.util.summarizeSseEvent

private const val TAG = "GoogleProvider"

class GoogleProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Google> {
    // 默认顺序模式（优先级语义：列表顺序即优先级，首个=首选，失败按序切下一个）；
    // LRU 保留为可选（未来配置切换），当前统一走默认顺序
    private val keyRoulette = KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }
    private val interactionsApi = InteractionsAPI(client, ::transformRequest)

    suspend fun startDeepResearch(setting: ProviderSetting.Google, prompt: String) =
        interactionsApi.startResearch(setting, prompt)

    suspend fun getDeepResearch(setting: ProviderSetting.Google, id: String) =
        interactionsApi.getResearch(setting, id)

    suspend fun cancelDeepResearch(setting: ProviderSetting.Google, id: String) =
        interactionsApi.cancelResearch(setting, id)

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
        } else if (providerSetting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            val key = keyRoulette.next(providerSetting.effectiveApiKeys, providerSetting.id.toString())
            if (providerSetting.vertexAI) {
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

    override suspend fun fetchModelCatalog(providerSetting: ProviderSetting.Google): CatalogFetchResult =
        withContext(Dispatchers.IO) {
            val scope = CatalogScope(providerSetting.id.toString(), listOf(if (providerSetting.vertexAI) "vertex" else "google"))
            try {
                val path = if (providerSetting.vertexAI) "publishers/google/models" else "models"
                // Pin the authenticated request once so every page uses the same credential.
                val initial = transformRequest(providerSetting, Request.Builder()
                    .url(buildUrl(providerSetting, path).newBuilder().addQueryParameter("pageSize", "100").build())
                    .get().applyCustomHeaders(providerSetting).build())
                collectCatalogPages(scope) { cursor ->
                    val url = initial.url.newBuilder().apply { cursor?.let { setQueryParameter("pageToken", it) } }.build()
                    parseGoogleCatalog(client.configureClientWithProxy(providerSetting.proxy)
                        .catalogJson(initial.newBuilder().url(url).build()), providerSetting.vertexAI)
                }
            } catch (e: Exception) {
                catalogFailure(e)
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        if (providerSetting.useInteractionsApi && providerSetting.supportsInteractionsApi) {
            return@withContext interactionsApi.generateText(providerSetting, messages, params)
        }
        val requestBody = buildCompletionRequestBody(providerSetting, messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:generateContent"
            } else {
                "models/${params.model.modelId}:generateContent"
            }
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                    .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureSessionHeaders(url.toString(), params.sessionId)
                .configureReferHeaders(providerSetting.baseUrl)
                .applyCustomHeaders(providerSetting, params.customHeaders)
                .build()
        )

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 阻断响应只有 promptFeedback 没有 candidates（Gemini 文档化行为）——像 streamText
        // 路径一样把 blockReason 作为错误上报，而不是抛 NPE
        val candidates = bodyJson["candidates"]?.jsonArray
        if (candidates.isNullOrEmpty()) {
            val blockReason = bodyJson["promptFeedback"]?.jsonObject
                ?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull
            throw Exception(if (blockReason != null) "Prompt blocked: $blockReason" else "No candidates in response")
        }
        val usage = bodyJson["usageMetadata"]?.jsonObject ?: JsonObject(emptyMap())

        TextGenerationResult(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            message = parseMessage(candidates.first().jsonObject),
            finishReason = null,
            usage = parseUsageMeta(usage),
        )
    }

    override suspend fun getQuota(providerSetting: ProviderSetting.Google): ProviderQuota? =
        client.configureClientWithProxy(providerSetting.proxy).runQuotaQuery(providerSetting) {
            keyRoulette.next(providerSetting.effectiveApiKeys, providerSetting.id.toString())
        }

    /**
     * Gemini 图片生成：复用 generateContent 端点（与聊天路径的 IMAGE 输出模态同一套请求形态，
     * 见 [buildCompletionRequestBody] 的 responseModalities 分支），鉴权/代理/Vertex 全部走
     * 既有 [transformRequest]/[buildUrl]，AI Studio 与 Vertex 服务账号两种形态都能跑。
     */
    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.Google) {
            "Expected Google provider setting"
        }
        // Gemini 图片模型（nano banana 系）一次请求只稳定产一张，candidateCount 对图片输出
        // 模型的多候选支持不稳定——按 OpenAI 路径的 n 语义循环补齐张数。
        repeat(params.numOfImages.coerceAtLeast(1)) {
            requestImage(providerSetting, params.model.modelId, params.customHeaders, imageGenerationBody(params))
                .forEach { emit(it) }
        }
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.Google) {
            "Expected Google provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }
        repeat(params.numOfImages.coerceAtLeast(1)) {
            requestImage(providerSetting, params.model.modelId, params.customHeaders, imageEditBody(params))
                .forEach { emit(it) }
        }
    }

    /** 生成请求体：单条 user 文本 + IMAGE 输出模态；模型级 customBody 合并进顶层（与聊天路径同约定）。 */
    internal fun imageGenerationBody(params: ImageGenerationParams): JsonObject = buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", params.prompt) })
                }
            })
        })
        put("generationConfig", imageGenerationConfig(params.size))
        put("safetySettings", safetySettingsOff())
    }.mergeCustomBody(params.customBody)

    /** 编辑请求体：参考图在前、提示词在后（Gemini 官方编辑示例顺序），参考图转 inlineData。 */
    internal suspend fun imageEditBody(params: ImageEditParams): JsonObject {
        val references = withContext(Dispatchers.IO) {
            params.images.map { path -> referenceImagePart(path) }
        }
        return buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        references.forEach { add(it) }
                        add(buildJsonObject { put("text", params.prompt) })
                    }
                })
            })
            put("generationConfig", imageGenerationConfig(params.size))
            put("safetySettings", safetySettingsOff())
        }.mergeCustomBody(params.customBody)
    }

    /** ImageGenSize 的 OpenAI 系数值 → Gemini aspectRatio；`auto` 与未知值都不下发（保守口径）。 */
    private fun imageAspectRatio(size: String): String? = when (size) {
        ImageGenSize.AUTO.value -> null
        "1024x1024", "256x256", "512x512" -> "1:1"
        "1536x1024" -> "3:2"
        "1024x1536" -> "2:3"
        "1792x1024" -> "16:9"
        "1024x1792" -> "9:16"
        else -> null
    }

    private fun imageGenerationConfig(size: String): JsonObject = buildJsonObject {
        put("responseModalities", buildJsonArray {
            add(JsonPrimitive("TEXT"))
            add(JsonPrimitive("IMAGE"))
        })
        imageAspectRatio(size)?.let {
            put("imageConfig", buildJsonObject { put("aspectRatio", it) })
        }
    }

    private fun referenceImagePart(path: String): JsonObject {
        val file = File(path)
        require(file.exists()) { "Image file does not exist: $path" }
        return buildJsonObject {
            put("inlineData", buildJsonObject {
                put("mimeType", file.imageMediaType())
                put("data", Base64.encode(file.readBytes()))
            })
        }
    }

    private suspend fun requestImage(
        providerSetting: ProviderSetting.Google,
        modelId: String,
        customHeaders: List<CustomHeader>,
        requestBody: JsonObject,
    ): List<ImageGenerationItem> = withContext(Dispatchers.IO) {
        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/$modelId:generateContent"
            } else {
                "models/$modelId:generateContent"
            }
        )
        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
                .configureReferHeaders(providerSetting.baseUrl)
                .applyCustomHeaders(providerSetting, customHeaders)
                .build()
        )
        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to generate image: ${response.code} ${response.body?.string()}")
        }
        parseImageResponse(response.body.string())
    }

    /**
     * 生图响应解析：收集所有候选里的 inlineData 图片（跳过思考草稿图，与聊天解析同口径）。
     * 图片为空时把模型的文本回复/拦截原因作为错误抛出——会话内工具会把 provider 失败转成
     * 文本回给模型（ImageToolTest 口径），纯文本拒绝能透传，比笼统的 "no image" 有用。
     */
    internal fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val candidates = bodyJson["candidates"]?.jsonArray
        if (candidates.isNullOrEmpty()) {
            val blockReason = bodyJson["promptFeedback"]?.jsonObject
                ?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull
            throw Exception(if (blockReason != null) "Prompt blocked: $blockReason" else "No candidates in response")
        }
        val images = mutableListOf<ImageGenerationItem>()
        val texts = mutableListOf<String>()
        for (candidate in candidates) {
            val parts = candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            for (part in parts) {
                val obj = part.jsonObject
                if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) continue
                val inline = obj["inlineData"]?.jsonObject
                if (inline == null) {
                    obj["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }?.let(texts::add)
                    continue
                }
                val mime = inline["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inline["data"]?.jsonPrimitive?.content ?: ""
                if (mime.startsWith("image/") && data.isNotBlank()) {
                    images.add(ImageGenerationItem(data, mime))
                }
            }
        }
        if (images.isEmpty()) {
            throw Exception(texts.joinToString("\n").ifBlank { "No image in response" })
        }
        return images
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = if (providerSetting.useInteractionsApi && providerSetting.supportsInteractionsApi) {
        interactionsApi.streamText(providerSetting, messages, params)
    } else {
        streamGenerateContent(providerSetting, messages, params)
    }

    private fun streamGenerateContent(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildCompletionRequestBody(providerSetting, messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:streamGenerateContent"
            } else {
                "models/${params.model.modelId}:streamGenerateContent"
            }
        ).newBuilder().addQueryParameter("alt", "sse").build()

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                    .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureSessionHeaders(url.toString(), params.sessionId)
                .configureReferHeaders(providerSetting.baseUrl)
                .applyCustomHeaders(providerSetting, params.customHeaders)
                .build()
        )

        Log.i(TAG, "streamText: ${summarizeRequestBody(json, requestBody)}")

        // 每条流一个解码器实例（对齐上游 #1648）；Gemini 没有独立终止事件，连接关闭时
        // 由 decoder.onClosed() 产出 Finish。
        val decoder = GoogleStreamDecoder(
            responseId = Uuid.random().toString(),
            model = params.model.modelId,
        )

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.i(TAG, "onEvent: chars=${data.length}")
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    result.chunks.forEach { chunk ->
                        trySend(chunk).onFailure { e ->
                            Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                        }
                    }
                    if (result.completed) close()
                } catch (e: Throwable) {
                    Log.w(TAG, "onEvent: decode error (${summarizeErrorBody(data)})")
                    close(e)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                var exception = t

                Log.w(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message ?: ""} httpCode=${response?.code}")

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            if (bodyElement is JsonObject) {
                                exception = Exception(
                                    bodyElement["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                                        ?: "unknown"
                                )
                            }
                        } else {
                            exception = Exception("Unknown error: ${response.code}")
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    // 错误体不是 JSON（如网关 502 的 HTML 页）时不要拿解析异常掩盖真实 HTTP 状态
                    exception = Exception("Unknown error: ${response?.code}", e)
                } finally {
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d(TAG, "onClosed: connection closed")
                try {
                    decoder.onClosed().forEach { chunk ->
                        trySend(chunk).onFailure { e ->
                            Log.w(TAG, "onClosed: chunk dropped (${e?.message})")
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onClosed: decode error (${e.message})")
                }
                close()
            }
        }

        val eventSource = EventSources.createFactory(client.configureClientWithProxy(providerSetting.proxy))
                .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private fun buildCompletionRequestBody(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): JsonObject = buildJsonObject {
        // System message if available。
        // 图片输出模式下不写 systemInstruction（保持请求形态不变），但**不能把 system 丢掉**：
        // buildContents 的防重复跳过只在此为 true 时生效，否则第一条 system 会被降级进 contents
        val systemInInstruction = !params.model.outputModalities.contains(Modality.IMAGE)
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && systemInInstruction) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put(
                            "text",
                            systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString { it.text })
                    })
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))

                    // Gemini 3 系列与 4 都用 thinkingLevel（上游 3a9ae690）；其余走 thinkingBudget
                    val useThinkingLevel =
                        ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId) ||
                            ModelRegistry.GEMINI_4.match(modelId = params.model.modelId)

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (useThinkingLevel) {
                                put("thinkingLevel", "minimal")
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            // 模型级覆盖优先,提供商级兜底:effort 文本可作 thinkingLevel,budget 数值可作 thinkingBudget
                            val override = resolveReasoningOverride(params.model, providerSetting)
                            if (useThinkingLevel) {
                                val thinkingLevel = override?.effort ?: when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> "low"
                                    ReasoningLevel.MEDIUM -> "medium"
                                    else -> "high" // HIGH, XHIGH
                                }
                                put("thinkingLevel", thinkingLevel)
                            } else {
                                put("thinkingBudget", override?.budgetTokens ?: params.reasoningLevel.budgetTokens)
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        put(
            "contents",
            buildContents(messages, skipFirstSystem = systemMessage != null && systemInInstruction)
        )

        // Tools（客户端 function declarations 与内置工具共存于同一数组——吸收上游 9851d037；
        // 此前分开两个 put("tools") 互相覆盖，内置工具一开 functionDeclarations 全部失效）
        val useFunctionTools =
            params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        if (useFunctionTools || params.model.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                if (useFunctionTools) {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            params.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    put(
                                        key = "parameters",
                                        element = json.encodeToJsonElement(tool.parameters())
                                            .removeElements(
                                                listOf(
                                                    "const",
                                                    "exclusiveMaximum",
                                                    "exclusiveMinimum",
                                                    "format",
                                                    "additionalProperties",
                                                    // propertyNames 是 JSON Schema 2020-12 关键字，
                                                    // Gemini 的 function declaration 不接受（上游 4391d5a5）。
                                                    // 注意：ai/util/ToolSchemaSize 有一份同源清单（估算用），改这里要一并改。
                                                    "propertyNames",
                                                    "enum",
                                                )
                                            )
                                            .ensureArrayItems()
                                    )
                                })
                            }
                        })
                    })
                }

                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> {
                            add(buildJsonObject {
                                put("googleSearch", buildJsonObject {})
                            })
                        }

                        BuiltInTools.UrlContext -> {
                            add(buildJsonObject {
                                put("urlContext", buildJsonObject {})
                            })
                        }

                        else -> {}
                    }
                }
            })
        }

        val supportsMixedTools = ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId) ||
            ModelRegistry.GEMINI_4.match(modelId = params.model.modelId)
        if (supportsMixedTools && useFunctionTools &&
            params.model.tools.any { it == BuiltInTools.Search || it == BuiltInTools.UrlContext }
        ) {
            put("toolConfig", buildJsonObject { put("includeServerSideToolInvocations", true) })
        }

        // Safety Settings
        put("safetySettings", safetySettingsOff())
    }.mergeCustomBody(params.customBody)

    /** 全类目 OFF（与聊天路径同一口径）：图片输出模型对安全拦截更敏感，保持与聊天可用性一致。 */
    private fun safetySettingsOff(): JsonArray = buildJsonArray {
        for (category in listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
            "HARM_CATEGORY_CIVIC_INTEGRITY",
        )) {
            add(buildJsonObject {
                put("category", category)
                put("threshold", "OFF")
            })
        }
    }

    private fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
        }
    }

    private fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            else -> error("Unknown role $role")
        }
    }

    private fun parseMessage(message: JsonObject): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val parts = parseMessageParts(content["parts"]?.jsonArray)

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        Log.i(TAG, "parseMessage: $groundingMetadata")
        val annotations = parseSearchGroundingMetadata(groundingMetadata)

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

    private fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
        if (jsonObject == null) return emptyList()
        val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
        val chunks = groundingChunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = uri
            )
        }
        Log.i(TAG, "parseSearchGroundingMetadata: $chunks")
        return chunks
    }

    private fun parseMessageParts(parts: JsonArray?): List<UIMessagePart> = buildList {
        val serverTools = mutableMapOf<String, Int>()
        parts.orEmpty().forEachIndexed { index, element ->
            val part = parseMessagePart(element.jsonObject, index) ?: return@forEachIndexed
            when (part) {
                is UIMessagePart.Text -> {
                    val signature = part.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature
                    val previous = signature?.takeIf { part.text.isEmpty() }?.let { lastOrNull()?.withGoogleSignature(it) }
                    if (previous != null) set(lastIndex, previous) else add(part)
                }
                is UIMessagePart.ServerTool -> {
                    val position = serverTools.getOrPut(part.toolCallId) { size }
                    val existing = getOrNull(position) as? UIMessagePart.ServerTool
                    if (existing == null) add(part) else set(position, existing.copy(
                        toolName = part.toolName.ifBlank { existing.toolName },
                        input = part.input ?: existing.input,
                        output = part.output ?: existing.output,
                        status = part.status,
                        metadata = mergeMetadata(existing.metadata, part.metadata),
                    ))
                }
                else -> add(part)
            }
        }
    }

    private fun parseMessagePart(jsonObject: JsonObject, index: Int): UIMessagePart? {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    // 签名推理块必须原样回传，挂上 metadata 后 UI 会禁用编辑（isProviderSigned）
                    metadata = jsonObject.googleThoughtMetadata(),
                ) else UIMessagePart.Text(text, jsonObject.googleThoughtMetadata())
            }

            jsonObject.containsKey("functionCall") -> {
                val call = jsonObject.getValue("functionCall").jsonObject
                val wireId = call.stringOrNull("id")
                UIMessagePart.Tool(
                    toolCallId = wireId ?: Uuid.random().toString(),
                    toolName = call.getValue("name").jsonPrimitive.content,
                    input = json.encodeToString(call["args"]),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = jsonObject.stringOrNull("thoughtSignature"),
                        googleFunctionCallId = wireId,
                    ).toMetadata()
                )
            }

            "toolCall" in jsonObject || "toolResponse" in jsonObject ->
                jsonObject.googleServerTool(index) { Uuid.random().toString() }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // 如果是思考过程中的草稿图，直接忽略
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null
                    )
                }
                UIMessagePart.Image(
                    url = data,
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = thoughtSignature,
                        inlineDataMime = mime,
                    ).toMetadata()
                )
            }

            else -> jsonObject.googleThoughtMetadata()?.let { UIMessagePart.Text("", it) }
        }
    }

    private fun buildContents(messages: List<UIMessage>, skipFirstSystem: Boolean = true): JsonArray {
        // 第一条 system 的内容已进 systemInstruction（见 buildCompletionRequestBody，仅当
        // skipFirstSystem=true）；**后续的 system 消息不能丢弃**（如轮次尾部注入的 system 段）——
        // Gemini 的 contents 不接受 "system" 角色，且 systemInstruction 在顶部（动态内容放那里
        // 会作废缓存前缀）⇒ 降级为 user 发送：位置语义保住，仅语气降级。
        // ⚠️ 过滤条件必须是「保留全部可上传消息」：曾写成
        // `it.role != SYSTEM || it === firstSystem`，非首条 system 会在进入 `when` 之前被滤掉 ⇒
        // 下面的 SYSTEM→user 降级分支成了**死代码**、后续 system 被**静默丢弃**（与上面声明的
        // 「不能丢弃」相反；2026-10-06 单测实测并修正，ClaudeProvider/ResponseAPI 两处同款一并改）。
        val firstSystem = if (skipFirstSystem) messages.firstOrNull { it.role == MessageRole.SYSTEM } else null
        return buildJsonArray {
            messages
                .filter { it.isValidToUpload() }
                .forEach { message ->
                    when {
                        message === firstSystem -> return@forEach // 已进 systemInstruction，跳过防重复
                        message.role == MessageRole.ASSISTANT -> addModelMessage(message)
                        message.role == MessageRole.SYSTEM -> addUserMessage(message.asDowngradedSystemMessage())
                        else -> addUserMessage(message)
                    }
                }
        }
    }

    private fun JsonArrayBuilder.addModelMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val partsBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.flatMap { it.toGoogleParts() }.forEach { partsBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 functionCall 到 parts 缓冲
                    group.tools.forEach { partsBuffer.add(it.toFunctionCallPart()) }

                    // 输出 model 消息
                    add(buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                    })
                    partsBuffer.clear()

                    // 紧跟 functionResponse
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            group.tools.forEach { add(it.toFunctionResponsePart()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (partsBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { partsBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") {
                message.parts.mapNotNull { it.toGooglePart() }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toGooglePart(): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("text", text)
            metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let { put("thoughtSignature", it) }
        }

        is UIMessagePart.Reasoning -> metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let { signature ->
            buildJsonObject {
                put("text", reasoning)
                put("thought", true)
                put("thoughtSignature", signature)
            }
        }

        is UIMessagePart.Image -> {
            encodeBase64(false).checkAttachmentLimit().getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        // 裸 base64 输入时 encodeBase64 只能默认 image/png；inlineDataMime
                        // 记录了解析期的真实 mime，优先用
                        val thoughtMeta = metadataAs<GoogleThoughtMetadata>()
                        put("mimeType", thoughtMeta?.inlineDataMime ?: encoded.mimeType)
                        put("data", encoded.base64)
                    })
                    metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
                        put("thoughtSignature", it)
                    }
                }
            }
        }

        is UIMessagePart.Video -> {
            encodeBase64(false).checkAttachmentLimit().getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "video/mp4")
                        put("data", base64Data)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            encodeBase64(false).checkAttachmentLimit().getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "audio/mp3")
                        put("data", base64Data)
                    })
                }
            }
        }

        else -> null
    }

    private fun UIMessagePart.toGoogleParts(): List<JsonObject> {
        if (this !is UIMessagePart.ServerTool) return listOfNotNull(toGooglePart())
        val metadata = metadataAs<ServerToolMetadata>() ?: return emptyList()
        if (metadata.protocol != ServerToolProtocol.GOOGLE_GENERATE_CONTENT) return emptyList()
        val call = metadata.call ?: return emptyList()
        val result = metadata.result ?: return emptyList()
        return listOf(call, result)
    }

    private fun UIMessagePart.Tool.toFunctionCallPart() = buildJsonObject {
        put("functionCall", buildJsonObject {
            put("name", toolName)
            put("args", inputAsJson())
            metadataAs<GoogleThoughtMetadata>()?.googleFunctionCallId?.let { put("id", it) }
        })
        metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
            put("thoughtSignature", it)
        }
    }

    private fun UIMessagePart.Tool.toFunctionResponsePart() = buildJsonObject {
            put("functionResponse", buildJsonObject {
                put("name", toolName)
                metadataAs<GoogleThoughtMetadata>()?.googleFunctionCallId?.let { put("id", it) }

                // 1. 拆分出纯文本部分
                val textParts = output.filterIsInstance<UIMessagePart.Text>()
                
                // 2. 提取所有的多模态(图片/视频/音频)，并直接转为 Google 要求的格式
                // 过滤出最终包含 inlineData 的数据块
                val mediaGoogleParts = output
                    .filter { it !is UIMessagePart.Text }
                    .mapNotNull { it.toGooglePart() }
                    .filter { it.containsKey("inlineData") } 

                // 3. 构建给模型看的结构化 response 节点
                put("response", buildJsonObject {
                    // 处理文本结果
                    if (textParts.isNotEmpty()) {
                        put(
                            "result", 
                            textParts.joinToString("\n") { it.text }
                        )
                    } else if (mediaGoogleParts.isEmpty()) {
                        // 如果工具啥都没返回，给个兜底成功状态
                        put("result", " ")
                    }

                    // 处理媒体数据（图片、音频、视频），打上 $ref 标签
                    mediaGoogleParts.forEachIndexed { index, _ ->
                        val refName = "media_ref_$index"
                        put(refName, buildJsonObject {
                            put("\$ref", refName)
                        })
                    }
                })

                // 4. 将真实的 Base64 多媒体数据挂载到 parts 中，并建立指针绑定
                if (mediaGoogleParts.isNotEmpty()) {
                    putJsonArray("parts") {
                        mediaGoogleParts.forEachIndexed { index, googlePart ->
                            val refName = "media_ref_$index"
                            val inlineData = googlePart["inlineData"]!!.jsonObject

                            add(buildJsonObject {
                                // 重新组装 inlineData，并在内部注入 displayName
                                put("inlineData", buildJsonObject {
                                    // 复制原有的 mimeType 和 data
                                    inlineData.forEach { (k, v) -> put(k, v) }
                                    // 添加能够让 $ref 认出它的唯一名称
                                    put("displayName", refName)
                                })
                                
                                // 保留可能存在的其他字段
                                googlePart.forEach { (k, v) ->
                                    if (k != "inlineData") put(k, v)
                                }
                            })
                        }
                    }
                }
            })
        }

    private fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) {
            return null
        }
        val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidatesTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidatesTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens
        )
    }
}


