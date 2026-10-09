package app.androidrik.rikkahub.data.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.util.ToolSchemaSize
import app.androidrik.rikkahub.data.ai.prompts.isCompactionCheckpointPreamble
import app.androidrik.rikkahub.data.ai.transformers.MessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.collectInjections
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.data.ai.transformers.transforms
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.CardSlot
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.estimateTokens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.uuid.Uuid

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
    /** system 人设正文（systemPrompt 或会话级覆盖；未拆分的兜底形态） */
    SYSTEM,

    /** #1542 Tier B：角色卡的单个槽位（system 块化的拆分形态） */
    CARD_SLOT,

    /** 注入的记忆（buildMemoryPrompt 产物） */
    MEMORY,

    /**
     * system 正文内的**工具引导文本**（`tool.systemPrompt()` 产物，如技能清单）。
     *
     * ⚠️ 与 [TOOL_SCHEMA] 是两件事：本段**在 system 消息里**，而 [TOOL_SCHEMA] 是请求的
     * **顶层 `tools` 数组**（`description`+`parameters`）。全仓仅少数工具提供前者。
     */
    TOOL_GUIDANCE,

    /**
     * 请求的顶层 `tools` 数组（实发的 `description` + `parameters`），**不在任何消息里**。
     *
     * 见 `docs/peer-apps-benchmark.md` §6.12 末节：这一段此前完全没被预览统计，
     * 是「预览估算远低于实发」的主因（实测过的场景里 MCP schema ≈33K token）。
     */
    TOOL_SCHEMA,

    /** 渲染提示（TikZ / 数学公式引导） */
    RENDER_HINT,

    /** 压缩检查点合成消息（见 `docs/design-auto-compaction.md`） */
    COMPRESSION_CHECKPOINT,

    /**
     * 工具输出（`UIMessagePart.Tool.output`）。
     *
     * ⚠️ 此前**完全不计**：预览的正文只取顶层 `Text` part，而工具输出是**独立的一类 part**，
     * 其文本嵌在 `Tool.output` 里。fork 单条工具输出上限 32KB，一次 agent 会话里几十次
     * 调用即可超过对话正文——是「预览远低于实发」的第二大来源
     * （`ContextLength.estimateTokenCount` 一直是计入的，故两处口径此前不一致）。
     *
     * 在**真实请求**里，工具结果不是拼进 assistant 消息，而是各自成为独立的 `tool` 角色
     * 消息 ⇒ 这里**每条输出一段**，与实发的消息序列对应。
     */
    TOOL_RESULT,

    /** 非 SYSTEM 的注入消息（世界书/模式注入落到 TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH） */
    INJECTION,

    /** 普通对话历史（可能已被 limitContext 截断） */
    CHAT_HISTORY,
}

/**
 * 分段的**缓存稳定性**分组（华灯借鉴）：一眼看出「哪些内容每轮都在变、因而不利缓存」。
 * 前缀缓存从第一个 token 连续匹配，任何每轮变化的内容都会从它的位置开始作废整段缓存。
 */
enum class PromptSegmentStability {
    /** 跨轮一致（配置级变化才变）：system 人设/角色卡槽位/工具引导/工具 schema/渲染提示/记忆 */
    STABLE,

    /** 事件驱动：出现或消失时改写一次前缀，之后稳定（压缩检查点/条件触发的注入） */
    EVENT,

    /** 每轮都在变（不利缓存）：对话历史/工具输出 */
    TURN,
}

/** 段类型 → 稳定性分组。 */
val PromptSegmentKind.stability: PromptSegmentStability
    get() = when (this) {
        PromptSegmentKind.SYSTEM,
        PromptSegmentKind.CARD_SLOT,
        PromptSegmentKind.TOOL_GUIDANCE,
        PromptSegmentKind.TOOL_SCHEMA,
        PromptSegmentKind.RENDER_HINT,
        PromptSegmentKind.MEMORY,
             -> PromptSegmentStability.STABLE

        PromptSegmentKind.COMPRESSION_CHECKPOINT,
        PromptSegmentKind.INJECTION,
             -> PromptSegmentStability.EVENT

        PromptSegmentKind.CHAT_HISTORY,
        PromptSegmentKind.TOOL_RESULT,
             -> PromptSegmentStability.TURN
    }

