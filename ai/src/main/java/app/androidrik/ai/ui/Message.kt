package app.androidrik.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.Model
import app.androidrik.ai.util.json
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.uuid.Uuid

// 公共消息抽象, 具体的Provider实现会转换为API接口需要的DTO
@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: LocalDateTime = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()),
    val finishedAt: LocalDateTime? = null,
    val modelId: Uuid? = null,
    /**
     * 生成该消息时所用模型/提供商的名称快照（token 统计按名称分组用）。
     *
     * 存快照而非引用 id：模型/提供商被删除后 id 查不到（悬空），且删除后重建会得到新 UUID
     * 导致同一模型在统计里裂成两行。快照保留"当时叫什么"，旧消息为 null 时回退按 id 查找。
     */
    val modelNameSnapshot: String? = null,
    val providerNameSnapshot: String? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null,
    /**
     * 本轮输出的 `finish_reason`（provider 原样值，如 `stop` / `length` / `MAX_TOKENS` /
     * `content_filter` / `SAFETY`）。`"unknown"` 是 chat/completions 侧「字段缺失」的既有占位，
     * 一律归一成 null（见 [GenerationHandler][app.androidrik.rikkahub.data.ai.GenerationHandler]）。
     *
     * 持久化（`MessageNodeEntity.messages` 是 JSON blob、反序列化开了 ignoreUnknownKeys，
     * 新增字段带默认值 ⇒ 零迁移）：用户看到的「这条回复为什么被截断」在重启后仍可解释。
     */
    val finishReason: String? = null,
    /**
     * **裸断流**标记：本轮流**没有**收到显式结束标记就关流了（网关/中转提前切连接），
     * 内容多半不完整。与 [finishReason] 正交——后者是 provider 表态，前者是「provider 根本没表态」。
     *
     * 语义是「**最后一次**流的结束状态」：续写成功后会被覆写回 false，故不需要额外的注解来
     * 清除提示（详见 `docs/peer-apps-benchmark.md` §四.7）。
     */
    val finishIncomplete: Boolean = false,
    // 请求期间生成的内部消息；该标记仅在内存中使用
    @Transient
    val isSynthetic: Boolean = false,
) {
    private fun appendChunk(chunk: MessageChunk): UIMessage {
        val choice = chunk.choices.getOrNull(0)
        val message = choice?.delta ?: choice?.message
        return message?.let { delta ->
            // Handle Parts
            var newParts = delta.parts.fold(parts) { acc, deltaPart ->
                when (deltaPart) {
                    is UIMessagePart.Text -> {
                        // Skip empty text deltas
                        if (deltaPart.text.isEmpty()) {
                            acc
                        } else {
                            val lastPart = acc.lastOrNull()
                            if (lastPart is UIMessagePart.Text) {
                                // Append to the last Text part
                                acc.dropLast(1) + lastPart.copy(text = lastPart.text + deltaPart.text)
                            } else {
                                // Create new Text part
                                acc + deltaPart
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val lastPart = acc.lastOrNull()
                        if (lastPart is UIMessagePart.Image) {
                            // Append to the last Image part (for streaming base64)
                            acc.dropLast(1) + lastPart.copy(
                                url = lastPart.url + deltaPart.url,
                                metadata = deltaPart.metadata ?: lastPart.metadata
                            )
                        } else {
                            // Create new Image part
                            acc + UIMessagePart.Image(
                                url = "data:image/png;base64,${deltaPart.url}",
                                metadata = deltaPart.metadata,
                            )
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        // Skip empty reasoning deltas
                        if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) {
                            acc
                        } else {
                            val lastPart = acc.lastOrNull()
                            if (lastPart is UIMessagePart.Reasoning) {
                                // Append to the last Reasoning part
                                acc.dropLast(1) + UIMessagePart.Reasoning(
                                    reasoning = lastPart.reasoning + deltaPart.reasoning,
                                    createdAt = lastPart.createdAt,
                                    finishedAt = null,
                                ).also {
                                    it.metadata = deltaPart.metadata ?: lastPart.metadata
                                }
                            } else {
                                // Create new Reasoning part
                                acc + deltaPart
                            }
                        }
                    }

                    is UIMessagePart.Tool -> {
                        if (deltaPart.toolCallId.isBlank()) {
                            // No ID yet - append to the last Tool if it also has no ID
                            val lastTool = acc.lastOrNull { it is UIMessagePart.Tool } as? UIMessagePart.Tool
                            if (lastTool != null) {
                                acc.map { part ->
                                    if (part === lastTool) part.merge(deltaPart) else part
                                }
                            } else {
                                acc + deltaPart.copy()
                            }
                        } else {
                            // Has ID - find and update by ID, or insert new
                            val existsPart = acc.find {
                                it is UIMessagePart.Tool && it.toolCallId == deltaPart.toolCallId
                            } as? UIMessagePart.Tool
                            if (existsPart == null) {
                                acc + deltaPart.copy()
                            } else {
                                acc.map { part ->
                                    if (part is UIMessagePart.Tool && part.toolCallId == deltaPart.toolCallId) {
                                        part.merge(deltaPart)
                                    } else part
                                }
                            }
                        }
                    }

                    is UIMessagePart.ServerTool -> {
                        // 内置工具（googleSearch/urlContext 等）整 part 到达：按 id 找到 IN_PROGRESS 则更新为 COMPLETED，否则追加
                        val existsPart = acc.find {
                            it is UIMessagePart.ServerTool && it.toolCallId == deltaPart.toolCallId
                        } as? UIMessagePart.ServerTool
                        if (existsPart == null) {
                            acc + deltaPart
                        } else {
                            acc.map { part ->
                                if (part is UIMessagePart.ServerTool && part.toolCallId == deltaPart.toolCallId) {
                                    part.copy(
                                        status = deltaPart.status,
                                        input = deltaPart.input ?: part.input,
                                        output = deltaPart.output ?: part.output,
                                    )
                                } else part
                            }
                        }
                    }

                    else -> {
                        println("delta part append not supported: $deltaPart")
                        acc
                    }
                }
            }
            // Handle Reasoning End
            if (parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isNotEmpty() && delta.parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isEmpty()
            ) {
                newParts = newParts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = Clock.System.now())
                    } else part
                }
            }
            // Handle annotations
            val newAnnotations = delta.annotations.ifEmpty {
                annotations
            }
            copy(
                parts = newParts,
                annotations = newAnnotations,
            )
        } ?: this
    }

    fun summaryAsText(maxLength: Int = Int.MAX_VALUE): String {
        val text = "[${role.name}]: " + parts.joinToString(separator = "\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }
        return if (text.length > maxLength) text.take(maxLength) + "..." else text
    }

    fun toText() = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> ""
        }
    }

    fun getTools() = parts.filterIsInstance<UIMessagePart.Tool>()

    fun isValidToUpload() = parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
            is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
            else -> true
        }
    }

    inline fun <reified P : UIMessagePart> hasPart(): Boolean {
        return parts.any {
            it is P
        }
    }

    fun hasBase64Part(): Boolean = parts.any {
        it is UIMessagePart.Image && it.url.startsWith("data:")
    }

    operator fun plus(chunk: MessageChunk): UIMessage {
        return this.appendChunk(chunk)
    }

    companion object {
        fun system(prompt: String) = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun user(prompt: String) = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun assistant(prompt: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt))
        )
    }
}

