package app.androidrik.rikkahub.data.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.prompts.isCompactionCheckpointPreamble
import app.androidrik.rikkahub.data.ai.transformers.MessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.transforms
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.utils.estimateTokens

/**
 * 拼装预览（只读）：把「这次请求实际会发什么」按**来源**拆开展示。
 *
 * 动机见 `docs/peer-apps-benchmark.md` §6.7 ③：社区里被明确称赞的能力是「给出实际拼装后的提示词，
 * 用来优化预设」。本仓此前唯一可见途径是 `LogPage` 里的原始 HTTP JSON——需先开两个开关、
 * 是 provider 协议形状（看不出哪段来自哪）、且必须真发一次请求才能看。
 *
 * **不漂移的关键**：本文件不自己组装消息，而是调用 [buildInternalMessages]（与真实请求
 * `GenerationHandler.generateInternal` 同一个函数），并施加**同一个** transformer 链。
 * 因此「预览看到的」与「实际发出的」在结构上一致。
 */

/** 预览中一段的可读来源标签。 */
enum class PromptSegmentKind {
    /** system 消息正文（systemPrompt + 记忆 + 工具 schema + 渲染提示） */
    SYSTEM,

    /** 压缩检查点合成消息（见 `docs/design-auto-compaction.md`） */
    COMPRESSION_CHECKPOINT,

    /** 非 SYSTEM 的注入消息（世界书/模式注入落到 TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH） */
    INJECTION,

    /** 普通对话历史（可能已被 limitContext 截断） */
    CHAT_HISTORY,
}

/**
 * 预览中的一段。
 *
 * @param label 该段的来源名（如「系统提示词」「对话历史」），由调用方传入本地化文案
 * @param isSynthetic 是否为请求期合成（不落库）——标出以免误以为会写进会话
 * @param tokens token 估算（与记忆注入同一套启发式，见 `ContextLength.kt`）
 */
data class PromptSegment(
    val kind: PromptSegmentKind,
    val label: String,
    val role: String,
    val content: String,
    val isSynthetic: Boolean,
    val tokens: Int,
)

/** 拼装预览结果。 */
data class PromptInspection(
    /** 按发送顺序排列的各段 */
    val segments: List<PromptSegment>,
    /** 各段 token 估算合计（用于对照上下文上限与固定开销） */
    val totalTokens: Int,
    /** 助手配置的上下文条数上限（0 = 不限制）；用于解释历史是否被截断 */
    val contextMessageLimit: Int,
    /**
     * 本次发送的**历史**条数（不含 system 与新增注入；压缩检查点计为 1，因为它代表被压缩的历史）。
     *
     * 刻意不把 system/注入算进来：那些是「额外加的」，与「历史被截掉多少」不是一回事——
     * 混在一起会让空会话显示成「1 条」（其实只有 system），反而误导。
     */
    val sentHistoryCount: Int,
    /** 会话消息总数（与 [sentHistoryCount] 对比可见 limitContext/压缩的裁剪量） */
    val totalMessageCount: Int,
)

/** [PromptInspection] 的本地化标签集合（Inspector 不依赖 Android 资源字符串，便于单测）。 */
data class PromptInspectionLabels(
    val system: String,
    val checkpoint: String,
    val injection: String,
    val history: String,
)

/**
 * 执行一次**只读**拼装：走真实路径同样的 [buildInternalMessages] + transformer 链，
 * 再把结果按来源标注成段。**不落库、不发请求**。
 *
 * @param inputTransformers 必须与 `ChatService` 传给生成的是**同一个链**（含 template /
 *   workspace reminder 等），否则预览会与实际请求不一致。
 */
suspend fun inspectPrompt(
    context: Context,
    settings: Settings,
    assistant: Assistant,
    model: Model,
    conversation: Conversation,
    memories: List<AssistantMemory>,
    tools: List<Tool>,
    inputTransformers: List<MessageTransformer>,
    labels: PromptInspectionLabels,
): PromptInspection {
    val windowMessages = conversation.currentMessages

    // 与真实请求同源：共用 buildInternalMessages（PromptAssembly.kt）
    val internalMessages = buildInternalMessages(
        assistant = assistant,
        settings = settings,
        model = model,
        messages = windowMessages,
        memories = memories,
        tools = tools,
        conversationSystemPrompt = conversation.customSystemPrompt,
        compressionWindow = conversation.compressionWindow(),
    )

    // 与真实请求同一个 transformer 链。processingStatus 传一次性 StateFlow：
    // 默认参数即可满足 transformer 的写入（如 OcrTransformer），预览不展示进度。
    val transformed = internalMessages.transforms(
        transformers = inputTransformers,
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        conversationModeInjectionIds = conversation.modeInjectionIds,
        conversationLorebookIds = conversation.lorebookIds,
        processingStatus = MutableStateFlow(null),
        workspaceCwd = conversation.workspaceCwd,
    )

    val segments = transformed.map { message ->
        val kind = message.segmentKind()
        PromptSegment(
            kind = kind,
            label = when (kind) {
                PromptSegmentKind.SYSTEM -> labels.system
                PromptSegmentKind.COMPRESSION_CHECKPOINT -> labels.checkpoint
                PromptSegmentKind.INJECTION -> labels.injection
                PromptSegmentKind.CHAT_HISTORY -> labels.history
            },
            role = message.role.name.lowercase(),
            content = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
            isSynthetic = message.isSynthetic,
            tokens = estimateTokens(
                message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            ),
        )
    }

    return PromptInspection(
        segments = segments,
        totalTokens = segments.sumOf { it.tokens },
        contextMessageLimit = assistant.contextMessageLimit,
        sentHistoryCount = segments.count {
            it.kind == PromptSegmentKind.CHAT_HISTORY || it.kind == PromptSegmentKind.COMPRESSION_CHECKPOINT
        },
        totalMessageCount = windowMessages.size,
    )
}

private fun UIMessage.segmentKind(): PromptSegmentKind = when {
    role == MessageRole.SYSTEM -> PromptSegmentKind.SYSTEM
    isCompactionCheckpoint -> PromptSegmentKind.COMPRESSION_CHECKPOINT
    // 非 SYSTEM 的合成消息 = 注入（世界书/模式注入落到对话位置）
    isSynthetic -> PromptSegmentKind.INJECTION
    else -> PromptSegmentKind.CHAT_HISTORY
}

/**
 * 该消息是否为压缩检查点合成消息。
 *
 * 复用 [isCompactionCheckpointPreamble]（生产者与判定同源的既有约定），而不是只看
 * `isSynthetic`——后者是 `@Transient`，持久化后会丢失（历史踩坑见
 * `docs/design-auto-compaction.md` 的 ce311156）。
 */
private val UIMessage.isCompactionCheckpoint: Boolean
    get() = isSynthetic &&
        role == MessageRole.USER &&
        parts.any { part -> part is UIMessagePart.Text && part.text.isCompactionCheckpointPreamble() }
