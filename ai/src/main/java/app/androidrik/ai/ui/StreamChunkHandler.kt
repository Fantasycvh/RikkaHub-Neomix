package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.core.merge
import app.androidrik.ai.provider.Model
import app.androidrik.ai.util.json
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 将 Provider 产生的通用流事件合并为一条 [UIMessage]（移植自上游 #1648）。
 *
 * 文本、推理和图片可能交错到达，因此不能只更新最后一个 part。本类会在收到对应的 `Start`
 * 事件时，记录事件 id 在 [UIMessage.parts] 中的位置；后续 `Delta`、`Snapshot` 和 `End`
 * 事件再通过同一 id 定位并更新该 part。工具调用则直接使用
 * [UIMessagePart.Tool.toolCallId] 定位。
 *
 * [handle] 不会修改传入的消息列表，而是返回包含更新后消息的新列表。如果列表末尾不是助手
 * 消息，第一个事件会自动创建一条空的助手消息。收到 [StreamChunk.Finish] 后，会标记消息完成
 * 并清除内部状态。
 *
 * 该类保存着一次响应流的合并状态，不是无状态转换器。每条并发响应流都必须使用独立实例，且
 * 事件应按 Provider 产生的顺序交给同一实例处理。
 */
class StreamChunkHandler(
    private val model: Model? = null,
    private val providerName: String? = null,
) {
    // Map 的值是对应 part 在当前助手消息 parts 列表中的下标。
    private val textPartIndexes = mutableMapOf<String, Int>()
    private val reasoningPartIndexes = mutableMapOf<String, Int>()
    private val imagePartIndexes = mutableMapOf<String, Int>()
    private val serverToolInputBuffers = mutableMapOf<String, StringBuilder>()

    /** 本仓特性：是否收到过显式 [StreamChunk.Finish]。用于判定裸断流（见 `explicitEnd` 语义）。 */
    var finished: Boolean = false
        private set

    /**
     * 将一个 [chunk] 合并进消息列表末尾的助手消息，并返回新的消息列表。
     *
     * @throws IllegalArgumentException 当 [messages] 为空时抛出
     */
    fun handle(messages: List<UIMessage>, chunk: StreamChunk): List<UIMessage> {
        require(messages.isNotEmpty()) { "messages must not be empty" }

        val targetMessages = if (messages.last().role != MessageRole.ASSISTANT) {
            messages + UIMessage(
                modelId = model?.id,
                modelNameSnapshot = model?.displayName?.takeIf { it.isNotBlank() },
                providerNameSnapshot = providerName?.takeIf { it.isNotBlank() },
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
            )
        } else {
            messages
        }
        val updatedMessage = append(targetMessages.last(), chunk)
        return targetMessages.dropLast(1) + updatedMessage
    }

    private fun append(message: UIMessage, chunk: StreamChunk): UIMessage = with(message) {
        when (chunk) {
            is StreamChunk.TextStart -> {
                if (chunk.id in textPartIndexes) this
                else copy(parts = parts + UIMessagePart.Text("")).also {
                    textPartIndexes[chunk.id] = parts.size
                }
            }

            is StreamChunk.TextDelta -> {
                val index = textPartIndexes[chunk.id]
                // 容忍 Provider 未发送 Start：首次收到 Delta 时直接创建对应 part。
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Text) {
                    copy(parts = parts + UIMessagePart.Text(chunk.text)).also {
                        textPartIndexes[chunk.id] = parts.size
                    }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val text = get(index) as UIMessagePart.Text
                        set(index, text.copy(text = text.text + chunk.text))
                    })
                }
            }

            is StreamChunk.TextEnd -> this.also { textPartIndexes.remove(chunk.id) }

            is StreamChunk.ReasoningStart -> {
                if (chunk.id in reasoningPartIndexes) this
                else copy(parts = parts + UIMessagePart.Reasoning(
                    reasoning = "",
                    finishedAt = null,
                    metadata = chunk.metadata,
                    reasoningType = chunk.reasoningType,
                )).also { reasoningPartIndexes[chunk.id] = parts.size }
            }

            is StreamChunk.ReasoningDelta -> {
                val index = reasoningPartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Reasoning) {
                    copy(parts = parts + UIMessagePart.Reasoning(
                        reasoning = chunk.text,
                        finishedAt = null,
                        metadata = chunk.metadata,
                        reasoningType = chunk.reasoningType,
                    )).also { reasoningPartIndexes[chunk.id] = parts.size }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val reasoning = get(index) as UIMessagePart.Reasoning
                        set(index, reasoning.copy(
                            reasoning = reasoning.reasoning + chunk.text,
                            metadata = chunk.metadata ?: reasoning.metadata,
                            reasoningType = chunk.reasoningType,
                        ))
                    })
                }
            }

            is StreamChunk.ReasoningEnd -> {
                val index = reasoningPartIndexes.remove(chunk.id)
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Reasoning) this
                else copy(parts = parts.toMutableList().apply {
                    val reasoning = get(index) as UIMessagePart.Reasoning
                    set(index, reasoning.copy(
                        finishedAt = Clock.System.now(),
                        metadata = chunk.metadata ?: reasoning.metadata,
                    ))
                })
            }

            is StreamChunk.ToolCallStart -> {
                if (parts.any { it is UIMessagePart.Tool && it.toolCallId == chunk.id }) this
                else copy(parts = parts + UIMessagePart.Tool(
                    toolCallId = chunk.id,
                    toolName = chunk.toolName,
                    input = "",
                    metadata = chunk.metadata,
                ))
            }

            is StreamChunk.ToolCallDelta -> copy(parts = parts.map { part ->
                // 工具调用可以并行生成，通过 toolCallId 而不是 part 位置识别目标。
                if (part is UIMessagePart.Tool && part.toolCallId == chunk.id) {
                    part.copy(
                        toolName = part.toolName + chunk.toolNameDelta,
                        input = part.input + chunk.inputDelta,
                        metadata = chunk.metadata ?: part.metadata,
                    )
                } else part
            })

            is StreamChunk.ToolCallEnd -> this

            is StreamChunk.ServerToolStart -> {
                val index = parts.indexOfFirst {
                    it is UIMessagePart.ServerTool && it.toolCallId == chunk.id
                }
                if (index < 0) {
                    copy(parts = parts + UIMessagePart.ServerTool(
                        toolCallId = chunk.id,
                        toolName = chunk.toolName,
                        input = chunk.input,
                        status = ServerToolStatus.IN_PROGRESS,
                        metadata = chunk.metadata,
                    ))
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val tool = get(index) as UIMessagePart.ServerTool
                        set(index, tool.copy(
                            toolName = chunk.toolName.ifBlank { tool.toolName },
                            input = chunk.input ?: tool.input,
                            metadata = mergeMetadata(tool.metadata, chunk.metadata),
                        ))
                    })
                }
            }

            is StreamChunk.ServerToolInputDelta -> {
                val buffer = serverToolInputBuffers.getOrPut(chunk.id) { StringBuilder() }
                buffer.append(chunk.inputDelta)
                updateServerTool(chunk.id) { tool ->
                    tool.copy(metadata = mergeMetadata(tool.metadata, chunk.metadata))
                }
            }

            is StreamChunk.ServerToolInputEnd -> {
                val input = serverToolInputBuffers.remove(chunk.id)?.toString()?.takeIf { it.isNotBlank() }
                    ?.let(::parseServerToolJson)
                if (input == null) this else updateServerTool(chunk.id) { it.copy(input = input) }
            }

            is StreamChunk.ServerToolEnd -> {
                val bufferedInput = serverToolInputBuffers.remove(chunk.id)?.toString()?.takeIf { it.isNotBlank() }
                    ?.let(::parseServerToolJson)
                val index = parts.indexOfFirst {
                    it is UIMessagePart.ServerTool && it.toolCallId == chunk.id
                }
                if (index < 0) {
                    copy(parts = parts + UIMessagePart.ServerTool(
                        toolCallId = chunk.id,
                        toolName = "",
                        input = chunk.input ?: bufferedInput,
                        output = chunk.output,
                        status = chunk.status,
                        metadata = chunk.metadata,
                    ))
                } else {
                    updateServerTool(chunk.id) { tool ->
                        tool.copy(
                            input = chunk.input ?: bufferedInput ?: tool.input,
                            output = chunk.output ?: tool.output,
                            status = chunk.status,
                            metadata = mergeMetadata(tool.metadata, chunk.metadata),
                        )
                    }
                }
            }

            is StreamChunk.ImageStart -> {
                if (chunk.id in imagePartIndexes) this
                else copy(parts = parts + UIMessagePart.Image(
                    url = "data:${chunk.mimeType};base64,",
                    metadata = chunk.metadata,
                )).also { imagePartIndexes[chunk.id] = parts.size }
            }

            is StreamChunk.ImageDelta -> {
                val index = imagePartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Image) {
                    copy(parts = parts + UIMessagePart.Image(chunk.data, chunk.metadata)).also {
                        imagePartIndexes[chunk.id] = parts.size
                    }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val image = get(index) as UIMessagePart.Image
                        set(index, image.copy(
                            url = image.url + chunk.data,
                            metadata = chunk.metadata ?: image.metadata,
                        ))
                    })
                }
            }

            is StreamChunk.ImageSnapshot -> {
                val index = imagePartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Image) {
                    copy(
                        parts = parts + UIMessagePart.Image(
                            url = "data:image/png;base64,${chunk.data}",
                            metadata = chunk.metadata,
                        )
                    ).also { imagePartIndexes[chunk.id] = parts.size }
                } else {
                    copy(parts = parts.toMutableList().apply {
                        val image = get(index) as UIMessagePart.Image
                        // Snapshot 是一张完整的可渲染图片，只替换 data URL 的数据部分；
                        // 与 ImageDelta 不同，它不会把数据追加到上一帧之后。
                        val dataUrlPrefix = image.url.substringBefore(",").takeIf { it.startsWith("data:") }
                            ?: "data:image/png;base64"
                        set(index, image.copy(
                            url = "$dataUrlPrefix,${chunk.data}",
                            metadata = chunk.metadata ?: image.metadata,
                        ))
                    })
                }
            }

            is StreamChunk.ImageEnd -> this.also { imagePartIndexes.remove(chunk.id) }

            is StreamChunk.Annotations -> copy(annotations = (annotations + chunk.annotations).distinct())

            is StreamChunk.Usage -> copy(usage = usage.merge(chunk.usage))

            is StreamChunk.Finish -> {
                finished = true
                copy(
                    finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                    finishReason = chunk.finishReason ?: finishReason,
                ).finishReasoning().also {
                    // Finish 同时结束尚未显式结束的 reasoning，并释放本次响应流的索引状态。
                    textPartIndexes.clear()
                    reasoningPartIndexes.clear()
                    imagePartIndexes.clear()
                    serverToolInputBuffers.clear()
                }
            }
        }
    }

    private fun UIMessage.updateServerTool(
        id: String,
        transform: (UIMessagePart.ServerTool) -> UIMessagePart.ServerTool,
    ): UIMessage = copy(parts = parts.map { part ->
        if (part is UIMessagePart.ServerTool && part.toolCallId == id) transform(part) else part
    })
}

private fun parseServerToolJson(value: String) = runCatching {
    json.parseToJsonElement(value)
}.getOrElse { JsonPrimitive(value) }

private fun mergeMetadata(old: JsonObject?, new: JsonObject?): JsonObject? = when {
    old == null -> new
    new == null -> old
    else -> JsonObject(old + new)
}