/**
 * 处理MessageChunk合并
 *
 * @receiver 已有消息列表
 * @param chunk 消息chunk
 * @param model 模型, 可以不传，如果传了，会把模型id写入到消息，标记是哪个模型输出的消息
 * @param providerName 提供商名称快照, 与 model 一同写入新消息, 供 token 统计按名称分组
 * @return 新消息列表
 */
fun List<UIMessage>.handleMessageChunk(
    chunk: MessageChunk,
    model: Model? = null,
    providerName: String? = null,
): List<UIMessage> {
    require(this.isNotEmpty()) {
        "messages must not be empty"
    }
    val choice = chunk.choices.getOrNull(0) ?: return this
    val message = choice.delta ?: choice.message ?: return this
    if (this.last().role != message.role) {
        return this + (
            UIMessage(
                modelId = model?.id,
                modelNameSnapshot = model?.displayName?.takeIf { it.isNotBlank() },
                providerNameSnapshot = providerName?.takeIf { it.isNotBlank() },
                role = message.role,
                parts = emptyList(),
            ) + chunk
            )
    } else {
        val last = this.last() + chunk
        return this.dropLast(1) + last
    }
}

/**
 * 判断这个消息是否有有任何用户**可输入内容**
 *
 * 例如: 文本，图片, 文档
 */
