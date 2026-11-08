package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.util.checkAttachmentLimit
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
import app.androidrik.ai.core.scalarOverride
import app.androidrik.ai.core.ReasoningOverride
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.provider.providers.asDowngradedSystemMessage
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderProxy
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.TextGenerationResult
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.provider.providers.PartGroup
import app.androidrik.ai.util.ensureArrayItems
import app.androidrik.ai.provider.providers.groupPartsByToolBoundary
import app.androidrik.ai.registry.ModelRegistry
import app.androidrik.ai.ui.OpenAIReasoningMetadata
import app.androidrik.ai.ui.ReasoningType
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.ServerToolMetadata
import app.androidrik.ai.ui.ServerToolProtocol
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.ui.toMetadata
import app.androidrik.ai.util.KeyRoulette
import app.androidrik.ai.util.apiEndpoint
import app.androidrik.ai.util.configureClientWithProxy
import app.androidrik.ai.util.configureReferHeaders
import app.androidrik.ai.util.configureSessionHeaders
import app.androidrik.ai.util.encodeBase64
import app.androidrik.ai.util.json
import app.androidrik.ai.util.mergeCustomBody
import app.androidrik.ai.util.parseErrorDetail
import app.androidrik.ai.util.stringSafe
import app.androidrik.ai.util.applyCustomHeaders
import app.androidrik.ai.util.toHeaders
import app.androidrik.common.http.await
import app.androidrik.common.http.jsonObjectOrNull
import app.androidrik.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock
import app.androidrik.ai.util.summarizeRequestBody
import app.androidrik.ai.util.summarizeErrorBody
import app.androidrik.ai.util.summarizeSseEvent

private const val TAG = "ResponseAPI"

/** `response.incomplete` 缺 `incomplete_details.reason` 时的 finishReason 兜底（非空即可）。 */
private const val INCOMPLETE_FALLBACK_FINISH_REASON = "incomplete"

/** 失败事件缺错误详情时，异常文案里最多带多少字符的原始事件。 */
private const val MAX_FAILURE_DETAIL_CHARS = 300

/**
 * Responses API 请求的目标：发往哪里、怎么鉴权，以及该网关对请求体的特殊要求（[capabilities]）。
 *
 * OpenAI 兼容提供商由 [ProviderSetting.OpenAI] 生成（`toResponsesEndpoint`）；地址写死的账号型
 * 提供商（ChatGPT / Grok）各自构造。抽象与上游 #2186 相同，末尾三个字段是本仓扩展
 * （P1 两层代理、思考强度覆盖、Codex 类后端的 SSE 容错）。
 */
internal class ResponsesEndpoint(
    val url: String,
    val capabilities: ResponseProviderCapabilities = ResponseProviderCapabilities(),
    // 构建请求时调用，负责请求头和鉴权；可挂起，以便先刷新 token
    val configureRequest: suspend Request.Builder.() -> Unit,
    /**
     * 本仓扩展：提供商级思考强度兜底覆盖（`ProviderSetting.reasoningOverride`）。
     * 模型级 [Model.reasoningOverride] 仍然优先；只影响 Responses 的 `reasoning.effort`。
     */
    val reasoningOverride: ReasoningOverride? = null,
    /** 本仓扩展：P1 两层代理——Inherit 时沿用 app 全局 client 的 ProxySelector */
    val proxy: ProviderProxy = ProviderProxy.Inherit,
    /**
     * 本仓扩展：Codex 类后端不发 `Content-Type: text/event-stream`，需要补 Accept 头并挂
     * [CodexSseContentTypeInterceptor]；其它网关保持 OkHttp 的严格校验。
     */
    val forceEventStream: Boolean = false,
)

/**
 * OpenAI 兼容提供商自己构造请求目标（地址、能力、代理、鉴权都在这里定）。
 *
 * 放在 ResponseAPI 之外，让 [OpenAIProvider] 不必经 ResponseAPI 的旧入口也能用新 endpoint API
 * （对齐上游 #2186「OpenAI 调用方自行构造 endpoint」）。
 */
