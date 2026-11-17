package app.androidrik.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.stream.DecodeResult
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.provider.stream.StreamChunkDecoder
import app.androidrik.ai.ui.GoogleThoughtMetadata
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.metadataAs
import app.androidrik.ai.ui.toMetadata
import app.androidrik.ai.util.json
import app.androidrik.common.http.jsonPrimitiveOrNull
import kotlinx.serialization.json.JsonArray
import kotlin.time.Clock

/** Translates Gemini streamGenerateContent events into the app's stream chunks. */
internal class GoogleStreamDecoder(
    private val responseId: String,
    private val model: String,
) : StreamChunkDecoder {
    private val streamState = GoogleStreamState()
    private var finishReason: String? = null
    private var finished = false
    private var toolSequence = 0
    private var partSequence = 0

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
        val chunks = finish()
        // Bare EOF without a provider finish reason is a truncation.
        return if (finishReason == null) chunks.filterNot { it is StreamChunk.Finish } else chunks
    }

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return streamState.finish(finishReason, responseId, model)
    }

    private fun parseMessage(content: JsonObject, groundingMetadata: JsonObject?): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parseParts(content["parts"]?.jsonArray),
        annotations = parseAnnotations(groundingMetadata),
    )

    private fun parseParts(parts: JsonArray?): List<UIMessagePart> = buildList {
        parts.orEmpty().forEach { raw ->
            val part = parsePart(raw.jsonObject, partSequence++) ?: return@forEach
            val signature = (part as? UIMessagePart.Text)?.takeIf { it.text.isEmpty() }
                ?.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature
            val previous = signature?.let { lastOrNull()?.withGoogleSignature(it) }
            if (previous != null) set(lastIndex, previous) else add(part)
        }
    }

    private fun parsePart(part: JsonObject, index: Int): UIMessagePart? = when {
        part.containsKey("text") -> {
            val text = part["text"]?.jsonPrimitive?.contentOrNull ?: ""
            val metadata = part.googleThoughtMetadata()
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                UIMessagePart.Reasoning(text, Clock.System.now(), null, metadata)
            } else {
                UIMessagePart.Text(text, metadata)
            }
        }
        part.containsKey("functionCall") -> {
            val functionCall = part["functionCall"]!!.jsonObject
            val wireId = functionCall.stringOrNull("id")
            UIMessagePart.Tool(
                toolCallId = wireId ?: "$responseId:tool-${++toolSequence}",
                toolName = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: "",
                input = functionCall["args"]?.let(json::encodeToString) ?: "",
                output = emptyList(),
                metadata = GoogleThoughtMetadata(
                    thoughtSignature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
                    googleFunctionCallId = wireId,
                ).toMetadata(),
            )
        }

        "toolCall" in part || "toolResponse" in part ->
            part.googleServerTool(index) { "$responseId:server-tool-${++toolSequence}" }

        part.containsKey("inlineData") -> {
            val inlineData = part["inlineData"]!!.jsonObject
            val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
            require(mimeType.startsWith("image/")) { "Only image mime type is supported" }
            if (part["thought"]?.jsonPrimitive?.booleanOrNull == true) {
                UIMessagePart.Reasoning("[Draft Image]\n", Clock.System.now(), null)
            } else {
                UIMessagePart.Image(
                    url = "data:$mimeType;base64,${inlineData["data"]?.jsonPrimitive?.contentOrNull ?: ""}",
                    metadata = part.googleThoughtMetadata(),
                )
            }
        }
        // Gemini 可能单独下发只含 thoughtSignature 的 part，按空文本处理，由 GoogleStreamState 挂到相邻 part 上
        part.containsKey("thoughtSignature") -> UIMessagePart.Text("", part.googleThoughtMetadata())

        // 未知 part 不中断整个流，直接跳过
        else -> null
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
        private var textHasMetadata = false
        private var reasoningId: String? = null
        private var reasoningHasMetadata = false
        private var imageId: String? = null
        private var imageSigned = false
        private val openToolIds = linkedSetOf<String>()
        private var lastToolSigned = false

        fun append(message: UIMessage, responseId: String): List<StreamChunk> = buildList {
            val imageCount = message.parts.count { it is UIMessagePart.Image }
            var emittedImages = 0
            message.parts.forEach { part ->
                when (part) {
                    is UIMessagePart.Text -> when {
                        part.text.isNotEmpty() -> {
                            addAll(closeReasoning())
                            addAll(closeImage())
                            addAll(closeTools())
                            if (textHasMetadata && part.metadata != null) addAll(closeText())
                            val id = textId ?: nextId(responseId, "text").also {
                                textId = it
                                add(StreamChunk.TextStart(it, part.metadata))
                            }
                            if (part.metadata != null) textHasMetadata = true
                            add(StreamChunk.TextDelta(id, part.text, part.metadata))
                        }

                        else -> part.metadata?.let { addAll(appendStandaloneMetadata(it, responseId)) }
                    }
                    is UIMessagePart.Reasoning -> if (part.reasoning.isNotEmpty() || part.metadata != null) {
                        addAll(closeText())
                        addAll(closeImage())
                        addAll(closeTools())
                        if (reasoningHasMetadata && part.metadata != null) addAll(closeReasoning())
                        val id = reasoningId ?: nextId(responseId, "reasoning").also {
                            reasoningId = it
                            add(StreamChunk.ReasoningStart(it, part.metadata))
                        }
                        if (part.metadata != null) reasoningHasMetadata = true
                        add(StreamChunk.ReasoningDelta(id, part.reasoning, part.metadata))
                    }
                    is UIMessagePart.Tool -> {
                        addAll(closeText())
                        addAll(closeReasoning())
                        addAll(closeImage())
                        val id = part.toolCallId.ifBlank { nextId(responseId, "tool") }
                        if (openToolIds.add(id)) {
                            lastToolSigned = false
                            add(StreamChunk.ToolCallStart(id, part.toolName, part.metadata))
                        }
                        if (part.metadata?.containsKey("thoughtSignature") == true) lastToolSigned = true
                        if (part.input.isNotEmpty()) {
                            add(StreamChunk.ToolCallDelta(id, inputDelta = part.input, metadata = part.metadata))
                        }
                    }
                    is UIMessagePart.ServerTool -> {
                        addAll(closeText())
                        addAll(closeReasoning())
                        addAll(closeImage())
                        addAll(
                            closeTools()
                        )
                        if (part.status == ServerToolStatus.IN_PROGRESS) {
                            add(
                                StreamChunk.ServerToolStart(
                                    id = part.toolCallId,
                                    toolName = part.toolName,
                                    input = part.input,
                                    metadata = part.metadata,
                                )
                            )
                        } else {
                            add(
                                StreamChunk.ServerToolEnd(
                                    id = part.toolCallId,
                                    input = part.input,
                                    output = part.output,
                                    status = part.status,
                                    metadata = part.metadata,
                                    toolName = part.toolName,
                                )
                            )
                        }
                    }
                    is UIMessagePart.Image -> {
                        addAll(closeText())
                        addAll(closeReasoning())
                        addAll(closeTools())
                        if (imageCount > 1 && emittedImages > 0) addAll(closeImage())
                        val id = imageId ?: nextId(responseId, "image").also {
                            imageId = it
                            add(StreamChunk.ImageStart(
                                id = it,
                                mimeType = part.url.substringAfter("data:").substringBefore(";base64,"),
                                metadata = part.metadata,
                            ))
                        }
                        if (part.metadata?.containsKey("thoughtSignature") == true) imageSigned = true
                        add(StreamChunk.ImageDelta(id, part.url.substringAfter(";base64,", part.url), part.metadata))
                        emittedImages++
                    }
                    else -> Unit
                }
            }
            if (message.annotations.isNotEmpty()) add(StreamChunk.Annotations(message.annotations))
        }

        fun finish(reason: String?, responseId: String, model: String): List<StreamChunk> = buildList {
            addAll(closeText())
            addAll(closeReasoning())
            addAll(closeImage())
            addAll(closeTools())
            add(StreamChunk.Finish(reason, responseId, model))
        }

        /**
         * 处理不带文本的签名 part（`{"thoughtSignature": ...}` 或 `{"text": "", "thoughtSignature": ...}`）：
         * 优先挂到当前还没有签名的文本/思考块上；否则单独开一个空文本块保存签名，保证回放时能原样带回。
         */
        private fun appendStandaloneMetadata(metadata: JsonObject, responseId: String): List<StreamChunk> = buildList {
            val currentText = textId
            val currentReasoning = reasoningId
            val currentImage = imageId
            val currentTool = openToolIds.lastOrNull()
            when {
                currentText != null && !textHasMetadata -> {
                    textHasMetadata = true
                    add(StreamChunk.TextDelta(currentText, "", metadata))
                }

                currentReasoning != null && !reasoningHasMetadata -> {
                    reasoningHasMetadata = true
                    add(StreamChunk.ReasoningDelta(currentReasoning, "", metadata))
                }

                currentImage != null && !imageSigned -> {
                    imageSigned = true
                    add(StreamChunk.ImageDelta(currentImage, "", metadata))
                }

                currentTool != null && !lastToolSigned -> {
                    lastToolSigned = true
                    add(StreamChunk.ToolCallDelta(currentTool, metadata = metadata))
                }

                else -> {
                    addAll(closeText())
                    addAll(closeReasoning())
                    addAll(closeImage())
                    addAll(closeTools())
                    val id = nextId(responseId, "text")
                    add(StreamChunk.TextStart(id, metadata))
                    add(StreamChunk.TextEnd(id))
                }
            }
        }

        private fun closeText() = textId?.let {
            textId = null
            textHasMetadata = false
            listOf(StreamChunk.TextEnd(it))
        }.orEmpty()
        private fun closeReasoning() = reasoningId?.let {
            reasoningId = null
            reasoningHasMetadata = false
            listOf(StreamChunk.ReasoningEnd(it))
        }.orEmpty()
        private fun closeImage() = imageId?.let {
            imageId = null
            imageSigned = false
            listOf(StreamChunk.ImageEnd(it))
        }.orEmpty()
        private fun closeTools() = openToolIds.toList().map { StreamChunk.ToolCallEnd(it) }.also {
            openToolIds.clear()
            lastToolSigned = false
        }
        private fun nextId(responseId: String, kind: String): String =
            "$responseId:$kind-${++sequence}"
    }
}
