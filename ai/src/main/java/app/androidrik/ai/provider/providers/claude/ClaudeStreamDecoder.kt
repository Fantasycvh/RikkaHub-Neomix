package app.androidrik.ai.provider.providers.claude

import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.providers.isClaudeServerToolError
import app.androidrik.ai.provider.providers.isClaudeServerToolResultType
import app.androidrik.ai.provider.providers.isClaudeServerToolUseType
import app.androidrik.ai.provider.stream.DecodeResult
import app.androidrik.ai.provider.stream.SseEvent
import app.androidrik.ai.provider.stream.StreamChunkDecoder
import app.androidrik.ai.ui.ClaudeReasoningMetadata
import app.androidrik.ai.ui.ServerToolMetadata
import app.androidrik.ai.ui.ServerToolProtocol
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.toMetadata
import app.androidrik.ai.util.json
import app.androidrik.ai.util.parseErrorDetail
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anthropic Messages 流式解码器（移植自上游 #1648）。
 *
 * 把 `content_block_*` / `message_delta` 事件映射为通用的 [StreamChunk]：
 * 文本、thinking（含 signature / redacted_thinking 元数据）、tool_use、服务端工具、用量与终态。
 */
internal class ClaudeStreamDecoder : StreamChunkDecoder {
    private val blocks = mutableMapOf<Int, ClaudeStreamBlock>()
    private var responseId: String? = null
    private var responseModel: String? = null
    private var finishReason: String? = null
    private var finished = false