/**
 * 预览中的一段。
 *
 * @param label 该段的来源名（如「系统提示词」「对话历史」），由调用方传入本地化文案
 * @param isSynthetic 是否为请求期合成（不落库）——标出以免误以为会写进会话
 * @param tokens token 估算（与记忆注入同一套启发式，见 `ContextLength.kt`）
 * @param source 该段的来源条目（**仅注入段**有值，Tier 4 归因）；null = 无处可跳
 * @param location 该段在**请求体**中的位置（消息序列 vs 顶层工具字段）。UI 据此提示
 *   「不在对话里」，避免把顶层 `tools` 误读成某条消息的一部分。
 */
data class PromptSegment(
    val kind: PromptSegmentKind,
    val label: String,
    val role: String,
    val content: String,
    val isSynthetic: Boolean,
    val tokens: Int,
    val source: PromptSegmentSource? = null,
    val location: PromptSegmentLocation = PromptSegmentLocation.MESSAGE,
    /** 缓存稳定性分组（由 [kind] 派生，默认即可） */
    val stability: PromptSegmentStability = kind.stability,
)

/** 该段在**请求体**里的落点（决定它是否属于对话消息序列）。 */
enum class PromptSegmentLocation {
    /** 某条消息的内容（system 正文 / 历史 / 注入消息） */
    MESSAGE,

    /**
     * 请求的**顶层字段**，不属于任何消息。
     *
     * 目前只有工具 schema（`tools`）——它每条请求都发、但不在 system 消息里。
     */
    TOP_LEVEL_FIELD,
}

/** 注入段的来源条目——「这段是从哪条注入来的」，供预览页跳转到其编辑处。 */
data class PromptSegmentSource(
    /** 条目 id（`Uuid` 的字符串形式，跨层传递不引入 ai 模块依赖） */
    val entryId: String,
    /** 条目名（可能为空——条目允许无名；UI 侧再兜底显示「未命名」） */
    val name: String,
    /** 承载它的容器类型 */
    val origin: PromptSegmentOrigin,
    /** 容器 id：预设 id（[PromptSegmentOrigin.PRESET]）或世界书 id（[PromptSegmentOrigin.LOREBOOK]） */
    val containerId: String,
    /** 容器名（预设名 / 世界书名），供跳转按钮的提示文案 */
    val containerName: String,
)

