package app.androidrik.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
import app.androidrik.ai.core.resolveReasoningPreset
import app.androidrik.ai.core.withReasoningPreset
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.Tool
import app.androidrik.ai.core.shouldEndTurn
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.core.accumulateRound
import app.androidrik.ai.core.merge
import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.rikkahub.data.ai.tools.local.PHONE_USE_TOOL_NAME
import app.androidrik.rikkahub.data.ai.tools.local.TODO_TOOL_NAME
import app.androidrik.rikkahub.data.ai.tools.local.hasUnfinishedItems
import app.androidrik.rikkahub.data.ai.tools.local.latestTodoPlan
import app.androidrik.ai.ui.isOutputLimitTruncation
import app.androidrik.ai.ui.isContentFilterTruncation
import app.androidrik.ai.ui.isEmptyUIMessage
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.StreamChunkHandler
import app.androidrik.ai.ui.handleTextGenerationResult
import app.androidrik.rikkahub.data.ai.prompts.isCompactionCheckpointPreamble
import app.androidrik.rikkahub.data.ai.prompts.withContinuationHintFor
import app.androidrik.rikkahub.data.ai.transformers.InputMessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.MessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.OutputMessageTransformer
import java.io.File
import app.androidrik.rikkahub.data.ai.transformers.balancedForRequest
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
import app.androidrik.rikkahub.utils.estimateTokenCount
import app.androidrik.rikkahub.utils.estimateTokens
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"

/**
 * 中途压缩的请求快照（见 [GenerationHandler.generateText] 的 `midTurnCompress` 参数）。
 *
 * 为什么带 [messages] 而不让调用方自己去会话里读：流式 emit 经 `coalesceMessageUpdates` 节流，
 * **会话内存态可能落后于本批工具回填**。压缩若读会话状态，压进去的是上一批的旧内容，
 * 摘要里丢掉刚执行的工具结果 ⇒ 下一步模型重复调用同一工具。本类手里的 `messages` 才是权威快照。
 */
data class MidTurnCompressionRequest(
    /** 本批工具全部回填后的 live 消息（含当前回复及其工具结果） */
    val messages: List<UIMessage>,
    /** 当前回复的消息 id（「同一条回复只试一次」的判据） */
    val replyMessageId: Uuid,
    /** 本次生成调用内已完成的模型请求数（仅用于日志与诊断） */
    val stepsExecuted: Int,
    /** 上下文占用 token（来自 [ContextBudgetGuard] 的估算；0 = 无守卫信息） */
    val usedTokens: Int,
    /** 模型上下文窗口 token */
    val windowTokens: Int,
)

/** 中途压缩的结果（见 [GenerationHandler.generateText] 的 `midTurnCompress` 参数）。 */
enum class MidTurnCompressionOutcome {
    /** 本次不需要压缩（未达阈值 / 开关关 / 同一条回复已试过 / 本轮已判定无意义）。 */
    NotNeeded,

    /** 压缩成功：检查点已插到当前回复之后，本类接下来新开一条回复继续。 */
    Compressed,

    /** 压缩失败：本轮照常继续写**当前**回复（同一条回复不重试，判据在调用方）。 */
    Failed,
}

/**
 * 请求面：裁掉末尾那条「刚为续跑新开、还没有任何 part」的 assistant 消息。
 *
 * 判据：中途压缩成功后，下一步必须写进新回复，而新回复此刻是空的——它只是**流式回填的落点**，
 * 不是要发出去的内容。各 provider 虽会按 `isValidToUpload` 过滤空消息，但「请求面 = 回写基线」
 * 的假设会因此失真（也让「检查点后面是什么」的续跑判定算错），在装配前直接裁掉更明确。
 * 返回同一实例 = 无需裁剪（正常步骤的末尾是用户消息或带 tool_call 的助手消息）。
 */
internal fun List<UIMessage>.withoutTrailingEmptyAssistantForRequest(): List<UIMessage> =
    if (lastOrNull()?.let { it.role == MessageRole.ASSISTANT && it.parts.isEmpty() } == true) {
        dropLast(1)
    } else {
        this
    }

/** 并行车道的并发上限（借鉴 NMoi D254 的 Read/Process 车道并发数 4）。 */
internal const val TOOL_LANE_MAX_CONCURRENCY = 4

/** 已过预检、待执行的工具调用（车道并行与串行共用的执行单元）。 */
internal data class PreparedTool(
    val tool: UIMessagePart.Tool,
    val signature: String,
    val outputBudget: Int,
    val hasShellAccess: Boolean,
    val workspaceBound: Boolean,
    val toolStartedAt: kotlinx.datetime.LocalDateTime,
)

// 上下文闸 B 的常量与三件执行体随批次 10 迁至 ToolOutputBudget.kt（闸 A 在 ToolOutputContextGate.kt）

/**
 * 空工具输出的占位文本（见 [finalizeToolOutput]）。
 *
 * 为什么必需：`UIMessagePart.Tool.isExecuted` 的定义是 `output.isNotEmpty()`
 * （`ai/.../ui/UIMessagePart.kt`）。工具返回 `emptyList()` 时结果回填后仍算「未执行」，
 * 下一轮 `getTools().filter { !it.isExecuted }` 又把它选出来再执行一次 ⇒ **之后每一步都被重复执行**。
 * 补一个占位 part 让它变成「已执行」，比改 `isExecuted` 的定义安全得多——后者会波及
 * 审批闸门（`isPending` / `canResumeExecution`）、渲染和导出。
 */
internal const val EMPTY_TOOL_OUTPUT = "(no output)"

// 搜索结果不参与截断的豁免清单随批次 10 迁至 ToolOutputBudget.kt（maybeTruncateToolOutput 同迁）

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

/**
 * 工具结果的最终形态：空输出补 [EMPTY_TOOL_OUTPUT] 占位，使其 `isExecuted` 为真。
 *
 * 只在**执行完成后**用（截断之后），因此不影响 `maybeTruncateToolOutput` 的既有行为。
 */
internal fun finalizeToolOutput(output: List<UIMessagePart>): List<UIMessagePart> =
    output.ifEmpty { listOf(UIMessagePart.Text(EMPTY_TOOL_OUTPUT)) }

/**
 * 审批阶段：只把「**新出现**且需要审批」的调用置为 `Pending`。
 *
 * 语义（与上游 `c8a9e4e2`/`e10e1043` 同源）：
 * - `Auto` + 需要审批 ⇒ `Pending`（[autoDeny] 时改为策略拒绝，无头 run 用）；
 * - 已经是 `Pending` 的**保持不变**（继续等用户）；
 * - 其余（`Approved` / `Denied` / `Answered` / 不需要审批的 `Auto`）一律不碰。
 *
 * **不能因为「本批有待审批的调用」就整批不执行**——同批那些不需要审批的自动调用
 * 必须照常在本轮执行（见 [toolsToExecute]）。
 *
 * @param requiresApproval 工具定义侧的审批判定；由调用方传入以便保留审批诊断日志
 */
internal fun markPendingApprovals(
    tools: List<UIMessagePart.Tool>,
    requiresApproval: (UIMessagePart.Tool) -> Boolean,
    autoDeny: Boolean = false,
): List<UIMessagePart.Tool> = tools.map { tool ->
    // 对每个工具都求值（即使已执行/已待批）：调用方在这里挂审批诊断日志，
    // 短路会让「该问却没问」那类现场失去唯一的证据行。
    val needsApproval = requiresApproval(tool)
    when {
        tool.isExecuted -> tool
        tool.approvalState is ToolApprovalState.Pending -> tool
        tool.approvalState is ToolApprovalState.Auto && needsApproval ->
            if (autoDeny) {
                // 无头 run：无人可批，等在 Pending 上 = 永远卡死。直接置为**策略拒绝**——
                // 文本经既有 Denied 通路回给模型（"Do not retry" 的措辞是拒绝文本契约）。
                tool.copy(approvalState = ToolApprovalState.Denied(reason = HEADLESS_DENIAL_REASON))
            } else {
                tool.copy(approvalState = ToolApprovalState.Pending)
            }

        else -> tool
    }
}