fun List<UIMessagePart>.isEmptyInputMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

/**
 * 判断这个消息在UI上是否显示任何内容
 */
fun List<UIMessagePart>.isEmptyUIMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Reasoning -> message.reasoning.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            // tool-only 消息是可见 UI 内容：操作按钮（重新生成/审批等）应保持可用（对齐 321443d8 / #1689）
            is UIMessagePart.Tool -> false
            is UIMessagePart.ServerTool -> false
            else -> true
        }
    }
}

/**
 * 截断后保留的消息条数占上限的比例
 *
 * 越小则截断点前进的步幅越大, 连续命中缓存的轮数越多, 但一次丢弃的上下文也越多
 */
private const val CONTEXT_KEEP_RATIO = 0.5f

/**
 * 按阶梯式(滞回)策略限制上下文消息数量
 *
 * 与每轮平移一条的滑动窗口不同, 截断点只在消息数越过 [limit] 时才前进一大步,
 * 在此之后的连续多轮里保持不动, 使请求前缀保持稳定, 从而命中提示词缓存。
 * 截断点仅由消息条数推导, 不需要额外持久化状态, 且对追加消息天然稳定。
 *
 * 保留的条数始终落在 `[limit * CONTEXT_KEEP_RATIO, limit)` 区间内。
 *
 * @param limit 触发截断的消息条数上限, 小于等于 0 表示不限制
 */
fun List<UIMessage>.limitContext(limit: Int): List<UIMessage> {
    if (limit <= 0 || this.size <= limit) return this

    // 截断后回落到的目标条数, 以及两次截断之间截断点前进的步幅
    // limit 为 1 时无法构造滞回(步幅至少为 1), 此时退化为逐条平移的滑动窗口
    val target = (limit * CONTEXT_KEEP_RATIO).roundToInt().coerceIn(1, limit)
    val stride = (limit - target).coerceAtLeast(1)

    // 每越过一级台阶, 截断点前进 stride 条; 台阶之内截断点不动
    // 上界兜底保证至少保留一条消息, 正常路径(limit >= 2)不会触发
    val startIndex = (((this.size - limit) / stride + 1) * stride).coerceAtMost(this.size - 1)

    return this.subList(alignContextStart(startIndex), this.size)
}

/**
 * 将截断起点回退到安全边界, 避免把 tool call 与其结果拆散, 或让上下文从半截的工具调用开始
 *
 * 只会向前(下标减小)调整, 因此不会破坏 [limitContext] 保留条数的下界。
 * 调整只依赖 `[0, startIndex]` 区间内的消息, 这部分在追加新消息时不会变化, 结果因此保持稳定。
 */
private fun List<UIMessage>.alignContextStart(startIndex: Int): Int {
    var adjustedStartIndex = startIndex

    // 循环往前查找, 直到满足所有依赖条件
    var needsAdjustment = true
    val visitedIndices = mutableSetOf<Int>()

    while (needsAdjustment && adjustedStartIndex > 0) {
        needsAdjustment = false

        // 防止无限循环
        if (adjustedStartIndex in visitedIndices) break
        visitedIndices.add(adjustedStartIndex)

        val currentMessage = this[adjustedStartIndex]

        // 如果当前消息包含已执行的tool（有output），往前查找对应的tool call
        if (currentMessage.getTools().any { it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].getTools().any { !it.isExecuted }) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }

        // 如果当前消息包含未执行的tool call，往前查找对应的用户消息
        if (currentMessage.getTools().any { !it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].role == MessageRole.USER) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }
    }

    return adjustedStartIndex
}

