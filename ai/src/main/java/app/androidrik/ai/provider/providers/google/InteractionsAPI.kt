package app.androidrik.ai.provider.providers.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.core.resolveReasoningOverride
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.TextGenerationResult
import app.androidrik.ai.provider.providers.PartGroup
import app.androidrik.ai.provider.providers.asDowngradedSystemMessage
import app.androidrik.ai.provider.providers.groupPartsByToolBoundary
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.ui.GoogleInteractionsMetadata
import app.androidrik.ai.ui.ServerToolMetadata
import app.androidrik.ai.ui.ServerToolProtocol
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.ui.toMetadata
import app.androidrik.ai.util.applyCustomHeaders
import app.androidrik.ai.util.checkAttachmentLimit
import app.androidrik.ai.util.configureClientWithProxy
import app.androidrik.ai.util.configureSessionHeaders
import app.androidrik.ai.util.encodeBase64
import app.androidrik.ai.util.ensureArrayItems
import app.androidrik.ai.util.HttpException
import app.androidrik.ai.util.json
import app.androidrik.ai.util.mergeCustomBody
import app.androidrik.ai.util.parseErrorDetail
import app.androidrik.ai.util.removeElements
import app.androidrik.common.http.await
import app.androidrik.common.http.jsonArrayOrNull
import app.androidrik.common.http.jsonObjectOrNull
import app.androidrik.common.http.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import kotlin.time.Clock

internal fun ProviderSetting.Google.interactionsUrl(): HttpUrl {
    require(supportsInteractionsApi)
    val host = if (location == "global") "aiplatform.googleapis.com" else "aiplatform.$location.rep.googleapis.com"
    return "https://$host/v1/projects".toHttpUrl().newBuilder()
        .addPathSegment(projectId.trim())
        .addPathSegment("locations")
        .addPathSegment(location)
        .addPathSegment("interactions")
        .build()
}

/**
 * Stateless Gemini Interactions on Vertex AI. Local message history supplies every turn.
 */
