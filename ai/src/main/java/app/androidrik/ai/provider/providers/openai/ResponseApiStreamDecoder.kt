package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.stream.DecodeResult
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.provider.stream.StreamChunkDecoder
import app.androidrik.ai.ui.OpenAIReasoningMetadata
import app.androidrik.ai.ui.ReasoningType
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.toMetadata
import app.androidrik.ai.util.json
import app.androidrik.ai.util.parseErrorDetail
import app.androidrik.common.http.jsonObjectOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI Responses API 流式解码器（移植自上游 #1648）。
 *
 * 负责 `response.*` 事件到 [StreamChunk] 的映射：文本/推理（含 summary 与正文两种）、
 * function_call、image_generation_call、服务端工具、用量与终态。`encrypted_content`
 * 通过 [OpenAIReasoningMetadata] 挂在推理块上，供多轮流式回传。
 */
internal class ResponseApiStreamDecoder : StreamChunkDecoder {
    private val state = ResponseStreamState()

    override fun accept(event: SseEvent): DecodeResult {
        if (state.finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(state.finish(), completed = true)

        val parsed = json.parseToJsonElement(event.data).jsonObject
        // SSE 的 event 名与 payload 的 type 双写，且部分中转只发 `event:`：
        // payload 缺少 type 时用 event 名兜底（对齐本仓既有归一逻辑）
        val payloadType = parsed["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val eventName = event.event?.takeIf { it.isNotBlank() }
        val payload = if (payloadType == null && eventName != null) {
            JsonObject(parsed + ("type" to JsonPrimitive(eventName)))
        } else {
            parsed
        }
        val eventType = payload["type"]?.jsonPrimitive?.contentOrNull
        val chunks = parseEvent(payload)
        val completed = eventType == "response.completed" || eventType == "response.incomplete" ||
            event.event == "response.completed" || event.event == "response.incomplete"
        return DecodeResult(chunks, completed)
    }

    override fun onClosed(): List<StreamChunk> = state.finish()

    private fun parseEvent(payload: JsonObject): List<StreamChunk> {
        val chunkType = payload["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
        val itemId = payload["item_id"]?.jsonPrimitive?.contentOrNull
        val contentIndex = payload["content_index"]?.jsonPrimitive?.intOrNull ?: 0
        val summaryIndex = payload["summary_index"]?.jsonPrimitive?.intOrNull ?: contentIndex
        val textId = itemId?.let { "$it:text:$contentIndex" }
        val summaryReasoningId = itemId?.let { "$it:reasoning:summary:$summaryIndex" }
        val contentReasoningId = itemId?.let { "$it:reasoning:content:$contentIndex" }

        return when (chunkType) {
            "response.output_text.delta" -> state.textDelta(
                textId ?: error("item_id not found"),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
            )

            "response.reasoning_summary_text.delta" -> state.reasoningDelta(
                summaryReasoningId ?: error("item_id not found"),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                state.reasoningMetadata[itemId],
                ReasoningType.SUMMARY_TEXT,
            )

            "response.reasoning_text.delta" -> state.reasoningDelta(
                contentReasoningId ?: error("item_id not found"),
                payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                state.reasoningMetadata[itemId],
                ReasoningType.REASONING_TEXT,
            )

            "response.content_part.added" -> {
                val part = payload["part"]?.jsonObject ?: return emptyList()
                if (part["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                    state.startText(textId ?: error("item_id not found"))
                } else emptyList()
            }

            "response.content_part.done", "response.output_text.done" ->
                state.endText(textId ?: error("item_id not found"))

            "response.reasoning_summary_part.added" -> state.startReasoning(
                summaryReasoningId ?: error("item_id not found"),
                state.reasoningMetadata[itemId],
                ReasoningType.SUMMARY_TEXT,
            )

            "response.reasoning_summary_part.done",
            "response.reasoning_summary_text.done",
            "response.reasoning_text.done" -> emptyList()

            "response.output_item.added" -> {
                val item = payload["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                // 服务端已执行函数工具的结果（#2181）：必须先于 id 解析——该项只保证有 call_id，
                // item.id 可能缺失（旧解析正是因为强制要求 id 而崩溃）
                if (type == "function_call_output") {
                    return functionCallOutputChunks(item)
                }
                // item.id 规范上可缺（function_call 只有 call_id 必填），故回退到 call_id
                val id = item["id"]?.jsonPrimitive?.contentOrNull
                    ?: item["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: error("chunk id not found")
                val canonicalId = state.toolCallIdResolver.registerItem(
                    itemId = id,
                    callId = item["call_id"]?.jsonPrimitive?.contentOrNull,
                    outputIndex = payload["output_index"]?.jsonPrimitive?.intOrNull,
                )
                when (type) {
                    "function_call" -> state.startTool(
                        id = canonicalId,
                        name = item["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        initialInput = item["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                    )

                    "image_generation_call" -> state.startImage(id)

                    "reasoning" -> {
                        state.reasoningMetadata[id] = OpenAIReasoningMetadata(
                            reasoningId = id,
                            encryptedContent = item["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                        ).toMetadata()
                        emptyList()
                    }

                    else -> if (isOpenAIServerToolCall(type)) {
                        state.startServerTool(item.toOpenAIServerTool())
                    } else emptyList()
                }
            }

            "response.output_item.done" -> {
                val item = payload["item"]?.jsonObject ?: error("chunk item not found")
                val type = item["type"]?.jsonPrimitive?.content ?: error("chunk type not found")
                if (type == "function_call_output") {
                    return functionCallOutputChunks(item)
                }
                val id = item["id"]?.jsonPrimitive?.contentOrNull
                    ?: item["call_id"]?.jsonPrimitive?.contentOrNull
                    ?: error("chunk id not found")
                val canonicalId = state.toolCallIdResolver.registerItem(
                    itemId = id,
                    callId = item["call_id"]?.jsonPrimitive?.contentOrNull,
                    outputIndex = payload["output_index"]?.jsonPrimitive?.intOrNull,
                )
                when (type) {
                    "reasoning" -> {
                        val metadata = OpenAIReasoningMetadata(
                            reasoningId = id,
                            encryptedContent = item["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                        ).toMetadata()
                        state.reasoningMetadata[id] = metadata
                        state.endReasoningItem(id, metadata)
                    }

                    "image_generation_call" -> buildList {
                        addAll(state.startImage(id))
                        item["result"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                            add(StreamChunk.ImageSnapshot(id, it))
                        }
                        addAll(state.endImage(id))
                    }

                    "function_call" -> state.endTool(canonicalId)

                    else -> if (isOpenAIServerToolCall(type)) {
                        val tool = item.toOpenAIServerTool()
                        state.endServerTool(
                            id = tool.toolCallId,
                            input = tool.input,
                            output = tool.output,
                            status = tool.status.takeUnless { it == ServerToolStatus.IN_PROGRESS }
                                ?: ServerToolStatus.COMPLETED,
                            metadata = tool.metadata,
                        )
                    } else emptyList()
                }
            }

            "response.function_call_arguments.delta" -> {
                state.toolDelta(
                    state.toolCallIdResolver.resolveArgumentsId(payload),
                    payload["delta"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            }

            "response.function_call_arguments.done" -> {
                val toolCallId = state.toolCallIdResolver.resolveArgumentsId(payload)
                buildList {
                    if (toolCallId !in state.toolIdsWithInput) {
                        addAll(
                            state.toolDelta(
                                toolCallId,
                                payload["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                            )
                        )
                    }
                    addAll(state.endTool(toolCallId))
                }
            }

            "response.image_generation_call.partial_image" -> {
                val requiredItemId = itemId ?: error("item_id not found")
                buildList {
                    addAll(state.startImage(requiredItemId))
                    add(
                        StreamChunk.ImageSnapshot(
                            requiredItemId,
                            payload["partial_image_b64"]?.jsonPrimitive?.contentOrNull ?: "",
                        )
                    )
                }
            }

            "response.completed", "response.incomplete" -> parseTerminalResponse(payload)
            "response.failed" -> failWithResponseError(payload)
            "error" -> failWithError(payload)
            else -> parseServerToolStatusEvent(chunkType, itemId)
        }
    }

    /**
     * 服务端已执行函数工具的结果（本仓 #2181）→ [StreamChunk.ToolCallOutput]。
     *
     * Responses API 会在 `output_item.added` 与 `output_item.done` 各带一份**完整**结果，
     * 这里两个事件都发；消费端 `Tool.merge` 会幂等去重（同内容只保留一份）。
     */
    private fun functionCallOutputChunks(item: JsonObject): List<StreamChunk> {
        val callId = item["call_id"]?.jsonPrimitive?.contentOrNull
            ?: item["id"]?.jsonPrimitive?.contentOrNull
            ?: return emptyList()
        // 空串/null 输出也要发：工具「已执行」的信号本身就是这个事件。
        // 丢掉它会让 output 为空 ⇒ isExecuted=false ⇒ 客户端重复执行（#2181 的原始缺陷）。
        return listOf(StreamChunk.ToolCallOutput(callId, item.functionCallOutputText()))
    }

    private fun parseTerminalResponse(payload: JsonObject): List<StreamChunk> {
        val response = payload["response"]?.jsonObject
        val status = response?.get("status")?.jsonPrimitive?.contentOrNull
            ?: payload["type"]?.jsonPrimitive?.contentOrNull?.removePrefix("response.")
        val incompleteReason = response?.get("incomplete_details")?.jsonObjectOrNull
            ?.get("reason")?.jsonPrimitive?.contentOrNull
        val finishReason = if (status == "incomplete" && incompleteReason != null) {
            "$status:$incompleteReason"
        } else {
            status
        }

        return buildList {
            parseUsage(response?.get("usage") as? JsonObject)?.let { add(StreamChunk.Usage(it)) }
            addAll(
                state.finish(
                    finishReason = finishReason,
                    responseId = response?.get("id")?.jsonPrimitive?.contentOrNull,
                    model = response?.get("model")?.jsonPrimitive?.contentOrNull,
                )
            )
        }
    }

    private fun failWithResponseError(payload: JsonObject): Nothing {
        val response = payload["response"]?.jsonObject
        return failWithError(
            response?.get("error")?.takeUnless { it is JsonNull }
                ?: response
                ?: payload
        )
    }

    private fun failWithError(error: JsonElement): Nothing {
        state.abort()
        throw error.parseErrorDetail()
    }

    private fun parseServerToolStatusEvent(chunkType: String, itemId: String?): List<StreamChunk> {
        val event = chunkType.removePrefix("response.")
        val itemType = event.substringBeforeLast('.', missingDelimiterValue = "")
        val providerStatus = event.substringAfterLast('.', missingDelimiterValue = "")
        if (!isOpenAIServerToolCall(itemType) || itemId == null) return emptyList()

        return when (providerStatus) {
            "completed" -> state.endServerTool(itemId, status = ServerToolStatus.COMPLETED)
            "failed", "cancelled", "incomplete" ->
                state.endServerTool(itemId, status = ServerToolStatus.FAILED)

            else -> state.startServerTool(itemId, itemType.removeSuffix("_call"))
        }
    }

    private fun parseUsage(usage: JsonObject?): TokenUsage? {
        if (usage == null) return null
        return TokenUsage(
            promptTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = usage["input_tokens_details"]?.jsonObjectOrNull
                ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private class ResponseStreamState {
        /**
         * toolCallId 归一（复用 [ResponseToolCallIdResolver]）：`item.id` 与 `call_id` 是两个标识，
         * 回传下一轮必须用 `call_id`；兼容网关还会在 arguments 事件里只给 `call_id`/`output_index`。
         */
        val toolCallIdResolver = ResponseToolCallIdResolver()
        val toolIdsWithInput = mutableSetOf<String>()
        val reasoningMetadata = mutableMapOf<String, JsonObject>()
        private val openTextIds = linkedSetOf<String>()
        private val openReasoningIds = linkedSetOf<String>()
        private val openImageIds = linkedSetOf<String>()
        private val openToolIds = linkedSetOf<String>()
        private val openServerToolIds = linkedSetOf<String>()
        var finished = false
            private set

        fun abort() {
            finished = true
        }

        fun startText(id: String) = if (openTextIds.add(id)) listOf(StreamChunk.TextStart(id)) else emptyList()
        fun textDelta(id: String, text: String) = startText(id) + StreamChunk.TextDelta(id, text)
        fun endText(id: String) = if (openTextIds.remove(id)) listOf(StreamChunk.TextEnd(id)) else emptyList()
        fun startReasoning(id: String, metadata: JsonObject?, reasoningType: ReasoningType) =
            if (openReasoningIds.add(id)) {
                listOf(StreamChunk.ReasoningStart(id, metadata, reasoningType))
            } else {
                emptyList()
            }

        fun reasoningDelta(
            id: String,
            text: String,
            metadata: JsonObject?,
            reasoningType: ReasoningType,
        ) = startReasoning(id, metadata, reasoningType) +
            StreamChunk.ReasoningDelta(id, text, metadata, reasoningType)

        fun endReasoning(id: String, metadata: JsonObject?) =
            if (openReasoningIds.remove(id)) listOf(StreamChunk.ReasoningEnd(id, metadata)) else emptyList()

        fun endReasoningItem(itemId: String, metadata: JsonObject?): List<StreamChunk> {
            reasoningMetadata.remove(itemId)
            val ids = openReasoningIds.filter { it.startsWith("$itemId:reasoning:") }
            if (ids.isNotEmpty()) return ids.flatMap { endReasoning(it, metadata) }

            // encrypted_content 可以在 summary 为空时单独出现，仍需物化 metadata-only reasoning part。
            val id = "$itemId:reasoning:metadata:0"
            return startReasoning(id, metadata, ReasoningType.REASONING_TEXT) + endReasoning(id, metadata)
        }

        fun startImage(id: String) = if (openImageIds.add(id)) listOf(StreamChunk.ImageStart(id)) else emptyList()
        fun endImage(id: String) = if (openImageIds.remove(id)) listOf(StreamChunk.ImageEnd(id)) else emptyList()
        fun startTool(id: String, name: String, initialInput: String): List<StreamChunk> = buildList {
            if (openToolIds.add(id)) add(StreamChunk.ToolCallStart(id, name))
            if (initialInput.isNotEmpty()) addAll(toolDelta(id, initialInput))
        }

        fun toolDelta(id: String, input: String): List<StreamChunk> = buildList {
            if (openToolIds.add(id)) add(StreamChunk.ToolCallStart(id))
            if (input.isNotEmpty()) {
                toolIdsWithInput += id
                add(StreamChunk.ToolCallDelta(id, inputDelta = input))
            }
        }

        fun endTool(id: String) = if (openToolIds.remove(id)) {
            toolIdsWithInput.remove(id)
            listOf(StreamChunk.ToolCallEnd(id))
        } else emptyList()

        fun startServerTool(tool: app.androidrik.ai.ui.UIMessagePart.ServerTool): List<StreamChunk> =
            startServerTool(tool.toolCallId, tool.toolName, tool.input, tool.metadata)

        fun startServerTool(
            id: String,
            toolName: String,
            input: JsonElement? = null,
            metadata: JsonObject? = null,
        ): List<StreamChunk> = if (openServerToolIds.add(id)) {
            listOf(StreamChunk.ServerToolStart(id, toolName, input, metadata))
        } else if (input != null || metadata != null) {
            listOf(StreamChunk.ServerToolStart(id, toolName, input, metadata))
        } else emptyList()

        fun endServerTool(
            id: String,
            input: JsonElement? = null,
            output: JsonElement? = null,
            status: ServerToolStatus,
            metadata: JsonObject? = null,
        ): List<StreamChunk> {
            openServerToolIds.remove(id)
            return listOf(StreamChunk.ServerToolEnd(id, input, output, status, metadata))
        }

        fun finish(
            finishReason: String? = null,
            responseId: String? = null,
            model: String? = null,
        ): List<StreamChunk> {
            if (finished) return emptyList()
            finished = true
            return buildList {
                openTextIds.toList().forEach { addAll(endText(it)) }
                reasoningMetadata.toMap().forEach { (itemId, metadata) ->
                    addAll(endReasoningItem(itemId, metadata))
                }
                openReasoningIds.toList().forEach { addAll(endReasoning(it, null)) }
                openImageIds.toList().forEach { addAll(endImage(it)) }
                openToolIds.toList().forEach { addAll(endTool(it)) }
                openServerToolIds.clear()
                add(StreamChunk.Finish(finishReason, responseId, model))
            }
        }
    }
}