/**
 * Sort message parts by type priority:
 * - Reasoning (-1): shown first
 * - Text, Tool, ToolCall, ToolResult, Search (0): middle
 * - Image, Video, Audio, Document (1): shown last
 *
 * WARNING: This function is intended for migration only.
 * Do not use for new messages as it may break the semantic order
 * when a message contains multiple Reasoning/Text parts.
 */
@Deprecated(
    message = "Only use for migration. May break semantic order for messages with multiple Reasoning/Text parts.",
    level = DeprecationLevel.WARNING
)
fun List<UIMessagePart>.toSortedMessageParts(): List<UIMessagePart> {
    // Skip sorting if multiple Reasoning or Text parts exist to preserve semantic order
    val reasoningCount = count { it is UIMessagePart.Reasoning }
    val textCount = count { it is UIMessagePart.Text }
    if (reasoningCount > 1 || textCount > 1) {
        return this
    }
    return sortedBy { part ->
        when (part) {
            is UIMessagePart.Reasoning -> -1
            is UIMessagePart.Text -> 0
            is UIMessagePart.Tool -> 0
            is UIMessagePart.ToolCall -> 0
            is UIMessagePart.ToolResult -> 0
            is UIMessagePart.ServerTool -> 0
            is UIMessagePart.Search -> 0
            is UIMessagePart.Image -> 1
            is UIMessagePart.Video -> 1
            is UIMessagePart.Audio -> 1
            is UIMessagePart.Document -> 1
        }
    }
}

fun UIMessage.finishReasoning(): UIMessage {
    return copy(
        parts = parts.map { part ->
            when (part) {
                is UIMessagePart.Reasoning -> {
                    if (part.finishedAt == null) {
                        part.copy(
                            finishedAt = Clock.System.now()
                        )
                    } else {
                        part
                    }
                }

                else -> part
            }
        }
    )
}

fun UIMessage.finishPendingTools(
    transform: (UIMessagePart.Tool) -> UIMessagePart.Tool
): UIMessage {
    val updatedParts = parts.map { part ->
        if (part is UIMessagePart.Tool && !part.isExecuted) {
            transform(part)
        } else {
            part
        }
    }

    if (updatedParts == parts) {
        return this
    }

    return copy(
        parts = updatedParts,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    ).finishReasoning()
}

/**
 * Migrate legacy ToolCall parts to new Tool type within a single message.
 * This converts ToolCall parts to Tool parts with empty output.
 */
@Suppress("DEPRECATION")
private fun UIMessage.migrateToolParts(): UIMessage {
    val toolCalls = parts.filterIsInstance<UIMessagePart.ToolCall>()
    if (toolCalls.isEmpty()) {
        // Even if no ToolCall migration needed, ensure parts are sorted
        val sortedParts = parts.toSortedMessageParts()
        return if (sortedParts != parts) copy(parts = sortedParts) else this
    }

    val migratedParts = parts.map { part ->
        if (part is UIMessagePart.ToolCall) {
            UIMessagePart.Tool(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                input = part.arguments,
                output = emptyList(),
                approvalState = part.approvalState,
                metadata = part.metadata
            )
        } else {
            part
        }
    }
    return copy(parts = migratedParts.toSortedMessageParts())
}

/**
 * Migrate TOOL role messages into previous ASSISTANT messages by
 * merging ToolResult parts into corresponding Tool parts.
 * Returns the migrated list with TOOL messages removed.
 */