    override fun accept(event: SseEvent): DecodeResult {
        if (finished) return DecodeResult(completed = true)
        if (event.data == "[DONE]") return DecodeResult(finish(), completed = true)

        val dataJson = json.parseToJsonElement(event.data).jsonObject
        // SSE 的 event 名与 JSON 的 type 双写，且都可能缺省/为空串：归一后再判定
        // （对齐本仓既有逻辑，部分中转只发一种）
        val eventType = event.event?.takeIf { it.isNotBlank() }
            ?: dataJson["type"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        if (eventType == "error") {
            throw (dataJson["error"] ?: dataJson).parseErrorDetail()
        }

        dataJson["message"]?.jsonObject?.let { message ->
            responseId = message["id"]?.jsonPrimitive?.contentOrNull ?: responseId
            responseModel = message["model"]?.jsonPrimitive?.contentOrNull ?: responseModel
        }
        dataJson["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let {
            finishReason = it
        }

        val chunks = buildList {
            parseTokenUsage(dataJson)?.let { add(StreamChunk.Usage(it)) }
            val index = dataJson["index"]?.jsonPrimitive?.intOrNull
            val contentBlock = dataJson["content_block"]?.jsonObject

            if (eventType == "content_block_start" && index != null && contentBlock != null) {
                val kind = contentBlock["type"]?.jsonPrimitive?.contentOrNull ?: ""
                val blockId = contentBlock["id"]?.jsonPrimitive?.contentOrNull
                    ?: contentBlock["tool_use_id"]?.jsonPrimitive?.contentOrNull
                    ?: "${responseId ?: event.id ?: "response"}:block-$index"
                val metadata = when (kind) {
                    "thinking" -> contentBlock["signature"]?.jsonPrimitive?.contentOrNull?.let {
                        ClaudeReasoningMetadata(signature = it).toMetadata()
                    }

                    // redacted_thinking 的思考内容不可见，只有加密数据：必须存进 metadata，
                    // 回传时才能还原成 {"type":"redacted_thinking","data":...}。
                    // 判据：本仓 763873803 只覆盖了非流式解析（parseMessage），流式此前丢弃 data
                    // ⇒ 该块回传成空 thinking 且无签名，Anthropic 拒绝整请求（上游 e1667163c 同点修复）。
                    "redacted_thinking" -> contentBlock["data"]?.jsonPrimitive?.contentOrNull?.let {
                        ClaudeReasoningMetadata(redactedData = it).toMetadata()
                    }

                    else -> null
                }
                blocks[index] = ClaudeStreamBlock(kind, blockId, metadata)
                when (kind) {
                    "text" -> add(StreamChunk.TextStart(blockId))
                    "thinking", "redacted_thinking" -> add(StreamChunk.ReasoningStart(blockId, metadata))
                    "tool_use" -> {
                        add(
                            StreamChunk.ToolCallStart(
                                id = blockId,
                                toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            )
                        )
                        val input = contentBlock["input"]?.jsonObject
                        if (input != null && input.isNotEmpty()) {
                            add(
                                StreamChunk.ToolCallDelta(
                                    id = blockId,
                                    inputDelta = json.encodeToString(input),
                                )
                            )
                        }
                    }

                    else -> if (kind.isClaudeServerToolUseType()) {
                        add(
                            StreamChunk.ServerToolStart(
                                id = blockId,
                                toolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: "",
                                input = contentBlock["input"],
                                metadata = ServerToolMetadata(
                                    protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                    call = contentBlock,
                                    callIndex = index,
                                ).toMetadata(),
                            )
                        )
                    } else if (kind.isClaudeServerToolResultType()) {
                        val output = contentBlock["content"]
                        add(
                            StreamChunk.ServerToolEnd(
                                id = blockId,
                                output = output,
                                status = if (output.isClaudeServerToolError()) {
                                    ServerToolStatus.FAILED
                                } else {
                                    ServerToolStatus.COMPLETED
                                },
                                metadata = ServerToolMetadata(
                                    protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                                    result = contentBlock,
                                    resultIndex = index,
                                ).toMetadata(),
                            )
                        )
                    }
                }
            }

            if (eventType == "content_block_delta" && index != null) {
                val delta = dataJson["delta"]?.jsonObject ?: JsonObject(emptyMap())
                val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull
                val block = blocks[index] ?: inferBlock(index, deltaType).also { inferred ->
                    // 懒创建：部分中转省略 content_block_start，旧解析容忍；不补 Start 事件的话
                    // 增量会挂到不存在的 part 上（下游会丢内容）
                    when (inferred.kind) {
                        "text" -> add(StreamChunk.TextStart(inferred.id))
                        "thinking", "redacted_thinking" ->
                            add(StreamChunk.ReasoningStart(inferred.id, inferred.metadata))

                        "tool_use" -> add(StreamChunk.ToolCallStart(inferred.id, ""))
                        else -> Unit
                    }
                }
                when (deltaType) {
                    "text_delta" -> add(
                        StreamChunk.TextDelta(
                            block.id,
                            delta["text"]?.jsonPrimitive?.contentOrNull ?: "",
                        )
                    )

                    "thinking_delta" -> add(
                        StreamChunk.ReasoningDelta(
                            block.id,
                            delta["thinking"]?.jsonPrimitive?.contentOrNull ?: "",
                            block.metadata,
                        )
                    )

                    "signature_delta" -> {
                        // 签名可能分多片到达：必须接在本块**已收到**的签名后面，而不是覆盖。
                        // 判据：Anthropic 只认「thinking 原文 + 该块完整签名」逐字匹配，覆盖写只剩最后
                        // 一片 ⇒ 下一轮回传该块触发 Invalid signature，整请求被拒（#2308 / 上游 e1667163c）。
                        // 截断/分批读取不影响：分片按到达顺序累积，与请求体内的最终值等价。
                        val metadata = delta["signature"]?.jsonPrimitive?.contentOrNull?.let { fragment ->
                            val received = block.metadata
                                ?.get("signature")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                .orEmpty()
                            ClaudeReasoningMetadata(signature = received + fragment).toMetadata()
                        }
                        blocks[index] = block.copy(metadata = metadata ?: block.metadata)
                        add(StreamChunk.ReasoningDelta(block.id, "", metadata))
                    }

                    "input_json_delta" -> {
                        val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (block.kind.isClaudeServerToolUseType()) {
                            add(StreamChunk.ServerToolInputDelta(block.id, partialJson))
                        } else {
                            add(StreamChunk.ToolCallDelta(id = block.id, inputDelta = partialJson))
                        }
                    }
                }
            }

            if (eventType == "content_block_stop" && index != null) {
                // 缺失 start 的 block 在 delta 阶段已懒创建；这里再缺失就静默跳过（旧解析容忍）
                blocks.remove(index)?.let { block -> endBlock(block)?.let(::add) }
            }
        }

        return if (eventType == "message_stop") {
            DecodeResult(chunks + finish(), completed = true)
        } else {
            DecodeResult(chunks)
        }
    }

    override fun onClosed(): List<StreamChunk> = finish()

    private fun finish(): List<StreamChunk> {
        if (finished) return emptyList()
        finished = true
        return buildList {
            blocks.values.mapNotNull(::endBlock).forEach(::add)
            blocks.clear()
            add(StreamChunk.Finish(finishReason, responseId, responseModel))
        }
    }

    private fun endBlock(block: ClaudeStreamBlock): StreamChunk? = when (block.kind) {
        "text" -> StreamChunk.TextEnd(block.id)
        "thinking", "redacted_thinking" -> StreamChunk.ReasoningEnd(block.id, block.metadata)
        "tool_use" -> StreamChunk.ToolCallEnd(block.id)
        else -> if (block.kind.isClaudeServerToolUseType()) {
            StreamChunk.ServerToolInputEnd(block.id)
        } else {
            null
        }
    }

    private fun parseTokenUsage(bodyJson: JsonObject): TokenUsage? {
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val inputTokens = usageJson["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedCreationTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completionTokens = usageJson["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val promptTokens = inputTokens + cachedInputTokens + cachedCreationTokens
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = promptTokens + completionTokens,
            cachedTokens = cachedInputTokens,
        )
    }

    /**
     * 依据 delta 类型推断缺失的 content block（异常中转会省略 `content_block_start`）。
     *
     * 返回的同时登记到 [blocks]，使后续 delta / stop 事件与正常路径走同一套状态。
     */
    private fun inferBlock(index: Int, deltaType: String?): ClaudeStreamBlock {
        val kind = when (deltaType) {
            "text_delta" -> "text"
            "thinking_delta", "signature_delta" -> "thinking"
            "input_json_delta" -> "tool_use"
            else -> ""
        }
        val id = "${responseId ?: "response"}:block-$index"
        return ClaudeStreamBlock(kind = kind, id = id).also { blocks[index] = it }
    }

    private data class ClaudeStreamBlock(
        val kind: String,
        val id: String,
        val metadata: JsonObject? = null,
    )
}