internal class InteractionsAPI(
    private val client: OkHttpClient,
    private val authorize: suspend (ProviderSetting.Google, Request) -> Request,
) {
    suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val requestBody = buildRequestBody(providerSetting, messages, params, stream = false)
        val request = buildRequest(providerSetting, params, requestBody)

        // await() waits for the response headers; reading the body can still block.
        client.configureClientWithProxy(providerSetting.proxy).newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw response.interactionsError()
            }
            val bodyJson = json.parseToJsonElement(response.body.string()).jsonObject
            parseInteraction(bodyJson, fallbackModel = params.model.modelId)
        }
    }

    fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildRequestBody(providerSetting, messages, params, stream = true)
        val request = buildRequest(providerSetting, params, requestBody)

        val decoder = InteractionsStreamDecoder(fallbackModel = params.model.modelId)

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    result.chunks.forEach { trySend(it) }
                    if (result.completed) close()
                } catch (e: Exception) {
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(if (response != null && !response.isSuccessful) response.interactionsError()
                    else t ?: IOException("Interactions stream failed"))
            }

            override fun onClosed(eventSource: EventSource) {
                decoder.onClosed().forEach { trySend(it) }
                close()
            }
        }

        val eventSource = EventSources.createFactory(client.configureClientWithProxy(providerSetting.proxy))
            .newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private suspend fun buildRequest(
        providerSetting: ProviderSetting.Google,
        params: TextGenerationParams,
        requestBody: JsonObject,
    ): Request {
        val url = providerSetting.interactionsUrl()
        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .configureSessionHeaders(url.toString(), params.sessionId)
            .build()
        return authorize(providerSetting, request).newBuilder()
            .applyCustomHeaders(providerSetting, params.customHeaders)
            .build()
    }

    private fun Response.interactionsError(): HttpException {
        val detail = runCatching { json.parseToJsonElement(body.string()).parseErrorDetail().message }.getOrNull()
        return HttpException("Vertex Interactions HTTP $code" + (detail?.let { ": $it" } ?: ""))
    }

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", params.model.modelId)
        val outputsImage = params.model.outputModalities.contains(Modality.IMAGE)

        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && !outputsImage) {
            put(
                "system_instruction",
                systemMessage.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            )
        }

        put("input", buildInput(messages, skipFirstSystem = !outputsImage))

        val generationConfig = buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("top_p", params.topP)
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinking_summaries", "auto")
                thinkingLevel(providerSetting, params)?.let { put("thinking_level", it) }
            }
        }
        if (generationConfig.isNotEmpty()) put("generation_config", generationConfig)

        if (outputsImage) {
            putJsonArray("response_format") {
                add(buildJsonObject { put("type", "text") })
                add(buildJsonObject { put("type", "image") })
            }
        }

        // Interactions API 的 tools 是扁平数组，函数工具和内置工具写在同一个 key 下
        val useFunctionTools =
            params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        val builtInTools = params.model.tools.mapNotNull { builtInTool ->
            when (builtInTool) {
                BuiltInTools.Search -> "google_search"
                BuiltInTools.UrlContext -> "url_context"
                else -> null
            }
        }
        if (useFunctionTools || builtInTools.isNotEmpty()) {
            putJsonArray("tools") {
                if (useFunctionTools) {
                    params.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            tool.parameters()?.let { schema ->
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(schema)
                                        .removeElements(listOf("propertyNames"))
                                        .ensureArrayItems(),
                                )
                            }
                        })
                    }
                }
                builtInTools.forEach { type ->
                    add(buildJsonObject { put("type", type) })
                }
            }
        }

    }.mergeCustomBody(params.customBody).let { body ->
        // Custom fields must not change the transport or send conversations into server-side storage.
        JsonObject(body - setOf("background", "previous_interaction_id") + mapOf(
            "stream" to JsonPrimitive(stream),
            "store" to JsonPrimitive(false),
        ))
    }

    private fun thinkingLevel(providerSetting: ProviderSetting.Google, params: TextGenerationParams): String? =
        when (params.reasoningLevel) {
            ReasoningLevel.AUTO -> null
            ReasoningLevel.OFF -> "low"
            else -> resolveReasoningOverride(params.model, providerSetting)?.effort ?: when (params.reasoningLevel) {
                ReasoningLevel.LOW -> "low"
                ReasoningLevel.MEDIUM -> "medium"
                ReasoningLevel.XHIGH, ReasoningLevel.MAX ->
                    if (params.model.modelId == "gemini-4-hydrogen") "xhigh" else "high"
                else -> "high"
            }
        }

    internal fun buildInput(messages: List<UIMessage>, skipFirstSystem: Boolean = true): JsonArray = buildJsonArray {
        val firstSystem = if (skipFirstSystem) messages.firstOrNull { it.role == MessageRole.SYSTEM } else null
        messages
            .filter { it !== firstSystem && it.isValidToUpload() }
            .forEach { message ->
                when (message.role) {
                    MessageRole.ASSISTANT -> addModelSteps(message)
                    MessageRole.SYSTEM -> addUserStep(message.asDowngradedSystemMessage())
                    else -> addUserStep(message)
                }
            }
    }

    private fun JsonArrayBuilder.addUserStep(message: UIMessage) {
        val content = message.parts.mapNotNull { it.toInteractionsContent() }
        if (content.isEmpty()) return
        add(buildJsonObject {
            put("type", "user_input")
            putJsonArray("content") { content.forEach { add(it) } }
        })
    }

    private fun JsonArrayBuilder.addModelSteps(message: UIMessage) {
        val contentBuffer = mutableListOf<JsonObject>()

        fun flushContent() {
            if (contentBuffer.isEmpty()) return
            val content = contentBuffer.toList()
            contentBuffer.clear()
            add(buildJsonObject {
                put("type", "model_output")
                putJsonArray("content") { content.forEach { add(it) } }
            })
        }

        for (group in groupPartsByToolBoundary(message.parts)) {
            when (group) {
                is PartGroup.Content -> group.parts.forEach { part ->
                    when (part) {
                        is UIMessagePart.Reasoning -> {
                            // 没有 Interactions 签名的思考来自其他协议，无法作为 thought step 回传
                            val signature = part.metadataAs<GoogleInteractionsMetadata>()?.signature
                                ?: return@forEach
                            flushContent()
                            add(buildJsonObject {
                                put("type", "thought")
                                put("signature", signature)
                                if (part.reasoning.isNotEmpty()) {
                                    putJsonArray("summary") {
                                        add(buildJsonObject {
                                            put("type", "text")
                                            put("text", part.reasoning)
                                        })
                                    }
                                }
                            })
                        }

                        is UIMessagePart.ServerTool -> {
                            val steps = part.toInteractionsServerToolSteps()
                            if (steps.isNotEmpty()) {
                                flushContent()
                                steps.forEach { add(it) }
                            }
                        }

                        is UIMessagePart.Text, is UIMessagePart.Image ->
                            part.toInteractionsContent()?.let { contentBuffer.add(it) }

                        else -> {}
                    }
                }

                is PartGroup.Tools -> {
                    flushContent()
                    // 同一批并发工具调用需先输出全部 function_call，再输出对应结果
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("id", tool.toolCallId)
                            put("name", tool.toolName)
                            put("arguments", tool.inputAsJson())
                            tool.metadataAs<GoogleInteractionsMetadata>()?.signature?.let {
                                put("signature", it)
                            }
                        })
                    }
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_result")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            putJsonArray("result") {
                                val content = tool.output
                                    .filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
                                    .mapNotNull { it.toInteractionsContent() }
                                if (content.isEmpty()) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", " ")
                                    })
                                } else {
                                    content.forEach { add(it) }
                                }
                            }
                        })
                    }
                }
            }
        }

        flushContent()
    }

    private fun UIMessagePart.toInteractionsContent(): JsonObject? = when (this) {
        is UIMessagePart.Text -> if (text.isEmpty()) {
            null
        } else {
            buildJsonObject {
                put("type", "text")
                put("text", text)
            }
        }

        is UIMessagePart.Image -> encodeBase64(false).checkAttachmentLimit().getOrNull()?.let { encoded ->
            buildJsonObject {
                put("type", "image")
                put("mime_type", encoded.mimeType)
                if (encoded.base64.startsWith("http")) {
                    put("uri", encoded.base64)
                } else {
                    put("data", encoded.base64)
                }
            }
        }

        is UIMessagePart.Video -> encodeBase64(false).checkAttachmentLimit().getOrNull()?.let { base64Data ->
            buildJsonObject {
                put("type", "video")
                put("mime_type", "video/mp4")
                put("data", base64Data)
            }
        }

        is UIMessagePart.Audio -> encodeBase64(false).checkAttachmentLimit().getOrNull()?.let { base64Data ->
            buildJsonObject {
                put("type", "audio")
                put("mime_type", "audio/mp3")
                put("data", base64Data)
            }
        }

        else -> null
    }

    private fun UIMessagePart.ServerTool.toInteractionsServerToolSteps(): List<JsonObject> {
        val metadata = metadataAs<ServerToolMetadata>() ?: return emptyList()
        if (metadata.protocol != ServerToolProtocol.GOOGLE_INTERACTIONS) return emptyList()
        // call 与 result 必须成对回传，流被中断导致缺少结果时整体丢弃
        val call = metadata.call ?: return emptyList()
        val result = metadata.result ?: return emptyList()
        return listOf(call, result)
    }

    internal fun parseInteraction(interaction: JsonObject, fallbackModel: String? = null): TextGenerationResult {
        interaction.requireSuccessfulInteraction()
        val parts = mutableListOf<UIMessagePart>()
        val annotations = mutableListOf<UIMessageAnnotation>()

        interaction["steps"]?.jsonArrayOrNull.orEmpty().forEachIndexed { index, element ->
            val step = element.jsonObjectOrNull ?: return@forEachIndexed
            val type = step.stringOrNull("type") ?: return@forEachIndexed
            when {
                type == "thought" -> {
                    val now = Clock.System.now()
                    parts.add(
                        UIMessagePart.Reasoning(
                            reasoning = step["summary"]?.jsonArrayOrNull.orEmpty()
                                .mapNotNull { it.jsonObjectOrNull?.stringOrNull("text") }
                                .joinToString(""),
                            createdAt = now,
                            finishedAt = now,
                            metadata = interactionsSignatureMetadata(step.stringOrNull("signature")),
                        )
                    )
                }

                type == "model_output" -> step["content"]?.jsonArrayOrNull.orEmpty().forEach { item ->
                    val content = item.jsonObjectOrNull ?: return@forEach
                    when (content.stringOrNull("type")) {
                        "text" -> {
                            parts.add(UIMessagePart.Text(content.stringOrNull("text") ?: ""))
                            annotations.addAll(content["annotations"].toInteractionsAnnotations())
                        }

                        "image" -> content.stringOrNull("data")?.let { data ->
                            val mimeType = content.stringOrNull("mime_type") ?: "image/png"
                            parts.add(UIMessagePart.Image(url = "data:$mimeType;base64,$data"))
                        }
                    }
                }

                type == "function_call" -> parts.add(
                    UIMessagePart.Tool(
                        toolCallId = step.stringOrNull("id") ?: "${interaction.stringOrNull("id")}:tool-$index",
                        toolName = step.stringOrNull("name") ?: "",
                        input = step["arguments"]?.let(json::encodeToString) ?: "",
                        output = emptyList(),
                        metadata = interactionsSignatureMetadata(step.stringOrNull("signature")),
                    )
                )

                isInteractionsServerToolCall(type) -> parts.add(
                    UIMessagePart.ServerTool(
                        toolCallId = step.stringOrNull("id") ?: "",
                        toolName = type.removeSuffix("_call"),
                        input = step["arguments"],
                        status = ServerToolStatus.IN_PROGRESS,
                        metadata = ServerToolMetadata(
                            protocol = ServerToolProtocol.GOOGLE_INTERACTIONS,
                            call = step,
                            callIndex = index,
                        ).toMetadata(),
                    )
                )

                isInteractionsServerToolResult(type) -> {
                    val callId = step.stringOrNull("call_id") ?: ""
                    val resultMetadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_INTERACTIONS,
                        result = step,
                        resultIndex = index,
                    ).toMetadata()
                    val existingIndex = parts.indexOfFirst {
                        it is UIMessagePart.ServerTool && it.toolCallId == callId
                    }
                    if (existingIndex < 0) {
                        parts.add(
                            UIMessagePart.ServerTool(
                                toolCallId = callId,
                                toolName = type.removeSuffix("_result"),
                                output = step["result"],
                                status = step.interactionsServerToolResultStatus(),
                                metadata = resultMetadata,
                            )
                        )
                    } else {
                        val existing = parts[existingIndex] as UIMessagePart.ServerTool
                        parts[existingIndex] = existing.copy(
                            output = step["result"],
                            status = step.interactionsServerToolResultStatus(),
                            metadata = JsonObject(existing.metadata.orEmpty() + resultMetadata),
                        )
                    }
                }
            }
        }

        return TextGenerationResult(
            id = interaction.stringOrNull("id") ?: "",
            model = interaction.stringOrNull("model") ?: fallbackModel ?: "",
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = parts,
                annotations = annotations.distinct(),
            ),
            finishReason = interaction.stringOrNull("status"),
            usage = parseInteractionsUsage(interaction["usage"]?.jsonObjectOrNull),
        )
    }
}