@Suppress("DEPRECATION")
fun List<UIMessage>.migrateToolMessages(): List<UIMessage> {
    val result = mutableListOf<UIMessage>()
    var i = 0

    while (i < size) {
        val message = this[i]

        // If this is a TOOL role message, merge its results into previous ASSISTANT message
        if (message.role == MessageRole.TOOL) {
            val toolResults = message.parts.filterIsInstance<UIMessagePart.ToolResult>()
            if (result.isNotEmpty() && result.last().role == MessageRole.ASSISTANT) {
                // Find the last ASSISTANT message and update its Tool parts with results
                val lastAssistant = result.removeAt(result.lastIndex)
                val updatedParts = lastAssistant.parts.map { part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted) {
                        val matchingResult = toolResults.find { result -> result.toolCallId == part.toolCallId }
                        if (matchingResult != null) {
                            part.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(matchingResult.content)
                                    )
                                )
                            )
                        } else {
                            part
                        }
                    } else if (part is UIMessagePart.ToolCall) {
                        // Also handle legacy ToolCall parts
                        val matchingResult = toolResults.find { result -> result.toolCallId == part.toolCallId }
                        if (matchingResult != null) {
                            UIMessagePart.Tool(
                                toolCallId = part.toolCallId,
                                toolName = part.toolName,
                                input = part.arguments,
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(matchingResult.content)
                                    )
                                ),
                                approvalState = part.approvalState,
                                metadata = part.metadata
                            )
                        } else {
                            UIMessagePart.Tool(
                                toolCallId = part.toolCallId,
                                toolName = part.toolName,
                                input = part.arguments,
                                output = emptyList(),
                                approvalState = part.approvalState,
                                metadata = part.metadata
                            )
                        }
                    } else {
                        part
                    }
                }
                result.add(lastAssistant.copy(parts = updatedParts.toSortedMessageParts()))
            }
            // Skip the TOOL message (don't add it to result)
            i++
            continue
        }

        // For other messages, migrate their tool parts first
        result.add(message.migrateToolParts())
        i++
    }

    return result
}

/**
 * Migrate legacy TOOL role messages at the MessageNode level.
 * This handles the case where TOOL messages are stored in separate MessageNodes
 * by merging ToolResult parts into the previous ASSISTANT node's Tool parts.
 *
 * @param MessageNode A container holding one or more UIMessages for branching.
 * @return Migrated list with TOOL nodes removed and their results merged into ASSISTANT nodes.
 */
@Suppress("DEPRECATION")
fun <T> List<T>.migrateToolNodes(
    getMessages: (T) -> List<UIMessage>,
    setMessages: (T, List<UIMessage>) -> T
): List<T> {
    val result = mutableListOf<T>()
    var i = 0

    while (i < size) {
        val node = this[i]
        val messages = getMessages(node)

        // Check if this node contains TOOL role messages
        val isToolNode = messages.any { it.role == MessageRole.TOOL }

        if (isToolNode && result.isNotEmpty()) {
            // Find the previous ASSISTANT node
            val lastIndex = result.lastIndex
            val lastNode = result[lastIndex]
            val lastMessages = getMessages(lastNode)
            val isAssistantNode = lastMessages.any { it.role == MessageRole.ASSISTANT }

            if (isAssistantNode) {
                // Collect all ToolResults from the TOOL node
                val toolResults = messages.flatMap { msg ->
                    msg.parts.filterIsInstance<UIMessagePart.ToolResult>()
                }

                // Update the ASSISTANT node's messages by merging ToolResults
                val updatedMessages = lastMessages.map { assistantMsg ->
                    if (assistantMsg.role != MessageRole.ASSISTANT) return@map assistantMsg

                    val updatedParts = assistantMsg.parts.map { part ->
                        when (part) {
                            is UIMessagePart.Tool -> {
                                if (!part.isExecuted) {
                                    val matchingResult = toolResults.find { it.toolCallId == part.toolCallId }
                                    if (matchingResult != null) {
                                        part.copy(
                                            output = listOf(
                                                UIMessagePart.Text(
                                                    json.encodeToString(matchingResult.content)
                                                )
                                            )
                                        )
                                    } else part
                                } else part
                            }

                            is UIMessagePart.ToolCall -> {
                                val matchingResult = toolResults.find { it.toolCallId == part.toolCallId }
                                if (matchingResult != null) {
                                    UIMessagePart.Tool(
                                        toolCallId = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.arguments,
                                        output = listOf(
                                            UIMessagePart.Text(
                                                json.encodeToString(matchingResult.content)
                                            )
                                        ),
                                        approvalState = part.approvalState,
                                        metadata = part.metadata
                                    )
                                } else {
                                    UIMessagePart.Tool(
                                        toolCallId = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.arguments,
                                        output = emptyList(),
                                        approvalState = part.approvalState,
                                        metadata = part.metadata
                                    )
                                }
                            }

                            else -> part
                        }
                    }
                    assistantMsg.copy(parts = updatedParts.toSortedMessageParts())
                }

                result[lastIndex] = setMessages(lastNode, updatedMessages)
                // Skip the TOOL node (don't add it to result)
                i++
                continue
            }
        }

        // For non-TOOL nodes, migrate their internal tool parts
        val migratedMessages = messages.migrateToolMessages()
        result.add(setMessages(node, migratedMessages))
        i++
    }

    return result
}

