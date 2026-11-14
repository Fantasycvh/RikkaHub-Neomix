package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.stream.DecodeResult
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.provider.stream.StreamChunkDecoder
import app.androidrik.ai.ui.OpenRouterReasoningMetadata
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toMetadata
import app.androidrik.ai.util.json
import app.androidrik.ai.util.parseErrorDetail
import app.androidrik.common.http.jsonArrayOrNull
import app.androidrik.common.http.jsonObjectOrNull
import app.androidrik.common.http.jsonPrimitiveOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * OpenAI Chat Completions 流式解码器（移植自上游 #1648）。
 *
 * 把 `data: {...}` 的增量 JSON 折叠成跨 provider 的 [StreamChunk] 事件：文本、推理、
 * 工具调用、图片、注解与用量。中间态复用 [UIMessage]，再用 [ChatCompletionsStreamState]
 * 把「part 增量」翻译成「事件增量」。
 */
internal class ChatCompletionsStreamDecoder : StreamChunkDecoder {
    private val streamState = ChatCompletionsStreamState()
    private val toolIdsByIndex = mutableMapOf<Int, String>()
    private val reasoningDetailsByIndex = linkedMapOf<Int, JsonObject>()
    private var responseId: String? = null
    private var responseModel: String? = null
    private var finishReason: String? = null
    private var finished = false

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val chunks = buildList {
            event.data.trim().split("\n").filter(String::isNotBlank).forEach { line ->
                val payload = json.parseToJsonElement(line).jsonObject
                // "error": null 是部分网关的「无错误」表示，kotlinx 里是 JsonNull 引用，不能当错误抛
                payload["error"]?.takeIf { it != JsonNull }?.let { throw it.parseErrorDetail() }
                responseId = payload["id"]?.jsonPrimitive?.contentOrNull ?: responseId
                responseModel = payload["model"]?.jsonPrimitive?.contentOrNull ?: responseModel

                payload["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.let { choice ->
                    (choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject)?.let { message ->
                        val messageWithoutTools = JsonObject(message.filterKeys { it != "tool_calls" })
                        addAll(streamState.append(parseMessage(messageWithoutTools), responseId))

                        message["tool_calls"]?.jsonArray?.forEachIndexed { fallbackIndex, element ->
                            val toolCall = element.jsonObject
                            val index = toolCall["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
                            val toolId = toolCall["id"]?.jsonPrimitive?.contentOrNull
                                ?.also { toolIdsByIndex[index] = it }
                                ?: toolIdsByIndex.getOrPut(index) {
                                    "${responseId ?: "response"}:tool-$index"
                                }
                            val function = toolCall["function"]?.jsonObject
                            addAll(
                                streamState.append(
                                    UIMessage(
                                        role = MessageRole.ASSISTANT,
                                        parts = listOf(
                                            UIMessagePart.Tool(
                                                toolCallId = toolId,
                                                toolName = function?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                                                input = function?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "",
                                                output = emptyList(),
                                            )
                                        ),
                                    ),
                                    responseId,
                                )
                            )
                        }
                    }
                    choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
                }
                parseUsage(payload["usage"] as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
            }
        }
        return DecodeResult(chunks)
    }

    override fun onClosed(): List<StreamChunk> = finish()

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return streamState.finish(finishReason, responseId, responseModel)
    }