/** 注入条目的承载容器：两种来源的编辑页不同，UI 据此选跳转目标。 */
enum class PromptSegmentOrigin { PRESET, LOREBOOK }

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
    /** #1542 Tier B：system 块化后的细分来源标签 */
    val memory: String = "",
    /** system 正文内的工具引导文本（`tool.systemPrompt()`） */
    val toolGuidance: String = "",
    /** 请求顶层 `tools` 数组（实发 schema）；支持 `%1$d`（工具数）与 `%2$d`（估算 token） */
    val toolSchema: String = "",
    /** 工具输出的段名；`%1$s` = 工具名 */
    val toolResult: String = "",
    val renderHint: String = "",
    /**
     * 非文本 part 的占位提示；四个 `%d` 依次为图片/文档/音频/视频数量。
     * 空串 = 不追加该提示。
     */
    val attachmentNote: String = "",
    /** 卡槽块的具体槽名（key = 槽位）。空 map = 卡槽块退化为统一「角色卡」标签 */
    val cardSlotNames: Map<CardSlot, String> = emptyMap(),
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

    // Tier 4：一次收集（与真实链同参），既用于 system 区插段，也用于把合并注入拆回逐条。
    val injections = collectInjections(
        messages = windowMessages,
        assistant = assistant,
        modeInjections = settings.modeInjections,
        lorebooks = settings.lorebooks,
        conversationModeInjectionIds = conversation.modeInjectionIds,
        conversationLorebookIds = conversation.lorebookIds,
        lorebookBudgetTokens = settings.lorebookInjectBudgetTokens,
        presetEntryIds = settings.promptPresets
            .filter { it.enabled && assistant.promptPresetIds.contains(it.id) }
            .flatMap { it.entries }
            .map { it.id }
            .toSet(),
    )
    val byPosition = injections.groupBy { it.position }
    val contentById = injections.associate { it.id to it.content }
    val sourceById = buildSourceById(settings)

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

    val segments = transformed.flatMap { message ->
        val kind = message.segmentKind()
        val content = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        // 非文本 part（图片/文档/音视频）此前**一律计 0**，而实发时它们会经 FileEncoder
        // 编码进请求（图片→JPEG base64，是一块很实的开销）。这里在段尾追加一行可见占位，
        // 使「有附件却显示 0」不再被误读成「附件不花钱」——
        // 精确体积依赖编码参数，本页不猜（见 §6.12 末节）。
        val attachmentNote = message.attachmentNote(labels)
        val contentWithNote = if (attachmentNote.isNullOrBlank()) content else {
            if (content.isBlank()) attachmentNote else "$content\n$attachmentNote"
        }
        // #1542 Tier B：system 消息按**块来源**细分（人设/卡槽/记忆/工具引导/渲染提示），
        // 与真实请求共用 buildSystemBlocks（同参）⇒ 归因与发送内容一致。
        // 其余消息（检查点/注入/历史）维持整条一段（注入段再由 Tier 4 拆回逐条）。
        val messageSegments: List<PromptSegment> = if (kind != PromptSegmentKind.SYSTEM) {
            val single = PromptSegment(
                kind = kind,
                label = when (kind) {
                    PromptSegmentKind.COMPRESSION_CHECKPOINT -> labels.checkpoint
                    PromptSegmentKind.INJECTION -> labels.injection
                    PromptSegmentKind.CHAT_HISTORY -> labels.history
                    else -> labels.system
                },
                role = message.role.name.lowercase(),
                content = contentWithNote,
                isSynthetic = message.isSynthetic,
                // token 只按 Text 段计：占位行是给人看的提示，不冒充被漏算的附件体积
                tokens = estimateTokens(content),
            )
            // Tier 4：合成注入消息是多条 `joinToString("\n")` 的合并体，按贡献者 id 拆回逐条，
            // 使预览能归因到具体条目（否则三条世界书条目表现为一段文本，无从跳转）。
            val own = if (kind == PromptSegmentKind.INJECTION) {
                splitMergedInjectionSegment(
                    annotations = message.annotations,
                    fallback = single,
                    contentById = contentById,
                    sourceById = sourceById,
                )
            } else {
                listOf(single)
            }
            // 工具输出紧随其所属消息之后（实发时它们是独立的 tool 角色消息，故各自成段）。
            own + toolResultSegments(message, labels)
        } else {
            // #1542 Tier B 修复：BEFORE/AFTER 注入由 PromptInjectionTransformer 在
            // transformer 链里**合并进 system 本体**，而 buildSystemBlocks 的 base 块
            // 不含它们——若只用 base 块分段，这部分内容会从预览里消失（实测回归）。
            // 修复：用与真实链相同的 collectInjections 参数取出系统区注入，
            // 作为 INJECTION 段插到 base 块前/后（顺序与 applyInjections 的合并一致）。
            val beforeSegments = (byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT] ?: emptyList())
                .map { entry -> injectionSegment(entry, labels.injection, "system", sourceById) }
            val afterSegments = (byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT] ?: emptyList())
                .map { entry -> injectionSegment(entry, labels.injection, "system", sourceById) }
            beforeSegments + systemBlocksToSegments(
                blocks = buildSystemBlocks(
                    assistant = assistant,
                    settings = settings,
                    model = model,
                    messages = windowMessages,
                    memories = memories,
                    tools = tools,
                    // 与 buildInternalMessages 同参：会话级覆盖口径一致
                    conversationSystemPrompt = conversation.customSystemPrompt,
                    // 会话级「不使用记忆」同样要作用到预览——预览与实际不一致是本仓明确防的坑
                    memoryDisabled = conversation.memoryDisabled,
                ),
                labels = labels,
                role = message.role.name.lowercase(),
            ) + afterSegments
        }
        // 投影无损校验（华灯投影原则）：每条实发消息必须产出至少一段——空段意味着
        // 预览**静默丢失**该消息（实测过 buildSystemBlocks 漏 BEFORE/AFTER 注入的回归形态）。
        // 失败即报错，绝不静默降级。
        require(messageSegments.isNotEmpty()) {
            "Prompt projection lost a message (kind=$kind, role=${message.role.name}, id=${message.id})"
        }
        messageSegments
    }

    // 请求顶层字段：工具 schema（`tools`）。**不属于任何消息**——与 `messages` 平级、
    // 每条请求都发。此前完全没被统计，是「预览估算远低于实发」的主因
    // （实测场景 MCP ≈33K token，见 docs/peer-apps-benchmark.md §6.12 末节）。
    // 放在段首：它逻辑上先于对话内容被 provider 读取，也提醒用户「对话之外还有内容」。
    val toolSegment = toolsToSegment(
        tools = tools,
        providerType = model.findProvider(settings.providers, checkOverwrite = false),
        json = JsonInstant,
        labels = labels,
    )
    val allSegments = if (toolSegment == null) segments else listOf(toolSegment) + segments

    return PromptInspection(
        segments = allSegments,
        totalTokens = allSegments.sumOf { it.tokens },
        contextMessageLimit = assistant.contextMessageLimit,
        // 按**消息**计而不是按段计：一条消息现在可能产出多个段（工具 schema / 注入拆条 /
        // 每条工具输出各一段），按段计会重复统计，且可能超过 totalMessageCount（误导）。
        sentHistoryCount = transformed.count {
            it.segmentKind() == PromptSegmentKind.CHAT_HISTORY ||
                it.segmentKind() == PromptSegmentKind.COMPRESSION_CHECKPOINT
        },
        totalMessageCount = windowMessages.size,
    )
}

