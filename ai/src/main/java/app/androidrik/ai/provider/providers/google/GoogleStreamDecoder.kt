package app.androidrik.ai.provider.providers.google

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.stream.DecodeResult
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.provider.stream.StreamChunkDecoder
import app.androidrik.ai.ui.GoogleThoughtMetadata
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toMetadata
import app.androidrik.ai.util.json
import app.androidrik.common.http.jsonPrimitiveOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock

/**
 * Google Gemini `streamGenerateContent` 解码器（移植自上游 #1648）。
 *
 * Gemini 每个 chunk 携带**完整/增量混合**的 candidate.parts，本解码器把 part 序列折叠为
 * 通用 [StreamChunk]：文本、thought（推理）、functionCall（工具）、inlineData（图片）与 grounding 注解。
 */
internal class GoogleStreamDecoder(
    private val responseId: String,
    private val model: String,
) : StreamChunkDecoder {
    private val streamState = GoogleStreamState()
    private var finishReason: String? = null
    private var finished = false
    private var toolSequence = 0

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val jsonData = json.parseToJsonElement(event.data).jsonObject
        val blockedReason = jsonData["promptFeedback"]?.jsonObject
            ?.get("blockReason")?.jsonPrimitiveOrNull?.contentOrNull
        if (blockedReason != null) error("Prompt feedback: $blockedReason")

        val chunks = buildList {
            parseUsage(jsonData["usageMetadata"] as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
            val candidate = jsonData["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@buildList
            candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
            val content = candidate["content"]?.jsonObject ?: return@buildList
            val message = parseMessage(content, candidate["groundingMetadata"] as? JsonObject)
            addAll(streamState.append(message, responseId))
        }
        return DecodeResult(chunks)
    }

    override fun onClosed(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        val chunks = streamState.finish(finishReason, responseId, model)
        // Gemini 没有独立终止事件：**带 finishReason 的末块**才是显式结束。
        // 连接关闭时没有 finishReason = 裸断流（网关提前切连接），不能补 Finish，
        // 否则下游会把截断当正常收尾（见 GenerationHandler 的 bare EOF 判定）。
        return if (finishReason == null) chunks.filterNot { it is StreamChunk.Finish } else chunks
    }

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return streamState.finish(finishReason, responseId, model)
    }

    private fun parseMessage(content: JsonObject, groundingMetadata: JsonObject?): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = content["parts"]?.jsonArray?.map { parsePart(it.jsonObject) }.orEmpty(),
        annotations = parseAnnotations(groundingMetadata),
    )

    private fun parsePart(part: JsonObject): UIMessagePart = when {
        part.containsKey("text") -> {
            val text = part["text"]?.jsonPrimitive?.contentOrNull ?: ""
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                UIMessagePart.Reasoning(text, Clock.System.now(), null)
            } else {
                UIMessagePart.Text(text)
            }
        }

        part.containsKey("functionCall") -> {
            val functionCall = part["functionCall"]!!.jsonObject
            UIMessagePart.Tool(
                toolCallId = "$responseId:tool-${++toolSequence}",
                toolName = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: "",
                input = functionCall["args"]?.let { json.encodeToString(it) } ?: "",
                output = emptyList(),
                metadata = GoogleThoughtMetadata(
                    thoughtSignature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
                ).toMetadata(),
            )
        }

        part.containsKey("inlineData") -> {
            val inlineData = part["inlineData"]!!.jsonObject
            val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
            require(mimeType.startsWith("image/")) { "Only image mime type is supported" }
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                UIMessagePart.Reasoning("[Draft Image]\n", Clock.System.now(), null)
            } else {
                UIMessagePart.Image(
                    url = "data:$mimeType;base64,${inlineData["data"]?.jsonPrimitive?.contentOrNull ?: ""}",
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
                    ).toMetadata(),
                )
            }
        }

        else -> error("unknown message part type: $part")
    }

    private fun parseAnnotations(metadata: JsonObject?): List<UIMessageAnnotation> =
        metadata?.get("groundingChunks")?.jsonArray.orEmpty().mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val url = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(title, url)
        }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        val promptTokens = usage["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = usage["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = usage["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidateTokens = usage["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = usage["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidateTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens,
        )
    }

    private class GoogleStreamState {
        private var sequence = 0
        private var textId: String? = null
        private var reasoningId: String? = null
        private var imageId: String? = null
        private val openToolIds = linkedSetOf<String>()

        fun append(message: UIMessage, responseId: String): List<StreamChunk> = buildList {
            val imageCount = message.parts.count { it is UIMessagePart.Image }
            var emittedImages = 0
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> if (part.text.isNotEmpty()) {
                        addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
                        val id = textId ?: nextId(responseId, "text").also {
                            textId = it; add(StreamChunk.TextStart(it))
                        }
                        add(StreamChunk.TextDelta(id, part.text))
                    }

                    is UIMessagePart.Reasoning -> if (part.reasoning.isNotEmpty() || part.metadata != null) {
                        addAll(closeText()); addAll(closeImage()); addAll(closeTools())
                        val id = reasoningId ?: nextId(responseId, "reasoning").also {
                            reasoningId = it
                            add(StreamChunk.ReasoningStart(it, part.metadata, part.reasoningType))
                        }
                        add(StreamChunk.ReasoningDelta(id, part.reasoning, part.metadata, part.reasoningType))
                    }

                    is UIMessagePart.Tool -> {
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeImage())
                        val id = part.toolCallId.ifBlank { nextId(responseId, "tool") }
                        if (openToolIds.add(id)) add(StreamChunk.ToolCallStart(id, part.toolName, part.metadata))
                        if (part.input.isNotEmpty()) {
                            add(StreamChunk.ToolCallDelta(id, inputDelta = part.input, metadata = part.metadata))
                        }
                    }

                    is UIMessagePart.Image -> {
                        addAll(closeText()); addAll(closeReasoning()); addAll(closeTools())
                        if (imageCount > 1 && emittedImages > 0) addAll(closeImage())
                        val id = imageId ?: nextId(responseId, "image").also {
                            imageId = it
                            add(
                                StreamChunk.ImageStart(
                                    id = it,
                                    mimeType = part.url.substringAfter("data:").substringBefore(";base64,"),
                                    metadata = part.metadata,
                                )
                            )
                        }
                        add(StreamChunk.ImageDelta(id, part.url.substringAfter(";base64,", part.url), part.metadata))
                        emittedImages++
                    }

                    else -> Unit
                }
            }
            if (message.annotations.isNotEmpty()) add(StreamChunk.Annotations(message.annotations))
        }

        fun finish(reason: String?, responseId: String, model: String): List<StreamChunk> = buildList {
            addAll(closeText()); addAll(closeReasoning()); addAll(closeImage()); addAll(closeTools())
            add(StreamChunk.Finish(reason, responseId, model))
        }

        private fun closeText() = textId?.let { textId = null; listOf(StreamChunk.TextEnd(it)) }.orEmpty()
        private fun closeReasoning() = reasoningId?.let {
            reasoningId = null; listOf(StreamChunk.ReasoningEnd(it))
        }.orEmpty()

        private fun closeImage() = imageId?.let { imageId = null; listOf(StreamChunk.ImageEnd(it)) }.orEmpty()
        private fun closeTools() = openToolIds.toList().map { StreamChunk.ToolCallEnd(it) }.also {
            openToolIds.clear()
        }

        private fun nextId(responseId: String, kind: String): String =
            "$responseId:$kind-${++sequence}"
    }
}
