package app.androidrik.ai.provider.providers.openai

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
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.provider.providers.asDowngradedSystemMessage
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.providers.PartGroup
import app.androidrik.ai.util.ensureArrayItems
import app.androidrik.ai.provider.providers.groupPartsByToolBoundary
import app.androidrik.ai.registry.ModelRegistry
import app.androidrik.ai.ui.terminalChunk
import app.androidrik.ai.ui.MessageChunk
import app.androidrik.ai.ui.OpenAIReasoningMetadata
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageChoice
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.ui.toMetadata
import app.androidrik.ai.util.KeyRoulette
import app.androidrik.ai.util.apiEndpoint
import app.androidrik.ai.util.configureClientWithProxy
import app.androidrik.ai.util.configureReferHeaders
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

private const val TAG = "ResponseAPI"

/** `response.incomplete` 缺 `incomplete_details.reason` 时的 finishReason 兜底（非空即可）。 */
private const val INCOMPLETE_FALLBACK_FINISH_REASON = "incomplete"

/** 失败事件缺错误详情时，异常文案里最多带多少字符的原始事件。 */
private const val MAX_FAILURE_DETAIL_CHARS = 300

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default()
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
        )
        val request = Request.Builder()
            .url(apiEndpoint(providerSetting.baseUrl, providerSetting.responsesPath))
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.effectiveApiKeys, providerSetting.id.toString())}"
            )
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .applyCustomHeaders(providerSetting, params.customHeaders)
            .build()

        Log.i(TAG, "generateText: ${json.encodeToString(requestBody)}")

        // await() 只等响应头；读 body 仍可能阻塞——use 确保流关闭，整体在 IO 线程执行（对齐 ab8b6cc8）
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
            }

            val bodyStr = response.body?.string() ?: ""
            Log.i(TAG, "generateText: $bodyStr")
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObjectOrNull
                ?: error("Responses API response is not a JSON object: ${bodyStr.take(200)}")
            parseResponseOutput(bodyJson)
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val request = Request.Builder()
            .url(apiEndpoint(providerSetting.baseUrl, providerSetting.responsesPath))
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.effectiveApiKeys, providerSetting.id.toString())}"
            )
            .configureReferHeaders(providerSetting.baseUrl)
            .applyCustomHeaders(providerSetting, params.customHeaders)
            .build()

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        // 每次流一个：把 item.id / output_index 归一到规范 toolCallId（= 回传时用的 call_id）。
        // 必须按流创建——ResponseAPI 是进程级共享实例（见 ResponseToolCallIdResolver 注释）。
        val toolCallIdResolver = ResponseToolCallIdResolver()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    trySend(terminalChunk())
                    close()
                    return
                }
                Log.d(TAG, "onEvent: $id/$type $data")
                val json = json.parseToJsonElement(data).jsonObjectOrNull
                // SSE 的 event 名与 JSON 的 type 双写，且两者可能只出现一个：
                // - 标准 OpenAI/兼容网关：都有；
                // - 部分中转只发 `event:`（JSON 里没有 type）⇒ parseResponseDelta 会
                //   error("chunk type not found")，整条流失败；
                // - 另有网关发 `event:` 空名（空字符串同样视为缺失）。
                val sseType = type?.takeIf { it.isNotBlank() }
                val payloadType = json?.get("type")?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
                // 结束/失败判定与解析必须同源：有 payload type 用它（parseResponseDelta 读的就是它），
                // 否则用 SSE event 名兜底。
                val eventType = payloadType ?: sseType
                if (json != null) {
                    // 把归一后的类型补进 payload，只读 JSON type 的 parseResponseDelta 才能继续解析
                    val payload = if (payloadType == null && sseType != null) {
                        JsonObject(json + ("type" to JsonPrimitive(sseType)))
                    } else {
                        json
                    }
                    val chunk = parseResponseDelta(payload, toolCallIdResolver)
                    if (chunk != null) {
                        trySend(chunk).onFailure { e ->
                            Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                        }
                    }
                }
                when (eventType) {
                    // 显式结束：正常完成，或 `response.incomplete`（撞输出上限等「模型本意结束」）。
                    // 两者都必须发 explicitEnd——否则下游把 incomplete 当裸断流，触发**无条件**自动续写，
                    // 一次截断变成最多 CONTINUE_LIMIT 次重发（见 ContinuationPolicy）。
                    "response.completed", "response.incomplete" -> {
                        trySend(terminalChunk())
                        close()
                    }

                    // 失败：内容已由前面的 delta 事件送出；usage 也照样送下游（失败通常已计费），
                    // 然后按错误结束——不能静默 close()，否则用户只看到半截回复且拿不到原因。
                    "response.failed", "error" -> {
                        val failureUsage = json?.get("response")
                            ?.jsonObjectOrNull
                            ?.get("usage")
                            ?.jsonObjectOrNull
                        parseTokenUsage(failureUsage)?.let { usage ->
                            trySend(
                                MessageChunk(
                                    id = "",
                                    model = "",
                                    choices = emptyList(),
                                    usage = usage,
                                )
                            )
                        }
                        close(parseStreamFailure(json, eventType))
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.javaClass?.name} ${t?.message} / $response")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        println(bodyElement)
                        exception = bodyElement.parseErrorDetail()
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw")
                    e.printStackTrace()
                } finally {
                    close(exception)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val eventSource = EventSources.createFactory(client.configureClientWithProxy(providerSetting.proxy))
            .newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource ")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val capabilities = resolveResponseProviderCapabilities(host)
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            put("store", false)

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            if (messages.any { it.role == MessageRole.SYSTEM }) {
                val parts = messages.first { it.role == MessageRole.SYSTEM }.parts
                put(
                    "instructions",
                    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
            }

            // messages
            // 见 requiresToolReasoningBackfill：思考模式下带 tool_call 的 assistant 消息缺 reasoning item
            // 会被网关直接拒（DeepSeek Responses / opencode 实测：400「reasoning_text must be passed back」）
            put("input", buildMessages(messages, isKnownReasoningTextValidator(host)))

            // reasoning
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                put("reasoning", buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    if (level != ReasoningLevel.AUTO) {
                        put("effort", level.effort)
                    }
                })
                if (capabilities.supportEncryptedContent) {
                    put("include", buildJsonArray {
                        add("reasoning.encrypted_content")
                    })
                }
            }

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
                                                part.encodeBase64().onSuccess { encoded ->
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
            if (encryptedContent == null || reasoningTextRequired) {
                val contentText = if (encryptedContent == null) text else REASONING_BACKFILL_PLACEHOLDER
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "reasoning_text")
                        put("text", contentText)
                    })
                })
                put("summary", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "summary_text")
                        put("text", contentText)
                    })
                })
            }
            encryptedContent?.let { put("encrypted_content", it) }
        })
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
                                    part.encodeBase64().onSuccess { encodedImage ->
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

    /**
     * 解析一条流式事件。
     *
     * [toolCallIdResolver] 由调用方**按流**创建并贯穿同一流的所有事件（见其类注释）。
     * 可见性放宽到 internal 以便单测直接驱动事件序列。
     */
    internal fun parseResponseDelta(
        jsonObject: JsonObject,
        toolCallIdResolver: ResponseToolCallIdResolver = ResponseToolCallIdResolver(),
    ): MessageChunk? {
        val chunkType = jsonObject["type"]?.jsonPrimitive?.content ?: error("chunk type not found")

        when (chunkType) {
            "response.output_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage.assistant(
                                jsonObject["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Reasoning(
                                        reasoning = jsonObject["delta"]?.jsonPrimitive?.contentOrNull
                                            ?: "",
                                        createdAt = Clock.System.now(),
                                        finishedAt = null
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    )
                )
            }

            "response.output_item.added" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                // 部分兼容网关在服务端执行函数工具，并把结果作为 function_call_output 下发（#2181）。
                // 必须先于 id 解析处理：该项只保证有 call_id，item.id 可能缺失
                if (type == "function_call_output") {
                    return functionCallOutputChunk(item)
                }
                // item.id 规范上可缺（function_call 只有 call_id 必填），故回退到 call_id；
                // 再登记「item.id / output_index → 规范 id」，供后续 arguments 事件回查
                val id = item["id"]?.jsonPrimitive?.contentOrNull
                    ?: item["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: error("chunk id not found")
                val canonicalId = toolCallIdResolver.registerItem(
                    itemId = id,
                    callId = item["call_id"]?.jsonPrimitive?.contentOrNull,
                    outputIndex = jsonObject["output_index"]?.jsonPrimitive?.intOrNull,
                )
                if (type == "function_call") {
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Tool(
                                            // 用规范 id（call_id 优先）：同一流内所有事件一致，
                                            // 且 next-round 请求回传的 call_id 正是它
                                            toolCallId = canonicalId,
                                            toolName = item["name"]?.jsonPrimitive?.content ?: "",
                                            input = item["arguments"]?.jsonPrimitive?.content
                                                ?: "",
                                            output = emptyList()
                                        )
                                    )
                                ),
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(UIMessagePart.Image(url = ""))
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                } else if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = OpenAIReasoningMetadata(
                                                reasoningId = id,
                                                encryptedContent = encryptedContent,
                                            ).toMetadata()
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                }
            }

            "response.output_item.done" -> {
                val item = jsonObject["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                // 同上：先处理服务端已执行的结果，再解析 id
                if (type == "function_call_output") {
                    return functionCallOutputChunk(item)
                }
                // id 缺失时回退 call_id：兼容网关的 item 形状并不总带 id（原先直接 error 会让整轮失败）
                val id = item["id"]?.jsonPrimitive?.contentOrNull
                    ?: item["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: error("chunk id not found")
                if (type == "reasoning") {
                    val encryptedContent = item["encrypted_content"]?.jsonPrimitive?.content
                    return MessageChunk(
                        id = id,
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                message = null,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Reasoning(
                                            reasoning = "",
                                            createdAt = Clock.System.now(),
                                            finishedAt = null,
                                            metadata = OpenAIReasoningMetadata(
                                                reasoningId = id,
                                                encryptedContent = encryptedContent,
                                            ).toMetadata()
                                        )
                                    )
                                ),
                                finishReason = null,
                            )
                        )
                    )
                } else if (type == "image_generation_call") {
                    val result = item["result"]?.jsonPrimitive?.content ?: error("result not found")
                    return MessageChunk(
                        id = item["id"]?.jsonPrimitive?.content ?: error("item_id not found"),
                        model = "",
                        choices = listOf(
                            UIMessageChoice(
                                index = 0,
                                delta = UIMessage(
                                    role = MessageRole.ASSISTANT,
                                    parts = listOf(
                                        UIMessagePart.Image(url = result)
                                    )
                                ),
                                message = null,
                                finishReason = null
                            )
                        )
                    )
                }
            }

            "response.function_call_arguments.done" -> {
                // 兼容网关可能只带 call_id 或 output_index（#1948），统一经 resolver 归一
                val toolCallId = toolCallIdResolver.resolveArgumentsId(jsonObject)
                val arguments =
                    jsonObject["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                return MessageChunk(
                    id = toolCallId,
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Tool(
                                        toolCallId = toolCallId,
                                        toolName = "",
                                        input = arguments,
                                        output = emptyList()
                                    )
                                )
                            ),
                            message = null,
                            finishReason = null
                        )
                    ),
                )
            }

            "response.completed" -> {
                return MessageChunk(
                    id = jsonObject["item_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    model = "",
                    choices = emptyList(),
                    usage = parseTokenUsage(jsonObject["response"]?.jsonObject?.get("usage")?.jsonObject)
                )
            }

            // 「有意的提前结束」：典型是撞输出上限
            // `{"type":"response.incomplete","response":{"status":"incomplete",
            //   "incomplete_details":{"reason":"max_output_tokens"},"usage":{...}}}`。
            // 必须把 reason 填进 finishReason 并让调用方发 explicitEnd，否则：
            // ① 下游看不到「已达输出上限」提示（只能发现回复戛然而止）；
            // ② 更糟——没有显式结束标记，会被当成裸断流而**无条件**自动续写。
            "response.incomplete" -> {
                val response = jsonObject["response"]?.jsonObjectOrNull
                val reason = response
                    ?.get("incomplete_details")
                    ?.jsonObjectOrNull
                    ?.get("reason")
                    ?.jsonPrimitiveOrNull
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                return MessageChunk(
                    id = response?.get("id")?.jsonPrimitiveOrNull?.contentOrNull ?: "",
                    model = "",
                    choices = listOf(
                        UIMessageChoice(
                            index = 0,
                            delta = null,
                            message = null,
                            // reason 缺失时给一个非空值：至少保住「显式结束」语义，避免误续写
                            finishReason = reason ?: INCOMPLETE_FALLBACK_FINISH_REASON,
                        )
                    ),
                    usage = parseTokenUsage(response?.get("usage")?.jsonObjectOrNull),
                )
            }
        }

        return null
    }

    /**
     * 把流失败事件（`response.failed` / `error`）归一成可抛出的异常。
     *
     * 事件形状不同，错误对象位置也不同：
     * - `{"type":"response.failed","response":{"status":"failed","error":{"code":…,"message":…}}}`
     *   ⇒ 错误在 `response.error`；
     * - `{"type":"error","error":{"type":"…","message":…}}` ⇒ 错误在顶层 `error`。
     *
     * 缺详情时退化为带事件类型的通用异常（附截断后的原始事件，便于排查），绝不返回 null——
     * 调用方用 `close(exception)` 结束流，静默 close 会被下游当成「裸断流」。
     */
    private fun parseStreamFailure(jsonObject: JsonObject?, eventType: String?): Throwable {
        val errorElement = jsonObject?.get("response")
            ?.jsonObjectOrNull
            ?.get("error")
            ?.takeIf { it !is JsonNull }
            ?: jsonObject?.get("error")?.takeIf { it !is JsonNull }
        return errorElement?.parseErrorDetail()
            ?: IllegalStateException(
                "Responses API stream $eventType without error detail: " +
                    (jsonObject?.toString()?.take(MAX_FAILURE_DETAIL_CHARS) ?: "<non-json event>")
            )
    }

    /**
     * 服务端已执行的函数工具输出（上游 #2181 / `675e59f0d`）：挂到同 `call_id` 的工具 part 上。
     *
     * 复用 [UIMessagePart.Tool.merge] 的 `output = output + other.output` 语义，因此**不需要**
     * 改流式合并层；工具是否已执行由 `output.isNotEmpty()` 自动判定。
     */
    private fun functionCallOutputChunk(item: JsonObject): MessageChunk? {
        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        return MessageChunk(
            id = callId,
            model = "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    message = null,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = callId,
                                toolName = "",
                                input = "",
                                output = listOf(UIMessagePart.Text(item.functionCallOutputText())),
                            )
                        )
                    ),
                    finishReason = null,
                )
            ),
        )
    }

    internal fun parseResponseOutput(jsonObject: JsonObject): MessageChunk {
        println(jsonObject)
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    val summary = output["summary"]?.jsonArray ?: error("summary not found")
                    summary.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now()
                                    )
                                )
                            }
                        }
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

                // 服务端已执行函数工具的结果（#2181）：回填到对应工具，避免客户端重复执行
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
                                parts.add(
                                    UIMessagePart.Text(
                                        text = text
                                    )
                                )
                            }

                            else -> error("unknown part type $partType")
                        }
                    }
                }
            }
        }

        return MessageChunk(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    message = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = parts,
                    ),
                    finishReason = null,
                    delta = null
                )
            ),
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObject)
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
    val supportEncryptedContent: Boolean = true
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