/**
 * 输出是否因 **token 上限**被截断（「模型说到一半被上限掐断」，非网关断流）。
 *
 * 三家 provider 各自命名 + 常见中转别名：OpenAI chat/completions 用 `length`，
 * Anthropic 用 `max_tokens`，Responses API 用 `max_output_tokens`，Google 用 `MAX_TOKENS`，
 * 部分兼容层写作 `token_limit`。统一在此判定，避免各调用点各写一份字符串比较而漂移。
 */
fun isOutputLimitTruncation(finishReason: String?): Boolean = when (finishReason?.lowercase()) {
    "length", "max_tokens", "max_output_tokens", "token_limit" -> true
    else -> false
}

/**
 * 输出是否被**内容安全策略**掐断（OpenAI `content_filter`、Anthropic `refusal`、
 * Google `SAFETY` / `PROHIBITED_CONTENT` / `RECITATION` / `BLOCKLIST` / `SPII`）。
 *
 * 语义上与 [isOutputLimitTruncation] 不同：**续写通常无意义**（同一内容会再次被拦），
 * 故 UI 只提示不提供「继续生成」。
 */
fun isContentFilterTruncation(finishReason: String?): Boolean = when (finishReason?.uppercase()) {
    "CONTENT_FILTER", "REFUSAL", "SAFETY", "PROHIBITED_CONTENT", "RECITATION", "BLOCKLIST", "SPII" -> true
    else -> false
}

@Serializable
data class MessageChunk(
    val id: String,
    val model: String,
    val choices: List<UIMessageChoice>,
    val usage: TokenUsage? = null,
    /**
     * **显式结束**标记：provider 明确告知本轮输出已完结（`[DONE]` / `message_stop` /
     * `response.completed` / 带 `finishReason` 的末块）。
     *
     * 为什么需要：各家 SSE 的「正常结束」与「连接被提前关闭」在**流层面无法区分**——
     * 两者都只是 `close()`，下游一律当成功收尾。中转站断流（裸 EOF）因此被静默吞掉：
     * 用户看到戛然而止的回答，既没有提示也无法自动续写（见 [UIMessage.finishIncomplete]）。
     *
     * 约定：`explicitEnd = true` 的 chunk 只作标记用，`choices` 为空、`id`/`model` 无意义，
     * 消费方（[handleMessageChunk]）对空 choices 原样返回、不产生副作用。
     */
    val explicitEnd: Boolean = false,
)

/**
 * 流终止标记：唯一的 `explicitEnd = true` 空 chunk。
 *
 * provider 在**显式结束**处发送它，随后关流；`onClosed`（无显式结束）则不发 ⇒ 消费方
 * 据「流结束了但没见到标记」判定裸断流。见 [MessageChunk.explicitEnd]。
 */
fun terminalChunk(): MessageChunk = MessageChunk(
    id = "",
    model = "",
    choices = emptyList(),
    explicitEnd = true,
)

@Serializable
data class UIMessageChoice(
    val index: Int,
    val delta: UIMessage?,
    val message: UIMessage?,
    val finishReason: String?
)