internal fun ProviderSetting.OpenAI.toResponsesEndpoint(
    params: TextGenerationParams,
    authenticator: OpenAIRequestAuthenticator,
): ResponsesEndpoint {
    val endpointUrl = responsesUrl()
    return ResponsesEndpoint(
        url = endpointUrl,
        capabilities = resolveResponseProviderCapabilities(this),
        configureRequest = {
            // 顺序与改造前一致：会话头 → refer 头 → 自定义头 → 鉴权（鉴权最后写，自定义 Authorization 会被盖掉）
            configureSessionHeaders(endpointUrl, params.sessionId)
            configureReferHeaders(baseUrl)
            applyCustomHeaders(this@toResponsesEndpoint, params.customHeaders)
            authenticator.authenticateApiKey(this, this@toResponsesEndpoint)
        },
        reasoningOverride = reasoningOverride,
        proxy = proxy,
        forceEventStream = looksLikeCodexEndpoint(),
    )
}

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default(),
) : OpenAIImpl {
    private val authenticator = OpenAIRequestAuthenticator(keyRoulette)

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult =
        generateText(providerSetting.toResponsesEndpoint(params, authenticator), messages, params)

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> =
        streamText(providerSetting.toResponsesEndpoint(params, authenticator), messages, params)

    internal suspend fun generateText(
        endpoint: ResponsesEndpoint,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val requestBody = buildRequestBody(
            endpoint = endpoint,
            messages = messages,
            params = params,
            stream = false,
        )
        val requestBuilder = Request.Builder()
            .url(endpoint.url)
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Content-Type", "application/json")
        endpoint.configureRequest.invoke(requestBuilder)
        val request = requestBuilder.build()

        Log.i(TAG, "generateText: ${summarizeRequestBody(json, requestBody)}")

        // await() 只等响应头；读 body 仍可能阻塞——use 确保流关闭，整体在 IO 线程执行（对齐 ab8b6cc8）
        client.configureClientWithProxy(endpoint.proxy).newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            Log.d(TAG, "generateText: response ${summarizeErrorBody(bodyStr)}")
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObjectOrNull
                ?: error("Responses API response is not a JSON object: ${bodyStr.take(200)}")
            parseResponseOutput(bodyJson)
        }
    }

    internal fun streamText(
        endpoint: ResponsesEndpoint,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildRequestBody(
            endpoint = endpoint,
            messages = messages,
            params = params,
            stream = true,
        )
        val requestBuilder = Request.Builder()
            .url(endpoint.url)
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .apply {
                // Codex 后端只支持流式；显式声明可避免部分网关按普通 JSON 响应处理
                if (endpoint.forceEventStream) addHeader("Accept", "text/event-stream")
            }
        // 鉴权可能要先刷新 token（挂起），因此在构建器上单独调用
        endpoint.configureRequest.invoke(requestBuilder)
        val request = requestBuilder.build()

        Log.i(TAG, "streamText: ${summarizeRequestBody(json, requestBody)}")

        // 每条流一个解码器实例：内部保存 open part / toolCallId 映射（对齐上游 #1648）
        val decoder = ResponseApiStreamDecoder()

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    sendChunks(decoder.onClosed())
                    close()
                    return
                }
                Log.d(TAG, "onEvent: ${summarizeSseEvent(type, data, terminal = type == "response.completed")}")
                try {
                    // SSE 的 event 名与 payload 的 type 双写，且两者可能只出现一个：
                    // 部分中转只发 `event:`（JSON 里没有 type）。解码器会优先读 payload.type、
                    // 缺失时回退到 event 名，这里保持同样的归一语义。
                    val payloadJson = json.parseToJsonElement(data).jsonObjectOrNull
                    val sseType = type?.takeIf { it.isNotBlank() }
                    val payloadType = payloadJson?.get("type")?.jsonPrimitiveOrNull?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                    val eventType = payloadType ?: sseType

                    // 失败通常已计费：先把 usage 送下游，再按错误结束（对齐本仓旧实现）
                    if (eventType == "response.failed" || eventType == "error") {
                        val failureUsage = payloadJson?.get("response")
                            ?.jsonObjectOrNull
                            ?.get("usage")
                            ?.jsonObjectOrNull
                        parseTokenUsage(failureUsage)?.let {
                            sendChunks(listOf(StreamChunk.Usage(it)))
                        }
                    }

                    val result = decoder.accept(SseEvent(id = id, event = sseType, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                Log.w(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message ?: ""} httpCode=${response?.code}")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse error body (${summarizeErrorBody(bodyRaw)})")
                    e.printStackTrace()
                } finally {
                    // ⚠️ close(null) 等于**正常结束**：HTTP 非 2xx 且错误体为空/解析失败时
                    // exception 仍是 null，必须给兜底，不能把失败当成功收尾
                    close(exception ?: java.io.IOException("SSE stream failed without exception (HTTP ${response?.code ?: "?"})"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                // ⚠️ 这里**不能**补 Finish：本仓靠「有没有收到 Finish」区分正常收尾与裸断流，
                // 无 response.completed 的连接关闭必须保持「未显式结束」的语义（见 GenerationHandler）。
                close()
            }
        }

        // Codex 类后端有时不发 Content-Type: text/event-stream：只对这类端点挂补头拦截器，
        // 其他 provider 的严格校验与真实错误不受影响
        var streamClient = client.configureClientWithProxy(endpoint.proxy)
        if (endpoint.forceEventStream) {
            streamClient = streamClient.newBuilder()
                .addNetworkInterceptor(CodexSseContentTypeInterceptor())
                .build()
        }
        val eventSource = EventSources.createFactory(streamClient)
            .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "awaitClose: closing eventSource")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /** 以请求目标为入口：host（回填门槛）与提供商级覆盖都从 endpoint 取 */
    internal fun buildRequestBody(
        endpoint: ResponsesEndpoint,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
    ): JsonObject = buildRequestBody(
        capabilities = endpoint.capabilities,
        messages = messages,
        params = params,
        stream = stream,
        reasoningOverride = endpoint.reasoningOverride,
        reasoningTextRequired = isKnownReasoningTextValidator(endpoint.url.toHttpUrl().host),
    )

    /**
     * 本仓既有的 [ProviderSetting.OpenAI] 入口（P1 的既有单测与调用点仍用它）。
     *
     * `prompt_cache_key` 只取 [TextGenerationParams.sessionId]（P1 起由 app 层显式传对话 id）；
     * 不再有旧的 provider+model 稳定键兜底（随旧订阅链路一起删除）。
     */
    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        return buildRequestBody(
            capabilities = resolveResponseProviderCapabilities(providerSetting),
            messages = messages,
            params = params,
            stream = stream,
            reasoningOverride = providerSetting.reasoningOverride,
            reasoningTextRequired = isKnownReasoningTextValidator(providerSetting.baseUrl.toHttpUrl().host),
        )
    }

    /**
     * 请求体构造（对齐上游 #2186 的签名，另加两个本仓参数）：
     * [reasoningOverride] 是本仓的思考强度覆盖；[reasoningTextRequired] 是「思考模式必须回传思维链」
     * 的网关门槛（见 [isKnownReasoningTextValidator]），只有 host 命中时才回填占位 reasoning。
     */
    internal fun buildRequestBody(
        capabilities: ResponseProviderCapabilities,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
        reasoningOverride: ReasoningOverride? = null,
        reasoningTextRequired: Boolean = false,
    ): JsonObject {
        val systemPrompt = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            ?.parts
            ?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("\n") { it.text }
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            put("store", false)

            if (capabilities.supportsSamplingParams) {
                if (isModelAllowTemperature(params.model)) {
                    if (params.temperature != null) put("temperature", params.temperature)
                    if (params.topP != null) put("top_p", params.topP)
                }
                if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)
            }

            // system 提示：默认走顶层 instructions；Codex 等要求 developer 消息的端点走 input 开头
            val systemRole = capabilities.systemMessageRole
            if (systemRole == null && systemPrompt != null) {
                put("instructions", systemPrompt)
            }

            // messages
            // 见 requiresToolReasoningBackfill：思考模式下带 tool_call 的 assistant 消息缺 reasoning item
            // 会被网关直接拒（DeepSeek Responses / opencode 实测：400「reasoning_text must be passed back」）
            put("input", buildJsonArray {
                if (systemRole != null && systemPrompt != null) {
                    add(buildJsonObject {
                        put("role", systemRole)
                        put("content", systemPrompt)
                    })
                }
                // buildMessages 会跳过首条 system（上面已单独处理），其余 system 仍按本仓规则降级为 user
                buildMessages(messages, reasoningTextRequired).forEach { add(it) }
            })

            // reasoning
            val reasoningModel = params.model.abilities.contains(ModelAbility.REASONING)
            if (reasoningModel) {
                val level = params.reasoningLevel
                // 本仓 P1：思考强度覆盖（模型级优先、提供商级兜底），只作用于「选中具体档位」时
                val effectiveOverride = (params.model.reasoningOverride.scalarOverride() ?: reasoningOverride.scalarOverride())
                    ?.takeIf { level.isEnabled && level != ReasoningLevel.AUTO }
                val reasoning = buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    val effort = effectiveOverride?.effort
                        ?: capabilities.reasoningEffort(level, params.model.modelId)
                    if (effort != null) put("effort", effort)
                }
                // 空对象（既无 summary 也无 effort，如 Grok AUTO）时不写该字段
                if (reasoning.isNotEmpty()) put("reasoning", reasoning)
            }
            // 服务端不保存会话（store=false）时，推理链要靠加密推理内容接续；Codex 强制要求
            if (capabilities.supportEncryptedContent &&
                (reasoningModel || capabilities.alwaysIncludeEncryptedContent)
            ) {
                put("include", buildJsonArray {
                    add("reasoning.encrypted_content")
                })
            }

            // 同会话请求命中服务端提示缓存（对齐上游 #2186：会话 ID 即 prompt_cache_key；
            // 本仓 app 层尚未传 sessionId 时由旧 Codex 稳定键兜底，见下面的 providerSetting 重载）
            params.sessionId?.let { put("prompt_cache_key", it) }

            // tools
            // Response API 的 tools 是扁平数组, 函数工具和内置工具可以共存, 必须写在同一个 key 下,
            // 否则后写入的会覆盖前者
            val useFunctionTools =
                params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
            if (useFunctionTools || params.model.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    if (useFunctionTools) {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                    ).ensureArrayItems()
                                )
                                // Response API 不传 strict 时默认按严格模式处理, 会把 schema 里的可选字段全部变成必填,
                                // 模型只能给本应省略的字段硬填默认值 (0 / "" / []), 导致工具参数校验失败
                                // （#1990：chart_display 等带可选字段的工具即因此被中转/官方端拒绝）
                                put("strict", false)
                            })
                        }
                    }
                    // built-in tools
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2")
                                })
                            }
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    internal fun buildMessages(
        messages: List<UIMessage>,
        reasoningTextRequired: Boolean = false,
    ) = buildJsonArray {
        // Responses API 的 `system` 只出现在顶层 `instructions`（见 buildRequestBody：取**第一条**
        // system 的文本），`input` 不接受 system 角色。**但后续的 system 消息不能丢弃**——轮次尾部
        // 注入的 system 段、压缩检查点等都会落在这里。此处对齐 ClaudeProvider / GoogleProvider 的
        // 声明做法：除已进 instructions 的第一条外，其余 system 降级为 user 发送（位置语义保住、
        // 缓存保住，仅语气降级）。
        //
        // ⚠️ 过滤条件必须是「保留全部可上传消息」，**不能**写成
        // `it.role != SYSTEM || it === firstSystem`：那样非首条 system 会在进入 `when` 之前就被滤掉，
        // 下面的 SYSTEM→user 降级分支成了**死代码**、内容被静默丢弃（2026-10-06 单测实测；Claude/
        // Google 两处同款写法同样如此，一并修正）。首条的跳过由 `when` 里的分支负责。
        val firstSystem = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        messages
            .filter { it.isValidToUpload() }
            .forEach { message ->
                when {
                    message === firstSystem -> Unit // 已进 instructions，跳过防重复
                    message.role == MessageRole.SYSTEM -> addUserItems(message.asDowngradedSystemMessage())
                    message.role == MessageRole.ASSISTANT -> addAssistantItems(message, reasoningTextRequired)
                    else -> addUserItems(message)
                }
            }
    }

    private fun JsonArrayBuilder.addAssistantItems(
        message: UIMessage,
        reasoningTextRequired: Boolean = false,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        // 状态维护：DeepSeek / opencode 网关按"每个工具调用轮次"校验 reasoning，
        // 同一轮里第二个及以后的 function_call 前也必须带 reasoning item（issue #1690）
        var lastReasoningText: String? = null
        var lastReasoningId: String? = null
        var lastEncryptedContent: String? = null
        var toolEmittedSinceReasoning = false
        // 本条 assistant 消息是否已输出过 reasoning item（供「历史里根本没有思维链」的回填判定）
        var reasoningEmitted = false

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                // 记录 reasoning 状态，供后续 tool 轮次补插
                                val reasoningMetadata = part.metadataAs<OpenAIReasoningMetadata>()
                                lastReasoningText = part.reasoning.takeIf { it.isNotBlank() }
                                lastReasoningId = reasoningMetadata?.reasoningId
                                lastEncryptedContent = reasoningMetadata?.encryptedContent
                                toolEmittedSinceReasoning = false
                                // 输出 reasoning item（content 为规范必填字段，summary 为摘要）
                                addReasoningItem(
                                    text = part.reasoning,
                                    id = reasoningMetadata?.reasoningId,
                                    encryptedContent = reasoningMetadata?.encryptedContent,
                                    reasoningTextRequired = reasoningTextRequired,
                                )
                                reasoningEmitted = true
                            }

                            is UIMessagePart.Image -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                addContentItem(MessageRole.USER, listOf(part))
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            // 服务端工具（web_search / shell / image_generation…）的 item 要原样回传给
                            // Responses 后端：Codex 订阅后端 `store=false`，历史缺失 item 会丢上下文、
                            // 甚至 400（对齐上游 server-tool 回传，§16.6 缺口）。
                            is UIMessagePart.ServerTool -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer)
                                    contentBuffer.clear()
                                }
                                addServerToolItem(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer)
                        contentBuffer.clear()
                    }

                    // 输出 function_call + function_call_output
                    group.tools.forEach { tool ->
                        // ① 这条 assistant 消息**根本没有思维链**可回放（开启思考前的旧消息 / 上游只给加密推理 /
                        //    用户关了「回传历史思考过程」/ 推理被**删空**）⇒ 网关以「reasoning_text must be passed
                        //    back」直接 400（DeepSeek Responses 实测）。先补**占位** reasoning item：
                        //    该 API 拒绝空文本，非空即可、id 可选。
                        //    ⚠️「编辑过」**不算**：`#1929` 编辑后文本非空 ⇒ 走 lastReasoningText 分支，
                        //    把用户改写过的文本当 reasoning 回传（2026-10-09 核实）。
                        // ② 同一轮第二个及以后的 function_call 前补插 reasoning item（issue #1690）。
                        val reasoningToEmit: String? = when {
                            !reasoningEmitted && reasoningTextRequired -> REASONING_BACKFILL_PLACEHOLDER
                            toolEmittedSinceReasoning -> lastReasoningText
                            else -> null
                        }
                        if (reasoningToEmit != null) {
                            val isBackfill = !reasoningEmitted
                            addReasoningItem(
                                text = reasoningToEmit,
                                // 补插副本（回填/同轮第二个及以后的 function_call 前）一律**不带 id**：
                                // 复用原 rs_ id 会让同一请求出现重复 item id（OpenAI 官方可能按
                                // 唯一性拒绝），而已知校验网关（DeepSeek/opencode）本就 id 可选
                                id = null,
                                // 与主 reasoning item 一致：已加密时不回放明文 content/summary（#1719）
                                encryptedContent = if (isBackfill) null else lastEncryptedContent,
                                reasoningTextRequired = reasoningTextRequired,
                            )
                            reasoningEmitted = true
                            toolEmittedSinceReasoning = false
                        }
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                            put("arguments", tool.inputAsJson().toString())
                        })
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            val hasImage = tool.output.any { it is UIMessagePart.Image }
                            if (hasImage) {
                                putJsonArray("output") {
                                    tool.output.forEach { part ->
                                        when (part) {
                                            is UIMessagePart.Image -> add(buildJsonObject {
                                                part.encodeBase64().checkAttachmentLimit().onSuccess { encoded ->
                                                    put("type", "input_image")
                                                    put("image_url", encoded.base64)
                                                }.onFailure {
                                                    it.printStackTrace()
                                                    put("type", "input_text")
                                                    put("text", "Error: Failed to encode image to base64")
                                                }
                                            })
                                            is UIMessagePart.Text -> add(buildJsonObject {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                            else -> {}
                                        }
                                    }
                                }
                            } else {
                                put(
                                    "output",
                                    tool.output.filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("\n") { it.text }
                                )
                            }
                        })
                        // 标记：此工具调用轮次已输出，后续 function_call 需补插 reasoning
                        toolEmittedSinceReasoning = true
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer)
        }
    }

    /**
     * 发射一个 reasoning item。
     *
     * `content[].reasoning_text` 是该 API 的**规范必填**字段（`summary` 为摘要）；若推理内容已加密
     * （`encrypted_content` 存在）则**只回传密文、不回放明文**，避免把历史明文推理重放回上下文（上游 #1719）。
     */
    private fun JsonArrayBuilder.addReasoningItem(
        text: String,
        id: String?,
        encryptedContent: String?,
        reasoningTextRequired: Boolean,
    ) {
        add(buildJsonObject {
            put("type", "reasoning")
            id?.let { put("id", it) }
            // `content[].reasoning_text` 是规范**必填**字段——**即使推理已加密也必须给非空文本**：
            // DeepSeek Responses 实测，只有 encrypted_content 而缺 reasoning_text 会 400
            // 「The `reasoning_text` in the thinking mode must be passed back to the API」。
            // 加密时按 #1719 **不回放明文**，改用占位文本满足结构校验（不泄漏推理内容）。
            // 非校验 host（如 OpenAI 官方）保持原样：加密时只回 encrypted_content，不塞未经
            // 验证的字段组合。
            // `summary` 是 reasoning item 的**规范必填**字段：只回传 encrypted_content 时也必须给
            // （空数组即可），否则 Codex 后端直接 400（2026-11-02 真机：缺 summary 的历史推理请求
            // 全部 400，表现为 "SSE stream failed without exception"）。
            val replayText = encryptedContent == null || reasoningTextRequired
            val contentText = if (encryptedContent == null) text else REASONING_BACKFILL_PLACEHOLDER
            put("summary", buildJsonArray {
                if (replayText) {
                    add(buildJsonObject {
                        put("type", "summary_text")
                        put("text", contentText)
                    })
                }
            })
            if (replayText) {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "reasoning_text")
                        put("text", contentText)
                    })
                })
            }
            encryptedContent?.let { put("encrypted_content", it) }
        })
    }

    /**
     * 把历史里的服务端工具 item 回传给 Responses 后端（上游同名移植）。
     *
     * 优先用解码时原样存下的 `metadata.call`（保真：`web_search_call` 的 `action` 结构/未知字段不丢）；
     * 没有原件（编辑/合成出来的 part）时按 `type={toolName}_call` + id/status/action/output 重建。
     * 其它协议（Claude/Google 侧）的服务端工具不归这里管，直接跳过。
     */
    private fun JsonArrayBuilder.addServerToolItem(tool: UIMessagePart.ServerTool) {
        val metadata = tool.metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.OPENAI_RESPONSES) return

        val rawCall = metadata?.call.takeIf { protocol == ServerToolProtocol.OPENAI_RESPONSES }
        if (rawCall != null) {
            add(rawCall)
            return
        }

        add(buildJsonObject {
            put("type", "${tool.toolName.removeSuffix("_call")}_call")
            put("id", tool.toolCallId)
            put("status", tool.status.toOpenAIStatus())
            tool.input?.let { input ->
                if (tool.toolName.removeSuffix("_call") == "web_search") {
                    put("action", input)
                } else if (input is JsonObject) {
                    input.forEach { (key, value) -> put(key, value) }
                } else {
                    put("input", input)
                }
            }
            tool.output?.let { put("output", it) }
        })
    }

    private fun ServerToolStatus.toOpenAIStatus(): String = when (this) {
        ServerToolStatus.IN_PROGRESS -> "in_progress"
        ServerToolStatus.COMPLETED -> "completed"
        ServerToolStatus.FAILED -> "failed"
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts)
        }
    }

    private fun JsonArrayBuilder.addContentItem(role: MessageRole, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().checkAttachmentLimit().onSuccess { encodedImage ->
                                        put("type", "input_image")
                                        put("image_url", encodedImage.base64)
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "input_text")
                                        put("text", "Error: Failed to encode image to base64")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    internal fun parseResponseOutput(jsonObject: JsonObject): TextGenerationResult {
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    val reasoningMetadata = OpenAIReasoningMetadata(
                        reasoningId = output["id"]?.jsonPrimitive?.contentOrNull,
                        encryptedContent = output["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                    ).toMetadata()
                    val reasoningPartStart = parts.size
                    output["summary"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now(),
                                        metadata = reasoningMetadata,
                                        reasoningType = ReasoningType.SUMMARY_TEXT,
                                    )
                                )
                            }
                        }
                    }
                    output["content"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { part ->
                        if (part["type"]?.jsonPrimitive?.contentOrNull == "reasoning_text") {
                            parts.add(
                                UIMessagePart.Reasoning(
                                    reasoning = part["text"]?.jsonPrimitive?.content ?: error("text not found"),
                                    createdAt = Clock.System.now(),
                                    finishedAt = Clock.System.now(),
                                    metadata = reasoningMetadata,
                                    reasoningType = ReasoningType.REASONING_TEXT,
                                )
                            )
                        }
                    }
                    if (parts.size == reasoningPartStart) {
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                createdAt = Clock.System.now(),
                                finishedAt = Clock.System.now(),
                                metadata = reasoningMetadata,
                                reasoningType = ReasoningType.REASONING_TEXT,
                            )
                        )
                    }
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                    val arguments =
                        output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            output = emptyList()
                        )
                    )
                }

                // 服务端已执行函数工具的结果（本仓 #2181）：回填到对应工具，避免客户端重复执行
                "function_call_output" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val index = parts.indexOfLast {
                        it is UIMessagePart.Tool && it.toolCallId == callId
                    }
                    if (index >= 0) {
                        val tool = parts[index] as UIMessagePart.Tool
                        parts[index] = tool.copy(
                            output = tool.output + UIMessagePart.Text(output.functionCallOutputText())
                        )
                    }
                }

                "message" -> {
                    val content = output["content"]?.jsonArray ?: error("content not found")
                    content.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "output_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(UIMessagePart.Text(text = text))
                            }

                            else -> error("unknown part type $partType")
                        }
                    }
                }

                else -> if (isOpenAIServerToolCall(type)) {
                    parts.add(output.toOpenAIServerTool())
                }
            }
        }

        return TextGenerationResult(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = parts,
            ),
            // `status` 是 `completed`/`incomplete`/`failed`：只把真正的「非正常收尾」暴露给下游，
            // 正常完成保持 null（对齐本仓非流式路径的既有语义，避免 finishReason 里出现 "completed"）
            finishReason = jsonObject["status"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it != "completed" },
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObject),
        )
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }
}

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}

