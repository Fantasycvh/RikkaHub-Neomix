package app.androidrik.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.Tool
import app.androidrik.ai.core.merge
import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.CustomBody
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.registry.ModelRegistry
import app.androidrik.ai.ui.isOutputLimitTruncation
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.handleMessageChunk
import app.androidrik.rikkahub.data.ai.prompts.isCompactionCheckpointPreamble
import app.androidrik.rikkahub.data.ai.transformers.InputMessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.MessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.OutputMessageTransformer
import app.androidrik.rikkahub.data.files.FileFolders
import java.io.File
import app.androidrik.rikkahub.data.ai.transformers.onGenerationFinish
import app.androidrik.rikkahub.data.ai.transformers.transforms
import app.androidrik.rikkahub.data.ai.transformers.visualTransforms
import app.androidrik.ai.provider.RetrySetting
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.ai.util.ToolSchemaSize
import app.androidrik.rikkahub.utils.applyPlaceholders
import app.androidrik.rikkahub.utils.estimateTokens
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

/**
 * 空回复补救提示：模型只输出思考、无可见正文也无工具调用时，向随后一次请求追加此提示
 * 要求补出可见回答。仅重试一次；不写入对话历史（Yuihub `8a3a37ac` 同源）。
 */
/**
 * 续写提示（裸断流）：网关/中转在没发终止标记的情况下提前关流。与 [CONTINUE_TRUNCATED_HINT]
 * 语义相同（从停处接着写、不重复、不重新开头），分开只为让日志/诊断能区分成因。
 */
internal const val CONTINUE_INCOMPLETE_HINT =
    "Your previous response ended unexpectedly in the middle (the stream was cut off before a " +
        "proper stop signal). Continue exactly from where it stopped, without repeating any text " +
        "you already produced and without restarting or adding a preamble."

/** 续写提示（撞输出上限）：模型被 max_tokens 掐断，语义同上。 */
internal const val CONTINUE_TRUNCATED_HINT =
    "Your previous response was cut off because it reached the output token limit. Continue " +
        "exactly from where it stopped, without repeating any text you already produced and " +
        "without restarting or adding a preamble."

private const val VISIBLE_ANSWER_RECOVERY_HINT =
    "Your previous response produced no visible output for the user (reasoning only). " +
        "Provide your visible answer now."
/** 翻译输入最大字符数：超长翻译文本曾 API 413 */
private const val MAX_TRANSLATE_CHARS = 200_000

/**
 * 工具调用指纹，用于识别同一批里的**重复调用**（相同工具 + 等价参数）。
 *
 * 参数先解析再**按键名递归排序**后序列化，使键序/空白不同的等价 JSON 归一为同一指纹
 * （`JsonObject.toString()` 保留插入顺序，直接序列化会漏掉键序不同的等价调用）；
 * **toolCallId 不参与**——重复下发的两条 call 恰恰是 id 不同、内容相同
 * （实测 Gemini 系会这样下发，导致 memory_tool create 重复建记忆）。
 */
internal fun toolCallSignature(toolName: String, input: String, json: Json): String {
    val normalized = runCatching {
        canonicalize(json.parseToJsonElement(input.ifBlank { "{}" })).toString()
    }.getOrDefault(input)
    return toolName + "\u0000" + normalized
}

/** 递归排序对象键，保证等价 JSON 的序列化结果一致。 */
private fun canonicalize(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject ->
            JsonObject(
                element.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) }
            )

        is JsonArray ->
            JsonArray(element.map { canonicalize(it) })

        else -> element
    }

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk

    /**
     * 需要让用户看到、但**不属于对话内容**的提示（如工具调用失控护栏停止本轮）。
     *
     * 为什么不写进消息：提示是给用户看的，写进消息会被持久化、并在下一轮作为上下文发给模型，
     * 变成「自己教自己」的噪音。走独立 chunk，由上层转成用户可见的提示。
     */
    data class Notice(
        val notice: GenerationNotice,
    ) : GenerationChunk
}

/** [GenerationChunk.Notice] 的载荷。 */
@Serializable
sealed interface GenerationNotice {
    /** 同一工具同参数反复调用，已提前结束本轮（见 [ToolCallLoopGuard]）。 */
    data class ToolLoopStopped(
        val toolName: String,
        val repeats: Int,
    ) : GenerationNotice

    /**
     * workspace（rootfs）工具的单轮总预算耗尽，已提前结束本轮（见 [WorkspaceTurnBudget]）。
     * 堵的是 LoopGuard 防不住的形态：每步换参数的 workspace 调用，累计输出/步数无上限。
     */
    data class WorkspaceBudgetExhausted(
        val usedChars: Long,
        val budgetChars: Long,
        val steps: Int,
        val by: WorkspaceTurnBudget.ExceedReason,
    ) : GenerationNotice

    /**
     * 无头 run 的重复拒绝达阈值（见 [DenialGuard]）——模型被策略拒绝后换着方式反复撞，
     * 继续跑只会到空响应；提前结束，run 以 `failed(permission_denied)` 收尾。
     */
    data class TooManyDenials(
        val denials: Int,
    ) : GenerationNotice

    /**
     * 上下文预算耗尽（设计 §十二）：轨迹把窗口吃到"剩下的不够产出一轮回答"，
     * 优雅停止本轮（已产生的工具结果照常回填）。
     *
     * 与 [WorkspaceBudgetExhausted] 的区别：那个计「工具输出堆了多少」，
     * 这个计「窗口还剩多少」——前者是成本/体量，后者是硬墙（撞上去 provider 直接报错）。
     */
    data class ContextBudgetExhausted(
        val usedTokens: Int,
        val windowTokens: Int,
    ) : GenerationNotice

    /**
     * **思考退化**（零工具调用的失控形态）——连续纯思考/复述上一步，或单次调用内逐字重复，
     * 已提前结束本轮（见 [ThinkingDegenerationGuard]）。
     *
     * @param cutRule 调用内断流命中的规则；null = 只是调用边界上的计数达阈值
     * @param repeated 断流时的重复次数（相同 delta 次数）或周期长度
     */
    data class ThinkingDegenerationStopped(
        val stalledCalls: Int,
        val restated: Boolean,
        val cutRule: ThinkingDegenerationGuard.Rule?,
        val repeated: Int,
    ) : GenerationNotice
}

/** 无头 run 的策略拒绝文本（拒绝文本契约 §9.2.1：策略终态、明说勿重试）。 */
const val HEADLESS_DENIAL_REASON =
    "Not permitted in this run by its capability envelope. " +
        "Do not retry this tool; proceed without it or finish."

/**
 * 思考退化护栏的**调用内断流信号**（内部用，不当错误）。
 *
 * 为什么用异常：`Flow.collect` 没有「优雅停下」的返回通道——抛出会沿链取消上游
 * （provider 的 SSE 连接随之关闭，不再继续计费）；而它在**紧挨 collect 的内层**被捕获，
 * 因此不会走到外层失败处理：不回滚快照、不触发重试/fallback、不记成错误。
 * 内容照常保留，停止提示由步骤循环发出（[GenerationNotice.ThinkingDegenerationStopped]）。
 */