    internal fun parseMessage(payload: JsonObject): UIMessage {
        val role = MessageRole.valueOf(
            payload["role"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "ASSISTANT"
        )
        // Mistral 开启推理时 content 是块列表；思考收尾与回答首段可能在同一帧，必须遍历所有块
        val parsedContent = parseChatCompletionsContent(payload["content"])
        val content = parsedContent.text
        val reasoning = payload["reasoning_content"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: payload["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull
            ?: parsedContent.reasoning
        val reasoningMetadata = accumulateReasoningDetails(payload["reasoning_details"]?.jsonArrayOrNull)
        val images = payload["images"] as? JsonArray ?: JsonArray(emptyList())
        val serverToolCalls = payload["server_tool_calls"] as? JsonArray ?: JsonArray(emptyList())

        return UIMessage(
            role = role,
            parts = buildList {
                if (!reasoning.isNullOrEmpty() || reasoningMetadata != null) {
                    add(
                        UIMessagePart.Reasoning(
                            reasoning = reasoning.orEmpty(),
                            createdAt = Clock.System.now(),
                            finishedAt = null,
                            metadata = reasoningMetadata,
                        )
                    )
                }
                // DeepSeek 内置工具块 → ServerTool part（一次性整块下发，配料 01 §8 W11）；
                // 原始 JSON 进 metadata，供下一轮原样回传（含 kind/signature）
                addAll(parseDeepSeekServerToolCalls(serverToolCalls))
                if (content.isNotEmpty()) add(UIMessagePart.Text(content))
                images.forEach { image ->
                    val imageObject = image.jsonObjectOrNull ?: return@forEach
                    if (imageObject["type"]?.jsonPrimitive?.contentOrNull != "image_url") return@forEach
                    val url = imageObject["image_url"]?.jsonObjectOrNull
                        ?.get("url")?.jsonPrimitive?.contentOrNull ?: return@forEach
                    // 与 `ChatCompletionsAPI.parseMessage` 的非流式路径一致：`data:` / `http(s)` 原样存，
                    // 其余形态跳过而不是抛——这里在流式解析链上，畸形输入不该杀掉整轮。
                    // 旧实现 `require(data:image)` 会让「中转回一张 https 图」变成整轮生成中断；
                    // `substringAfter("data:image/png;base64,")` 会把 jpeg/webp 的 data URI 切成裸 base64
                    // （transformer 与 encodeBase64 都不认）。两个历史问题同因，见 2026-10-16 非流式修复。
                    if (url.startsWith("data:") || url.startsWith("http")) {
                        add(UIMessagePart.Image(url))
                    }
                }
            },
            annotations = parseAnnotations(payload["annotations"]?.jsonArrayOrNull ?: JsonArray(emptyList())),
        )
    }

    private fun accumulateReasoningDetails(details: JsonArray?): JsonObject? {
        if (details == null) return null
        details.forEachIndexed { fallbackIndex, element ->
            val incoming = element.jsonObject
            val index = incoming["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
            reasoningDetailsByIndex[index] = mergeReasoningDetail(reasoningDetailsByIndex[index], incoming)
        }
        return OpenRouterReasoningMetadata(
            reasoningDetails = JsonArray(reasoningDetailsByIndex.toSortedMap().values.toList()),
        ).toMetadata()
    }

    private fun mergeReasoningDetail(existing: JsonObject?, incoming: JsonObject): JsonObject = buildJsonObject {
        existing?.forEach { (key, value) -> put(key, value) }
        incoming.forEach { (key, value) ->
            val delta = value.jsonPrimitiveOrNull?.contentOrNull
            if (existing != null && key in REASONING_DETAIL_DELTA_FIELDS && delta != null) {
                val previous = existing[key]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                put(key, JsonPrimitive(previous + delta))
            } else {
                put(key, value)
            }
        }
    }

    // 与 ChatCompletionsAPI.parseAnnotations 同一条定向放宽：未知注解类型跳过 + 日志，
    // 不再整轮 error（DeepSeek annotations 形态未真机确认，一次漂移不该杀掉整轮——配料 03 §5.2.4）
    private fun parseAnnotations(array: JsonArray): List<UIMessageAnnotation> = array.mapNotNull { element ->
        val annotation = element.jsonObjectOrNull
        val type = annotation?.get("type")?.jsonPrimitive?.contentOrNull ?: error("type is null")
        when (type) {
            "url_citation" -> UIMessageAnnotation.UrlCitation(
                title = annotation["url_citation"]?.jsonObject
                    ?.get("title")?.jsonPrimitive?.contentOrNull ?: "",
                url = annotation["url_citation"]?.jsonObject
                    ?.get("url")?.jsonPrimitive?.contentOrNull ?: "",
            )
            else -> {
                android.util.Log.w("ChatCompletionsStreamDecoder", "skipped unknown annotation type: $type")
                null
            }
        }
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        // DeepSeek usage.server_tool_use：内置工具**计费次数**（实测见配料 04 §9）
        val serverToolUse = usage["server_tool_use"]?.jsonObjectOrNull
        return TokenUsage(
            promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            toolSearch = serverToolUse?.get("tool_search")?.jsonPrimitive?.intOrNull ?: 0,
            toolFetch = serverToolUse?.get("tool_fetch")?.jsonPrimitive?.intOrNull ?: 0,
            toolImageSearch = serverToolUse?.get("tool_image_search")?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = usage["prompt_tokens_details"]?.jsonObjectOrNull
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: usage["cached_tokens"]?.jsonPrimitive?.intOrNull
                ?: usage["prompt_cache_hit_tokens"]?.jsonPrimitive?.intOrNull
                ?: 0,
        )
    }

    private class ChatCompletionsStreamState {
        private var sequence = 0
        private var textId: String? = null
        private var reasoningId: String? = null
        private var imageId: String? = null
        private val openToolIds = linkedSetOf<String>()
        private var lastToolId: String? = null

        fun append(message: UIMessage, sourceId: String?): List<StreamChunk> = buildList {
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> if (part.text.isNotEmpty()) {
                        addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
                        val id = textId ?: nextId(sourceId, "text").also {
                            textId = it; add(StreamChunk.TextStart(it))
                        }
                        add(StreamChunk.TextDelta(id, part.text))
                    }

                    is UIMessagePart.Reasoning -> if (part.reasoning.isNotEmpty() || part.metadata != null) {
                        addAll(closeText()); addAll(closeImage()); addAll(closeTools())
                        val id = reasoningId ?: nextId(sourceId, "reasoning").also {
                            reasoningId = it
                            add(StreamChunk.ReasoningStart(it, part.metadata, part.reasoningType))
                        }
                        add(StreamChunk.ReasoningDelta(id, part.reasoning, part.metadata, part.reasoningType))
                    }

                    is UIMessagePart.Tool -> {
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeImage())
                        val id = part.toolCallId.ifBlank { lastToolId ?: nextId(sourceId, "tool") }
                        var nameDelta = part.toolName
                        if (openToolIds.add(id)) {
                            nameDelta = ""
                            add(StreamChunk.ToolCallStart(id, part.toolName, part.metadata))
                        }
                        lastToolId = id
                        if (nameDelta.isNotEmpty() || part.input.isNotEmpty()) {
                            add(StreamChunk.ToolCallDelta(id, nameDelta, part.input, part.metadata))
                        }
                    }

                    is UIMessagePart.Image -> {
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeTools())
                        val id = imageId ?: nextId(sourceId, "image").also {
                            imageId = it; add(StreamChunk.ImageStart(it, metadata = part.metadata))
                        }
                        add(StreamChunk.ImageDelta(id, part.url.substringAfter(";base64,", part.url), part.metadata))
                    }

                    is UIMessagePart.ServerTool -> {
                        // DeepSeek server_tool_calls 是整块一次性出现（官方：不会分段下发），
                        // Start/End 同帧即可，思考链 UI 折叠块直接可用（配料 03 §5.2.2）
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
                        val id = part.toolCallId.ifBlank { nextId(sourceId, "server_tool") }
                        add(StreamChunk.ServerToolStart(id, part.toolName, part.input, part.metadata))
                        add(StreamChunk.ServerToolEnd(id, part.input, part.output, part.status, part.metadata))
                    }

                    else -> Unit
                }
            }
            if (message.annotations.isNotEmpty()) add(StreamChunk.Annotations(message.annotations))
        }

        fun finish(reason: String?, responseId: String?, model: String?): List<StreamChunk> = buildList {
            addAll(closeText()); addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
            add(StreamChunk.Finish(reason, responseId, model))
        }

        private fun closeText() = textId?.let { textId = null; listOf(StreamChunk.TextEnd(it)) }.orEmpty()
        private fun closeReasoning() = reasoningId?.let {
            reasoningId = null; listOf(StreamChunk.ReasoningEnd(it))
        }.orEmpty()

        private fun closeImage() = imageId?.let { imageId = null; listOf(StreamChunk.ImageEnd(it)) }.orEmpty()
        private fun closeTools() = openToolIds.toList().map { StreamChunk.ToolCallEnd(it) }.also {
            openToolIds.clear(); lastToolId = null
        }

        private fun nextId(sourceId: String?, kind: String): String =
            "${sourceId?.takeIf(String::isNotBlank)?.let { "$it:" }.orEmpty()}$kind-${++sequence}"
    }

    private companion object {
        val REASONING_DETAIL_DELTA_FIELDS = setOf("text", "summary", "data", "signature")
    }
}
