package app.androidrik.ai.provider.providers.openai

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
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
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.resolveReasoningOverride
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.provider.Modality
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
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessageChoice
import app.androidrik.ai.ui.UIMessagePart
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
import app.androidrik.common.http.jsonArrayOrNull
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

private const val TAG = "ChatCompletionsAPI"

class ChatCompletionsAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette,
    /**
     * SSE 事件源工厂（**测试注入点**）。
     *
     * 生产环境用 `EventSources.createFactory(configureClientWithProxy(...))`；单测用 fake 工厂
     * 驱动真实 listener 的回调，覆盖「结构 400 重发 / 取消 / 旧 source 迟到回调」这些
     * 无法用纯函数验证的并发路径。
     */
    private val eventSourceFactory: (OkHttpClient) -> EventSource.Factory = { EventSources.createFactory(it) },
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk = withContext(Dispatchers.IO) {
        val requestBody =
            buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting
            )

        val request = Request.Builder()
            .url(apiEndpoint(providerSetting.baseUrl, providerSetting.chatCompletionsPath))
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.effectiveApiKeys, providerSetting.id.toString())}")
            .configureReferHeaders(providerSetting.baseUrl)
            .applyCustomHeaders(providerSetting, params.customHeaders)
            .build()

        Log.i(TAG, "generateText: ${summarizeRequestBody(json, requestBody)}")

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObjectOrNull
            ?: error("Chat response is not a JSON object: ${bodyStr.take(200)}")

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        // 空数组（异常/空响应的网关形态）用 firstOrNull 兜底，避免下标越界淹没真实错误
        val choice = bodyJson["choices"]?.jsonArray?.firstOrNull()?.jsonObjectOrNull
            ?: error("choices is null or empty")

        val message = choice["message"]?.jsonObject ?: throw Exception("message is null")
        val finishReason = choice["finish_reason"]
            ?.jsonPrimitive
            ?.content
            ?: "unknown"
        val usage = parseTokenUsage(bodyJson["usage"] as? JsonObject)

        MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = parseMessage(message),
                    finishReason = finishReason
                )
            ),
            usage = usage
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk> = callbackFlow {
        val host = providerSetting.baseUrl.toHttpUrl().host

        // 从错误自学习（2026-10-13 用户拍板）：先按静态判定发一次；若网关回
        // 「思考模式必须回传思维链」的结构校验（见 ToolReasoningBackfill），
        // 就记住这个 host 并把**同一次请求**带着回填补发一遍 —— 用户拿到的是一次成功回复，
        // 而下次起该 host 直接走回填，不再多花一次 400。
        //
        // 为什么在这儿重试而不是交给上游 GenerationHandler：上游拿不到「host 需要回填」这个信息，
        // 且它重试会先把错误报给用户。这里 400 是**无副作用的纯结构校验失败**（请求没被处理），
        // 重发安全。只重发一次（`retriedWithBackfill` 门控），不会循环。
        var retriedWithBackfill = false
        // 本次尝试是否会带回填。**必须在 learn 之前算出来**：学习会改变
        // requiresToolReasoningBackfill 的结果，若每次都重算，重发时就会误以为
        // 「本来就回填了」而不再重发（自己把自己锁死）。
        val attemptWillBackfill = requiresToolReasoningBackfill(host, params.model)

        // 连接管理（AI-3）：重发出来的新 EventSource 必须能被 `awaitClose` 取消，
        // 且旧 source 的迟到回调不能污染/关闭新 stream。
        val tracker = StreamingEventSourceTracker()

        // 事件源可被替换：结构校验失败时按带回填的请求重建一个（见 armBackfillRetry + openSource）。
        // 工厂与代理配置不变，只有请求体不同。

        /**
         * 最近一次 [buildRequest] 生成的请求体 JSON 字符串，仅供日志使用。
         *
         * 之所以另存一份而不是事后从 `request.body` 里读：`RequestBody` 没有序列化器，
         * `json.encodeToString(request.body)` 会抛 `Serializer for class 'RequestBody' is not found`。
         * 真机事故（2026-10-13）：这条日志写在成功路径上，导致**每次流式请求都失败**。
         */
        var lastRequestBodyJson: String? = null

        fun buildRequest(): Request {
            // ⚠️ `JsonObject` 必须先落到**显式类型的局部变量**再 encodeToString。
            // 不能把它内联进 `.post(json.encodeToString(buildChatCompletionRequest(...))...)`：
            // `.post()` 有接受 `RequestBody` 的重载，Kotlin 会把 `encodeToString` 的**期望类型**
            // 推断成 `RequestBody` ⇒ 报同一条错。
            val requestBody: JsonObject = buildChatCompletionRequest(
                messages = messages,
                params = params,
                providerSetting = providerSetting,
                stream = true,
            )
            val bodyJson = json.encodeToString(requestBody)
            lastRequestBodyJson = bodyJson
            return Request.Builder()
                .url(apiEndpoint(providerSetting.baseUrl, providerSetting.chatCompletionsPath))
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${keyRoulette.next(providerSetting.effectiveApiKeys, providerSetting.id.toString())}")
                .addHeader("Content-Type", "application/json")
                .configureReferHeaders(providerSetting.baseUrl)
                .applyCustomHeaders(providerSetting, params.customHeaders)
                .build()
        }

        /**
         * 是否该带着回填**重发一次**：只做判定与学习，**不创建连接**。
         *
         * 建连统一交给 [openSource]：旧实现把「建连接」也放在重试 helper 里，于是同步建连失败时
         * helper 建一个、外层再建一个 —— 多出一条未纳入 `awaitClose` 的连接（AI-3）。
         */
        fun armBackfillRetry(exception: Throwable?): Boolean {
            if (retriedWithBackfill || attemptWillBackfill) return false
            if (!isReasoningBackfillError(exception?.message)) return false
            // 先学、后重发：learnReasoningTextValidator 会让重发那次的
            // buildChatCompletionRequest 自动带上 reasoning_content。
            learnReasoningTextValidator(host)
            retriedWithBackfill = true
            Log.i(TAG, "streamText: host=$host requires reasoning_content backfill, resending once")
            return true
        }

        // listener 里的重试需要「建连接」，而建连接又需要 listener —— 用 lateinit 打破互相引用。
        lateinit var openSource: (Request) -> EventSource

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                // 旧 source 的迟到事件必须丢弃：结构失败重发后旧连接可能还在回调（不得污染新 stream）。
                if (!tracker.isActive(eventSource)) return
                if (data == "[DONE]") {
                    Log.d(TAG, "onEvent: terminal [DONE]")
                    // 显式结束标记：下游据此区分「正常收尾」与「裸断流」（见 MessageChunk.explicitEnd）
                    trySend(terminalChunk())
                    close()
                    return
                }
                Log.d(TAG, "onEvent: chars=${data.length}")
                data
                    .trim()
                    .split("\n")
                    .filter { it.isNotBlank() }
                    .mapNotNull { json.parseToJsonElement(it).jsonObjectOrNull }
                    .forEach {
                        // ⚠️ `!= null` 只防引用判空、不防 JSON null：部分网关用 `"error": null`
                        // 表示无错误，kotlinx 里那是非空的 JsonNull 引用，误进错误分支会拿
                        // HttpException("null") 杀掉整条流（同 ResponseAPI.parseStreamFailure 处理）
                        if (it["error"] != null && it["error"] !is JsonNull) {
                            val error = it["error"]!!.parseErrorDetail()
                            throw error
                        }
                        val id = it["id"]?.jsonPrimitive?.contentOrNull ?: ""
                        val model = it["model"]?.jsonPrimitive?.contentOrNull ?: ""

                        val choices = it["choices"] as? JsonArray ?: JsonArray(emptyList())
                        val choiceList = buildList {
                            if (choices.isNotEmpty()) {
                                // 部分 OpenAI 兼容接口会把 choices[0]/delta 显式写成 null，强制解析会中断整轮生成
                                val choice = choices[0] as? JsonObject ?: return@buildList
                                val message =
                                    choice["delta"] as? JsonObject ?: choice["message"] as? JsonObject
                                    ?: return@buildList
                                val finishReason =
                                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                                        ?: "unknown"
                                add(
                                    UIMessageChoice(
                                        index = 0,
                                        delta = parseMessage(message),
                                        message = null,
                                        finishReason = finishReason,
                                    )
                                )
                            }
                        }
                        val usage = parseTokenUsage(it["usage"] as? JsonObject)

                        val messageChunk = MessageChunk(
                            id = id,
                            model = model,
                            choices = choiceList,
                            usage = usage
                        )
                        trySend(messageChunk).onFailure { e ->
                            Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                        }
                    }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // 旧 source 的迟到失败同样不能影响已被替换的新 stream。
                if (!tracker.isActive(eventSource)) return
                var exception: Throwable? = t

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
                    exception = e
                } finally {
                    // ⚠️ `close(exception)` 传 null 等于**正常结束**：onFailure 但既没有 Throwable
                    // 也没有可解析 body 时，必须给一个 IOException 兜底，不能把失败当成功。
                    val failure: Throwable =
                        exception ?: IOException("SSE stream failed without exception")
                    // 结构校验失败 ⇒ 带着回填重发一次（此时**不**关闭流，调用方看不到这次失败）。
                    if (armBackfillRetry(failure)) {
                        try {
                            openSource(buildRequest())
                        } catch (e: Throwable) {
                            // 重发也没建立起事件源：如实上报。这里必须自己吞掉——本函数跑在
                            // OkHttp 的回调线程上，往外抛既没人接、又会让这次生成悬着不结束
                            // （调用方等不到 close）。
                            Log.w(TAG, "streamText: resend with reasoning backfill failed", e)
                            close(failure)
                        }
                    } else {
                        close(failure)
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                // 旧 source 迟到的 onClosed 不得关闭已被替换的新 stream。
                if (!tracker.isActive(eventSource)) return
                close()
            }
        }

        val factory = eventSourceFactory(client.configureClientWithProxy(providerSetting.proxy))

        /**
         * 建连并登记。
         *
         * ⚠️ 不能只写成 `newEventSource(...).also { tracker.activate(it) }`：OkHttp 在
         * `newEventSource` **返回前**就可能同步回调（内层 enqueue 之后立即执行），那时 tracker
         * 还没 active，首个 onEvent/onFailure/onClosed 会被丢弃（丢首个 chunk，甚至 flow 永不结束）。
         * 因此把 attempt token 绑在 per-source 的 [GuardedEventSourceListener] 上：回调先
         * `allowsCallback`（当前 attempt 可认领 active），旧 attempt 事后无法重新抢占。
         */
        fun openAndTrack(req: Request): EventSource {
            val attempt = tracker.newAttempt()
            val guarded = GuardedEventSourceListener(tracker, attempt, listener)
            return factory.newEventSource(req, guarded).also { tracker.activate(attempt, it) }
        }

        openSource = { req -> openAndTrack(req) }

        val request = buildRequest()
        // ⚠️ **不要**写 `json.encodeToString(request.body)`：`request.body` 的类型是
        // `okhttp3.RequestBody`，`encodeToString` 会去给它找序列化器并抛
        // `Serializer for class 'RequestBody' is not found` —— 而这是**成功路径上的日志**，
        // 于是每一次流式请求都会当场失败。要打请求体就在构建处把字符串带出来（见 buildRequest 的
        // lastRequestBodyJson）。
        Log.i(TAG, "streamText: ${lastRequestBodyJson?.let { "chars=${it.length}" } ?: "<unavailable>"}")

        // ⚠️ 整段「建连 + awaitClose」必须包在 try/finally 里：如果建连（或同步回调）在
        // `awaitClose` 之前抛错，旧写法不会执行 awaitClose 里的清理 ⇒ 若 tracker 已经通过
        // 同步回调 claim 了 source，这条连接会泄漏（且 flow 以异常结束但连接还在跑）。
        try {
            try {
                openSource(request)
            } catch (e: Throwable) {
                // 同步失败（未能建立事件源）走的是另一条路径，onFailure 不会被调用：
                // 这里同样给一次自学习机会，否则这类网关永远学不到。
                if (!armBackfillRetry(e)) throw e
                // 重发**只建一个**替代连接（旧实现在这里会再建一个未纳入 awaitClose 的连接，AI-3）。
                openSource(buildRequest())
            }

            awaitClose {
                Log.d(TAG, "awaitClose: closing eventSource")
            }
        } finally {
            // 任何退出路径（正常收尾 / 建连抛错 / 同步回调抛错 / 取消）都回收当前 source。
            // 取消的是**当前**（可能是重发后的新）source，而不是首次创建的那个（AI-3）。
            tracker.close()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)


    private fun buildChatCompletionRequest(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        providerSetting: ProviderSetting.OpenAI,
        stream: Boolean = false,
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        return buildJsonObject {
            put("model", params.model.modelId)
            val builtMessages = buildMessages(
                messages = messages,
                includeHistoryReasoning = providerSetting.includeHistoryReasoning,
                supportInputModalities = params.model.inputModalities,
                // 见 requiresToolReasoningBackfill：网关要求「思考模式 + 带 tool_calls」的消息必须回传思维链
                backfillReasoning = requiresToolReasoningBackfill(host, params.model),
            )
            put("messages", builtMessages)

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_tokens", params.maxTokens)

            put("stream", stream)
            if (stream) {
                if (host != "api.mistral.ai") { // mistral 不支持 stream_options
                    put("stream_options", buildJsonObject {
                        put("include_usage", true)
                    })
                }
            }

            // open router适配
            if(host == "openrouter.ai") {
                if(params.model.outputModalities.contains(Modality.IMAGE)) {
                    put("modalities", buildJsonArray {
                        add("image")
                        add("text")
                    })
                }
            }

            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                // 模型级覆盖优先,提供商级兜底;只在选中具体档位时生效,OFF/AUTO 的开关语义不变
                val reasoningOverride = resolveReasoningOverride(params.model, providerSetting)
                    ?.takeIf { level.isEnabled && level != ReasoningLevel.AUTO }
                when (host) {
                    "openrouter.ai" -> {
                        // https://openrouter.ai/docs/use-cases/reasoning-tokens
                        put("reasoning", buildJsonObject {
                            when (level) {
                                ReasoningLevel.OFF -> put("effort", "none")
                                ReasoningLevel.AUTO -> put("enabled", true)
                                else -> put("effort", reasoningOverride?.effort ?: level.effort)
                            }
                        })
                    }

                    "dashscope.aliyuncs.com" -> {
                        // 阿里云百炼
                        // https://bailian.console.aliyun.com/console?tab=doc#/doc/?type=model&url=https%3A%2F%2Fhelp.aliyun.com%2Fdocument_detail%2F2870973.html&renderType=iframe
                        put("enable_thinking", level.isEnabled)
                        if (level != ReasoningLevel.AUTO) {
                            put("thinking_budget", reasoningOverride?.budgetTokens ?: level.budgetTokens)
                        }
                    }

                    "ark.cn-beijing.volces.com" -> {
                        // 豆包 (火山)
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.mistral.ai" -> {
                        // Mistral 不支持
                    }

                    "chat.intern-ai.org.cn" -> {
                        // 书生
                        // https://internlm.intern-ai.org.cn/api/document?lang=zh
                        put("thinking_mode", level.isEnabled)
                    }

                    "api.siliconflow.cn" -> {
                        // https://docs.siliconflow.cn/cn/userguide/capabilities/reasoning#3-1-api-%E5%8F%82%E6%95%B0
                        val modelId = params.model.modelId
                        val siliconflowThinkingModels = setOf(
                            "Pro/moonshotai/Kimi-K2.5",
                            "Pro/zai-org/GLM-5",
                            "Pro/zai-org/GLM-5.1",
                            "Pro/zai-org/GLM-4.7",
                            "deepseek-ai/DeepSeek-V3.2",
                            "Pro/deepseek-ai/DeepSeek-V3.2",
                            "Qwen/Qwen3.5-397B-A17B",
                            "Qwen/Qwen3.5-122B-A10B",
                            "Qwen/Qwen3.5-35B-A3B",
                            "Qwen/Qwen3.5-27B",
                            "Qwen/Qwen3.5-9B",
                            "Qwen/Qwen3.5-4B",
                            "zai-org/GLM-4.6",
                            "Qwen/Qwen3-8B",
                            "Qwen/Qwen3-14B",
                            "Qwen/Qwen3-32B",
                            "Qwen/Qwen3-30B-A3B",
                            "tencent/Hunyuan-A13B-Instruct",
                            "zai-org/GLM-4.5V",
                            "deepseek-ai/DeepSeek-V3.1-Terminus",
                            "Pro/deepseek-ai/DeepSeek-V3.1-Terminus",
                            "deepseek-ai/DeepSeek-V4-Flash",
                            "Pro/deepseek-ai/DeepSeek-V4-Flash",
                            "deepseek-ai/DeepSeek-V4-Pro",
                            "Pro/deepseek-ai/DeepSeek-V4-Pro",
                        )
                        if (modelId in siliconflowThinkingModels) {
                            put("enable_thinking", level.isEnabled)
                        }
                    }

                    "aiping.cn" -> {
                        put("enable_thinking", level.isEnabled)
                    }

                    "open.bigmodel.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                    }

                    "api.moonshot.cn" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                            // K2.6 的 thinking.keep 默认为 null（忽略历史思考），思考开启时
                            // 需显式传 "all" 才是保留式思考；文档推荐与 enabled 搭配（#1586）
                            if (level.isEnabled && ModelRegistry.KIMI_K2_6.match(params.model.modelId)) {
                                put("keep", "all")
                            }
                        })
                    }

                    "api.deepseek.com" -> {
                        put("thinking", buildJsonObject {
                            put("type", if (!level.isEnabled) "disabled" else "enabled")
                        })
                        if (level.isEnabled && level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", reasoningOverride?.effort ?: level.effort)
                        }
                    }

                    "integrate.api.nvidia.com" -> {
                        if ("deepseek-v4" in params.model.modelId.lowercase()) {
                            if (level != ReasoningLevel.AUTO) {
                                val effort = reasoningOverride?.effort ?: when (level) {
                                    ReasoningLevel.XHIGH -> "max"
                                    ReasoningLevel.MAX -> "max"
                                    ReasoningLevel.OFF -> "none"
                                    else -> "high"
                                }
                                put("reasoning_effort", effort)
                            }
                        } else {
                            if (level != ReasoningLevel.AUTO) {
                                put(
                                    "reasoning_effort",
                                    reasoningOverride?.effort
                                        ?: if (level.effort == "none") "low" else level.effort
                                )
                            }
                        }
                    }

                    "opencode.ai" -> {
                        if (level != ReasoningLevel.AUTO) {
                            put("reasoning_effort", reasoningOverride?.effort ?: level.effort)
                        }
                    }

                    else -> {
                        // OpenAI 官方
                        // 文档中，completions API 只支持 "low", "medium", "high"
                        if (level != ReasoningLevel.AUTO) {
                            put(
                                "reasoning_effort",
                                reasoningOverride?.effort
                                    ?: if (level.effort == "none") "low" else level.effort
                            )
                        }
                    }
                }
            }

            if (params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                            ?: InputSchema.Obj(properties = JsonObject(emptyMap()))
                                    ).ensureArrayItems()
                                )
                            })
                        })
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    private fun isModelAllowTemperature(model: Model): Boolean {
        val isMoonshotRestricted = ModelRegistry.KIMI_K2_5.match(model.modelId) ||
                ModelRegistry.KIMI_K2_6.match(model.modelId) ||
                ModelRegistry.KIMI_K3.match(model.modelId) ||
                ModelRegistry.KIMI_K3_ALIAS.match(model.modelId)
        return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && 
               !ModelRegistry.GPT_5.match(model.modelId) && 
               !isMoonshotRestricted
    }

    private fun buildMessages(
        messages: List<UIMessage>,
        includeHistoryReasoning: Boolean = true,
        supportInputModalities: List<Modality> = listOf(Modality.TEXT, Modality.IMAGE),
        backfillReasoning: Boolean = false,
    ) = buildJsonArray {
        val filteredMessages = messages.filter { it.isValidToUpload() }

        filteredMessages.forEach { message ->
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(
                    message = message,
                    includeReasoning = includeHistoryReasoning,
                    supportInputModalities = supportInputModalities,
                    backfillReasoning = backfillReasoning,
                )
            } else {
                addNonAssistantMessage(message)
            }
        }
    }

    private fun JsonArrayBuilder.addAssistantMessages(
        message: UIMessage,
        includeReasoning: Boolean,
        supportInputModalities: List<Modality>,
        backfillReasoning: Boolean = false,
    ) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()
        var reasoningPart: UIMessagePart.Reasoning? = null

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    // 从当前 group 中提取 reasoning（保持顺序）
                    if (includeReasoning) {
                        group.parts.filterIsInstance<UIMessagePart.Reasoning>().firstOrNull()?.let {
                            reasoningPart = it
                        }
                    }
                    group.parts
                        .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                        .forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 输出 assistant 消息（包含累积的内容 + tool_calls）
                    buildAssistantMessageJson(
                        contentParts = contentBuffer,
                        tools = group.tools,
                        reasoningPart = reasoningPart,
                        backfillReasoning = backfillReasoning,
                    )?.let { assistantMessage ->
                        add(assistantMessage)
                    }
                    contentBuffer.clear()
                    reasoningPart = null // 清空，下一个 group 可能有新的 reasoning

                    // 紧跟 tool 结果消息
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", tool.toolCallId)
                            put("content", tool.toToolResultContent(supportInputModalities))
                        })
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty() || reasoningPart != null) {
            buildAssistantMessageJson(
                contentParts = contentBuffer,
                tools = emptyList(),
                reasoningPart = reasoningPart,
                backfillReasoning = backfillReasoning,
            )?.let { assistantMessage ->
                add(assistantMessage)
            }
        }
    }

    private fun buildAssistantMessageJson(
        contentParts: List<UIMessagePart>,
        tools: List<UIMessagePart.Tool>,
        reasoningPart: UIMessagePart.Reasoning?,
        backfillReasoning: Boolean = false,
    ): JsonObject? {
        val hasUsableContent = contentParts.any { part ->
            when (part) {
                is UIMessagePart.Text -> part.text.isNotBlank()
                is UIMessagePart.Image -> part.url.isNotBlank()
                else -> false
            }
        }
        val hasReasoning = !reasoningPart?.reasoning.isNullOrBlank()
        if (!hasUsableContent && !hasReasoning && tools.isEmpty()) {
            return null
        }

        return buildJsonObject {
            put("role", "assistant")

            // reasoning_content
            if (hasReasoning) {
                put("reasoning_content", reasoningPart.reasoning)
            } else if (backfillReasoning) {
                // 网关的结构性校验：**思考模式下每条 assistant 消息都必须回传思维链**，
                // 而这条没有可回传的（旧消息 / 只给加密推理 / 用户关了回传 / 推理被删空）⇒
                // 回填**空串**。空串被接受（2026-10-13 对 api.deepseek.com 实测：
                // 空串与占位都 200，省略字段则 400）。
                //
                // ⚠️ 判据里**不能**再要求 `tools.isNotEmpty()`——原先只给带 tool_calls 的 assistant
                // 消息回填，于是「最后那条普通总结性 assistant 消息」漏了，DeepSeek 报
                // 「reasoning_content must be passed back」。真机定位过程（2026-10-13，同一份 17KB
                // 报文在设备上重放）：
                //   - 原报文（thinking=enabled，末条普通 assistant 缺字段）→ 400
                //   - 给末条补上（哪怕是空串）→ **200**
                //   - 删掉带 tool_calls 那条的字段 → 仍 400（说明校验不限于工具消息）
                //   - thinking=disabled → 200（校验只在思考模式生效）
                //   - 删掉末条 assistant → 200
                // 故这里对**所有** assistant 消息回填；是否回填整体仍由 host/模型门槛决定
                // （见 requiresToolReasoningBackfill），不会波及不认识的网关。
                //
                // ⚠️ 另注意「编辑过」**不算**没有可回传的：`#1929` 编辑后文本非空 ⇒ 走上面分支，
                // 把**用户改写过的文本**当 `reasoning_content` 回传（2026-10-09 核实；早前注释曾把它
                // 列为回填情形，前提不成立）。
                put("reasoning_content", "")
            }

            // content
            if (contentParts.isEmpty()) {
                put("content", "")
            } else if (contentParts.size == 1 && contentParts[0] is UIMessagePart.Text) {
                put("content", (contentParts[0] as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    contentParts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }

            // tool_calls
            if (tools.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    tools.forEach { tool ->
                        add(buildJsonObject {
                            put("id", tool.toolCallId)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", tool.toolName)
                                // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                                put("arguments", tool.inputAsJson().toString())
                            })
                        })
                    }
                })
            }
        }
    }

    private fun JsonArrayBuilder.addNonAssistantMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", JsonPrimitive(message.role.name.lowercase()))

            if (message.parts.isOnlyTextPart()) {
                put("content", message.parts.filterIsInstance<UIMessagePart.Text>().first().text)
            } else {
                putJsonArray("content") {
                    message.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    part.encodeBase64().onSuccess { encodedImage ->
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject {
                                            put("url", encodedImage.base64)
                                        })
                                    }.onFailure {
                                        it.printStackTrace()
                                        put("type", "text")
                                        put("text", "")
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

    private fun UIMessagePart.Tool.toToolResultContent(supportInputModalities: List<Modality>): JsonElement {
        // 只考虑文字和图片;只有模型支持图片输入时,图片才作为多模态内容回传,否则以文本占位,避免发给不支持的模型报错
        val supportsImageInput = Modality.IMAGE in supportInputModalities
        val hasImageToSend = output.any { it is UIMessagePart.Image && supportsImageInput }
        return if (!hasImageToSend) {
            JsonPrimitive(output.mapNotNull { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text
                    is UIMessagePart.Image -> "[Image output omitted: current model does not support image input]"
                    else -> null
                }
            }.joinToString("\n"))
        } else {
            buildJsonArray {
                output.forEach { part ->
                    when (part) {
                        is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", part.text)
                                })
                            }
                        }

                        is UIMessagePart.Image -> {
                            add(buildJsonObject {
                                part.encodeBase64().onSuccess { encodedImage ->
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", encodedImage.base64)
                                    })
                                }.onFailure {
                                    Log.w(TAG, "encode tool result image failed: ${part.url}", it)
                                    put("type", "text")
                                    put("text", "Error: Failed to encode image to base64")
                                }
                            })
                        }

                        else -> {}
                    }
                }
            }
        }
    }

    // internal 而非 private：本仓需要能对"响应解析"写端到端测试（上游 #2137 的三条输入就是现成用例）。
    // 同模块的 test 源集可见 internal。
    internal fun parseMessage(jsonObject: JsonObject): UIMessage {
        val role = MessageRole.fromValueOrNull(jsonObject["role"]?.jsonPrimitiveOrNull?.contentOrNull)
            ?: MessageRole.ASSISTANT

        // 也许支持其他模态的输出content?
        val content = jsonObject["content"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
        val reasoning = jsonObject["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: jsonObject["content"]?.takeIf { it is JsonArray }?.let { arr ->
                // Mistral接口
                // {"id":"","object":"chat.completion.chunk","created":1772351733,"model":"magistral-medium-2509","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"好的"}]}]},"finish_reason":null}]}
                arr.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull?.get("thinking")?.jsonArrayOrNull?.getOrNull(0)?.jsonObjectOrNull?.get(
                    "text"
                )?.jsonPrimitiveOrNull?.contentOrNull
            }
        val toolCalls = jsonObject["tool_calls"] as? JsonArray ?: JsonArray(emptyList())
        val images = jsonObject["images"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty()) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning,
                            createdAt = Clock.System.now(),
                            finishedAt = null
                        )
                    )
                }
                toolCalls.forEach { toolCalls ->
                    val toolCallObj = toolCalls as? JsonObject ?: return@forEach
                    val type = toolCallObj["type"]?.jsonPrimitive?.contentOrNull
                    if (!type.isNullOrEmpty() && type != "function") error("tool call type not supported: $type")
                    val toolCallId = toolCallObj["id"]?.jsonPrimitive?.contentOrNull
                    val toolName =
                        (toolCallObj["function"] as? JsonObject)?.let { it["name"]?.jsonPrimitive?.contentOrNull }
                    val arguments =
                        (toolCallObj["function"] as? JsonObject)?.let { it["arguments"]?.jsonPrimitive?.contentOrNull }
                    add(
                        UIMessagePart.Tool(
                            toolCallId = toolCallId ?: "",
                            toolName = toolName ?: "",
                            input = arguments ?: "",
                            output = emptyList()
                        )
                    )
                }
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    val type = imageObject["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (type != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    // **原样存**：`data:` 与 `http(s)` 正是下游两种能吃的形态 ——
                    //  · data: 由 Base64ImageToLocalFileTransformer 转成本地文件（FilesManager:290）
                    //  · http 由 Coil 显示；发请求时 encodeBase64 原样透传（FileEncoder.kt:75）
                    // 其余形态（相对路径等）**跳过而不是抛**：这里在流式解析链上，畸形输入不该杀掉整轮。
                    //
                    // 两个已修的历史问题（2026-10-16，上游 #2137 同因核查时发现）：
                    //  ① 原先 `require(url.startsWith("data:image"))` 会对 https 图片 URL 抛
                    //     IllegalArgumentException —— 而 https 恰是 OpenAI 兼容接口里最常见的形态，
                    //     于是"中转回一张图"会变成"整轮生成中断"。
                    //  ② 原先 `url.substringAfter("data:image/png;base64,")` 去掉前缀后存的是**裸 base64**，
                    //     而 transformer 只认 `data:image` 开头、encodeBase64 只认 file:/data:/http 三种
                    //     ⇒ 两边都不认，图在发下一轮时被静默换成空文本。**去掉前缀看着像省事，实际是把它
                    //     变成了谁都不认识的字符串。**
                    if (url.startsWith("data:") || url.startsWith("http")) {
                        add(UIMessagePart.Image(url))
                    }
                }
            },
            annotations = parseAnnotations(
                jsonArray = jsonObject["annotations"]?.jsonArrayOrNull ?: JsonArray(
                    emptyList()
                )
            ),
        )
    }

    // 注解数组里出现 `null` 元素、或 `url_citation` 显式为 `null`，都是中转侧真实会发生的形态
    // （上游 #2137 的第三条测试输入就是 `"url_citation":null`）。注意 **`?.` 只防 null 引用、
    // 不防 `JsonNull` 这个值** —— JSON 的 null 到了 kotlinx 手里是非空引用，所以这里必须用
    // jsonObjectOrNull，而不是 `element.jsonObject` / `?.jsonObject`。
    //
    // 与上游保持同一条线：只跳过"元素/字段本身是 null"，`type` 缺失（error("type is null")）与
    // 未知注解类型仍然**响亮失败** —— 上游的修复也没动这两处，本仓不擅自比上游更宽容。
    private fun parseAnnotations(jsonArray: JsonArray): List<UIMessageAnnotation> {
        return jsonArray.mapNotNull { element ->
            val annotation = element.jsonObjectOrNull ?: return@mapNotNull null
            val type =
                annotation["type"]?.jsonPrimitive?.contentOrNull ?: error("type is null")
            when (type) {
                "url_citation" -> {
                    UIMessageAnnotation.UrlCitation(
                        title = annotation["url_citation"]?.jsonObjectOrNull?.get("title")?.jsonPrimitive?.contentOrNull
                            ?: "",
                        url = annotation["url_citation"]?.jsonObjectOrNull?.get("url")?.jsonPrimitive?.contentOrNull
                            ?: "",
                    )
                }

                else -> error("unknown annotation type: $type")
            }
        }
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            // 各 provider 汇报缓存命中的字段形状不统一，按方言兜底解析（#1576）：
            // OpenAI 嵌套 -> Moonshot 顶层 cached_tokens -> DeepSeek prompt_cache_hit_tokens
            cachedTokens = jsonObject["prompt_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: jsonObject["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: jsonObject["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }

    private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
        val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
        val texts = filter { it is UIMessagePart.Text }.size
        return gonnaSend == texts && texts == 1
    }
}