private object ThinkingDegenerationCut : Exception("thinking degeneration: stream cut by guard")

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    /**
     * @param messages 会话**全量**消息（与 messageNodes 对齐），既是回写基线，也是发送上下文的来源
     * @param compressionWindow 压缩发送窗口的**可重算**描述（检查点 + 边界下标）；null = 无压缩，
     *   直接发送 [messages]。**刻意传描述而非冻结列表**：生成循环里 [messages] 会因工具调用增长，
     *   冻结列表会让模型看不到刚执行的工具结果 → 重复调用（见 [Conversation.CompressionWindow]）。
     *   仅用于构建发给 provider 的上下文——**不可**作为回写基线（否则合成消息会被持久化，
     *   且与 messageNodes 索引错位）
     */
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        compressionWindow: Conversation.CompressionWindow? = null,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<AssistantMemory>? = null,
        /**
         * 会话级「不使用记忆」（隐身对话，见 `docs/design/design-memory-discipline.md` §4.4）：
         * 为真时**不注入记忆块**、且工具装配侧**不装配记忆工具**（两者是同一件事的两半）。
         * 由 ChatService 从 `Conversation.memoryDisabled` 传入；无头 run 恒为 false。
         */
        memoryDisabled: Boolean = false,
        /**
         * **L1 冻结的锚点 id 集合**（`docs/design/design-memory-injection-tiering.md` §5）：
         * 非 null 时只渲染这些锚点（文本仍按 [memories] 实时取）。由 ChatService 从
         * `Conversation.frozenAnchorIds` 传入 —— **本类刻意不去读会话**（它拿不到
         * Conversation，也不该为此长出第二个装配真相源）。
         *
         * 传 null = 未冻结 ⇒ 实时渲染，与改动前逐字节一致。
         */
        frozenAnchorIds: Set<String>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        /**
         * 工具调用失控护栏。由调用方（ChatService）从**会话**上取，使状态跨「审批续跑」累积——
         * 见 [ToolCallLoopGuard] 类注释里真机实测的原因。默认值只服务于单测等场景。
         */
        loopGuard: ToolCallLoopGuard = ToolCallLoopGuard(),
        /**
         * **规则提醒引擎**（护栏家族，见 [RuleEngine]）：连续多次探索类工具调用且零落地时，
         * 在对话尾部注入一段自查提醒。取会话上的实例使效果窗口跨「审批续跑」累积——
         * 挂载理由与 [loopGuard] 完全同款。默认值只服务单测等场景。
         */
        ruleEngine: RuleEngine = RuleEngine(),
        /**
         * **思考退化护栏**（护栏家族第四员，见 [ThinkingDegenerationGuard]）。默认**开启**——
         * 堵的是前三员的前提都落空时的形态：零工具调用的退化（纯思考空转 / 复述上一步 /
         * 单次调用内逐字重复）。挂载理由与 [loopGuard] 同款（挂会话、跨审批续跑累积、
         * 新一轮重置）；调用内断流由本类在流消费处执行。
         */
        thinkingGuard: ThinkingDegenerationGuard = ThinkingDegenerationGuard(),
        /** 前缀分叉诊断的会话键（聊天路径传 conversationId；工具循环每一步都会记录） */
        debugKey: String? = null,
        /**
         * workspace（rootfs）工具的**单轮总预算**。挂载理由与 [loopGuard] 同款（跨审批累积、
         * 新一轮重置），堵的是 LoopGuard 防不住的形态——每步换参数的 workspace 调用。
         * 见 [WorkspaceTurnBudget] 类注释。默认值只服务于单测等场景。
         */
        workspaceBudget: WorkspaceTurnBudget = WorkspaceTurnBudget(),
        /**
         * 无头 run 的**自动拒绝**：`needsApproval=true` 的工具不进入「等用户」状态，
         * 而是直接置为策略拒绝（文本经既有 Denied 通路回给模型，见 §9.2.1）。
         * 配合 [denialGuard] 使用（聊天路径两个都走默认值，行为不变）。
         */
        autoDenyApproval: Boolean = false,
        /** 重复拒绝护栏（仅无头 run 传入；见 [DenialGuard] 类注释的覆盖盲区说明）。 */
        denialGuard: DenialGuard? = null,
        /**
         * 上下文预算守卫（仅无头 run 传入；见 [ContextBudgetGuard]）。
         *
         * 聊天路径不传：它有自动压缩 + `contextMessageLimit` 兜底，再叠一层硬停会改变既有行为。
         */
        contextGuard: ContextBudgetGuard? = null,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        /**
         * 首轮的「续写/补救提示」（只进本请求的系统提示、不写入对话历史）。
         * 手动「继续生成」用它把被截断的消息接着写下去（见 [CONTINUE_INCOMPLETE_HINT]）。
         */
        initialRecoveryHint: String? = null,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        // 跨 provider 轻量降级（fallback 模型链）：主模型整体不可用时切备用模型重跑整轮。
        // 仅限"零输出失败"（重试耗尽后仍无可见内容）——半截输出（GenerationPartialFailureException）
        // 保留草稿不 fallback（#1812 约束，避免丢已计费内容）；取消不 fallback。
        // 备用模型优先级：助手设置 > 全局默认（与主模型 chatModelId 的两级模式一致）
        // 优先级：助手级 > 全局级。助手级 ID 悬空（模型被删/重建导致 ID 失效）时
        // 回退到全局级——否则悬空的助手引用会遮蔽有效的全局配置（用户实测踩坑）。
        val assistantFallbackModel = assistant.fallbackChatModelId?.let { settings.findModelById(it) }
        if (assistant.fallbackChatModelId != null && assistantFallbackModel == null) {
            Log.w(TAG, "assistant fallback model ${assistant.fallbackChatModelId} not found, trying global fallback")
        }
        val fallbackModel = assistantFallbackModel
            ?: settings.fallbackChatModelId?.let { settings.findModelById(it) }
        if (fallbackModel == null &&
            (assistant.fallbackChatModelId != null || settings.fallbackChatModelId != null)
        ) {
            // 助手级与全局级都解析失败：fallback 不生效，必须可见
            Log.w(TAG, "fallback model not found in providers, fallback disabled")
        }
        val fallbackProvider = fallbackModel?.findProvider(settings.providers)
        val fallbackProviderImpl = fallbackProvider?.let { providerManager.getProviderByType(it) }

        suspend fun runLoop(
            targetModel: Model,
            targetProvider: ProviderSetting,
            targetProviderImpl: Provider<ProviderSetting>,
        ) {
            var messages: List<UIMessage> = messages

            // 本轮的工具调用失控护栏：**由调用方传入**（挂在会话上），跨「审批续跑」累积。
            // 这里刻意不新建实例——真机实测每次批准都会重新进入 runLoop（step #0 重来），
            // 自建实例每次都被重置，护栏对审批类工具等于失效。
            // 护栏停止提示（三个来源共用、先到先得）：同签名循环 / workspace 预算耗尽 /
            // 重复拒绝达阈值。设一次，本批结果回填后停本轮（优雅停止）。
            var stopNotice: GenerationNotice? = null

            // 上下文预算的固定开销（system 正文 + 工具 schema）：每轮不变量，故只算一次。
            // 有 usage 锚点时它会被忽略（锚点已包含这些），只在第一步无锚点时才起作用。
            // 用 `tools` 而不是循环内的 `toolsInternal`：后者就是 `buildList { addAll(tools) }`。
            val overheadTokens = if (contextGuard == null) {
                0
            } else {
                runCatching {
                    val providerType = targetModel.findProvider(settings.providers, checkOverwrite = false)
                    estimateTokens(assistant.systemPrompt) +
                        ToolSchemaSize.estimate(tools, providerType, json)
                }.getOrDefault(0)
            }

            // 空回复补救（Yuihub `8a3a37ac` 同源）：多步操作后模型可能只输出思考、
            // 无可见正文也无工具调用——直接结束会让用户看到「思考完就不回复」。
            // 只重试一次；恢复提示只作用于下一次请求的系统提示，不写入对话历史。
            var visibleAnswerRecovered = false
            var pendingRecoveryHint: String? = initialRecoveryHint
            var autoContinuations = 0

            for (stepIndex in 0 until maxSteps) {
                Log.i(TAG, "streamText: start step #$stepIndex (${targetModel.id})")

            val toolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                // 工具集（含记忆工具）已由 ChatToolFactory 装配后经 tools 传入。
                // 早前记忆工具在此处内层叠加，是第二个装配点；集中到 factory 后此处只透传。
                addAll(tools)
            }

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                // 规则提醒：**每一步拼装前**求值 ⇒ 作用于下一步（本仓工具循环每步重拼提示词）。
                // 刻意放在 `if` 内：`reminder()` 有副作用（标记已提醒），算了不发等于白丢一次提醒。
                // 预算比例取会话上的 WorkspaceTurnBudget（撞上限前的那次提醒就靠它）。
                val ruleReminder = ruleEngine.reminder(
                    RuleSnapshot(budgetRatio = workspaceBudget.usageRatio()),
                )
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    compressionWindow = compressionWindow,
                    recoveryHint = pendingRecoveryHint,
                    ruleReminder = ruleReminder,
                    memoryDisabled = memoryDisabled,
                    frozenAnchorIds = frozenAnchorIds,
                    debugKey = debugKey,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = targetModel,
                            assistant = assistant,
                            settings = settings
                        )
                        emit(
                            GenerationChunk.Messages(
                                messages.visualTransforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = targetModel,
                                    assistant = assistant,
                                    settings = settings
                                )
                            )
                        )
                    },
                    transformers = inputTransformers,
                    model = targetModel,
                    providerImpl = targetProviderImpl,
                    provider = targetProvider,
                    tools = toolsInternal,
                    memories = memories ?: emptyList(),
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    degenerationGuard = thinkingGuard,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationModeInjectionIds = conversationModeInjectionIds,
                    conversationLorebookIds = conversationLorebookIds,
                    workspaceCwd = workspaceCwd,
                    // 有 fallback 时压缩主模型重试预算（最多 2 次），快速切备用模型——
                    // 否则连接类故障会先等完整个延迟序列（默认可达 100s+）才降级
                    retryBudgetOverride = if (fallbackModel != null) 2 else null,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = targetModel,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = targetModel,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                // 思考退化护栏（家族第四员）：调用内已断流，或本步属于「纯思考 / 复述上一步」的连续计数
                // 达阈值 ⇒ 记停止提示。**先于**空回复补救与续写判定生效——两者都会再发一次请求，
                // 而对一个已经退化的模型续跑只是重复计费（社区插件的同款取舍：不做 fallback/不自动重试）。
                if (stopNotice == null) {
                    val cut = thinkingGuard.takeCut()
                    val lastAssistant = messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
                    val callVerdict = lastAssistant?.let {
                        thinkingGuard.onCallFinished(
                            reasoning = it.reasoningText(),
                            hasVisibleText = it.hasVisibleTextOutput(),
                            hasToolCalls = it.hasToolCalls(),
                        )
                    }
                    val stalled = callVerdict as? ThinkingDegenerationGuard.Verdict.Stalled
                    if (cut != null || stalled != null) {
                        Log.w(
                            TAG,
                            "streamText: thinking degeneration " +
                                "(cut=${cut?.rule}/${cut?.repeated}, stalled=${stalled?.stalledCalls}, " +
                                "restated=${stalled?.restated}), stopping this turn",
                        )
                        stopNotice = GenerationNotice.ThinkingDegenerationStopped(
                            stalledCalls = stalled?.stalledCalls ?: thinkingGuard.stalledCallCount,
                            restated = stalled?.restated == true,
                            cutRule = cut?.rule,
                            repeated = cut?.repeated ?: 0,
                        )
                    }
                }

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    // 思考退化：不补救、不续写，直接停本轮（提示已备好）
                    stopNotice?.let { stopped ->
                        emit(GenerationChunk.Notice(stopped))
                        break
                    }
                    val last = messages.lastOrNull()
                    // 空回复补救：最后一次请求只产出思考、无可见正文也无工具调用时，
                    // 注入一次性恢复提示再请求一轮（见循环前的注释）
                    val hasVisibleOutput = last?.parts.orEmpty().any { part ->
                        (part is UIMessagePart.Text && part.text.isNotBlank()) ||
                            part is UIMessagePart.Image
                    }
                    if (!hasVisibleOutput && !visibleAnswerRecovered) {
                        visibleAnswerRecovered = true
                        pendingRecoveryHint = VISIBLE_ANSWER_RECOVERY_HINT
                        Log.i(TAG, "streamText: empty visible reply, requesting visible answer once")
                        continue
                    }
                    // 续写（本批新增，见 `docs/peer-apps-benchmark.md` §四.7 D）：输出不完整时
                    // 就地从停处接着写，续写追加到**同一条助手消息**（不新建候选、不重发全轮）。
                    //
                    // 两类成因的策略不同（成本权衡）：
                    // - 裸断流（finishIncomplete）：网关/中转提前切连接，**非模型本意** ⇒ 自动续写。
                    //   每次续写都要重发整个上下文，故设上限（CONTINUE_LIMIT）防「中转一直切、
                    //   一直重发」的烧费。
                    // - 撞输出上限（finishReason=length 等）：模型确实写满了配额，续写多半是
                    //   用户想要的长文；但连烧 3 轮的成本由用户承担 ⇒ **默认只提示、由用户手动继续**
                    //   （设置里可开自动，见 RetrySetting.autoContinueOnLengthTruncation）。
                    val continueHint = autoContinueHintFor(
                        finishIncomplete = last?.finishIncomplete == true,
                        finishReason = last?.finishReason,
                        autoContinueOnLengthTruncation =
                            (provider.retryOption ?: settings.retrySetting).autoContinueOnLengthTruncation,
                        alreadyContinued = autoContinuations,
                    )
                    if (continueHint != null && autoContinuations < CONTINUE_LIMIT) {
                        autoContinuations++
                        pendingRecoveryHint = continueHint
                        Log.i(
                            TAG,
                            "streamText: incomplete output (incomplete=${last?.finishIncomplete}, " +
                                "finishReason=${last?.finishReason}), auto-continuing " +
                                "($autoContinuations/$CONTINUE_LIMIT)"
                        )
                        continue
                    }
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = tools.map { tool ->
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    // 审批判定留一条永远开着的诊断行：审批类问题（"该问却没问" / "不该问却问了"）
                    // 只看结果无从判断，而这一行同时给出"工具定义在不在、要不要批、当前策略"
                    val requiresApproval = toolDef?.needsApproval(tool.inputAsJson()) == true
                    if (requiresApproval) {
                        Log.i(
                            TAG,
                            "approval check: tool=${tool.toolName} state=${tool.approvalState} autoDeny=$autoDenyApproval",
                        )
                    }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        requiresApproval && tool.approvalState is ToolApprovalState.Auto -> {
                            if (autoDenyApproval) {
                                // 无头 run：无人可批，等在 Pending 上 = 永远卡死。直接置为
                                // **策略拒绝**——文本经下方既有的 Denied 通路回给模型
                                // （"Do not retry" 的措辞是 §9.2.1 的拒绝文本契约）。
                                tool.copy(
                                    approvalState = ToolApprovalState.Denied(
                                        reason = HEADLESS_DENIAL_REASON
                                    )
                                )
                            } else {
                                hasPendingApproval = true
                                tool.copy(approvalState = ToolApprovalState.Pending)
                            }
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            // 同一批里「工具名 + 参数」完全相同的重复调用只真正执行一次，其余复用首个结果。
            // 为什么需要：部分提供商（实测 Gemini 系）会在一次响应里下发**多条内容相同、
            // 但 toolCallId 不同**的并行 tool_call，逐条执行会重复产生副作用
            // （实测 memory_tool create 被下发 2 条 → 重复建记忆）。
            // 注意：每个 toolCallId 都必须有 tool 结果（协议要求），故把首条输出复制回填给重复项。
            val executedSignature = mutableMapOf<String, List<UIMessagePart>>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        // 重复拒绝护栏（仅无头 run 传入）：**不按签名**计数——换参数/换工具
                        // 的连续拒绝是 LoopGuard 的盲区（§9.2.1）。超限 → 优雅停止，
                        // run 以 failed(permission_denied) 收尾而非「正常完成」。
                        if (denialGuard != null) {
                            val verdict = denialGuard.record()
                            if (verdict is DenialGuard.Verdict.Exceeded && stopNotice == null) {
                                Log.w(
                                    TAG,
                                    "generateText: too many denials (${verdict.denials}), " +
                                        "will stop this turn after applying results",
                                )
                                stopNotice = GenerationNotice.TooManyDenials(verdict.denials)
                            }
                        }
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        // 去重：同工具同参数的重复下发只执行一次，其余复用首个结果
                        val signature = toolCallSignature(tool.toolName, tool.input, json)
                        executedSignature[signature]?.let { priorOutput ->
                            Log.i(TAG, "generateText: duplicate ${tool.toolName} call skipped (same args), reused result")
                            executedTools += tool.copy(output = priorOutput)
                            return@forEach
                        }
                        // 护栏记录发生在**发起时**（而非执行成功后）：判定的是「模型反复要什么」，
                        // 失败时由下方 onFailure 用 forget 撤回（见 ToolCallLoopGuard 约束 2）。
                        // 注意只在**批内首次**出现时记录：同一响应里的重复下发已被上面的 dedup 收拢，
                        // 若逐条记录，一次 Gemini 式重复下发就会被算成 2 次、两步即误判。
                        // 规则提醒的效果窗口：与 LoopGuard 同处记录（上面的批内 dedup 保证
                        // 一次响应里的重复下发只算一次，窗口密度才不失真）。
                        ruleEngine.record(tool.toolName, tool.inputAsJson())
                        when (val verdict = loopGuard.record(tool.toolName, signature)) {
                            is ToolCallLoopGuard.Verdict.Loop -> {
                                if (stopNotice == null) stopNotice = GenerationNotice.ToolLoopStopped(
                                    toolName = verdict.toolName,
                                    repeats = verdict.repeats,
                                )
                                Log.w(
                                    TAG,
                                    "generateText: tool loop detected (${verdict.toolName} ×${verdict.repeats}, " +
                                        "failed=${verdict.failed}), will stop this turn after applying results",
                                )
                            }

                            ToolCallLoopGuard.Verdict.Continue -> Unit
                        }
                        // 上下文预算：**执行前**判定（与另外两个护栏不同）——撞满窗口是硬墙，
                        // provider 会直接报错，先停能保住已经攒到的结果
                        if (contextGuard != null && stopNotice == null) {
                            val budget = contextGuard.evaluate(messages, overheadTokens)
                            Log.i(TAG, "generateText: context budget ${budget.describe()}")
                            if (budget.verdict is ContextBudget.Verdict.Exhausted) {
                                Log.w(
                                    TAG,
                                    "generateText: context budget exhausted (${budget.describe()}), " +
                                        "will stop this turn before executing ${tool.toolName}",
                                )
                                stopNotice = GenerationNotice.ContextBudgetExhausted(
                                    usedTokens = budget.usedTokens,
                                    windowTokens = budget.windowTokens,
                                )
                            }
                        }
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = try {
                                val parsed = parseToolArguments(tool.input.ifBlank { "{}" }, json)
                                if (parsed.trailingChars > 0 || parsed.lenient) {
                                    // 降级修复命中（#1985 双拼接 / Yuihub `0eaf2057` 同源的宽松
                                    // 兜底）：已恢复执行；记 warning 便于定位是哪家中转/模型的流
                                    // 拼接或畸形输出问题
                                    Log.w(
                                        TAG,
                                        "Tool args for ${tool.toolName} repaired" +
                                            "(lenient=${parsed.lenient}, trailingChars=${parsed.trailingChars})"
                                    )
                                }
                                parsed.args
                            } catch (t: Throwable) {
                                // Throwable：深嵌套工具输入抛 StackOverflowError（Error），runCatching 捕不到
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${t.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            val result = executeToolWithTimeout(toolDef, args) { toolDef.execute(it) }
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            val output = maybeTruncateToolOutput(tool, result, hasShellAccess)
                            executedSignature[signature] = output
                            executedTools += tool.copy(output = output)
                            // workspace 单轮预算：**执行并截断后**计费——计的是「实际进入上下文」
                            // 的字符数（截断掉的部分写进了 tool_outputs 文件、不进上下文）。
                            // 执行前拒绝会让本调用的结果变成合成错误文本，与 LoopGuard 的
                            // 「优雅停止」不一致；此处超限只设提示，本批结果照常回填后停本轮。
                            if (tool.toolName.startsWith(WorkspaceTurnBudget.TOOL_NAME_PREFIX)) {
                                val chars = output.filterIsInstance<UIMessagePart.Text>().sumOf { it.text.length }
                                val verdict = workspaceBudget.record(chars)
                                if (verdict is WorkspaceTurnBudget.Verdict.Exceeded && stopNotice == null) {
                                    Log.w(
                                        TAG,
                                        "generateText: workspace budget exceeded " +
                                            "(${verdict.usedChars}/${verdict.budgetChars} chars, " +
                                            "${verdict.steps} steps, by=${verdict.by}), " +
                                            "will stop this turn after applying results",
                                    )
                                    stopNotice = GenerationNotice.WorkspaceBudgetExhausted(
                                        usedChars = verdict.usedChars,
                                        budgetChars = verdict.budgetChars,
                                        steps = verdict.steps,
                                        by = verdict.by,
                                    )
                                }
                            }
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            // forget() 补丁：把本次标记为**失败**，使「失败调用」不被算作
                            // 「成功调用的重复」——网络恢复后用相同参数重试是合理恢复路径。
                            // 注意本仓**不采用**同源实现的「直接移出窗口」：真机实测那样会让
                            // 「反复撞同一个必然失败」永远不触发（详见 ToolCallLoopGuard 类注释）。
                            loopGuard.markFailed(signature)
                            it.printStackTrace()
                            // 失败调用的错误文本（含堆栈）同样进入上下文 ⇒ 同样计费——否则
                            // 「每步换参数、每次都失败」的 shell 调用既不占输出预算也不占步数。
                            // 判定结果此处不设提示（成功路径统一负责），只记账。
                            val errorText = buildString {
                                append("[${it.javaClass.name}] ${it.message}")
                                append("\n${it.stackTraceToString()}")
                            }
                            if (tool.toolName.startsWith(WorkspaceTurnBudget.TOOL_NAME_PREFIX)) {
                                workspaceBudget.record(errorText.length)
                            }
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive(errorText))
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = targetModel,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )

            // 护栏命中（同签名循环 或 workspace 预算耗尽）：结果已全部回填（协议要求每个
            // toolCallId 都有结果，不能半途丢），现在提前结束本轮。**走正常结束路径**而非抛
            // 异常——抛异常会让本轮已生成内容只停留在内存（onSuccess 才落库），把护栏的
            // 「防失控」变成「丢内容」。
            stopNotice?.let { stopped ->
                emit(GenerationChunk.Notice(stopped))
                break
            }
        }
        }

        // 主模型整轮失败（重试耗尽、非半截、非取消）时切 fallback 模型重跑整轮。
        // 半截输出（GenerationPartialFailureException）保留草稿不 fallback——#1812 约束。
        try {
            runLoop(model, provider, providerImpl)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (e is GenerationPartialFailureException) throw e
            if (fallbackModel != null && fallbackProviderImpl != null) {
                Log.w(TAG, "primary model failed, fallback to ${fallbackModel.id}: ${e.message}")
                runLoop(fallbackModel, fallbackProvider!!, fallbackProviderImpl)
            } else {
                throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 生成失败自动重试。
     *
     * 配置解析优先级：per-provider [ProviderSetting.retryOption]（非 null 时完全覆盖）→
     * 全局 [Settings.retrySetting]。未启用或间隔序列为空时直接执行，零开销。
     *
     * 语义：delaysSeconds 长度 = 重试次数（不含首次请求），值 = 每次重试前的等待秒数。
     * 仅对「可重试错误」（网络异常/限流/服务端错误）重试；用户取消（CancellationException）
     * 与不可重试错误（鉴权失败、参数错误等）立即上抛。重试等待状态通过
     * [processingStatus] 反馈到 UI。
     */
    private suspend fun <T> withGenerationRetry(
        providerSetting: ProviderSetting,
        settings: Settings,
        processingStatus: MutableStateFlow<String?>?,
        maxRetries: Int? = null,
        action: suspend (ProviderSetting) -> T,
    ): T {
        val plan = providerSetting.retryOption ?: settings.retrySetting
        if (!plan.enabled || plan.delaysSeconds.isEmpty()) return action(providerSetting)
        // 重试预算：有 fallback 模型时压缩（快速失败切备用，避免长延迟重试拖慢恢复）
        val retryBudget = maxRetries?.coerceAtMost(plan.delaysSeconds.size) ?: plan.delaysSeconds.size

        var currentSetting = providerSetting
        var retryIndex = -1 // 已执行的重试序号；首次请求前为 -1
        while (true) {
            try {
                return action(currentSetting)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenerationPartialFailureException) {
                // #1812：半截输出失败不进重试循环——已流式内容有计费价值，
                // 清空重发必然丢失；直接上抛由 ChatService 保留为草稿
                throw e
            } catch (e: Throwable) {
                val next = retryIndex + 1
                // 鉴权失败（401/403）或账户限流（429，多账户场景换 key 可避开）且还有其他 key：
                // 立即换 key 重试，不等延迟。OrderKeyRoulette 固定取首个 key，故轮转 =
                // 从瞬态副本去掉刚失败的首个 key（不落盘），下次请求自然用下一个；
                // 每次换 key 消耗一个 key，耗尽后 hasMultipleEnabledKeys=false → 走正常路径上抛，
                // 不会死循环。安全保证：#1812 分流——流式半截 429 已抛
                // GenerationPartialFailureException 被上方 rethrow（保留草稿不换 key），
                // 能走到这里的一定是零输出，换 key 无计费损失。
                if (isKeySwitchingError(e) && hasMultipleEnabledKeys(currentSetting)) {
                    val rotated = rotateProviderKeys(currentSetting)
                    if (rotated == null) throw e // 理论不可达（hasMultipleEnabledKeys 已保证 ≥2 个）
                    retryIndex = next
                    currentSetting = rotated
                    Log.w(
                        TAG,
                        "key error, switching key (${splitEffectiveKeys(rotated.effectiveApiKeys).size} key(s) left) " +
                            "and retrying: ${e.message}"
                    )
                    continue
                }
                if (next >= retryBudget || !isRetryableGenerationError(e)) throw e
                retryIndex = next
                val waitSec = plan.delaysSeconds[retryIndex].coerceAtLeast(0)
                Log.w(
                    TAG,
                    "generation failed, retry ${retryIndex + 1}/$retryBudget in ${waitSec}s: ${e.message}"
                )
                if (processingStatus != null) {
                    processingStatus.value = context.getString(
                        R.string.generation_retry_waiting,
                        retryIndex + 1,
                        retryBudget,
                        waitSec,
                    )
                }
                try {
                    delay(waitSec * 1000L)
                } finally {
                    if (processingStatus != null) processingStatus.value = null
                }
            }
        }
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        /**
         * 压缩发送窗口的可重算描述；null = 不做压缩窗口处理。
         *
         * **本步重算**（而非接收调用方的冻结列表）：工具执行会让 [messages] 在本回合内增长，
         * 重算才能把刚产生的 tool call + 结果带进上下文（否则模型重复调用同一工具）。
         * 边界之前由检查点合成消息替代、边界之后取 live——见 [Conversation.CompressionWindow.applyTo]。
         */
        compressionWindow: Conversation.CompressionWindow? = null,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        retryBudgetOverride: Int? = null,
        /** 思考退化护栏：流消费处用它做**调用内断流**（null = 不启用） */
        degenerationGuard: ThinkingDegenerationGuard? = null,
        /** 空回复补救提示（只作用于本请求系统提示，不进入对话历史） */
        recoveryHint: String? = null,
        /** 规则提醒块（只作用于本请求，作为尾部合成消息，不进入对话历史） */
        ruleReminder: String? = null,
        /** 会话级「不使用记忆」：为真时不注入记忆块（工具侧已在装配点处理） */
        memoryDisabled: Boolean = false,
        /** L1 冻结的锚点 id 集合：非 null 时只渲染这些锚点（见 [generateText] 同名参数）。 */
        frozenAnchorIds: Set<String>? = null,
        debugKey: String? = null,
    ) {
        // 组装发送消息：**与「拼装预览」共用同一个函数**（PromptAssembly.kt）——
        // 原先此段内联实现，预览只能另写一份、必然漂移。改这里即同时改预览。
        val internalMessages = buildInternalMessages(
            assistant = assistant,
            settings = settings,
            model = model,
            messages = messages,
            memories = memories,
            tools = tools,
            conversationSystemPrompt = conversationSystemPrompt,
            compressionWindow = compressionWindow,
            recoveryHint = recoveryHint,
            ruleReminder = ruleReminder,
            memoryDisabled = memoryDisabled,
            frozenAnchorIds = frozenAnchorIds,
        )
        // 输入处理链（时间提醒 / 模式注入 / 世界书 / ST 宏 / 占位符 / 文档转提示 / OCR / 引用 /
        // 助手模板 / workspace 提醒）：**返回值必须接住**——它才是真正发给 provider 的列表。
        //
        // 2026-10-06 修复：此处曾写成裸语句 `internalMessages.transforms(...)`（`d63f649d2` 改造
        // 前缀分叉诊断时把赋值丢了），链的产物被丢弃、请求发的是**未处理**列表，而拼装预览
        // （`PromptInspector` 那一份有 `val transformed =`）照常显示注入 ⇒「预览里有、实发里没有」，
        // 注入 / 时间提醒 / 引用展开 / OCR / 文档转提示全部静默失效。
        // 守卫测试：`RequestMessagePipelineGuardTest`（源码扫描，防止再次丢赋值）。
        val requestMessages = internalMessages.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )
        // 前缀分叉诊断：缓存**实际发送**的消息列表（即处理链之后的那份，单次请求差分才有意义）
        debugKey?.let { PromptDebugState.recordRequest(it, requestMessages) }

        var messages: List<UIMessage> = messages
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }
        )
        if (stream) {
            withGenerationRetry(
                providerSetting = provider,
                settings = settings,
                processingStatus = processingStatus,
                maxRetries = retryBudgetOverride,
            ) { provider ->
                // 快照：流中途失败时回滚已输出的部分内容，重试从干净状态重新生成
                val snapshot = messages
                // 流结束语义（见 MessageChunk.explicitEnd）：本次流是否见到 provider 的显式结束标记。
                // 没有 = 裸断流（网关/中转提前切连接）——此前与「正常收尾」无法区分，整轮被当成功。
                var sawExplicitEnd = false
                var finishReason: String? = null
                // 思考退化护栏：调用内逐字重复 ⇒ 从内部中止本次流（抛出沿链取消上游 SSE 连接），
                // 已生成内容照常保留，由步骤循环收尾（见 [ThinkingDegenerationGuard]）。
                degenerationGuard?.onCallStarted()
                try {
                    try {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = requestMessages,
                            params = params
                        ).collect { chunk ->
                            if (chunk.explicitEnd) {
                                sawExplicitEnd = true
                                return@collect
                            }
                            // "unknown" 是 chat/completions 解析层「字段缺失」的占位，归一成 null
                            chunk.choices.firstOrNull()?.finishReason
                                ?.takeIf { it.isNotBlank() && it != "unknown" }
                                ?.let { finishReason = it }
                            messages = messages.handleMessageChunk(
                                chunk = chunk,
                                model = model,
                                providerName = provider.name,
                            )
                            chunk.usage?.let { usage ->
                                messages = messages.mapIndexed { index, message ->
                                    if (index == messages.lastIndex) {
                                        message.copy(usage = message.usage.merge(usage))
                                    } else {
                                        message
                                    }
                                }
                            }
                            onUpdateMessages(messages)
                            // 判定放在**本条已并入 messages 且已回调之后**：断流不丢已生成内容
                            val deltaVerdict = degenerationGuard?.onDelta(chunk.choices.firstOrNull()?.delta)
                            if (deltaVerdict is ThinkingDegenerationGuard.Verdict.Repeating) {
                                Log.w(
                                    TAG,
                                    "generateInternal: thinking degeneration (${deltaVerdict.rule}, " +
                                        "repeated=${deltaVerdict.repeated}), cutting this call",
                                )
                                throw ThinkingDegenerationCut
                            }
                        }
                    } catch (e: ThinkingDegenerationCut) {
                        // 护栏主动断流：不是失败——不回滚快照、不重试、不 fallback，
                        // 与另外三个护栏的「优雅停止」同款（停止提示由步骤循环发出）
                    }
                    // 流正常收尾：把「结束状态」写到末尾助手消息上（裸断流也会走到这里，
                    // 区别只在 sawExplicitEnd）。续写成功会再次覆写，故不需要额外的清除逻辑。
                    messages = messages.markLastFinish(finishReason, incomplete = !sawExplicitEnd)
                    if (!sawExplicitEnd) {
                        Log.w(
                            TAG,
                            "stream ended without explicit terminator (bare EOF)" +
                                " — content may be truncated (finishReason=$finishReason)"
                        )
                    }
                    onUpdateMessages(messages)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    val plan = provider.retryOption ?: settings.retrySetting
                    // #1812 分流：已流式输出可见内容后断开 → 保留为草稿（不再回滚清空）；
                    // 阈值可配（partialOutputMinChars），防呆下限 1（避免误配 0/负数把零输出也当半截）
                    if (plan.keepPartialOnFailure && !plan.retryAfterPartial &&
                        isPartialOutput(messages, plan.partialOutputMinChars.coerceAtLeast(1))
                    ) {
                        throw GenerationPartialFailureException(e, messages)
                    }
                    messages = snapshot
                    onUpdateMessages(messages)
                    throw e
                }
            }
        } else {
            withGenerationRetry(
                providerSetting = provider,
                settings = settings,
                processingStatus = processingStatus,
                maxRetries = retryBudgetOverride,
            ) { provider ->
                val snapshot = messages
                try {
                    val chunk = providerImpl.generateText(
                        providerSetting = provider,
                        messages = requestMessages,
                        params = params,
                    )
                    messages = messages.handleMessageChunk(
                        chunk = chunk,
                        model = model,
                        providerName = provider.name,
                    )
                    chunk.usage?.let { usage ->
                        messages = messages.mapIndexed { index, message ->
                            if (index == messages.lastIndex) {
                                message.copy(
                                    usage = message.usage.merge(usage)
                                )
                            } else {
                                message
                            }
                        }
                    }
                    // 非流式：整包返回（要么完整要么抛错）⇒ 不存在裸断流，只记 finish_reason
                    messages = messages.markLastFinish(
                        reason = chunk.choices.firstOrNull()?.finishReason
                            ?.takeIf { it.isNotBlank() && it != "unknown" },
                        incomplete = false,
                    )
                    onUpdateMessages(messages)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    messages = snapshot
                    onUpdateMessages(messages)
                    throw e
                }
            }
        }
    }

    /**
     * 把本次流的结束状态写到**末尾助手消息**上（见 [UIMessage.finishReason] / [UIMessage.finishIncomplete]）。
     *
     * 只动末尾一条：流式期间新增的输出永远落在末尾助手消息（同角色 chunk 追加），
     * 与 [UIMessage.finishIncomplete] 的「最后一次流的结束状态」语义一致。
     */
    private fun List<UIMessage>.markLastFinish(reason: String?, incomplete: Boolean): List<UIMessage> {
        val last = lastOrNull() ?: return this
        if (last.role != MessageRole.ASSISTANT) return this
        return dropLast(1) + last.copy(finishReason = reason, finishIncomplete = incomplete)
    }

    private fun maybeTruncateToolOutput(
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        if (tool.toolName in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return output

        val toolCallId = tool.toolCallId
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        // [FIX] toolCallId 由模型提供（外部可控），直接拼文件名可含 "/" 或 "../" →
        // 逃逸 tool_outputs 目录写入 app 私有目录其他位置（移植自 rikkahub-turbo a9449cc）。
        val safeFileName = toolCallId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val fileName = "$safeFileName.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        val outputFile = File(outputDir, fileName)
        outputFile.writeText(fullText)
        // 投影无损回环校验（华灯投影原则）：外部全文是模型恢复上下文的**唯一**来源，
        // 写坏（磁盘满/静默截断）必须当场报错——绝不能让「预览说存了、文件却是残的」。
        require(outputFile.readText() == fullText) {
            "Tool output projection write failed (lossless check): ${'$'}fileName"
        }

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText.take(MAX_TRANSLATE_CHARS),
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            withGenerationRetry(provider, settings, null) { provider ->
                val snapshot = messages
                try {
                    providerHandler.streamText(
                        providerSetting = provider,
                        messages = messages,
                        params = TextGenerationParams(
                            model = model,
                            reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                        ),
                    ).collect { chunk ->
                        messages = messages.handleMessageChunk(chunk)
                        translatedText = messages.lastOrNull()?.toText() ?: ""

                        if (translatedText.isNotBlank()) {
                            onStreamUpdate?.invoke(translatedText)
                            emit(translatedText)
                        }
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    messages = snapshot
                    throw e
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = withGenerationRetry(provider, settings, null) { provider ->
                providerHandler.generateText(
                    providerSetting = provider,
                    messages = messages,
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.3f,
                        topP = 0.95f,
                        customBody = listOf(
                            CustomBody(
                                key = "translation_options",
                                value = buildJsonObject {
                                    put("source_lang", JsonPrimitive("auto"))
                                    put(
                                        "target_lang",
                                        JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                    )
                                }
                            )
                        )
                    ),
                )
            }
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * #1812：半截输出失败信号——以异常为载体把「已流式合并的半截消息」交给上层落草稿。
 *
 * [withGenerationRetry] 识别该类型直接上抛（不重试）；ChatService catch 后保留候选草稿。
 */
class GenerationPartialFailureException(
    cause: Throwable,
    val partialMessages: List<UIMessage>,
) : Exception(cause)

/** 半截输出判定默认阈值：过滤 reasoning 后最后一条 assistant 消息可见 Text ≥ 该值视为「已产生有效输出」。 */
private const val PARTIAL_OUTPUT_THRESHOLD_CHAR = 50

/** 是否达到「半截输出」判定线：仅看最后一条 assistant 消息的可见 Text（reasoning 不计入）。 */
internal fun isPartialOutput(
    messages: List<UIMessage>,
    minChars: Int = PARTIAL_OUTPUT_THRESHOLD_CHAR,
): Boolean {
    val last = messages.lastOrNull() ?: return false
    if (last.role != MessageRole.ASSISTANT) return false
    val visibleChars = last.parts.sumOf { part ->
        if (part is UIMessagePart.Text) part.text.length else 0
    }
    return visibleChars >= minChars
}

/**
 * 判断生成错误是否值得自动重试：
 * - 网络层异常（超时/DNS/连接失败/TLS 等 IOException 家族）→ 重试
 * - 响应解析失败（中转站返回 HTML 错误页等非 JSON 内容）→ 重试
 * - HTTP 408/425/429/5xx → 重试；401/403（鉴权）、400（参数）、404（模型不存在）→ 不重试
 *
 * provider 层抛的是字符串异常（如 "Failed to get response: 429 ..."），状态码从 message 提取。
 * **内容风控拦截不重试**：同样内容重发同样被拦（见 [CONTENT_BLOCKED_MARKERS]）。
 */
internal fun isRetryableGenerationError(t: Throwable): Boolean {
    // 内容风控/违禁词：确定性失败，须用户改内容——优先于网络/状态码判定（重发同样被拦）
    if (t.matchesAnyMarker(CONTENT_BLOCKED_MARKERS)) return false
    if (t is java.io.IOException) return true
    if (t is kotlinx.serialization.SerializationException) return true
    // 额度耗尽是确定性失败：余额不会自恢复，等待无意义——快速失败走换 key / fallback
    if (t.matchesAnyMarker(QUOTA_EXHAUSTED_MARKERS)) return false
    // 限流：等窗口重置可解（无状态码的 SSE 流内限流错误在此兜底）
    if (t.matchesAnyMarker(RATE_LIMITED_MARKERS)) return true
    val code = findHttpStatusCode(t.message) ?: return false
    return code == 408 || code == 425 || code == 429 || code in 500..599
}

/**
 * 换 key 可解决的错误：401/403（鉴权/凭证失效）+ 429（账户级限流，多账户场景换 key 可避开），
 * 以及无状态码的 SSE 流内错误文本兜底（中转站 200 + error 事件）——额度耗尽/限流都是账户级问题。
 * 到此处的一定是零输出（流式半截 429 已被 #1812 分流拦截），换 key 无计费损失。
 *
 * **例外（内容风控拦截）**：网关按内容判定违禁并返回 403 时，问题在**请求内容**而非凭证——
 * 所有 key 都会撞同一道防火墙，换 key 必然全败且把额度/时间白耗在轮换上。故先行排除（见
 * [CONTENT_BLOCKED_MARKERS]）。这类错误也不重试（同样内容同样被拦）。
 */
internal fun isKeySwitchingError(t: Throwable): Boolean {
    if (t.matchesAnyMarker(CONTENT_BLOCKED_MARKERS)) return false
    val code = findHttpStatusCode(t.message)
    if (code == 401 || code == 403 || code == 429) return true
    return t.matchesAnyMarker(QUOTA_EXHAUSTED_MARKERS) || t.matchesAnyMarker(RATE_LIMITED_MARKERS)
}

/**
 * 内容风控/违禁词拦截特征：网关按**内容**拒绝，与凭证无关。
 *
 * 实测样例（中转站/网关 403）：「触发网站风控违禁词，无法调用模型：内容命中网关内容防火墙规则
 * [关键词：...]，已被拦截。请修改内容后重试。」——**换 key 无用**（同一防火墙）、**重试无用**
 * （同样内容同样被拦），只能由用户改内容。若不加此判据，会被 403 规则误判为凭证失效而轮换所有 key。
 *
 * 注意：网关多按**子串**匹配（如 `sb`、`sm`、`anal` 会命中 `val sb = StringBuilder()`、`analyze`
 * 等普通标识符），故用户侧报错常显「莫名其妙」；这是网关行为，客户端无法规避，只能如实归类。
 */
private val CONTENT_BLOCKED_MARKERS = listOf(
    // 中文网关/中转站（"风控" 是内容风控惯用词）
    "风控", "违禁词", "内容防火墙", "已被拦截", "命中网关", "请修改内容后重试",
    // 国际厂商内容策略
    "content policy", "content_policy_violation", "content filter", "content moderation",
    "moderation_blocked", "safety system", "flagged by", "responsible ai",
)

/** 账户额度耗尽特征：等待无用（余额不自恢复），换 key（换账户）或 fallback 模型可解 */
private val QUOTA_EXHAUSTED_MARKERS = listOf(
    "quota is not enough",       // new-api 系中转站（实测复制样例）
    "insufficient quota",        // OpenAI 官方系
    "insufficient_quota",
    "exceeded your current quota",
    "quota exceeded",
    "余额不足", "额度不足",
)

/** 限流特征（TPM/RPM/速率）：等窗口重置或换 key（换账户）可解；锚定写法防 "tpm-mini" 之类误伤 */
private val RATE_LIMITED_MARKERS = listOf(
    "rate limit", "rate_limit", "ratelimit",
    "too many requests",
    "tpm/rpm", "exceeds tpm", "exceeds rpm", "tpm limit", "rpm limit",
    "请求过于频繁", "触发限流",
)

private fun Throwable.matchesAnyMarker(markers: List<String>): Boolean {
    val message = this.message?.lowercase() ?: return false
    return markers.any { it in message }
}

/** 该提供商是否配置了多个可用 key（enabled 过滤后） */
internal fun hasMultipleEnabledKeys(providerSetting: ProviderSetting): Boolean {
    val keys = when (providerSetting) {
        is ProviderSetting.OpenAI -> providerSetting.apiKeys
        is ProviderSetting.Google -> providerSetting.apiKeys
        is ProviderSetting.Claude -> providerSetting.apiKeys
    }
    return keys.count { it.enabled && it.key.isNotBlank() } > 1
}

/**
 * 换 key 重试的轮转：去掉首个生效 key（OrderKeyRoulette 固定取首个 = 刚失败的那个），
 * 剩余 key 写回瞬态副本（apiKeys + 旧 apiKey 字段同时覆盖，兼容旧字符串多 key）。
 * 副本仅用于本次重试循环内传给 provider，不落盘。只剩 ≤1 个 key 时返回 null（无可切换）。
 */
internal fun rotateProviderKeys(setting: ProviderSetting): ProviderSetting? {
    val keys = splitEffectiveKeys(setting.effectiveApiKeys)
    if (keys.size <= 1) return null
    val remaining = keys.drop(1)
    val entries = remaining.map { ApiKeyEntry(it) }
    val legacy = remaining.joinToString("\n")
    return when (setting) {
        is ProviderSetting.OpenAI -> setting.copy(apiKeys = entries, apiKey = legacy)
        is ProviderSetting.Google -> setting.copy(apiKeys = entries, apiKey = legacy)
        is ProviderSetting.Claude -> setting.copy(apiKeys = entries, apiKey = legacy)
    }
}

/** 拆分 effectiveApiKeys 字符串（与 KeyRoulette.splitKey 同规则：空白/逗号分隔 + 去重） */
internal fun splitEffectiveKeys(keys: String): List<String> =
    keys.split("[\\s,]+".toRegex()).map { it.trim() }.filter { it.isNotBlank() }.distinct()

/** 从错误 message 提取 HTTP 状态码：优先 "code/status: NNN" 形态，回退到首个独立的 3 位数字。 */
private fun findHttpStatusCode(message: String?): Int? {
    if (message == null) return null
    val prefixed = Regex("""(?:code|status|HTTP)[\s:=]*(\d{3})""", RegexOption.IGNORE_CASE)
        .find(message)?.groupValues?.get(1)?.toIntOrNull()
    if (prefixed != null) return prefixed
    // 回退：形如 ": 429"、">502"、"#401" 的独立三位数（覆盖中转站 HTML 错误页正文与搜索服务 "#${code}" 形态）
    return Regex("""[\s:>(#](\d{3})\b""").find(message)?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * 用工具自带的**外层上限**包一层 [withTimeout]（见 [Tool.executionTimeoutMillis]）。
 *
 * `null` = 工具自管超时（如 `dispatch_subagent` 阻塞等整轮子代理、`workspace_shell` 自带
 * 命令超时与进程树杀法），外层不介入。
 *
 * **超时转成普通异常而不是让 [TimeoutCancellationException] 逃出去**：后者**是**
 * `CancellationException` 的子类，而工具执行的 `onFailure` 里对 `CancellationException`
 * 一律**原样上抛**（那是「用户停止生成」的语义，必须传播）——若直接放行，超时会被误当成
 * 用户停止：既没有可见的错误输出给模型，也不会被 LoopGuard 记成失败。故这里主动换成
 * `IllegalStateException`，走「工具执行失败」的既有通路（错误文本回填上下文 + 记失败）。
 */
internal suspend fun executeToolWithTimeout(
    tool: Tool,
    args: JsonElement,
    block: suspend (JsonElement) -> List<UIMessagePart>,
): List<UIMessagePart> {
    val limit = tool.executionTimeoutMillis ?: return block(args)
    return try {
        withTimeout(limit) { block(args) }
    } catch (e: TimeoutCancellationException) {
        throw IllegalStateException(
            "Tool ${tool.name} timed out after ${limit / 1000}s without returning a result. " +
                "Narrow the request, pass a smaller timeout if the tool supports one, or retry.",
            e,
        )
    }
}