/**
 * 执行阶段要处理的调用：**所有尚未执行**的（含 `Auto` / `Approved` / `Denied` / `Answered`）。
 *
 * 不能用 `canResumeExecution` 筛：它把 `Auto` 判成 false（见 `ai/.../ui/UIMessagePart.kt`），
 * 于是「同批里有需要审批的调用」时，不需要审批的自动调用会被整批排除——这正是要修的 bug
 * （被跳过的 `Auto` 还会在下一轮被 `ChatService.checkInvalidMessages` 连同那条助手回复一起删掉）。
 * `Pending` 也会被选出来，但执行处的 `when` 分支会跳过它们（等用户批准）。
 */
internal fun toolsToExecute(tools: List<UIMessagePart.Tool>): List<UIMessagePart.Tool> =
    tools.filter { !it.isExecuted }

/**
 * 把一个工具结果并回**最后一条助手消息**（其余 part 原样保留）。
 *
 * **一次只并一个**：调用方每拿到一个结果就并一次并 emit（见 `GenerationHandler` 执行段）——
 * 取消时已完成的结果已经在内存态里，停止生成能读到（`ChatService.persistStoppedGeneration` 只读内存态），
 * 不会随「攒到最后统一回填」的局部列表一起丢掉。
 *
 * 注意：**仅靠 emit 不足以保证「已完成的结果不丢」**。本函数是纯同步变换、永不半途被打断，
 * 但把结果送到会话内存态的那一步（`emit` → `flowOn` 的 channel → collect 端）是挂起的，
 * 生成协程被取消时会整段丢失，而且**加 `withContext(NonCancellable)` 也救不回来**：
 * 1. `flow {}` 的 `SafeCollector` 按 Job 校验发射上下文，换成 `NonCancellable` 会直接抛
 *    `Flow invariant is violated`（coroutines 1.11；见 `GenerationToolLoopTest` 的用例说明）；
 * 2. 即便改成 `channelFlow` 让发射合法，`flowOn` 的 channel 在 collect 端被取消后
 *    也不会再把缓冲值交给 collector（本仓实测：[1] 到、NonCancellable 里的 [2] 不到）。
 *
 * 所以「取消时必定落进消息」由 `generateText` 的 `onMessagesCommitted` 旁路落点保证
 * （每次回填同步写一份快照，`ChatService.stopGeneration` 读它补进内存态）。
 */