/**
 * `function_call_output.output` 的文本化（上游 #2181 / `675e59f0d`）。
 *
 * 三种合法形状：字符串、`input_text` 等内容数组、`null`。数组里取不到 `input_text` 时
 * 退回 `toString()`（宁可把原始 JSON 显示给用户，也不静默丢结果）。
 */
internal fun JsonObject.functionCallOutputText(): String = when (val output = get("output")) {
    null, is JsonNull -> ""
    is JsonPrimitive -> output.content
    is JsonArray -> output.mapNotNull { item ->
        item.jsonObjectOrNull?.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "input_text" }
            ?.get("text")?.jsonPrimitive?.contentOrNull
    }.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: output.toString()
    else -> output.toString()
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true,
    /**
     * 非 null 时：system 提示以该角色的消息放在 `input` 开头，**不使用** `instructions` 字段
     * （Codex 后端要求 `developer`）。null = 沿用 `instructions`（默认行为）。
     */
    val systemMessageRole: String? = null,
    /** 是否接受 `temperature`/`top_p`/`max_output_tokens`；Codex 后端不接受 */
    val supportsSamplingParams: Boolean = true,
    /**
     * 非推理模型也请求加密推理内容：服务端不保存会话时，自带推理的模型要靠它接上多轮推理
     * （Codex 后端必须回传）。
     */
    val alwaysIncludeEncryptedContent: Boolean = false,
    /** 推理档位 → `reasoning.effort`；返回 null 时不带该字段（默认：AUTO 由调用侧跳过） */
    val reasoningEffort: (ReasoningLevel, String) -> String? =
        { level, _ -> level.effort.takeIf { level != ReasoningLevel.AUTO } },
)