/**
 * 工具输出 → 预览段（**纯函数**，便于 JVM 单测）。
 *
 * 为什么必须单列：`UIMessagePart.Tool.output` 是**独立的一类 part**，其文本嵌在 `output`
 * 里，而预览此前只取顶层 `Text` ⇒ **整条工具输出计 0**。fork 单条工具输出上限 32KB，
 * 一次 agent 会话里几十次调用即可超过对话正文（`ContextLength.estimateTokenCount`
 * 一直是计入的 ⇒ 两处口径此前不一致）。
 *
 * 为什么**每条一段**而非合并成一段：在真实请求里工具结果不是拼进 assistant 消息，
 * 而是各自成为独立的 `tool` 角色消息 ⇒ 一条一段才与实发的消息序列对应。
 *
 * 只取 `Text` 子 part，与 [app.androidrik.rikkahub.utils.estimateTokenCount] 同口径
 * （工具输出里的图片等非文本内容不计，同全页处理）。
 *
 * @return 无已执行工具输出时返回空列表（不产生空段）
 */
internal fun toolResultSegments(
    message: UIMessage,
    labels: PromptInspectionLabels,
): List<PromptSegment> = message.parts
    .filterIsInstance<UIMessagePart.Tool>()
    .filter { it.isExecuted }
    .mapNotNull { tool ->
        val text = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        // 跳过无文本输出的工具（如仅返回图片）：金额计 0，成段只会制造噪音
        if (text.isBlank()) return@mapNotNull null
        PromptSegment(
            kind = PromptSegmentKind.TOOL_RESULT,
            label = labels.toolResult.format(tool.toolName),
            role = "tool",
            content = text,
            isSynthetic = message.isSynthetic,
            tokens = estimateTokens(text),
        )
    }

/**
 * 非文本 part 的可见占位（图片/文档/音视频）。空串 = 无此类 part。
 *
 * 为什么需要：这些 part 此前在预览里**一律计 0 token**，而实发时它们会被编码进请求
 * （图片走 `FileEncoder` 压 JPEG 再 base64）。不给提示的话，「附件显示 0」会被误读成
 * 「附件不花钱」——那是比数字不准更糟的误导。**本页不猜精确体积**（依赖编码参数与图片内容），
 * 只如实说明「有几个、未计入」。
 */
private fun UIMessage.attachmentNote(labels: PromptInspectionLabels): String? {
    if (labels.attachmentNote.isEmpty()) return null
    val images = parts.count { it is UIMessagePart.Image }
    val documents = parts.count { it is UIMessagePart.Document }
    val audios = parts.count { it is UIMessagePart.Audio }
    val videos = parts.count { it is UIMessagePart.Video }
    val total = images + documents + audios + videos
    if (total == 0) return null
    return labels.attachmentNote.format(images, documents, audios, videos)
}