internal fun applyToolOutput(
    messages: List<UIMessage>,
    result: UIMessagePart.Tool,
): List<UIMessage> {
    val lastMessage = messages.last()
    val updatedParts = lastMessage.parts.map { part ->
        if (part is UIMessagePart.Tool && part.toolCallId == result.toolCallId) result else part
    }
    return messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
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
    /** 每次 provider 请求写一行用量流水（见 [UsageRecorder]） */
    private val usageRecorder: UsageRecorder,
) {
    // 批次 10 拆出的翻译生成器（translateText 公开签名不变，委托过去；ChatService 调用面不动）
    private val translationGenerator = TranslationGenerator(context, providerManager, usageRecorder)
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
        /**
         * 中途自动压缩（上游 `bcc649abc`；本仓落点）。一批工具**全部回填之后**调用，返回
         * [MidTurnCompressionOutcome.Compressed] 时本类新开一条 assistant 回复继续下一步。
         *
         * 为什么是回调而不是在本类里做压缩：压缩要读会话内存态、写 compression_checkpoint 表、
         * 发一次独立模型请求——全是 ChatService/provider 侧的知识；本类只负责**时机**（工具批之后）
         * 与**判据输入**（本批 live messages + 上下文占用）。做不做、怎么做交给调用方。
         *
         * 线程：回调在生成流的**生产协程**里执行（本 flow 已 `flowOn(Dispatchers.IO)`），
         * 因此压缩的网络/DB 工作天然不在主线程——这正是上游把压缩放「生成任务内部」的收益。
         *
         * 默认 null：无头 run 与「重新生成」路径不启用（语义见 `ChatService.handleMessageComplete`）。
         */
        midTurnCompress: (suspend (MidTurnCompressionRequest) -> MidTurnCompressionOutcome)? = null,
        /**
         * 中途压缩成功后重新取压缩窗口（检查点边界已变）。仅在上面回调返回 Compressed 后调用；
         * 返回 null 时保留旧窗口（宁可多发一段原文，也不要拿错边界把检查点之后的内容再丢掉）。
         */
        refreshCompressionWindow: (() -> Conversation.CompressionWindow?)? = null,
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
         * 请求级会话语义 id（聊天路径传 conversationId）。
         *
         * 对齐上游 #2186：Responses 协议提供商把它当 `prompt_cache_key` / `X-Session-ID`，
         * 账号类提供商（ChatGPT `session-id`、Grok `x-grok-conv-id`）也用它命中服务端提示缓存。
         * 无头 run（SubagentRunner 等）传 null ⇒ 与改动前逐字节一致。
         */
        sessionId: String? = null,
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
        /**
         * Phone Use 回合授权：本轮已经批准过，同一回合跨审批续跑时不再弹卡。
         * 只对 `phone_use` 生效；其他工具的审批不受影响。
         */
        phoneUseApprovedForTurn: Boolean = false,
        /** 重复拒绝护栏（仅无头 run 传入；见 [DenialGuard] 类注释的覆盖盲区说明）。 */
        denialGuard: DenialGuard? = null,
        /**
         * 上下文预算守卫（见 [ContextBudgetGuard]）。
         *
         * 契约（design-tool-context-gate §6，修订自原「仅无头 run 传入」）：
         * 自动压缩 + `contextMessageLimit` 仍是第一道线（轮间起作用）；本守卫管的是
         * 工具循环**内部**的增长——每个工具执行前 evaluate，剩余余量 <4K 判
         * [GenerationNotice.ContextBudgetExhausted] 优雅停止。聊天路径现在也传入。
         */
        contextGuard: ContextBudgetGuard? = null,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        /**
         * 收尾闸门配置（`docs/design/design-deliverable-gate.md` §三）。null = 不挂闸
         * （默认——**普通聊天路径恒为 null**，行为逐位不变）。只在工具循环的「空工具分支」
         * 自然出口生效；判定与打回语义见 [evaluateLoopGate]。由有明确任务的调用方显式传入
         * （定时任务 run / 子代理 run），本类不做任何场景反查。
         */
        loopGate: LoopGateConfig? = null,
        /**
         * 首轮的「续写/补救提示」（只进本请求的系统提示、不写入对话历史）。
         * 手动「继续生成」用它把被截断的消息接着写下去（见 [CONTINUE_INCOMPLETE_HINT]）。
         */
        initialRecoveryHint: String? = null,
        /**
         * 每次工具结果回填后的**取消安全落点**（见 `commitToolResult`）。
         *
         * 为什么不是「把 emit 包进 `NonCancellable`」就够了：发射要经过 `flow`/`flowOn`，
         * 而生成协程被取消时这条链路必然断（`flow {}` 里加 `NonCancellable` 会直接抛
         * Flow invariant，`channelFlow` 的缓冲值也不会再被已取消的 collector 取走）。
         * 回调是**同步调用**，与 `messages = applyToolOutput(...)` 在同一段不可挂起代码里，
         * 因此取消请求无法把它撕开 ⇒ 调用方（`ChatService`）能在取消/停止后拿到最后一份
         * 已完成结果，补进会话内存态再落库。
         *
         * 默认 null：无头 run（`SubagentRunner`）自己维护 `currentMessages`，无需此落点。
         */
        onMessagesCommitted: ((List<UIMessage>) -> Unit)? = null,
        consumeStepNotices: (suspend (List<UIMessage>) -> List<UIMessage>)? = null,
        /**
         * 用量流水的归属标记（来源 / 助手 / 会话）。
         *
         * 本类是所有 provider 请求的唯一出口（聊天 / 子代理 / 记忆整理都经由它），因此流水在这里
         * 统一记录：每次 `generateText` / `streamText`（含重试的每一次尝试）各记一行。
         * 聊天主流程用默认值；标题、翻译等由调用方传自己的 [UsageSource]。
         */
        usageTag: UsageTag = UsageTag(UsageSource.CHAT),
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = usageRecorder.wrap(providerManager.getProviderByType(provider), usageTag)

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
        val fallbackProviderImpl = fallbackProvider?.let {
            usageRecorder.wrap(providerManager.getProviderByType(it), usageTag)
        }

        var committedNoticeMessages: List<UIMessage>? = null
        suspend fun runLoop(
            targetModel: Model,
            targetProvider: ProviderSetting,
            targetProviderImpl: Provider<ProviderSetting>,
        ) {
            var messages: List<UIMessage> = committedNoticeMessages ?: messages

            // 本轮的工具调用失控护栏：**由调用方传入**（挂在会话上），跨「审批续跑」累积。
            // 这里刻意不新建实例——真机实测每次批准都会重新进入 runLoop（step #0 重来），
            // 自建实例每次都被重置，护栏对审批类工具等于失效。
            // 护栏停止提示（三个来源共用、先到先得）：同签名循环 / workspace 预算耗尽 /
            // 重复拒绝达阈值。设一次，本批结果回填后停本轮（优雅停止）。
            var stopNotice: GenerationNotice? = null
            var stepsExecuted = 0
            // 发送窗口：中途压缩成功后检查点边界会变（见 [refreshCompressionWindow]），
            // 故本步用的窗口是可变值；未压缩时与入参逐位相同。
            var window: Conversation.CompressionWindow? = compressionWindow

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

            // 计划提醒（`plan_missing`）的两个输入，**每轮只算一次**：
            // ①本轮开始时分支里是否已有「仍在进行」的计划 —— 全完成的旧清单不算，否则同一会话里
            //   开新任务永远会被上一轮做完的清单压住；②计划工具是否可用 —— 不可用时绝不能叫模型
            //   去调一个不存在的工具（那只会白制造一次失败调用）。
            // 中途新建的计划由 `planWrittenThisTurn` 捕获，不必重扫消息。
            val planAtTurnStart = latestTodoPlan(messages).hasUnfinishedItems()
            // "计划工具可用"要**两层都成立**：①助手启用了 `todo_write`；②**模型真的收得到工具** ——
            // 请求体里 `tools` 的发送条件正是 `model.abilities.contains(TOOL)`（ChatCompletionsAPI:526）。
            // 只查 ① 会在"模型不支持工具"时叫模型去调一个它根本看不到的工具：
            // 真机假上游复现时踩到的就是这个 —— 临时 mock 模型没有 TOOL 能力、请求里一个 tools 都没有，
            // 提醒却照样发了（**这道闸门要防的正是这件事**，只是我原先只看了助手那一侧）。
            val planToolAvailable = tools.any { it.name == TODO_TOOL_NAME } &&
                targetModel.abilities.contains(ModelAbility.TOOL)
            var planWrittenThisTurn = false

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

            // Only between completed tool batches, never before approved tools or across an approval/guard stop.
            if (pendingTools.isEmpty() && messages.none { m -> m.getTools().any { it.isPending } }) {
                consumeStepNotices?.let { consume ->
                    val updated = consume(messages)
                    if (updated != messages) {
                        messages = updated
                        committedNoticeMessages = updated
                        onMessagesCommitted?.invoke(messages)
                        emit(GenerationChunk.Messages(messages))
                    }
                }
            }
            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                // 规则提醒：**每一步拼装前**求值 ⇒ 作用于下一步（本仓工具循环每步重拼提示词）。
                // 刻意放在 `if` 内：`reminder()` 有副作用（标记已提醒），算了不发等于白丢一次提醒。
                // 三个输入都是显式传参：预算比例取自会话上的 WorkspaceTurnBudget（撞上限前的那次
                // 提醒）、计划状态取自本轮开始时的分支 + 本轮的 todo_write、工具可用性取自工具表。
                val ruleReminder = ruleEngine.reminder(
                    RuleSnapshot(
                        budgetRatio = workspaceBudget.usageRatio(),
                        hasActivePlan = planAtTurnStart || planWrittenThisTurn,
                        planToolAvailable = planToolAvailable,
                    ),
                )
                try {
                    generateInternal(
                        assistant = assistant,
                        settings = settings,
                        messages = messages,
                        compressionWindow = window,
                        recoveryHint = pendingRecoveryHint,
                        ruleReminder = ruleReminder,
                        memoryDisabled = memoryDisabled,
                        frozenAnchorIds = frozenAnchorIds,
                        debugKey = debugKey,
                        sessionId = sessionId,
                        onUpdateMessages = { updated, coalescible ->
                            messages = updated.transforms(
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
                                    ),
                                    coalescible = coalescible,
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
                } catch (e: ContextGateHardStop) {
                    // 上下文闸 B 硬限：卸载后仍 ≥90%。此异常发生在**请求发出之前**（装配期），
                    // 不会进重试/fallback；本轮已生成内容都已在前面 emit，直接发停止提示收束。
                    // 不走 stopNotice 变量：executedTools 为空会先 break、丢通知（见循环尾注释）。
                    Log.w(
                        TAG,
                        "generateText: context gate hard stop (${e.usedTokens}/${e.windowTokens} tokens), stopping turn"
                    )
                    emit(
                        GenerationChunk.Notice(
                            GenerationNotice.ContextBudgetExhausted(
                                usedTokens = e.usedTokens,
                                windowTokens = e.windowTokens,
                            )
                        )
                    )
                    return
                }
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
                        // §14：续写路径的复述判据存在假阳性 ⇒ 该会话的命中率不可信，标记后
                        // 从 P0 决策门口的分子/分母里整体排除（保守口径，见 store 类注释）
                        debugKey?.let { key ->
                            runCatching { PurgeObservationStore.markGuardStopped(context.filesDir, key) }
                        }
                        stopNotice = GenerationNotice.ThinkingDegenerationStopped(
                            stalledCalls = stalled?.stalledCalls ?: thinkingGuard.stalledCallCount,
                            restated = stalled?.restated == true,
                            cutRule = cut?.rule,
                            repeated = cut?.repeated ?: 0,
                        )
                    }
                }

                // 本步已完整发起并收完一次 LLM 请求（置于各 break 之前防 off-by-one；
                // 审批恢复的迭代走 else 分支、不发请求，不计）
                stepsExecuted++

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
                    val hasVisibleOutput = last?.takeIf { it.role == MessageRole.ASSISTANT }
                        ?.parts.orEmpty().any { part ->
                            when (part) {
                                is UIMessagePart.Text -> part.text.isNotBlank()
                                is UIMessagePart.Image -> part.url.isNotBlank()
                                is UIMessagePart.Audio -> part.url.isNotBlank()
                                is UIMessagePart.Video -> part.url.isNotBlank()
                                is UIMessagePart.Document -> part.url.isNotBlank()
                                else -> false
                            }
                        }
                    // Safety refusals are explicit outcomes, not invitations to resend the prompt.
                    if (isContentFilterTruncation(last?.finishReason)) break
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
                    // no tool calls — 唯一自然出口（模型宣告结束）。收尾闸门
                    // （design-deliverable-gate.md §三）只挂在这一个出口：stopNotice /
                    // 上下文硬限 / maxSteps 耗尽 / 待审批等**环境掐断**的出口一律不触发
                    // （模型没宣告结束，打回只会雪上加霜）。普通聊天路径 loopGate 恒 null。
                    if (loopGate != null) {
                        when (val verdict = evaluateLoopGate(loopGate)) {
                            is LoopGateVerdict.Retry -> {
                                Log.i(TAG, "generateText: loop gate retry #${loopGate.retries}")
                                // 打回 = 一条**新的用户指令**（「补齐交付物/自检」），打回后模型
                                // 重复上一步的调用是合理复查而非失控——重置 LoopGuard 滑动窗口，
                                // 否则「闸门要求的复查」会被护栏误判成工具循环提前掐轮
                                // （2026-10-31 用户决议；详见 design-deliverable-gate.md §3.3）。
                                loopGuard.reset()
                                messages = messages + UIMessage.user(verdict.prompt)
                                emit(GenerationChunk.Messages(messages))
                                continue
                            }
                            LoopGateVerdict.Pass -> Unit
                        }
                    }

                    // 出口审计 R1（bigeye `action_chain.py` 动作对账，见 RuleEngine.unverifiedCompletionHint）：
                    // 声称完成、但最后一次写之后没有「跑一次 / 读回」的证据 ⇒ 注入一条提醒再给一轮机会。
                    // 不拦截：模型可以补验证，也可以明说未验证；每轮最多一次（引擎内 latch）。
                    val answerText = last?.parts.orEmpty()
                        .filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                    val completionEvidenceHint = ruleEngine.unverifiedCompletionHint(answerText)
                    if (completionEvidenceHint != null) {
                        Log.i(TAG, "streamText: unverified completion claim, injecting verification reminder")
                        // 与收尾闸门同理：提醒后的补验证不是失控，别让轮次循环护栏误判成打转
                        loopGuard.reset()
                        messages = messages + UIMessage.user(completionEvidenceHint)
                        emit(GenerationChunk.Messages(messages))
                        continue
                    }
                    break
                }

                // Check for tools that need approval
                // 审批判定留一条永远开着的诊断行：审批类问题（"该问却没问" / "不该问却问了"）
                // 只看结果无从判断，而这一行同时给出"工具定义在不在、要不要批、当前策略"
                val updatedTools = markPendingApprovals(
                    tools = tools,
                    requiresApproval = { tool ->
                        val toolDef = toolsInternal.find { it.name == tool.toolName }
                        val requiresApproval = toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            !(phoneUseApprovedForTurn && tool.toolName == PHONE_USE_TOOL_NAME)
                        if (requiresApproval) {
                            Log.i(
                                TAG,
                                "approval check: tool=${tool.toolName} state=${tool.approvalState} " +
                                    "autoDeny=$autoDenyApproval phoneUseGranted=$phoneUseApprovedForTurn",
                            )
                        }
                        requiresApproval
                    },
                    autoDeny = autoDenyApproval,
                )

                // If any tools were updated to Pending, update the message so the UI can ask
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

                // **不在这里 break**：本批里不需要审批的调用必须照常在本轮执行，
                // 待审批的那些由下方的「仍有待审批」判定在结果回填**之后**收尾。
                toolsToProcess = toolsToExecute(updatedTools)
            } else {
                // Resuming after user interaction: 执行**所有尚未执行**的调用（含 Auto）。
                // 不能筛 `canResumeExecution`——`Auto → false`，同批里只要还有一条待审批的调用，
                // 自动调用就会被整批漏掉（bug）。
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = toolsToExecute(messages.last().getTools())
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            // **每完成一个工具就回填并 emit 一次**（不再攒到整批之后统一回填）：
            // 取消/停止时已完成的结果已经在内存态里，`ChatService.persistStoppedGeneration`
            // （只读内存态）能读到；攒到最后统一回填的实现会把它们随局部列表一起丢掉。
            //
            // 回填的**取消安全**（本仓 2026-10-18 核查项 ①）：工具已算完、结果还没并进消息
            // 就被取消时，只包 `withContext(NonCancellable) { emit(...) }` 是**做不到**的——
            // `flow {}` 的 SafeCollector 会因 Job 变化抛 `Flow invariant is violated`
            // （coroutines 1.11），改用 `channelFlow` 也只能送进缓冲、已取消的 collector 不再取
            // （两条都实测过，用例见 `GenerationToolLoopTest`）。
            // 因此这里做两件事：
            // 1. 「合并 + 写旁路落点」放进 `NonCancellable`：这一段今天不含挂起点（语义等价），
            //    但把「已完成结果必须落地」的意图钉在唯一的回填点上——日后若在此处加落库/检查点
            //    这类挂起调用，取消在飞也不会把它半途撕开；
            // 2. 落点在 emit **之前**同步写给 `onMessagesCommitted`，由停止路径补进内存态，
            //    这条路径不依赖 flow（emit 仍照常尽力送达：collector 还活着时 UI 立刻可见）。
            // 注意：**工具执行本身不在 NonCancellable 内**（上面 `runCatching` 里的 execute 仍是
            // 可取消的），否则用户点停止后会继续跑到工具结束。
            val commitToolResult: suspend (UIMessagePart.Tool) -> Unit = { result ->
                executedTools += result
                val committed = withContext(NonCancellable) {
                    messages = applyToolOutput(messages, result)
                    onMessagesCommitted?.invoke(messages)
                    messages
                }
                emit(
                    GenerationChunk.Messages(
                        committed.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = targetModel,
                            assistant = assistant,
                            settings = settings
                        )
                    )
                )
            }
            // 同一批里「工具名 + 参数」完全相同的重复调用只真正执行一次，其余复用首个结果。
            // 为什么需要：部分提供商（实测 Gemini 系）会在一次响应里下发**多条内容相同、
            // 但 toolCallId 不同**的并行 tool_call，逐条执行会重复产生副作用
            // （实测 memory_tool create 被下发 2 条 → 重复建记忆）。
            // 注意：每个 toolCallId 都必须有 tool 结果（协议要求），故把首条输出复制回填给重复项。
            val executedSignature = mutableMapOf<String, List<UIMessagePart>>()
            var endTurnAfterTools = false

            // —— 车道并行（借鉴 NMoi D254「车道并行」，台账 nmoi-agent.md §13.4 候选 2）——
            // 批内**连续**的 concurrentSafe（只读）工具进并行车道，遇到串行工具即冲刷——
            // 写类/审批类工具之间保持原有顺序语义。关键约束：
            // - **护栏与去重的记录仍在派发前按调用顺序逐条做**（prepareExecutableTool），
            //   与串行完全一致——LoopGuard/ContextBudget 判定的是「模型要什么」，不是执行序；
            // - 只有「执行 + 回填」并发，结果按完成序回填（每个子协程算完立即 commit，
            //   与下方串行的逐个回填同款取消安全语义）；
            // - 同签名重复下发（Gemini 系实测）：串行靠「先执行先入 map」天然去重，并行车道
            //   里重复项的预检先于执行——挂起等首调用出结果再复用（deferredDuplicates），
            //   失败的首调用不进 map，重复项照常重走执行（与串行的失败重试语义一致）；
            // - endTurnAfterTools/stopNotice 会被并行子协程并发写：都是「置真/设一次」的
            //   单向写，竞态最坏情形是两个等价 stopNotice 互相覆盖，语义无害。
            val preparedSignatures = mutableSetOf<String>()
            val deferredDuplicates = mutableListOf<UIMessagePart.Tool>()
            val pendingLane = mutableListOf<PreparedTool>()
            val laneSemaphore = kotlinx.coroutines.sync.Semaphore(TOOL_LANE_MAX_CONCURRENCY)
            // 车道子协程只「合并进 messages + 写旁路落点」，**不 emit**——flow 的
            // SafeCollector 禁止跨协程并发发射（`GenerationToolLoopTest` 实测：
            // "Emission from another coroutine is detected"），发射统一由冲刷点在
            // join 之后按合并序补发。合并本身串行化在 laneMutex 里（executedSignature
            // 的并发写同样由它兜住）。
            val laneMutex = kotlinx.coroutines.sync.Mutex()
            val lanePendingEmissions = mutableListOf<List<UIMessage>>()

            suspend fun emitLaneSnapshots() {
                lanePendingEmissions.forEach { snapshot ->
                    emit(
                        GenerationChunk.Messages(
                            snapshot.transforms(
                                transformers = outputTransformers,
                                context = context,
                                model = targetModel,
                                assistant = assistant,
                                settings = settings
                            )
                        )
                    )
                }
                lanePendingEmissions.clear()
            }

            /**
             * 预检（顺序语义与串行一致）：去重 → 规则提醒/LoopGuard/ContextBudget 记录 →
             * 输出预算与执行窗口起点。返回 null = 该调用已被处理（复用提交或挂起等首调用）。
             */
            suspend fun prepareExecutableTool(tool: UIMessagePart.Tool): PreparedTool? {
                val signature = toolCallSignature(tool.toolName, tool.input, json)
                executedSignature[signature]?.let { priorOutput ->
                    Log.i(TAG, "generateText: duplicate ${tool.toolName} call skipped (same args), reused result")
                    commitToolResult(tool.copy(output = priorOutput))
                    return null
                }
                if (!preparedSignatures.add(signature)) {
                    // 同批先到的同签名调用还没出结果（并行车道执行中/失败重试中）：挂起，
                    // 冲刷后按结果复用或重走执行
                    deferredDuplicates += tool
                    return null
                }
                // 护栏记录发生在**发起时**（而非执行成功后）：判定的是「模型反复要什么」，
                // 失败时由下方 onFailure 用 forget 撤回（见 ToolCallLoopGuard 约束 2）。
                ruleEngine.record(tool.toolName, tool.inputAsJson())
                // 本轮写过计划 ⇒ `plan_missing` 立刻闭嘴（这是该条规则的"落地"形态）
                if (tool.toolName == TODO_TOOL_NAME) planWrittenThisTurn = true
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
                // 上下文闸 A：per-call 动态限幅（每次执行前按当前窗口占用算预算）；
                // 能力判定放 runCatching 外——成功/失败两条路径共用
                val outputBudget = toolOutputBudget(messages, targetModel)
                val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                val workspaceBound = toolsInternal.any { it.name == "workspace_read_file" }
                // 执行窗口起点：**护栏全过后、真正执行前**（不含审批等待——语义=执行耗时）
                val toolStartedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
                return PreparedTool(
                    tool = tool,
                    signature = signature,
                    outputBudget = outputBudget,
                    hasShellAccess = hasShellAccess,
                    workspaceBound = workspaceBound,
                    toolStartedAt = toolStartedAt,
                )
            }

            suspend fun runPreparedTool(prepared: PreparedTool) {
                val tool = prepared.tool
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
                    if (toolDef.shouldEndTurn(result)) endTurnAfterTools = true
                    // 空输出补占位：否则 isExecuted 仍为假 ⇒ 下一轮又被选出来重复执行
                    val output = finalizeToolOutput(
                        maybeTruncateToolOutput(
                            context,
                            tool,
                            result,
                            maxChars = prepared.outputBudget,
                            workspaceBound = prepared.workspaceBound,
                            hasShellAccess = prepared.hasShellAccess,
                        )
                    )
                    executedSignature[prepared.signature] = output
                    // 车道合并点：只合并不发射（见 laneMutex 处注释）；NonCancellable
                    // 保持与 commitToolResult 相同的「已完成结果必须落地」语义
                    withContext(NonCancellable) {
                        laneMutex.withLock {
                            val committed = tool.copy(
                                output = output,
                                startedAt = prepared.toolStartedAt,
                                finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                            )
                            messages = applyToolOutput(messages, committed)
                            executedTools += committed
                            onMessagesCommitted?.invoke(messages)
                            lanePendingEmissions += messages
                        }
                    }
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
                    loopGuard.markFailed(prepared.signature)
                    it.printStackTrace()
                    // 失败调用的错误文本（含堆栈）同样进入上下文 ⇒ 同样计费——否则
                    // 「每步换参数、每次都失败」的 shell 调用既不占输出预算也不占步数。
                    // 判定结果此处不设提示（成功路径统一负责），只记账。
                    // 错误文本同样受 per-call 限幅（堆栈可以非常长）；JSON 包裹后
                    // 截断会破坏结构，故在编码前硬切。
                    val errorText = buildString {
                        append("[${it.javaClass.name}] ${it.message}")
                        append("\n${it.stackTraceToString()}")
                    }.let { text ->
                        if (text.length > prepared.outputBudget) {
                            text.take(prepared.outputBudget) +
                                "\n[... error text truncated at ${prepared.outputBudget} chars]"
                        } else {
                            text
                        }
                    }
                    if (tool.toolName.startsWith(WorkspaceTurnBudget.TOOL_NAME_PREFIX)) {
                        workspaceBudget.record(errorText.length)
                    }
                    // 失败输出同样走车道合并点（只合并不发射，冲刷点统一补发）
                    withContext(NonCancellable) {
                        laneMutex.withLock {
                            val committed = tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive(errorText))
                                            }
                                        )
                                    )
                                ),
                                startedAt = prepared.toolStartedAt,
                                finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()),
                            )
                            messages = applyToolOutput(messages, committed)
                            executedTools += committed
                            onMessagesCommitted?.invoke(messages)
                            lanePendingEmissions += messages
                        }
                    }
                }
            }

            /** 冲刷并行车道（串行单工具路径也走这里以兑现挂起的重复调用）。 */
            suspend fun flushParallelLane() {
                if (pendingLane.isEmpty()) return
                val lane = pendingLane.toList()
                pendingLane.clear()
                if (lane.size == 1) {
                    runPreparedTool(lane.single())
                    emitLaneSnapshots()
                } else {
                    Log.i(TAG, "generateText: parallel lane with ${lane.size} concurrent-safe tools")
                    coroutineScope {
                        lane.forEach { prepared ->
                            launch { laneSemaphore.withPermit { runPreparedTool(prepared) } }
                        }
                    }
                    // join 之后按合并序补发（车道子协程不 emit，见 laneMutex 处注释）
                    emitLaneSnapshots()
                }
                // 兑现挂起的重复调用：首调用已出结果则复用；没出（首调用失败）则重走
                // 预检+执行，与串行的「失败调用重试」语义一致
                val deferred = deferredDuplicates.toList()
                deferredDuplicates.clear()
                deferred.forEach { tool ->
                    val signature = toolCallSignature(tool.toolName, tool.input, json)
                    val priorOutput = executedSignature[signature]
                    if (priorOutput != null) {
                        commitToolResult(tool.copy(output = priorOutput))
                    } else {
                        prepareExecutableTool(tool)?.let {
                            runPreparedTool(it)
                            emitLaneSnapshots()
                        }
                    }
                }
            }

            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // 冲刷车道：拒绝/应答/待审批的提交先于并行工具的结果进入消息序
                        flushParallelLane()
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
                        commitToolResult(
                            tool.copy(
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
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        flushParallelLane()
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        commitToolResult(
                            tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(answer)
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // 同批里待审批的调用：本轮不执行，等用户批准。
                        // （自动调用已在上面照常执行完，见 markPendingApprovals / toolsToExecute）
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        // 预检保持顺序语义（去重/护栏/预算，见 prepareExecutableTool）；
                        // 只读工具进并行车道，写类/未知工具冲刷车道后照旧串行执行
                        val prepared = prepareExecutableTool(tool)
                        if (prepared != null) {
                            val laneEligible = toolsInternal
                                .find { it.name == tool.toolName }?.concurrentSafe == true
                            if (laneEligible) {
                                pendingLane += prepared
                            } else {
                                flushParallelLane()
                                runPreparedTool(prepared)
                                emitLaneSnapshots()
                            }
                        }
                    }
                }
            }

            // 批内工具全部处理完：冲刷尾部车道（批内最后几条是只读工具时它们还挂在车道上）
            flushParallelLane()

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // 注：本批结果已在上面的执行段**逐个**回填并 emit（取消时已完成的不会丢），
            // 这里不再做整批回填。

            // 护栏命中（同签名循环 或 workspace 预算耗尽）：结果已全部回填（协议要求每个
            // toolCallId 都有结果，不能半途丢），现在提前结束本轮。**走正常结束路径**而非抛
            // 异常——抛异常会让本轮已生成内容只停留在内存（onSuccess 才落库），把护栏的
            // 「防失控」变成「丢内容」。
            stopNotice?.let { stopped ->
                emit(GenerationChunk.Notice(stopped))
                break
            }

            // 仍有待审批的调用：本轮里不需要审批的调用已经执行完并回填，现在结束等用户批准。
            // （审批阶段不能提前 break——那正是「同批自动调用被跳过」的 bug。）
            if (messages.last().getTools().any { it.isPending }) {
                Log.i(TAG, "generateText: waiting for tool approval")
                break
            }
            // A successful wait releases this generation normally; completed task events resume later.
            if (endTurnAfterTools) break

            // ---- 中途自动压缩（上游 bcc649abc 语义；本仓落点）----
            // 判据一：必须在一批工具**全部回填之后**。工具协议要求 tool_use 与 tool_result 成对出现，
            //   切在批中间会让发送窗口里出现「有调用没结果」的助手消息（部分 provider 直接 400），
            //   也可能让下一步的模型以为工具还没跑而重复调用。
            // 判据二：必须放在上面所有「提前结束」判定**之后**。护栏停止 / 待审批 / endTurn /
            //   最后一步都已有收尾结论，这时再花一次压缩请求毫无收益（压缩是预防手段），
            //   而且压缩请求自身也可能撞窗。
            // 判据三（做不做、压不压得动）在回调里：开关、阈值、同一条回复只试一次、防抖。
            if (midTurnCompress != null &&
                stepIndex < maxSteps - 1 &&
                messages.last().role == MessageRole.ASSISTANT
            ) {
                val budget = contextGuard?.evaluate(messages, overheadTokens)
                // 占用口径：守卫的 usage 锚点是「**上一次请求**发出去的量」，不含刚回填的
                // 工具输出（它们要下一次请求才计入）。而中途压缩的触发恰恰来自这批输出——
                // 只看锚点会晚一批，若这批输出直接把窗口撞满，压缩就来不及了（请求 400）。
                // 故取「锚点」与「本地按发送窗口估算的下一请求量」的较大者：
                // 锚点准、估算能看见新增，谁更能反映真实占用就用谁（同一量纲，见 utils/ContextLength）。
                val estimatedNext = runCatching {
                    estimateTokenCount(window?.applyTo(messages) ?: messages) +
                        overheadTokens.coerceAtLeast(0)
                }.getOrDefault(0)
                val outcome = midTurnCompress(
                    MidTurnCompressionRequest(
                        messages = messages,
                        replyMessageId = messages.last().id,
                        stepsExecuted = stepsExecuted,
                        usedTokens = maxOf(budget?.usedTokens ?: 0, estimatedNext),
                        windowTokens = budget?.windowTokens ?: 0,
                    ),
                )
                when (outcome) {
                    MidTurnCompressionOutcome.Compressed -> {
                        // 检查点插在**当前回复之后**，后续步骤写进新开的一条 assistant 回复。
                        // 为什么必须新开：当前回复连同它的工具结果已经被压进摘要（否则刚撑爆窗口的
                        // 那批输出原样留着，压缩等于白做）；若继续往同一条回复里追加，这段内容会
                        // 同时存在于摘要和原文里，模型看到重复内容。新回复此刻是空消息——请求面由
                        // `withoutTrailingEmptyAssistantForRequest` 裁掉（见 [generateInternal]）。
                        messages = messages + UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
                        window = refreshCompressionWindow?.invoke() ?: window
                        // 步数按「本条回复」重新计：统计徽标与正常单回复同口径，不把压缩前的步数带过来
                        stepsExecuted = 0
                        Log.i(TAG, "generateText: mid-turn compression done, continuing in a new reply")
                    }

                    MidTurnCompressionOutcome.Failed ->
                        Log.w(TAG, "generateText: mid-turn compression failed, continuing current reply")

                    MidTurnCompressionOutcome.NotNeeded -> Unit
                }
            }
        }

        // 轮级步数（上游 660b5d65f 面）：落在最后一条 assistant 消息上，>1 时统计行显示徽标。
        // 审批恢复是新一次 runLoop ⇒ 各自计数；取消中途不落。
        if (stepsExecuted > 1) {
            val lastMessage = messages.last()
            messages = messages.dropLast(1) + lastMessage.copy(stepCount = stepsExecuted)
            emit(GenerationChunk.Messages(messages))
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
        onUpdateMessages: suspend (List<UIMessage>, Boolean) -> Unit,
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
        /** 请求级会话 id（见 [generateText] 同名参数）；null = 不带。 */
        sessionId: String? = null,
    ) {
        // 工具 schema 压缩（批次4，design §ToolSchemaCompactor）：只裁描述文本不改参数/required，
        // 默认 OFF。**只作用请求面**——工具执行查找走 runLoop 的 toolsInternal（未压缩原名表），
        // 两边按 name 对齐，互不影响。
        val requestTools = ToolSchemaCompactor.compact(
            tools = tools,
            level = ToolSchemaCompactor.Level.from(settings.toolSchemaCompactLevel),
        )
        // 请求面两处适配（中途压缩，见 [generateText] 的 `midTurnCompress`）：
        // ① 新开的那条回复此刻还没有任何 part——它只是**流式回填的落点**，不能当请求内容发出去；
        // ② 检查点后面不是用户消息时给检查点追加续跑提示。②必须在**应用窗口后的窗口消息**上判断
        //    （`withContinuationHintFor` 内部做）：装配产物尾部还有记忆数据/规则提醒两条合成 USER
        //    消息，拿它判断会被骗成「后面是用户消息」，续跑提示就永远不出现。
        val requestBasis = messages.withoutTrailingEmptyAssistantForRequest()
        val sendWindow = compressionWindow?.withContinuationHintFor(requestBasis)
        // 组装发送消息：**与「拼装预览」共用同一个函数**（PromptAssembly.kt）——
        // 原先此段内联实现，预览只能另写一份、必然漂移。改这里即同时改预览。
        val assembly = buildInternalMessagesWithPlan(
            assistant = assistant,
            settings = settings,
            model = model,
            messages = requestBasis,
            memories = memories,
            tools = requestTools,
            conversationSystemPrompt = conversationSystemPrompt,
            compressionWindow = sendWindow,
            recoveryHint = recoveryHint,
            ruleReminder = ruleReminder,
            memoryDisabled = memoryDisabled,
            frozenAnchorIds = frozenAnchorIds,
            // 净化 P0 dry-run（design-context-purification §7/§11）：不改发送，只记报告 + 日志；
            // debugKey 为空的无头 run（SubagentRunner）天然跳过。
            purgeObserver = debugKey?.let { key ->
                { report ->
                    val flipped = PromptDebugState.recordPurge(key, report)
                    // P0 持久计数（design-context-purification §7 的持久化缺口）：内存 LRU 与
                    // Logcat 都会丢，决策门双口径需要跨进程累计。失败不影响生成（store 内部自吞）。
                    runCatching {
                        PurgeObservationStore.record(
                            filesDir = context.filesDir,
                            sessionKey = key,
                            groupKey = "${provider.name}/${model.modelId}",
                            result = report,
                            flipped = flipped,
                        )
                    }
                    if (report.decisions.isNotEmpty()) {
                        Log.i(
                            TAG, "purge.dryrun window=${report.windowCount} " +
                                "decisions=${report.decisions.size} savedChars=${report.savedChars} :: " +
                                report.decisions.joinToString("; ") {
                                    "${it.rule}@${it.messageIndex}.${it.partIndex} ${it.detail.take(60)}"
                                },
                        )
                    }
                    if (flipped) {
                        Log.w(TAG, "purge.dryrun FLIP: same window hash but decisions changed (§4.1 观察项)")
                    }
                }
            },
        )
        val internalMessages = assembly.messages
        // 上下文闸 B（design-tool-context-gate §5）：**请求前**对实发载荷做紧急卸载。
        // 挂在装配产物上而非原始会话：压缩窗口/上下文条数裁剪之后才是真正要发的东西，
        // 在原始会话上估算是白算。改写的是每请求临时物 ⇒ 天然投影、零持久化副作用；
        // DB/UI 里的全文是权威，guard 文件只是发送面备份。
        // 85% 触发卸载；卸完仍 ≥90% 抛 [ContextGateHardStop]，由步骤循环转成优雅停止
        // （不采 Yuihub 的 throw 直达失败——本仓护栏家族一律优雅停止）。
        // 无工具的请求（标题/翻译）没有 Tool part，算法自然空转，无需特判。
        val gatedMessages = enforceContextBudget(context, internalMessages, model)
        if (assistant.enableMemory && !memoryDisabled) {
            val plan = assembly.memoryPlan
            Log.i(TAG, "memory.inject budget=${plan.budgetTokens} estimated=${plan.totalEstimatedTokens} " +
                "counter=${plan.estimationMode} l0=${plan.disciplineTokens} l1=${plan.anchorTokens} " +
                "tail=${plan.dataTokens} anchors=${plan.anchors.size} full=${plan.fullText.size} " +
                "index=${plan.indexed.size} omitted=${plan.omittedCount} " +
                "tasks=${plan.tasks.size} taskOmitted=${plan.taskOmittedCount}")
        }
        // 输入处理链（时间提醒 / 模式注入 / 世界书 / ST 宏 / 占位符 / 文档转提示 / OCR / 引用 /
        // 助手模板 / workspace 提醒）：**返回值必须接住**——它才是真正发给 provider 的列表。
        //
        // 2026-10-06 修复：此处曾写成裸语句 `internalMessages.transforms(...)`（`d63f649d2` 改造
        // 前缀分叉诊断时把赋值丢了），链的产物被丢弃、请求发的是**未处理**列表，而拼装预览
        // （`PromptInspector` 那一份有 `val transformed =`）照常显示注入 ⇒「预览里有、实发里没有」，
        // 注入 / 时间提醒 / 引用展开 / OCR / 文档转提示全部静默失效。
        // 守卫测试：`RequestMessagePipelineGuardTest`（源码扫描，防止再次丢赋值）。
        // 发送前护栏：工具 call / 结果数量配平（纯派生，不落库、不改 UI）——挂在链的产物上，
        // 因为这里是**所有 provider 的唯一请求出口**（chat / subagent / 记忆整理都经由本函数）。
        // 为什么链尾还要再兜一遍：`SubagentRunner` 传的是它自己的 `inputTransformers`（可能为空链），
        // 只靠 ChatService 那条链接入会漏掉它。已配平时返回**同一个实例**，零开销。
        // 见 transformers/ToolResultBalancingTransformer.kt。
        val requestMessages = gatedMessages.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        ).balancedForRequest()
        // 前缀分叉诊断：缓存**实际发送**的消息列表（即处理链之后的那份，单次请求差分才有意义）
        debugKey?.let { PromptDebugState.recordRequest(it, requestMessages) }

        var messages: List<UIMessage> = messages
        val reasoningPreset = resolveReasoningPreset(model, provider, assistant.reasoningPresetId)
        val params = TextGenerationParams(
            model = model.withReasoningPreset(reasoningPreset),
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = requestTools,
            reasoningLevel = reasoningPreset?.level ?: assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            sessionId = sessionId,
        )
        if (stream) {
            withGenerationRetry(
                context,
                providerSetting = provider,
                settings = settings,
                processingStatus = processingStatus,
                maxRetries = retryBudgetOverride,
            ) { provider ->
                // 多步工具轮：上一步收尾写下的 finishedAt 若不清掉，实时 tok/s 的闸门
                // （loading && finishedAt == null）从这一步起恒为 false 不再显示，
                // 且 UI 会提前显示「终值」tok/s 卡片——新一步开始即清除
                messages.lastOrNull()?.takeIf { it.finishedAt != null }?.let { last ->
                    messages = messages.dropLast(1) + last.copy(finishedAt = null)
                    onUpdateMessages(messages, false)
                }
                // 快照：流中途失败时回滚已输出的部分内容，重试从干净状态重新生成
                val snapshot = messages
                // 本步 usage 单独记账（chunk 级 merge 是"新值替换"语义，只反映本步），
                // 与进入本步前的累计值相加得到全轮口径，见 TokenUsage.plus
                val roundBaseUsage = messages.last().usage ?: TokenUsage()
                var stepUsage: TokenUsage? = null
                // 流结束语义：本次流是否见到 provider 的显式终止事件（[StreamChunk.Finish]：
                // `[DONE]` / `message_stop` / `response.completed` / 带 finishReason 的末块）。
                // 没有 = 裸断流（网关/中转提前切连接）——此前与「正常收尾」无法区分，整轮被当成功。
                var sawExplicitEnd = false
                var finishReason: String? = null
                var receivedAssistantContent = false
                // 每步一个 chunk 处理器（移植自上游 #1648）：内部维护 open text/reasoning/image 的
                // 索引与 toolCallId → part 的映射，逐条把 StreamChunk 折叠进消息列表。
                val chunkHandler = StreamChunkHandler(model = model, providerName = provider.name)
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
                            if (chunk.hasGenerationContent()) {
                                receivedAssistantContent = true
                            }
                            // "unknown" 是 chat/completions 解析层「字段缺失」的占位，归一成 null
                            (chunk as? StreamChunk.Finish)?.finishReason
                                ?.takeIf { it.isNotBlank() && it != "unknown" }
                                ?.let { finishReason = it }
                            messages = chunkHandler.handle(messages, chunk)
                            // 显式结束标记由 handler 记录（本次流已处理过 Finish）
                            if (chunkHandler.finished) sawExplicitEnd = true
                            (chunk as? StreamChunk.Usage)?.usage?.let { usage ->
                                stepUsage = stepUsage.merge(usage)
                                messages = messages.mapIndexed { index, message ->
                                    if (index == messages.lastIndex) {
                                        message.copy(
                                            usage = roundBaseUsage.accumulateRound(stepUsage ?: TokenUsage()),
                                            // #2307：同一次写入顺带记「本步自己的 usage」——统计行的输入/
                                            // 缓存要显示最近一次请求，而 usage 是整轮口径
                                            lastUsage = stepUsage,
                                        )
                                    } else {
                                        message
                                    }
                                }
                            }
                            onUpdateMessages(
                                messages,
                                chunk is StreamChunk.TextDelta || chunk is StreamChunk.ReasoningDelta ||
                                    chunk is StreamChunk.ToolCallDelta || chunk is StreamChunk.ServerToolInputDelta ||
                                    chunk is StreamChunk.Usage,
                            )
                            // 判定放在**本条已并入 messages 且已回调之后**：断流不丢已生成内容
                            val deltaVerdict = degenerationGuard?.onDelta(chunk.toGuardDelta())
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
                    // Only output from THIS request counts: an earlier tool result or continuation
                    // must not turn a completely empty 200 response into success.
                    requireAssistantResponse(receivedAssistantContent, finishReason)
                    if (!receivedAssistantContent && isContentFilterTruncation(finishReason) &&
                        messages.lastOrNull()?.role != MessageRole.ASSISTANT
                    ) {
                        messages = messages + UIMessage(
                            role = MessageRole.ASSISTANT, parts = emptyList(), modelId = model.id,
                        )
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
                    onUpdateMessages(messages, false)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    val plan = provider.retryOption ?: settings.retrySetting
                    // #1812 分流：已流式输出可见内容后断开 → 保留为草稿（不再回滚清空）；
                    // 阈值可配（partialOutputMinChars），防呆下限 1（避免误配 0/负数把零输出也当半截）
                    if (e !is EmptyGenerationResponseException && plan.keepPartialOnFailure && !plan.retryAfterPartial &&
                        isPartialOutput(messages, plan.partialOutputMinChars.coerceAtLeast(1))
                    ) {
                        throw GenerationPartialFailureException(e, messages)
                    }
                    messages = snapshot
                    onUpdateMessages(messages, false)
                    throw e
                }
            }
        } else {
            withGenerationRetry(
                context,
                providerSetting = provider,
                settings = settings,
                processingStatus = processingStatus,
                maxRetries = retryBudgetOverride,
            ) { provider ->
                // 同流式路径：清上一步的 finishedAt + usage 按全轮口径累计
                messages.lastOrNull()?.takeIf { it.finishedAt != null }?.let { last ->
                    messages = messages.dropLast(1) + last.copy(finishedAt = null)
                    onUpdateMessages(messages, false)
                }
                val snapshot = messages
                val roundBaseUsage = messages.last().usage ?: TokenUsage()
                try {
                    val result = providerImpl.generateText(
                        providerSetting = provider,
                        messages = requestMessages,
                        params = params,
                    )
                    messages = messages.handleTextGenerationResult(
                        result = result,
                        model = model,
                        providerName = provider.name,
                    )
                    result.usage?.let { usage ->
                        messages = messages.mapIndexed { index, message ->
                            if (index == messages.lastIndex) {
                                message.copy(
                                    usage = roundBaseUsage.accumulateRound(usage),
                                    // #2307：本次请求自己的 usage（同流式路径同一语义）
                                    lastUsage = usage,
                                )
                            } else {
                                message
                            }
                        }
                    }
                    requireAssistantResponse(
                        receivedContent = result.message.role == MessageRole.ASSISTANT &&
                            result.message.parts.hasGenerationContent(),
                        finishReason = result.finishReason,
                    )
                    // 非流式：整包返回（要么完整要么抛错）⇒ 不存在裸断流，只记 finish_reason
                    messages = messages.markLastFinish(
                        reason = result.finishReason?.takeIf { it.isNotBlank() && it != "unknown" },
                        incomplete = false,
                    )
                    onUpdateMessages(messages, false)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    messages = snapshot
                    onUpdateMessages(messages, false)
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


    /**
     * 翻译生成（批次 10 拆出，见 [TranslationGenerator]）：公开签名不变，
     * 唯一外部调用方 ChatService 经此委托。
     */
    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = translationGenerator.translateText(settings, sourceText, targetLanguage, onStreamUpdate)
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

/** A valid HTTP response with no assistant output is a failure, not a successful user message. */
internal class EmptyGenerationResponseException : java.io.IOException(
    "The model returned an empty response. Please retry or choose another model."
)

private fun List<UIMessagePart>.hasGenerationContent(): Boolean =
    !isEmptyUIMessage() || any { it is UIMessagePart.Reasoning && it.metadata != null }

/** 该流式事件是否带来**可渲染内容**（用于整步「空响应」判定，口径与 `StreamChunk` 的内容事件一致）。 */
private fun StreamChunk.hasGenerationContent(): Boolean = when (this) {
    is StreamChunk.TextDelta -> text.isNotBlank()
    is StreamChunk.ReasoningDelta -> text.isNotBlank() || metadata != null
    is StreamChunk.ToolCallStart, is StreamChunk.ToolCallDelta, is StreamChunk.ToolCallEnd,
    is StreamChunk.ToolCallOutput -> true
    is StreamChunk.ServerToolStart, is StreamChunk.ServerToolInputDelta,
    is StreamChunk.ServerToolInputEnd, is StreamChunk.ServerToolEnd -> true

    is StreamChunk.ImageStart, is StreamChunk.ImageDelta, is StreamChunk.ImageSnapshot -> true
    else -> false
}

/**
 * 把流式事件归一成 [ThinkingDegenerationGuard] 认识的 delta 形态。
 *
 * 护栏只看「文本/推理增量」与「是否有工具增量」，故推理增量直接映射成 Text——
 * 两者在 `onDelta` 里被拼进同一个字符串比较，语义等价。
 */
private fun StreamChunk.toGuardDelta(): UIMessage? {
    val parts: List<UIMessagePart> = when (this) {
        is StreamChunk.TextDelta -> listOf(UIMessagePart.Text(text))
        is StreamChunk.ReasoningDelta -> listOf(UIMessagePart.Text(text))
        is StreamChunk.ToolCallStart -> listOf(
            UIMessagePart.Tool(toolCallId = id, toolName = toolName, input = "", output = emptyList())
        )

        is StreamChunk.ToolCallDelta -> listOf(
            UIMessagePart.Tool(toolCallId = id, toolName = "", input = inputDelta, output = emptyList())
        )

        is StreamChunk.ToolCallEnd -> listOf(
            UIMessagePart.Tool(toolCallId = id, toolName = "", input = "", output = emptyList())
        )

        is StreamChunk.ToolCallOutput -> listOf(
            UIMessagePart.Tool(toolCallId = id, toolName = "", input = "", output = emptyList())
        )

        is StreamChunk.ServerToolStart -> listOf(
            UIMessagePart.Tool(toolCallId = id, toolName = toolName, input = "", output = emptyList())
        )

        is StreamChunk.ServerToolInputDelta -> listOf(
            UIMessagePart.Tool(toolCallId = id, toolName = "", input = inputDelta, output = emptyList())
        )

        is StreamChunk.ServerToolInputEnd -> listOf(
            UIMessagePart.Tool(toolCallId = id, toolName = "", input = "", output = emptyList())
        )

        is StreamChunk.ServerToolEnd -> listOf(
            UIMessagePart.Tool(toolCallId = id, toolName = "", input = "", output = emptyList())
        )

        else -> return null
    }
    return UIMessage(role = MessageRole.ASSISTANT, parts = parts)
}

internal fun requireAssistantResponse(receivedContent: Boolean, finishReason: String?) {
    // Preserve explicit safety outcomes so the UI can explain them without retrying the same prompt.
    if (!receivedContent && !isContentFilterTruncation(finishReason)) throw EmptyGenerationResponseException()
}

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