internal fun JsonObject.requireSuccessfulInteraction() {
    val status = stringOrNull("status")
    if (status == "failed" || status == "cancelled") {
        val detail = this["error"]?.parseErrorDetail()?.message
        throw HttpException("Vertex Interactions $status" + (detail?.let { ": $it" } ?: ""))
    }
}

internal fun interactionsSignatureMetadata(signature: String?): JsonObject? =
    signature?.takeIf { it.isNotEmpty() }?.let { GoogleInteractionsMetadata(signature = it).toMetadata() }

internal fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitiveOrNull?.contentOrNull

// function_call / function_result 由客户端执行，其余 *_call / *_result 都是服务端工具
internal fun isInteractionsServerToolCall(type: String): Boolean =
    type.endsWith("_call") && type != "function_call"

internal fun isInteractionsServerToolResult(type: String): Boolean =
    type.endsWith("_result") && type != "function_result"

internal fun JsonObject.interactionsServerToolResultStatus(): ServerToolStatus =
    if (this["is_error"]?.jsonPrimitiveOrNull?.booleanOrNull == true) {
        ServerToolStatus.FAILED
    } else {
        ServerToolStatus.COMPLETED
    }

internal fun JsonElement?.toInteractionsAnnotations(): List<UIMessageAnnotation> =
    this?.jsonArrayOrNull.orEmpty().mapNotNull { element ->
        val annotation = element.jsonObjectOrNull ?: return@mapNotNull null
        if (annotation.stringOrNull("type") != "url_citation") return@mapNotNull null
        val url = annotation.stringOrNull("url") ?: return@mapNotNull null
        UIMessageAnnotation.UrlCitation(title = annotation.stringOrNull("title") ?: url, url = url)
    }

internal fun parseInteractionsUsage(usage: JsonObject?): TokenUsage? {
    if (usage == null) return null
    fun count(key: String) = usage[key]?.jsonPrimitiveOrNull?.intOrNull ?: 0
    return TokenUsage(
        promptTokens = count("total_input_tokens"),
        // total_output_tokens 不包含思考 token
        completionTokens = count("total_output_tokens") + count("total_thought_tokens"),
        totalTokens = count("total_tokens"),
        cachedTokens = count("total_cached_tokens"),
    )
}
