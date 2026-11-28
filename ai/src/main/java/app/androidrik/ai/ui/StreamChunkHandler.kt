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

    // 增量缓冲态：避免流式高频 delta 触发 O(N²) 字符串拼接与列表拷贝。
    private var prefixMessages: List<UIMessage>? = null
    private var workingAssistant: UIMessage? = null
    private var workingParts: MutableList<UIMessagePart>? = null
    private var lastInputMessage: UIMessage? = null
    private var lastInputSize: Int = -1
    private var hasPendingChanges: Boolean = false

    private val textBuilders = mutableMapOf<Int, StringBuilder>()
    private val reasoningBuilders = mutableMapOf<Int, StringBuilder>()
    private val imageUrlBuilders = mutableMapOf<Int, StringBuilder>()
    private val toolNameBuilders = mutableMapOf<String, StringBuilder>()
    private val toolInputBuilders = mutableMapOf<String, StringBuilder>()
    private val dirtyTextIndexes = mutableSetOf<Int>()
    private val dirtyReasoningIndexes = mutableSetOf<Int>()
    private val dirtyImageIndexes = mutableSetOf<Int>()
    private val dirtyToolIds = mutableSetOf<String>()

    /** 本仓特性：是否收到过显式 [StreamChunk.Finish]。用于判定裸断流（见 `explicitEnd` 语义）。 */
    var finished: Boolean = false
        private set

    /** 缓冲区中是否存在尚未通过 [flush] 物化为不可变列表的改动。 */
    val hasBufferedChanges: Boolean
        get() = hasPendingChanges

    /** 当前活跃助手消息中文本/推理/工具参数的总字符数估算，用于流控自适应节流。 */
    val activeContentChars: Int
        get() {
            val parts = workingParts ?: return 0
            var total = 0
            for (index in parts.indices) {
                when (val part = parts[index]) {
                    is UIMessagePart.Text -> total += textBuilders[index]?.length ?: part.text.length
                    is UIMessagePart.Reasoning -> total += reasoningBuilders[index]?.length ?: part.reasoning.length
                    is UIMessagePart.Tool -> total += toolInputBuilders[part.toolCallId]?.length ?: part.input.length
                    else -> Unit
                }
            }
            return total
        }

    /**
     * 将一个 [chunk] 合并进消息列表末尾的助手消息，并返回新的消息列表。
     *
     * @throws IllegalArgumentException 当 [messages] 为空时抛出
     */
    fun handle(messages: List<UIMessage>, chunk: StreamChunk): List<UIMessage> {
        accept(messages, chunk)
        return flush(messages)
    }

    /**
     * 接收单个 [chunk] 并写入内部缓冲，不立即拷贝 `parts` 与 `messages`。
     * 需配合 [flush] 获取最新的不可变消息列表快照。
     *
     * @throws IllegalArgumentException 当 [messages] 为空时抛出
     */
    fun accept(messages: List<UIMessage>, chunk: StreamChunk) {
        ensureSession(messages)
        val assistant = workingAssistant!!
        val parts = workingParts!!

        when (chunk) {
            is StreamChunk.TextStart -> {
                if (chunk.id !in textPartIndexes) {
                    textPartIndexes[chunk.id] = parts.size
                    parts += UIMessagePart.Text("", chunk.metadata)
                    hasPendingChanges = true
                }
            }

            is StreamChunk.TextDelta -> {
                val index = textPartIndexes[chunk.id]
                // 容忍 Provider 未发送 Start：首次收到 Delta 时直接创建对应 part。
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Text) {
                    val newIndex = parts.size
                    textPartIndexes[chunk.id] = newIndex
                    parts += UIMessagePart.Text(chunk.text, chunk.metadata)
                    textBuilders.remove(newIndex)
                    dirtyTextIndexes.remove(newIndex)
                    hasPendingChanges = true
                } else {
                    val textPart = parts[index] as UIMessagePart.Text
                    val builder = textBuilders.getOrPut(index) { StringBuilder(textPart.text) }
                    builder.append(chunk.text)
                    if (chunk.metadata != null) {
                        parts[index] = textPart.copy(metadata = mergeMetadata(textPart.metadata, chunk.metadata))
                    }
                    dirtyTextIndexes += index
                    hasPendingChanges = true
                }
            }

            is StreamChunk.TextEnd -> {
                val index = textPartIndexes.remove(chunk.id)
                if (index != null) {
                    materializeText(index)
                    textBuilders.remove(index)
                }
            }

            is StreamChunk.ReasoningStart -> {
                if (chunk.id !in reasoningPartIndexes) {
                    reasoningPartIndexes[chunk.id] = parts.size
                    parts += UIMessagePart.Reasoning(
                        reasoning = "",
                        finishedAt = null,
                        metadata = chunk.metadata,
                        reasoningType = chunk.reasoningType,
                    )
                    hasPendingChanges = true
                }
            }

            is StreamChunk.ReasoningDelta -> {
                val index = reasoningPartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Reasoning) {
                    val newIndex = parts.size
                    reasoningPartIndexes[chunk.id] = newIndex
                    parts += UIMessagePart.Reasoning(
                        reasoning = chunk.text,
                        finishedAt = null,
                        metadata = chunk.metadata,
                        reasoningType = chunk.reasoningType,
                    )
                    reasoningBuilders.remove(newIndex)
                    dirtyReasoningIndexes.remove(newIndex)
                    hasPendingChanges = true
                } else {
                    val reasoning = parts[index] as UIMessagePart.Reasoning
                    val builder = reasoningBuilders.getOrPut(index) { StringBuilder(reasoning.reasoning) }
                    builder.append(chunk.text)
                    val newMetadata = chunk.metadata ?: reasoning.metadata
                    if (newMetadata !== reasoning.metadata || chunk.reasoningType != reasoning.reasoningType) {
                        parts[index] = reasoning.copy(
                            metadata = newMetadata,
                            reasoningType = chunk.reasoningType,
                        )
                    }
                    dirtyReasoningIndexes += index
                    hasPendingChanges = true
                }
            }

            is StreamChunk.ReasoningEnd -> {
                val index = reasoningPartIndexes.remove(chunk.id)
                if (index != null && parts.getOrNull(index) is UIMessagePart.Reasoning) {
                    materializeReasoning(index)
                    reasoningBuilders.remove(index)
                    val reasoning = parts[index] as UIMessagePart.Reasoning
                    parts[index] = reasoning.copy(
                        finishedAt = Clock.System.now(),
                        metadata = chunk.metadata ?: reasoning.metadata,
                    )
                    hasPendingChanges = true
                }
            }

            is StreamChunk.ToolCallStart -> {
                if (parts.none { it is UIMessagePart.Tool && it.toolCallId == chunk.id }) {
                    parts += UIMessagePart.Tool(
                        toolCallId = chunk.id,
                        toolName = chunk.toolName,
                        input = "",
                        metadata = chunk.metadata,
                    )
                    hasPendingChanges = true
                }
            }

            is StreamChunk.ToolCallDelta -> {
                // 工具调用可以并行生成，通过 toolCallId 而不是 part 位置识别目标。
                for (index in parts.indices) {
                    val part = parts[index]
                    if (part is UIMessagePart.Tool && part.toolCallId == chunk.id) {
                        if (chunk.toolNameDelta.isNotEmpty()) {
                            toolNameBuilders.getOrPut(chunk.id) { StringBuilder(part.toolName) }
                                .append(chunk.toolNameDelta)
                        }
                        if (chunk.inputDelta.isNotEmpty()) {
                            toolInputBuilders.getOrPut(chunk.id) { StringBuilder(part.input) }
                                .append(chunk.inputDelta)
                        }
                        if (chunk.metadata != null) {
                            parts[index] = part.copy(metadata = mergeMetadata(part.metadata, chunk.metadata))
                        }
                        dirtyToolIds += chunk.id
                        hasPendingChanges = true
                    }
                }
            }

            is StreamChunk.ToolCallEnd -> {
                materializeTool(chunk.id)
                toolNameBuilders.remove(chunk.id)
                toolInputBuilders.remove(chunk.id)
            }

            is StreamChunk.ServerToolStart -> {
                val index = parts.indexOfFirst {
                    it is UIMessagePart.ServerTool && it.toolCallId == chunk.id
                }
                if (index < 0) {
                    parts += UIMessagePart.ServerTool(
                        toolCallId = chunk.id,
                        toolName = chunk.toolName,
                        input = chunk.input,
                        status = ServerToolStatus.IN_PROGRESS,
                        metadata = chunk.metadata,
                    )
                } else {
                    val tool = parts[index] as UIMessagePart.ServerTool
                    parts[index] = tool.copy(
                        toolName = chunk.toolName.ifBlank { tool.toolName },
                        input = chunk.input ?: tool.input,
                        metadata = mergeMetadata(tool.metadata, chunk.metadata),
                    )
                }
                hasPendingChanges = true
            }

            is StreamChunk.ServerToolInputDelta -> {
                val buffer = serverToolInputBuffers.getOrPut(chunk.id) { StringBuilder() }
                buffer.append(chunk.inputDelta)
                updateWorkingServerTool(chunk.id) { tool ->
                    tool.copy(metadata = mergeMetadata(tool.metadata, chunk.metadata))
                }
            }

            is StreamChunk.ServerToolInputEnd -> {
                val input = serverToolInputBuffers.remove(chunk.id)?.toString()?.takeIf { it.isNotBlank() }
                    ?.let(::parseServerToolJson)
                if (input != null) {
                    updateWorkingServerTool(chunk.id) { it.copy(input = input) }
                }
            }

            is StreamChunk.ServerToolEnd -> {
                val bufferedInput = serverToolInputBuffers.remove(chunk.id)?.toString()?.takeIf { it.isNotBlank() }
                    ?.let(::parseServerToolJson)
                val index = parts.indexOfFirst {
                    it is UIMessagePart.ServerTool && it.toolCallId == chunk.id
                }
                if (index < 0) {
                    parts += UIMessagePart.ServerTool(
                        toolCallId = chunk.id,
                        toolName = chunk.toolName,
                        input = chunk.input ?: bufferedInput,
                        output = chunk.output,
                        status = chunk.status,
                        metadata = chunk.metadata,
                    )
                    hasPendingChanges = true
                } else {
                    updateWorkingServerTool(chunk.id) { tool ->
                        tool.copy(
                            toolName = chunk.toolName.ifBlank { tool.toolName },
                            input = chunk.input ?: bufferedInput ?: tool.input,
                            output = chunk.output ?: tool.output,
                            status = chunk.status,
                            metadata = mergeMetadata(tool.metadata, chunk.metadata),
                        )
                    }
                }
            }

            is StreamChunk.ImageStart -> {
                if (chunk.id !in imagePartIndexes) {
                    imagePartIndexes[chunk.id] = parts.size
                    parts += UIMessagePart.Image(
                        url = "data:${chunk.mimeType};base64,",
                        metadata = chunk.metadata,
                    )
                    hasPendingChanges = true
                }
            }

            is StreamChunk.ImageDelta -> {
                val index = imagePartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Image) {
                    val newIndex = parts.size
                    imagePartIndexes[chunk.id] = newIndex
                    parts += UIMessagePart.Image(chunk.data, chunk.metadata)
                    imageUrlBuilders.remove(newIndex)
                    dirtyImageIndexes.remove(newIndex)
                    hasPendingChanges = true
                } else {
                    val image = parts[index] as UIMessagePart.Image
                    val builder = imageUrlBuilders.getOrPut(index) { StringBuilder(image.url) }
                    builder.append(chunk.data)
                    if (chunk.metadata != null) {
                        parts[index] = image.copy(metadata = chunk.metadata)
                    }
                    dirtyImageIndexes += index
                    hasPendingChanges = true
                }
            }

            is StreamChunk.ImageSnapshot -> {
                val index = imagePartIndexes[chunk.id]
                if (index == null || parts.getOrNull(index) !is UIMessagePart.Image) {
                    val newIndex = parts.size
                    imagePartIndexes[chunk.id] = newIndex
                    parts += UIMessagePart.Image(
                        // 快照可能不经 ImageStart 冷启动（#2311 同族）：mime 用 decoder 给的
                        // 真值，未知（null）才落回 png 默认——jpeg/webp 不再被误标成 png。
                        url = "data:${chunk.mimeType ?: "image/png"};base64,${chunk.data}",
                        metadata = chunk.metadata,
                    )
                    imageUrlBuilders.remove(newIndex)
                    dirtyImageIndexes.remove(newIndex)
                    hasPendingChanges = true
                } else {
                    materializeImage(index)
                    imageUrlBuilders.remove(index)
                    val image = parts[index] as UIMessagePart.Image
                    // Snapshot 是一张完整的可渲染图片，只替换 data URL 的数据部分；
                    // 与 ImageDelta 不同，它不会把数据追加到上一帧之后。
                    // decoder 显式给了 mime（如 Responses output_format）就以它为准；
                    // 没给则保留部件已有前缀（ImageStart 可能为非 png），最后才落 png。
                    val dataUrlPrefix = chunk.mimeType?.let { "data:$it;base64" }
                        ?: image.url.substringBefore(",").takeIf { it.startsWith("data:") }
                        ?: "data:image/png;base64"
                    parts[index] = image.copy(
                        url = "$dataUrlPrefix,${chunk.data}",
                        metadata = chunk.metadata ?: image.metadata,
                    )
                    hasPendingChanges = true
                }
            }

            is StreamChunk.ImageEnd -> {
                val index = imagePartIndexes.remove(chunk.id)
                if (index != null) {
                    materializeImage(index)
                    imageUrlBuilders.remove(index)
                }
            }

            is StreamChunk.ToolCallOutput -> {
                materializeTool(chunk.id)
                // 复用 Tool.merge 的幂等去重：Responses API 会在 added/done 各带一份完整结果
                val delta = UIMessagePart.Tool(
                    toolCallId = chunk.id,
                    toolName = "",
                    input = "",
                    output = listOf(UIMessagePart.Text(chunk.output)),
                )
                val index = parts.indexOfFirst {
                    it is UIMessagePart.Tool && it.toolCallId == chunk.id
                }
                if (index >= 0) {
                    parts[index] = (parts[index] as UIMessagePart.Tool).merge(delta)
                } else {
                    // 容忍未先收到 ToolCallStart：直接落一个「已执行」的工具 part
                    parts += delta
                }
                hasPendingChanges = true
            }

            is StreamChunk.Annotations -> {
                workingAssistant = assistant.copy(
                    annotations = (assistant.annotations + chunk.annotations).distinct(),
                )
                hasPendingChanges = true
            }

            is StreamChunk.Usage -> {
                workingAssistant = assistant.copy(usage = assistant.usage.merge(chunk.usage))
                hasPendingChanges = true
            }

            is StreamChunk.Finish -> {
                materializeDirtyParts()
                finished = true
                val finishedMessage = assistant.copy(
                    parts = parts.toList(),
                    finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                    finishReason = chunk.finishReason ?: assistant.finishReason,
                    vertexInteraction = chunk.vertexInteraction,
                ).finishReasoning()
                workingAssistant = finishedMessage
                workingParts = finishedMessage.parts.toMutableList()
                hasPendingChanges = true
                // Finish 同时结束尚未显式结束的 reasoning，并释放本次响应流的索引与缓冲状态。
                clearSessionIndexesAndBuilders()
            }
        }
    }

    /**
     * 将缓冲中的修改物化为不可变消息列表。若没有待物化变更，直接返回 [fallbackMessages]。
     */
    fun flush(fallbackMessages: List<UIMessage>): List<UIMessage> {
        if (!hasPendingChanges) return fallbackMessages
        val assistant = workingAssistant ?: return fallbackMessages
        val parts = workingParts ?: return fallbackMessages
        materializeDirtyParts()
        val flushedAssistant = assistant.copy(parts = parts.toList())
        workingAssistant = flushedAssistant
        val result = prefixMessages.orEmpty() + flushedAssistant
        lastInputMessage = flushedAssistant
        lastInputSize = result.size
        hasPendingChanges = false
        return result
    }

    private fun ensureSession(messages: List<UIMessage>) {
        require(messages.isNotEmpty()) { "messages must not be empty" }
        val last = messages.last()
        if (workingAssistant != null &&
            last === lastInputMessage &&
            messages.size == lastInputSize
        ) {
            return
        }
        clearPartBuildersOnly()
        lastInputMessage = last
        lastInputSize = messages.size
        if (last.role != MessageRole.ASSISTANT) {
            val newAssistant = UIMessage(
                modelId = model?.id,
                modelNameSnapshot = model?.displayName?.takeIf { it.isNotBlank() },
                providerNameSnapshot = providerName?.takeIf { it.isNotBlank() },
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
            )
            prefixMessages = messages
            workingAssistant = newAssistant
            workingParts = mutableListOf()
            hasPendingChanges = false
        } else {
            prefixMessages = messages.dropLast(1)
            workingAssistant = last
            workingParts = last.parts.toMutableList()
            hasPendingChanges = false
        }
    }

    private fun materializeDirtyParts() {
        if (dirtyTextIndexes.isNotEmpty()) {
            dirtyTextIndexes.toList().forEach(::materializeText)
        }
        if (dirtyReasoningIndexes.isNotEmpty()) {
            dirtyReasoningIndexes.toList().forEach(::materializeReasoning)
        }
        if (dirtyImageIndexes.isNotEmpty()) {
            dirtyImageIndexes.toList().forEach(::materializeImage)
        }
        if (dirtyToolIds.isNotEmpty()) {
            dirtyToolIds.toList().forEach(::materializeTool)
        }
    }

    private fun materializeText(index: Int) {
        if (!dirtyTextIndexes.remove(index)) return
        val parts = workingParts ?: return
        val builder = textBuilders[index] ?: return
        val current = parts.getOrNull(index) as? UIMessagePart.Text ?: return
        parts[index] = current.copy(text = builder.toString())
    }

    private fun materializeReasoning(index: Int) {
        if (!dirtyReasoningIndexes.remove(index)) return
        val parts = workingParts ?: return
        val builder = reasoningBuilders[index] ?: return
        val current = parts.getOrNull(index) as? UIMessagePart.Reasoning ?: return
        parts[index] = current.copy(reasoning = builder.toString())
    }

    private fun materializeImage(index: Int) {
        if (!dirtyImageIndexes.remove(index)) return
        val parts = workingParts ?: return
        val builder = imageUrlBuilders[index] ?: return
        val current = parts.getOrNull(index) as? UIMessagePart.Image ?: return
        parts[index] = current.copy(url = builder.toString())
    }

    private fun materializeTool(toolCallId: String) {
        if (!dirtyToolIds.remove(toolCallId)) return
        val parts = workingParts ?: return
        val nameBuilder = toolNameBuilders[toolCallId]
        val inputBuilder = toolInputBuilders[toolCallId]
        if (nameBuilder == null && inputBuilder == null) return
        for (index in parts.indices) {
            val current = parts[index]
            if (current is UIMessagePart.Tool && current.toolCallId == toolCallId) {
                parts[index] = current.copy(
                    toolName = nameBuilder?.toString() ?: current.toolName,
                    input = inputBuilder?.toString() ?: current.input,
                )
            }
        }
    }

    private fun updateWorkingServerTool(
        id: String,
        transform: (UIMessagePart.ServerTool) -> UIMessagePart.ServerTool,
    ) {
        val parts = workingParts ?: return
        var changed = false
        for (index in parts.indices) {
            val part = parts[index]
            if (part is UIMessagePart.ServerTool && part.toolCallId == id) {
                parts[index] = transform(part)
                changed = true
            }
        }
        if (changed) hasPendingChanges = true
    }

    private fun clearPartBuildersOnly() {
        textBuilders.clear()
        reasoningBuilders.clear()
        imageUrlBuilders.clear()
        toolNameBuilders.clear()
        toolInputBuilders.clear()
        dirtyTextIndexes.clear()
        dirtyReasoningIndexes.clear()
        dirtyImageIndexes.clear()
        dirtyToolIds.clear()
    }

    private fun clearSessionIndexesAndBuilders() {
        textPartIndexes.clear()
        reasoningPartIndexes.clear()
        imagePartIndexes.clear()
        serverToolInputBuffers.clear()
        clearPartBuildersOnly()
    }
}

private fun parseServerToolJson(value: String) = runCatching {
    json.parseToJsonElement(value)
}.getOrElse { JsonPrimitive(value) }

internal fun mergeMetadata(old: JsonObject?, new: JsonObject?): JsonObject? = when {
    old == null -> new
    new == null -> old
    else -> JsonObject(old + new)
}