/**
 * 一条注入实体 → 一个预览段（带来源）。
 *
 * `kind` 固定为 INJECTION：这里只服务 system 区 BEFORE/AFTER 那一路（非 system 位置
 * 走 [splitMergedInjectionSegment] 从合并消息里拆）。
 */
private fun injectionSegment(
    entry: PromptInjection,
    fallbackLabel: String,
    role: String,
    sourceById: Map<Uuid, PromptSegmentSource>,
): PromptSegment = PromptSegment(
    kind = PromptSegmentKind.INJECTION,
    label = sourceById[entry.id]?.name?.takeIf { it.isNotBlank() } ?: fallbackLabel,
    role = role,
    content = entry.content,
    isSynthetic = true,
    tokens = estimateTokens(entry.content),
    source = sourceById[entry.id],
)

/**
 * 把一条合并注入消息按来源注解拆回逐条段（**纯函数**，便于 JVM 单测）。
 *
 * 何时不拆：①消息没有来源注解（旧数据或 system 区那一路）；②任一贡献者 id 在当前
 * 注入集合里查不到内容。**宁可整段显示、不可少显示**——拆的前提是能完整还原，
 * 半拆会让预览与实际请求内容不符，比不拆更糟（该页存在的意义就是「与自己发出的一致」）。
 */
internal fun splitMergedInjectionSegment(
    annotations: List<UIMessageAnnotation>,
    fallback: PromptSegment,
    contentById: Map<Uuid, String>,
    sourceById: Map<Uuid, PromptSegmentSource>,
): List<PromptSegment> {
    val ids = annotations
        .filterIsInstance<UIMessageAnnotation.InjectionSources>()
        .flatMap { it.entryIds }
    if (ids.isEmpty()) return listOf(fallback)
    val contents = ids.map { contentById[it] ?: return listOf(fallback) }
    return ids.zip(contents).map { (id, content) ->
        val source = sourceById[id]
        fallback.copy(
            label = source?.name?.takeIf { it.isNotBlank() } ?: fallback.label,
            content = content,
            tokens = estimateTokens(content),
            source = source,
        )
    }
}

/**
 * 注入条目 id → 来源信息（承载容器 + 条目名）。
 *
 * 覆盖面：预设条目（[Settings.promptPresets]，含未启用的——预览只展示启用的那些，
 * 但映射表多几项无害）与世界书条目（[Settings.lorebooks]）。**同名 id 先见者胜**：
 * 预设内部本就要求 id 唯一，跨容器重复属异常数据（`reconcileEntries` 已按先到先得处理）。
 */
private fun buildSourceById(settings: Settings): Map<Uuid, PromptSegmentSource> {
    val map = mutableMapOf<Uuid, PromptSegmentSource>()
    settings.promptPresets.forEach { preset ->
        preset.entries.forEach { entry ->
            map.getOrPut(entry.id) {
                PromptSegmentSource(
                    entryId = entry.id.toString(),
                    name = entry.name,
                    origin = PromptSegmentOrigin.PRESET,
                    containerId = preset.id.toString(),
                    containerName = preset.name,
                )
            }
        }
    }
    settings.lorebooks.forEach { book ->
        book.entries.forEach { entry ->
            map.getOrPut(entry.id) {
                PromptSegmentSource(
                    entryId = entry.id.toString(),
                    name = entry.name,
                    origin = PromptSegmentOrigin.LOREBOOK,
                    containerId = book.id.toString(),
                    containerName = book.name,
                )
            }
        }
    }
    return map
}

private fun UIMessage.segmentKind(): PromptSegmentKind = when {
    role == MessageRole.SYSTEM -> PromptSegmentKind.SYSTEM
    isCompactionCheckpoint -> PromptSegmentKind.COMPRESSION_CHECKPOINT
    // 非 SYSTEM 的合成消息 = 注入（世界书/模式注入落到对话位置）
    isSynthetic -> PromptSegmentKind.INJECTION
    else -> PromptSegmentKind.CHAT_HISTORY
}

