package app.androidrik.rikkahub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val conversationModeInjectionIds: Set<Uuid> = emptySet(),
    val conversationLorebookIds: Set<Uuid> = emptySet(),
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val workspaceCwd: String? = null,
)

interface MessageTransformer {
    /**
     * 消息转换器，用于对消息进行转换
     *
     * 对于输入消息，消息会转换被提供给API模块
     *
     * 对于输出消息，会对消息输出chunk进行转换
     */
    suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    /**
     * 本 transformer 在**输出**里标记的"易变区段"（时间、占位符替换值这类每轮都不同的片段）。
     *
     * 计数侧将至少 16 字符的变化窗口扩展到完整 tokenizer 边界，稳定块查缓存，变化块直接计数。
     * 返回与 [transformed] 等长、按消息索引对齐的 UTF-16 字符区间；坐标为 Text parts 用单个换行
     * 拼接后的正文，不含附件或工具输出。`null`（默认）= 本转换器未提供优化标注，并不证明内容稳定。
     *
     * 不实现它不会影响任何现有 transformer：默认等价于"没有易变区段"。
     */
    fun volatileSpans(
        ctx: TransformerContext,
        original: List<UIMessage>,
        transformed: List<UIMessage>,
    ): List<List<IntRange>>? = null
}

interface InputMessageTransformer : MessageTransformer

interface OutputMessageTransformer : MessageTransformer {
    /**
     * 一个视觉的转换，例如转换think tag为reasoning parts
     * 但是不实际转换消息，因为流式输出需要处理消息delta chunk
     * 不能还没结束生成就transform，因此提供一个visualTransform
     */
    suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    /**
     * 消息生成完成后调用
     */
    suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    workspaceCwd: String? = null,
    onTransformerTiming: ((index: Int, name: String, nanos: Long) -> Unit)? = null,
): List<UIMessage> {
    val ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        processingStatus = processingStatus,
        workspaceCwd = workspaceCwd,
    )
    return transformsCollectingVolatileSpans(
        ctx = ctx,
        transformers = transformers,
        onTransformerTiming = onTransformerTiming,
        collectAnnotations = false,
    ).messages
}

/** [transforms] 的带易变区段版本：输出消息 + 每条消息内已合并的易变区间。 */
class TransformedMessages(
    val messages: List<UIMessage>,
    /** 与 messages 等长；无标记的消息是空列表。 */
    val volatileSpans: List<List<IntRange>>,
)

suspend fun List<UIMessage>.transformsCollectingVolatileSpans(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    conversationModeInjectionIds: Set<Uuid> = emptySet(),
    conversationLorebookIds: Set<Uuid> = emptySet(),
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    workspaceCwd: String? = null,
    onTransformerTiming: ((index: Int, name: String, nanos: Long) -> Unit)? = null,
): TransformedMessages = transformsCollectingVolatileSpans(
    ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        conversationModeInjectionIds = conversationModeInjectionIds,
        conversationLorebookIds = conversationLorebookIds,
        processingStatus = processingStatus,
        workspaceCwd = workspaceCwd,
    ),
    transformers = transformers,
    onTransformerTiming = onTransformerTiming,
)

private suspend fun List<UIMessage>.transformsCollectingVolatileSpans(
    ctx: TransformerContext,
    transformers: List<MessageTransformer>,
    onTransformerTiming: ((index: Int, name: String, nanos: Long) -> Unit)? = null,
    collectAnnotations: Boolean = true,
): TransformedMessages {
    var result = this
    var spans: List<List<IntRange>> = List(result.size) { emptyList() }
    transformers.forEachIndexed { index, transformer ->
        val before = result
        if (onTransformerTiming == null) {
            result = transformer.transform(ctx, before)
        } else {
            val started = System.nanoTime()
            try {
                result = transformer.transform(ctx, before)
            } finally {
                onTransformerTiming(index, transformer.javaClass.simpleName, System.nanoTime() - started)
            }
        }
        if (!collectAnnotations) return@forEachIndexed
        // Match by message id, not list index: transformers may insert/delete/reorder messages.
        val previous = before.withIndex().associateBy { it.value.id }
        val declared = runCatching { transformer.volatileSpans(ctx, before, result) }.getOrNull()
            ?.takeIf { it.size == result.size }
        spans = result.mapIndexed { outputIndex, output ->
            val prior = previous[output.id]
            val retained = prior?.let {
                val oldRanges = spans.getOrElse(it.index) { emptyList() }
                if (oldRanges.isEmpty()) emptyList() else
                    rebaseVolatileRanges(it.value.countingBody(), output.countingBody(), oldRanges)
            }.orEmpty()
            val extra = declared?.get(outputIndex).orEmpty()
            if (retained.isEmpty() && extra.isEmpty()) emptyList() else
                mergeVolatileRanges(retained + extra, output.countingBody().length)
        }
    }
    return TransformedMessages(result, spans)
}

suspend fun List<UIMessage>.visualTransforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.visualTransform(ctx, acc)
        } else {
            acc
        }
    }
}

suspend fun List<UIMessage>.onGenerationFinish(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.onGenerationFinish(ctx, acc)
        } else {
            acc
        }
    }
}