/**
 * 官方 Codex 后端（ChatGPT 订阅账号态）的请求体能力，取自上游 #2186 `CODEX_CAPABILITIES`：
 * - system 提示以 `developer` 消息放 `input` 开头，不用 `instructions`；
 * - 不接受采样参数；
 * - 必须请求并用回加密推理内容（`store=false` 下多轮推理靠它接续）。
 */
internal val CODEX_CAPABILITIES = ResponseProviderCapabilities(
    systemMessageRole = "developer",
    supportsSamplingParams = false,
    alwaysIncludeEncryptedContent = true,
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        else -> ResponseProviderCapabilities()
    }
}

internal fun resolveResponseProviderCapabilities(
    providerSetting: ProviderSetting.OpenAI
): ResponseProviderCapabilities = if (providerSetting.looksLikeCodexEndpoint()) {
    CODEX_CAPABILITIES
} else {
    resolveResponseProviderCapabilities(providerSetting.baseUrl.toHttpUrl().host)
}

/**
 * OpenAI Responses 中「服务端工具调用」的判定（移植自上游 #1648）。
 *
 * 除内置的 function/custom/computer/shell/image_generation 之外，`*_call` 都是服务端工具
 * （`web_search_call` / `file_search_call` / `code_interpreter_call` / `mcp_call` 等）。
 */