/**
 * system 的块列表 → 预览段（**纯函数**，便于 JVM 单测——`inspectPrompt` 本体需要
 * Android Context 跑 transformer 链）。PERSONA 块保留 SYSTEM kind（兜底形态），
 * 其余块按来源映射到细分 kind；卡槽块的 label 用调用方传入的本地化槽名。
 */
internal fun systemBlocksToSegments(
    blocks: List<SystemBlock>,
    labels: PromptInspectionLabels,
    role: String,
): List<PromptSegment> = blocks
    // 跳过**空的工具引导块**：`buildSystemBlocks` 为每个工具各加一个 TOOL 块，而绝大多数
    // 工具不提供 `systemPrompt`（默认空串）⇒ 实测 13 个工具里有 11 个空块，若照原样成段
    // 会渲染成一串「工具引导 ≈0」的噪音。
    //
    // 注意：这里只影响**预览展示**，不改发送内容——空块在 system 正文里仍贡献一个换行
    // （逐字节契约见 buildSystemBlocks 的 kdoc，由 PromptAssemblyTest 锁住）。
    .filterNot { it.source == SystemBlockSource.TOOL && it.content.isBlank() }
    .map { block ->
    val (kind, label) = when (block.source) {
        SystemBlockSource.PERSONA -> PromptSegmentKind.SYSTEM to labels.system
        SystemBlockSource.CARD_SLOT -> PromptSegmentKind.CARD_SLOT to (
            labels.cardSlotNames[block.cardSlot] ?: labels.system
            )
        SystemBlockSource.MEMORY -> PromptSegmentKind.MEMORY to labels.memory
        // TOOL 块 = `tool.systemPrompt()` 产物，**在 system 正文里**（引导文本，不是 schema）；
        // 实发的顶层 tools 数组另由 [toolsToSegment] 成段（见 TOOL_SCHEMA 的注释）。
        SystemBlockSource.TOOL -> PromptSegmentKind.TOOL_GUIDANCE to (
            labels.toolGuidance.ifBlank { labels.toolSchema }
            )
        SystemBlockSource.DIAGRAM_HINT, SystemBlockSource.MATH_HINT ->
            PromptSegmentKind.RENDER_HINT to labels.renderHint
    }
    PromptSegment(
        kind = kind,
        label = label,
        role = role,
        content = block.content,
        isSynthetic = true,
        tokens = estimateTokens(block.content),
    )
}

/**
 * 工具 schema → 单个预览段（**纯函数**，便于 JVM 单测）。
 *
 * 这一段**不属于任何消息**：`tools` 是请求体的顶层字段（与 `messages` 平级），
 * 每条请求都会发。此前预览完全不统计它 ⇒ 实测过的场景（MCP 54 工具 ≈33K token、
 * 占 4 万总量约 80%）在预览里是 0，这是「预览估算远低于实发」的主因
 * （`docs/peer-apps-benchmark.md` §6.12 末节）。
 *
 * `content` 用**真实下发的 JSON**（经 provider 形状变换与 `ensureArrayItems`），
 * 使展示与实发同源；token 数由调用方用 [ToolSchemaSize] 的同口径估算传入
 * （它比字符启发式更准，因为绑定了 provider 的实际包装）。
 *
 * @return 无工具时返回 null（不产生空段）
 */
internal fun toolsToSegment(
    tools: List<Tool>,
    providerType: ProviderSetting?,
    json: Json,
    labels: PromptInspectionLabels,
): PromptSegment? {
    if (tools.isEmpty()) return null
    val tokens = ToolSchemaSize.estimate(tools, providerType, json)
    // 逐条渲染真实 JSON（与 provider 发送前的变换一致），供用户核对 schema 原文
    val content = tools.joinToString("\n") { tool ->
        ToolSchemaSize.wireJson(tool, providerType, json)
    }
    return PromptSegment(
        kind = PromptSegmentKind.TOOL_SCHEMA,
        label = labels.toolSchema.format(tools.size, tokens),
        role = "tools",
        content = content,
        isSynthetic = true,
        tokens = tokens,
        location = PromptSegmentLocation.TOP_LEVEL_FIELD,
    )
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