internal fun isOpenAIServerToolCall(type: String): Boolean =
    type.endsWith("_call") && type !in setOf(
        "function_call",
        "custom_tool_call",
        "computer_call",
        "local_shell_call",
        "shell_call",
        "image_generation_call",
    )

/** 把 Responses 的服务端工具 item 原样转换成通用 [UIMessagePart.ServerTool]，供多轮回传。 */
internal fun JsonObject.toOpenAIServerTool(): UIMessagePart.ServerTool {
    val type = get("type")?.jsonPrimitive?.contentOrNull ?: "server_tool_call"
    val protocolFields = setOf("type", "id", "status", "result", "output")
    val input = get("action") ?: JsonObject(filterKeys { it !in protocolFields })
        .takeUnless { it.isEmpty() }
    return UIMessagePart.ServerTool(
        toolCallId = get("id")?.jsonPrimitive?.contentOrNull ?: "",
        toolName = type.removeSuffix("_call"),
        input = input,
        output = get("output") ?: get("result"),
        status = get("status")?.jsonPrimitive?.contentOrNull.toServerToolStatus(),
        metadata = ServerToolMetadata(
            protocol = ServerToolProtocol.OPENAI_RESPONSES,
            call = this,
        ).toMetadata(),
    )
}

internal fun String?.toServerToolStatus(): ServerToolStatus = when (this) {
    "completed" -> ServerToolStatus.COMPLETED
    "failed", "incomplete", "cancelled" -> ServerToolStatus.FAILED
    else -> ServerToolStatus.IN_PROGRESS
}
