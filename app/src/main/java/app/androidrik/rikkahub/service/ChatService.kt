package app.androidrik.rikkahub.service

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.appendInterruptionNotice
import app.androidrik.ai.ui.isOutputLimitTruncation
import app.androidrik.ai.ui.isProviderSigned
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.canResumeToolExecution
import app.androidrik.ai.ui.finishPendingTools
import app.androidrik.ai.ui.finishReasoning
import app.androidrik.ai.ui.isEmptyInputMessage
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.CONTINUE_TRUNCATED_HINT
import app.androidrik.rikkahub.data.ai.CONTINUE_INCOMPLETE_HINT
import app.androidrik.rikkahub.data.ai.GenerationChunk
import app.androidrik.rikkahub.data.ai.GenerationNotice
import app.androidrik.rikkahub.data.ai.GenerationPartialFailureException
import app.androidrik.rikkahub.data.ai.GenerationHandler
import app.androidrik.rikkahub.data.ai.parseLoopGuardSensitivity
import app.androidrik.rikkahub.data.ai.PromptInspection
import app.androidrik.rikkahub.data.ai.PromptInspectionLabels
import app.androidrik.rikkahub.data.ai.inspectPrompt
import app.androidrik.rikkahub.data.ai.WorkspaceTurnBudget
import app.androidrik.rikkahub.data.ai.selectMemoriesForPrompt
import app.androidrik.rikkahub.data.ai.shouldGenerateTitle
import app.androidrik.rikkahub.data.ai.ThinkingDegenerationGuard
import app.androidrik.rikkahub.data.ai.mcp.McpManager
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import app.androidrik.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import app.androidrik.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import app.androidrik.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import app.androidrik.rikkahub.data.ai.transformers.OcrTransformer
import app.androidrik.rikkahub.data.ai.transformers.PlaceholderTransformer
import app.androidrik.rikkahub.data.ai.transformers.PromptInjectionTransformer
import app.androidrik.rikkahub.data.ai.transformers.RegexOutputTransformer
import app.androidrik.rikkahub.data.ai.transformers.QuoteTransformer
import app.androidrik.rikkahub.data.ai.transformers.TemplateTransformer
import app.androidrik.rikkahub.data.ai.transformers.ThinkTagTransformer
import app.androidrik.rikkahub.data.ai.transformers.TavernMacroTransformer
import app.androidrik.rikkahub.data.ai.transformers.TimeReminderTransformer
import app.androidrik.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import app.androidrik.rikkahub.data.event.AppEvent
import app.androidrik.rikkahub.data.event.AppEventBus
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.datastore.getAssistantById
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.datastore.getCurrentChatModel
import app.androidrik.rikkahub.data.datastore.resolveCompressionModel
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.utils.DEFAULT_CONTEXT_LENGTH
import app.androidrik.rikkahub.utils.effectiveContextLength
import app.androidrik.rikkahub.utils.estimateTokens
import app.androidrik.rikkahub.utils.estimateWindowTokens
import app.androidrik.rikkahub.data.ai.compression.shouldAutoCompress
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_RETAIN_RATIO
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_TARGET_TOKENS
import app.androidrik.rikkahub.data.ai.compression.estimateCompressionPlan
import app.androidrik.rikkahub.data.ai.compression.splitIntoChunks
import app.androidrik.rikkahub.data.model.markGeneratingNodeIncomplete
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.service.QueuedMessage
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.data.model.localFileUrls
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantAffectScope
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.TruncationPlan
import app.androidrik.rikkahub.data.model.nodeIndexOfMessage
import app.androidrik.rikkahub.data.model.regenerateBaselineRange
import app.androidrik.rikkahub.data.model.regenerateReplyNodeIndex
import app.androidrik.rikkahub.data.model.restoreAfterTruncation
import app.androidrik.rikkahub.data.model.truncationPlan
import app.androidrik.rikkahub.data.model.replaceRegexes
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.FolderRepository
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.web.BadRequestException
import app.androidrik.rikkahub.web.NotFoundException
import app.androidrik.rikkahub.utils.applyPlaceholders
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/**
 * 打断当前轮次的**来源**（2026-10-13 用户拍板：两条路径都写 `Denied`，只是理由要能区分）。
 *
 * 为什么要区分：两种打断对用户的含义不同——「点停止」是**我主动不要这轮了**；
 * 「插队发送」是**我要先说这句、这轮让路**。审批卡片上写清是哪一种，
 * 用户回看时才知道那个未执行的工具是被自己取消的、还是被插队顺带拒掉的。
 *
 * 理由是给**人**看的（落到 `ToolApprovalState.Denied.reason`、在卡片上显示），故直接给文案；
 * 工具结果里的 error 串是给**模型**看的（英文、与既有 `cancelled` 形态一致）。
 *
 * 可见性是 `public` 而非 `internal`：它出现在 [ChatService.stopGeneration] 的签名上，
 * 而后者是 public（跨包被 UI / web 路由调用）——Kotlin 不允许 public 函数暴露 internal 类型。
 * 语义上它仍只是聊天打断流程的参数，不打算给外部随意构造。
 */
enum class InterruptReason(
    /** 显示在审批卡片上的理由（人看） */
    val approvalReason: String,
    /** 写进工具结果的错误串（模型看） */
    val toolOutputError: String,
) {
    /** 用户主动点「停止」 */
    UserCancelled(
        approvalReason = "已取消本轮生成，未执行该工具",
        toolOutputError = "Generation cancelled by user before tool execution completed.",
    ),

    /** 用户在有内容生成时直接发送新消息（插队），当前轮次让路 */
    QueueJumped(
        approvalReason = "插队发送，本轮让路，未执行该工具",
        toolOutputError = "Generation interrupted by a newer user message before tool execution completed.",
    ),
}

/** 用户消息单点最大字符数：超长消息存库膨胀 + 上下文爆表 + API 413 */
private const val MAX_USER_INPUT_CHARS = 200_000

/**
 * 流式生成检查点的最小落库间隔。
 *
 * 为什么不每个 chunk 都落库：一个 chunk 一次事务在高速流（>50 chunk/s）下会把 IO 打满，
 * 也是 ANR 风险（与上下文占用估算那次同类的「高频 × 单次成本」乘积问题）。2 秒是
 * 「崩溃最多丢 2 秒内容」与「写放大可忽略」的折中。
 */
private const val STREAM_CHECKPOINT_INTERVAL_MS = 2_000L

/**
 * 将压缩结果合并到最新会话状态，而不是用压缩开始时的快照整体覆盖。
 *
 * compressConversation 要跑多个 LLM 请求（长对话可达数十秒~数分钟），期间用户可能继续
 * 发消息/生成回复（追加在会话末尾）。旧实现用快照 copy 后整体 saveConversation，会把
 * 压缩期间的新消息连内存带库一起覆盖丢失。此函数保留快照之后追加的新节点，并基于
 * latest 而非快照做 copy，避免把期间更新的 title/folderId 等字段回滚成旧值。
 */
fun mergeCompressedConversation(
    snapshot: Conversation,
    latest: Conversation,
    compressedNodes: List<MessageNode>,
): Conversation = latest.copy(
    messageNodes = compressedNodes + latest.messageNodes.drop(snapshot.messageNodes.size),
    chatSuggestions = emptyList(),
)

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

/** 标题尾部的 `(序号)` 后缀。 */
private val forkTitleSuffixRegex = Regex("""\((\d+)\)$""")

/**
 * 分叉会话的标题：`源标题(序号)`，取**第一个未被占用**的序号。
 *
 * **为什么需要**（上游 `458c16df`）：此前分叉出的会话标题为空，侧栏里一堆「新对话」分不清谁是谁。
 * 更隐蔽的一层：空标题会让既有自动起名（`generateTitle` 仅在 `title.isBlank()` 时触发）在首条消息后
 * 把它改成一个**与源会话无关**的名字，「这是从哪条分出来的」这条线索就断了。
 *
 * **三处有意与上游不同**（fork 手工适配，见 `docs/upstream-analysis.md`）：
 * 1. **源标题为空时保持为空**，不生成 `(1)`。上游写法在源标题为空时会产出 `"(1)"`——
 *    这既没有信息量，又因为「非空」导致自动起名**永远不再修正它**（`shouldGenerate` 要求 isBlank）。
 *    保持空白才能让自动起名在首条消息后正常命名。
 * 2. **序号取第一个未占用值**，而不是「已有数量 + 1」。用户删掉某次分叉后再分叉会复用空出的序号，
 *    避免序号无界增长（与上游 `generateSequence` 的「找第一个空位」语义一致，此处只是把它显式化并加测试）。
 * 3. **入参是 [Conversation] 而非标题字符串**（上文本地化差异，保留下沉）。
 *
 * **已有序号递增而非叠加**（上游 `95fed05e` / #1961，本仓同款缺陷）：源标题本身就带 `(N)` 时
 * （对 `Chat(1)` 再分叉），旧写法产出 `Chat(1)(1)`、再分叉成 `Chat(1)(1)(1)`。
 * 现在先剥掉尾部的 `(N)` 作为基名、从 `N+1` 起找空位 ⇒ `Chat(2)`、`Chat(3)`……
 * 非数字后缀（`Chat(abc)`）不算序号，仍走「基名 + 新序号」路径（`Chat(abc)(1)`）。
 *
 * 抽成独立纯函数是为了能上 JVM 单测——Composable/Service 内的内联逻辑测不到，
 * 而「标题怎么取」正是本功能唯一的行为契约。
 *
 * @param existingTitles 该助手下**已有**会话的标题集合（用于避让）
 */
internal fun forkConversationTitle(source: Conversation, existingTitles: Set<String>): String {
    if (source.title.isBlank()) return ""
    val suffix = forkTitleSuffixRegex.find(source.title)
    val baseTitle = suffix?.let { source.title.removeRange(it.range) } ?: source.title
    // 源标题已带 (N) 时从 N+1 起编号，避免叠加成 xxx(1)(1)
    val start = suffix?.groupValues?.get(1)?.toIntOrNull()?.plus(1) ?: 1
    return generateSequence(start) { it + 1 }
        .map { "$baseTitle($it)" }
        .first { it !in existingTitles }
}

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
    /** 该助手下已有会话的标题（避让序号）；默认空集只服务于单测等场景 */
    existingTitles: Set<String> = emptySet(),
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    title = forkConversationTitle(source, existingTitles),
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

/**
 * 该不该再报一条「配置缺失」错误（#1975 的防刷屏契约，抽成纯函数以便单测）。
 *
 * 规则：**同一会话 + 同一 [solution] 只报一次**。用户点掉后（列表里没有了）自然恢复上报。
 * 跨会话不抑制——每个会话各提示一次是合理的（用户可能在新会话里才注意到）。
 */
internal fun shouldReportConfigError(
    existing: List<ChatError>,
    conversationId: Uuid,
    solution: ChatErrorSolution,
): Boolean = existing.none { it.conversationId == conversationId && it.solution == solution }

/**
 * 「编辑思考过程」的**纯函数核**（#1929）：按 messageId + Reasoning part 的创建时刻
 * 原地替换推理文本，不新建分支、其余 part 与消息字段（含 createdAt/finishedAt）一个不动。
 *
 * 目标消息或 Reasoning part 不存在时返回 null（= 无操作），由调用方区分「没找到」
 * 与「改好了」。**纯函数**便于 JVM 单测。
 */
internal fun updateMessageReasoningInNodes(
    nodes: List<MessageNode>,
    messageId: Uuid,
    reasoningCreatedAt: kotlin.time.Instant,
    reasoningText: String,
): List<MessageNode>? {
    var edited = false
    val updated = nodes.map { node ->
        if (node.messages.none { it.id == messageId }) {
            node
        } else {
            node.copy(messages = node.messages.map { msg ->
                if (msg.id != messageId) {
                    msg
                } else {
                    var touched = false
                    val newParts = msg.parts.map { part ->
                        if (part is UIMessagePart.Reasoning &&
                            part.createdAt == reasoningCreatedAt &&
                            // Claude 签名 thinking / Gemini thoughtSignature：必须原样回传，编辑会被上游拒绝
                            // ⇒ 纯函数核直接拒绝（无操作）；UI 侧编辑入口同时置灰（2026-10-12 由「只警告」改为「禁用」）。
                            !part.isProviderSigned()
                        ) {
                            touched = true
                            part.copy(reasoning = reasoningText)
                        } else {
                            part
                        }
                    }
                    if (touched) edited = true
                    msg.copy(parts = newParts)
                }
            })
        }
    }
    return if (edited) updated else null
}

/**
 * 工具调用失控护栏停止本轮（见 `data/ai/ToolCallLoopGuard.kt`）。
 *
 * 刻意**不是**「生成失败」：本轮已正常结束、内容照常落库，它只用于把「为什么停了」
 * 变成一条用户可见提示（[ChatError.title] 给本地化说明，本异常只携带技术细节）。
 */
class ToolCallLoopStoppedException(
    val toolName: String,
    val repeats: Int,
) : Exception("$toolName ×$repeats")

/**
 * **思考退化护栏**停止本轮（见 `data/ai/ThinkingDegenerationGuard.kt`）——零工具调用的退化形态：
 * 连续纯思考/复述上一步，或单次调用内逐字重复（已断流）。
 *
 * 与 [ToolCallLoopStoppedException] 同款：刻意**不是**「生成失败」，本轮已正常结束、
 * 已生成内容照常落库，它只用于把「为什么停了」变成一条用户可见提示。
 */
class ThinkingDegenerationStoppedException(
    val stalledCalls: Int,
    val restated: Boolean,
    val cutRule: ThinkingDegenerationGuard.Rule?,
    val repeated: Int,
) : Exception(
    "thinking degeneration: stalled=$stalledCalls restated=$restated " +
        "cut=${cutRule ?: "none"} repeated=$repeated"
)

class WorkspaceBudgetExhaustedException(
    val usedChars: Long,
    val budgetChars: Long,
    val steps: Int,
    val by: WorkspaceTurnBudget.ExceedReason,
) : Exception("workspace budget: $usedChars/$budgetChars chars, $steps steps")

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        TavernMacroTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
        QuoteTransformer,
    )
}

internal val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val chatToolFactory: ChatToolFactory,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    /**
     * 「配置缺失」类提示的**去重**上报（#1975）。
     *
     * 为什么必须去重：标题与建议生成在**每轮生成后**都会尝试一次，模型没配好时不去重就会
     * 每轮弹一张错误卡把用户刷屏（真问题反而被淹没）。同一会话内同一 [solution] 只报一次；
     * 用户点掉后（列表里已无该条）下次仍会重新提醒。
     *
     * 顺带解决「标题与建议同时失败」的重复：两者共用同一个 [ChatErrorSolution]，
     * 故一轮对话最多出一张卡。
     */
    private fun addConfigErrorOnce(
        conversationId: Uuid,
        error: Throwable,
        title: String,
        solution: ChatErrorSolution,
    ) {
        if (!shouldReportConfigError(_errors.value, conversationId, solution)) return
        addError(error = error, conversationId = conversationId, title = title, solution = solution)
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                loopGuardSensitivity = parseLoopGuardSensitivity(settings.loopGuardSensitivity),
                turnBudgetChars = settings.workspaceTurnBudgetChars,
                turnBudgetSteps = settings.workspaceTurnMaxSteps,
                onIdle = { removeSession(it) },
                onGenerationFinished = { sessionId, cause ->
                    val session = sessions[sessionId]
                    if (cause != null) session?.messageQueue?.pause()
                    // 本轮停在工具审批：语音模式的 reply 等待者等不到文本，直接失败提示
                    if (session?.state?.value?.currentMessages?.any { message ->
                            message.parts.any { it is UIMessagePart.Tool && it.isPending }
                        } == true) {
                        session.messageQueue.failReplyWaiters(
                            context.getString(R.string.chat_page_voice_tool_approval)
                        )
                    }
                    appScope.launch { dispatchNextQueuedMessage(sessionId) }
                }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (session.isGenerating) {
            // **加载守卫**（b1273a72 同族）：生成中的会话内存态比 DB 新——流式内容只走
            // updateConversation 纯内存，落库点仅在轮次结束/取消收尾。此时无条件用 DB
            // 覆盖内存会把半截内容静默抹掉（web 路由的消息/编辑/分叉/删除/分支切换等
            // 入口、以及重开会话页都会走到这里）。只保证 session 存在与「当前助手」
            // 指针，不覆盖内容；生成结束后再初始化会走下面的正常加载。
            if (conversation != null) settingsStore.updateAssistant(conversation.assistantId)
            Log.i(TAG, "initializeConversation: $conversationId is generating, skip DB overwrite")
            return
        }
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    /**
     * **插队发送**：打断当前轮次，立刻发这条。
     *
     * ## 语义（2026-10-13 用户拍板）
     *
     * 与 [sendMessage] 相反——那里是「生成期间不打断、只排队」。这里三件事一次做完：
     * 1. **打断当前轮次**：复用 [stopGeneration]（`reason = QueueJumped`），它已经是用户点
     *    「停止」时跑的同一套（取消 jobs → join → 收尾待审批 → **半截落库**）；
     * 2. **待审批工具默认拒绝**：`finishInterruptedPendingTools` 会把未执行的那些写成
     *    `Denied("插队发送，本轮让路，未执行该工具")` 并把审批闸门放开（否则
     *    `dispatchNextQueuedMessage` 会因 pending 工具挡着而发不出去）；
     * 3. **半截回复落草稿**：即 [persistStoppedGeneration] 的既有行为——半截作为该节点的
     *    可编辑候选留在会话里，**不回填输入框**（用户明确要求；否则会和这条插队消息打架）。
     *
     * **不做二次确认**（用户明确）：半截会落库、并非真丢，与「停止」保持一致；
     * 没有可打断的东西时它自然退化成一次普通入队。
     *
     * 打断必须**在入队之前完成**：`dispatchNextQueuedMessage` 会检查 `getJob() != null` 与
     * pending 工具，先入队就会因为闸门还没放开而派发失败。
     */
    fun sendInterrupting(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        annotations: List<UIMessageAnnotation> = emptyList(),
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        appScope.launch {
            // 先打断（含半截落库与待审批收尾），完成后再入队派发
            stopGeneration(
                conversationId = conversationId,
                persistStoppedContent = true,
                reason = InterruptReason.QueueJumped,
            )
            synchronized(session) {
                if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
                session.messageQueue.enqueue(content, answer, annotations = annotations)
                dispatchNextQueuedMessage(conversationId)
            }
        }
    }

    fun sendMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        annotations: List<UIMessageAnnotation> = emptyList(),
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        synchronized(session) {
            // 生成期间不打断当前轮次——入队，等当前轮次（含待审批工具）结束后自动派发
            //
            // **这是「普通发送」的有意语义**（2026-10-13 留档）：想打断当前轮次立刻发，走
            // [sendInterrupting]（生成中 + 输入框非空时 UI 会多给一个「插队发送」入口）。
            // 两条路径分开而不是让这条路自己判断，是因为「排队等」和「打断」对用户的含义不同，
            // 不该由「当时有没有在生成」这种状态悄悄替用户决定。
            // 队列内重排（把某条挪到队首）仍未做，属未立项。
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer, annotations = annotations)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    /** 语音模式：立即入队，返回的 Deferred 在本轮生成完成后 resolve 为新增的 assistant 文本。 */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = getOrCreateSession(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        getOrCreateSession(conversationId).messageQueue.state

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessions[conversationId]?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null,
        annotations: List<UIMessageAnnotation>? = null,
    ) {
        sessions[conversationId]?.messageQueue?.finishEdit(messageId, parts, annotations)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences = buildSet {
                    for (candidate in candidates) {
                        if (conversationRepo.hasFileReference(candidate)) add(candidate)
                    }
                }
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessions.values.toList()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    /** 当前无生成任务且无待审批工具时才派发下一条（与审批串行化天然对齐）。返回派发的 Job（供语音模式观测）。 */
    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessions[conversationId] ?: return null
        synchronized(session) {
            if (session.getJob() != null || session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }) return null
            val next = session.messageQueue.takeNext() ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val previousAssistantIds = session.state.value.currentMessages.map { it.id }.toSet()
        val job = appScope.launch {
            try {
                // 派发新消息前的兜底收尾：正常情况下闸门（getJob() != null / pending 工具）已经保证
                // 不该有残留 pending，这里防的是「gate 检查之后、派发之前」新出现的。
                // 用默认理由（UserCancelled）——这类残留不是插队造成的。
                finishInterruptedPendingTools(conversationId, InterruptReason.UserCancelled)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                        // #1906：引用随队列项一起落地成注解（展开成请求文本在 QuoteTransformer）
                        annotations = queued.annotations,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)
                session.submittingMessage = null

                // 新一轮开始（新的用户消息）：重置工具调用护栏。
                // 这是「轮」的真正边界——工具审批的续跑不走这里，故护栏的历史跨审批保留。
                session.toolLoopGuard.reset()
                session.workspaceTurnBudget.reset()
                session.thinkingGuard.reset()

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
                // 语音模式：等本轮生成真正落库后，把新增的 assistant 文本 resolve 给 reply。
                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!session.messageQueue.state.value.paused) {
                        context.getString(R.string.chat_page_voice_generation_failed)
                    }
                    check(messages.none { message ->
                        message.parts.any { it is UIMessagePart.Tool && it.isPending }
                    }) { context.getString(R.string.chat_page_voice_tool_approval) }
                    messages
                        .filter { it.id !in previousAssistantIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
            } catch (e: Exception) {
                e.printStackTrace()
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
            if (cause != null) queued.reply?.completeExceptionally(cause)
            appScope.launch { dispatchNextQueuedMessage(conversationId) }
        }
        session.setJob(job)
        return job
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        ).take(MAX_USER_INPUT_CHARS)
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    /**
     * 重生成的作用范围（与消息角色**无关**——用户编辑后既可能想"只修正这一段"，
     * 也可能想"从这里重做"，见 `docs/design/design-regenerate-scope.md` §一/§三）。
     */
    enum class RegenerateScope {
        /** 仅本条与对应回复：不删下游。**非破坏**，无需确认。 */
        THIS_ONLY,

        /** 含后续所有对话：先截断下游再重生成。**破坏**，须确认（UI 层负责）。 */
        INCLUDE_FOLLOWING,
    }

    /**
     * 重生成消息。
     *
     * 目标节点（新回复归属的节点）：**用户消息重做的是它的回复**（下一个节点），
     * **助手消息重做的是自身**——两者的共同点是"基线不含目标节点"，故新回复总是作为
     * 该节点的**候选**追加（而非新建节点），与 [handleMessageComplete] 的索引写回一致。
     *
     * @param scope [RegenerateScope.THIS_ONLY] 不删下游；[RegenerateScope.INCLUDE_FOLLOWING] 先截断。
     *   默认 [RegenerateScope.INCLUDE_FOLLOWING] 以**保持既有行为**（web/API 调用方不受影响）；
     *   UI 总是显式传入用户所选 scope。
     * @param onTruncated 仅在发生截断时回调（scope B 且有尾部），供 UI 弹「撤销」。
     */
    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        scope: RegenerateScope = RegenerateScope.INCLUDE_FOLLOWING,
        onTruncated: ((TruncationPlan) -> Unit)? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        // 重新生成 = 用户发起的**新一轮尝试**（往往正是因为上一轮卡住/不满意）：
        // 护栏历史一并清空，让新尝试从干净的额度开始，避免上一轮的重复记录误伤本次。
        session.toolLoopGuard.reset()
        session.workspaceTurnBudget.reset()
        session.thinkingGuard.reset()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val nodeIndex = conversation.nodeIndexOfMessage(message.id)
                    ?: throw NotFoundException("Message not found")
                val replyNodeIndex = regenerateReplyNodeIndex(
                    nodeIndex = nodeIndex,
                    isUserMessage = message.role == MessageRole.USER,
                )

                if (scope == RegenerateScope.INCLUDE_FOLLOWING) {
                    conversation.truncationPlan(message.id)?.let { plan ->
                        saveConversation(
                            conversationId,
                            conversation.copy(messageNodes = conversation.messageNodes.take(plan.sizeAfterTruncation)),
                        )
                        onTruncated?.invoke(plan)
                    }
                }

                // 基线不含目标节点本身 → 新回复作为该节点的候选追加。
                handleMessageComplete(
                    conversationId,
                    messageRange = regenerateBaselineRange(replyNodeIndex, conversation.messageNodes.size),
                )

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    /**
     * 手动「继续生成」：把一条**输出不完整**的助手消息接着写下去。
     *
     * 与 [regenerateAtMessage] 的区别（为什么不能复用它）：重生成是「重开一个候选」，会把原内容
     * 丢到候选列表里；续写是「同一条消息往下写」——基线取到那条消息为止（末尾就是它），
     * 流式新增文本由 `handleMessageChunk` 按同角色追加规则拼进**同一条**消息。
     *
     * 提示词按结束状态选（与自动续写同一套判定）：裸断流用 [CONTINUE_INCOMPLETE_HINT]，
     * 撞输出上限用 [CONTINUE_TRUNCATED_HINT]。
     */
    fun continueMessage(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val last = conversation.currentMessages.lastOrNull()
                if (last == null || last.role != MessageRole.ASSISTANT) {
                    throw IllegalStateException("No assistant message to continue")
                }
                // 续写是用户发起的**新一轮尝试**（与重生成同理）：护栏额度重置
                session.toolLoopGuard.reset()
                session.workspaceTurnBudget.reset()
                session.thinkingGuard.reset()

                handleMessageComplete(
                    conversationId,
                    initialRecoveryHint = if (isOutputLimitTruncation(last.finishReason)) {
                        CONTINUE_TRUNCATED_HINT
                    } else {
                        CONTINUE_INCOMPLETE_HINT
                    },
                )
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    /**
     * 撤销一次 [RegenerateScope.INCLUDE_FOLLOWING] 的截断（snackbar 撤销）。
     *
     * 步骤：**先取消在飞生成并等待结束**（避免还原后又追加），再 `take(sizeAfterTruncation)`
     * 去掉「截断后新产生的节点」、拼回被移除的尾巴。
     *
     * 为什么用 `take` 而非直接追加：截断后生成可能已产出新回复（甚至 #1812 半截草稿），
     * 而**撤销的语义是「回到截断前」**，故这些新内容一并撤掉是正确行为。
     */
    fun undoRegenerateTruncation(
        conversationId: Uuid,
        plan: TruncationPlan,
    ) {
        appScope.launch {
            runCatching {
                // persistStoppedContent=false：本函数的语义是「回到截断前」，下面的
                // saveConversation(restored) 才是权威结果；让 stopGeneration 先落一次
                // 半截内容纯属多余写入。
                stopGeneration(conversationId, persistStoppedContent = false)
                val current = getConversationFlow(conversationId).value
                val restored = current.copy(
                    messageNodes = restoreAfterTruncation(current.messageNodes, plan),
                )
                // 诊断：撤销是破坏性操作且难以从 UI 观察，记一行便于排查/验证
                Log.i(
                    TAG,
                    "undoRegenerateTruncation: nodes ${current.messageNodes.size} -> ${restored.messageNodes.size} " +
                        "(restored ${plan.removedNodes.size} removed)",
                )
                saveConversation(conversationId, restored)
            }.onFailure {
                addError(it, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()

        val job = appScope.launch {
            try {
                // 串行化审批落地：等前任决策保存完成后再应用本决策——连续审批不互相打断
                //（55506496 修复：旧实现 setJob 会 cancel 前任 → 连续工具审批丢失）。
                afterPreviousGeneration(previousJob) {
                    val conversation = session.state.value
                    // 忽略双击与对已完成/不活跃工具的过期审批：已执行（isExecuted）或已取消
                    // 的工具不再等待审批，直接跳过本次落地。
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // 内存态更新：短路重建，只有包含目标 tool 的分支才深拷贝，
                    // 避免每次批准都全量重建整棵消息树（大量命令批准时主线程累积阻塞 → ANR）。
                    val updatedNodes = conversation.messageNodes.map { node ->
                        val targetMsgIndex = node.messages.indexOfFirst { msg ->
                            msg.parts.any { it is UIMessagePart.Tool && it.toolCallId == toolCallId }
                        }
                        if (targetMsgIndex < 0) {
                            node
                        } else {
                            val targetMsg = node.messages[targetMsgIndex]
                            val updatedParts = targetMsg.parts.map { part ->
                                if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                                    part.copy(approvalState = newApprovalState)
                                } else {
                                    part
                                }
                            }
                            node.copy(
                                messages = node.messages.mapIndexed { i, msg ->
                                    if (i == targetMsgIndex) msg.copy(parts = updatedParts) else msg
                                }
                            )
                        }
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)

                    // 持久化移 IO 线程：Room 写 + 深拷贝不在主线程执行
                    withContext(Dispatchers.IO) {
                        saveConversation(conversationId, updatedConversation)
                    }

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        // β: yield 让出主线程，先让"批准状态更新 → Compose 重组"完成渲染，
                        // 再重启生成，避免状态更新与生成首帧双重组在同一帧竞争主线程（交互卡顿）
                        yield()
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 拼装预览（只读） ----

    /**
     * 组装一次「这次请求会发什么」的只读预览，按来源分段。
     *
     * **不落库、不发请求、不改任何状态**。与真实发送共用 [buildInternalMessages] 与
     * 同一个 transformer 链、同一套工具装配（[chatToolFactory]），因此预览与实际请求一致
     * ——避免预览自己另写一套组装而随时间漂移（`docs/peer-apps-benchmark.md` §6.7 ③）。
     *
     * 与 [handleMessageComplete] 的差异**仅**在于：不跑自动压缩（预览不产生副作用）、
     * 不注入重试/回退等生成期逻辑。
     */
    suspend fun inspectPromptAssembly(
        conversationId: Uuid,
        labels: PromptInspectionLabels,
    ): PromptInspection? {
        val settings = settingsStore.settingsFlow.first()
        val conversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(conversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return null

        val memories = if (conversation.memoryDisabled) {
            // 与实发一致：隐身对话不加载记忆（预览也要显示「没有记忆段」）
            emptyList()
        } else if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        }
        val tools = chatToolFactory.createTools(
            settings = settings,
            assistant = assistant,
            model = model,
            workspaceCwd = conversation.workspaceCwd,
            conversationId = conversation.id.toString(),
            memoryDisabled = conversation.memoryDisabled,
        )

        return inspectPrompt(
            context = context,
            settings = settings,
            assistant = assistant,
            model = model,
            conversation = conversation,
            memories = memories,
            tools = tools,
            inputTransformers = buildList {
                addAll(inputTransformers)
                add(templateTransformer)
                add(workspaceReminderTransformer)
            },
            labels = labels,
        )
    }

    // ---- 处理消息补全 ----

    /**
     * 批量批准当前所有待批准的工具调用（方案 C：减少逐条批准次数，缓解大量命令场景下的 ANR）。
     */
    fun handleApproveAllPending(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                // 找到所有 pending tools，统一置 Approved
                var approvedCount = 0
                val updatedNodes = conversation.messageNodes.map { node ->
                    var nodeChanged = false
                    val updatedMessages = node.messages.map { msg ->
                        val updatedParts = msg.parts.map { part ->
                            if (part is UIMessagePart.Tool && part.isPending) {
                                nodeChanged = true
                                approvedCount++
                                part.copy(approvalState = ToolApprovalState.Approved)
                            } else {
                                part
                            }
                        }
                        if (nodeChanged) msg.copy(parts = updatedParts) else msg
                    }
                    if (nodeChanged) node.copy(messages = updatedMessages) else node
                }
                if (approvedCount == 0) return@launch // 没有待批准工具，无需处理

                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                withContext(Dispatchers.IO) {
                    saveConversation(conversationId, updatedConversation)
                }

                // 批量批准后没有 pending，继续生成
                // β: yield 让出主线程，先完成"批量批准状态 → 重组"渲染，再重启生成
                yield()
                handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        /** 首轮续写/补救提示（手动「继续生成」用；见 [continueMessage]） */
        initialRecoveryHint: String? = null,
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        // 模型自带内置搜索时不注册外挂搜索（上游 #1666）：工具注册与「工具不可用」告警同口径
        val useExternalWebSearch = shouldUseExternalWebSearch(assistant, model)

        // 流式检查点节流时间戳（见 STREAM_CHECKPOINT_INTERVAL_MS）
        var lastCheckpointAt = 0L

        // 主线程分段计时（诊断 ANR 用）：AppScope 是 Dispatchers.Main，这条链路上的准备阶段
        // （读设置 / 载记忆 / 装工具 / 组装上下文）与逐块处理**都在主线程**。
        // 真机上出过一次 5 秒无响应，而现场只能说明"在数 token"（实测毫秒级），
        // 所以把各段耗时打进日志，让下一次复现能直接点名谁吃掉了主线程。
        val prepStartedAt = SystemClock.elapsedRealtime()

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (useExternalWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)

            // 自动压缩（design §4.2 两层分工）：占用达阈值时先把早期历史摘要为检查点，
            // 再按发送窗口组装上下文。失败静默（不影响本次发送，limitContext 仍作兜底）。
            // 重生成历史消息时跳过（走完整轨迹，压缩不改变重生成语义）。
            val autoCompressAt = SystemClock.elapsedRealtime()
            if (messageRange == null) {
                maybeAutoCompress(conversationId, settings, model)
            }

            val conversation = getConversationFlow(conversationId).value
            val afterCompressAt = SystemClock.elapsedRealtime()

            // start generating
            val session = getOrCreateSession(conversationId)
            val memoriesAt = SystemClock.elapsedRealtime()
            // 提到局部变量只为一件事：**分段计时**（AppScope=Main，这一段全在主线程上）
            val loadedMemories = if (conversation.memoryDisabled) {
                // 隐身对话：不加载记忆（注入侧另有 memoryDisabled 门控，这里省掉一次查询）
                emptyList()
            } else if (assistant.useGlobalMemory) {
                memoryRepository.getGlobalMemories()
            } else {
                memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
            }
            val loadedMemoriesAt = SystemClock.elapsedRealtime()
            val assembledTools = chatToolFactory.createTools(
                settings = settings,
                assistant = assistant,
                model = model,
                workspaceCwd = conversation.workspaceCwd,
                conversationId = conversationId.toString(),
                memoryDisabled = conversation.memoryDisabled,
            )
            Log.i(
                TAG,
                "turn prep phases on ${Thread.currentThread().name}: " +
                    "pre=${memoriesAt - prepStartedAt}ms " +
                    "compress=${afterCompressAt - autoCompressAt}ms " +
                    "memories=${loadedMemoriesAt - memoriesAt}ms " +
                    "tools=${SystemClock.elapsedRealtime() - loadedMemoriesAt}ms",
            )
            generationHandler.generateText(
                settings = settings,
                model = model,
                debugKey = conversationId.toString(),
                processingStatus = session.processingStatus,
                // 护栏取自 session（不是在此处新建）：一次「轮」会跨多次 generateText——
                // 工具审批让循环 break 等用户，批准后是新的调用续跑同一轮。挂在 session 上才能
                // 跨审批累积，并在新一轮用户消息/重新生成时由本类 reset（见 ToolCallLoopGuard）。
                loopGuard = session.toolLoopGuard,
                workspaceBudget = session.workspaceTurnBudget,
                // 思考退化护栏（家族第四员）：同上取 session 上的实例，跨审批续跑累积
                thinkingGuard = session.thinkingGuard,
                // 回写基线：**全量**消息（与 messageNodes 索引对齐，updateCurrentMessages 依赖此对齐）；
                // 重生成历史时取指定范围。注意不要把「发送窗口」当基线——检查点合成消息会被误持久化，
                // 且窗口与 messageNodes 索引错位会覆盖边界内的历史节点。
                messages = if (messageRange != null) {
                    conversation.currentMessages.let {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    }
                } else {
                    conversation.currentMessages
                },
                // 发送窗口：压缩时 = 检查点合成消息 + 边界后原文（design §4.1 三边界对齐）；
                // 重生成历史时传 null（= 与 messages 相同）——保持原始轨迹，压缩不改变重生成语义。
                // 传「可重算的窗口描述」而非冻结列表：生成循环内每步对 live messages 重算，
                // 本回合工具调用的结果才能进入上下文（否则模型重复调用同一工具）。
                // 重生成历史时传 null（走完整轨迹，压缩不改变重生成语义）。
                compressionWindow = if (messageRange != null) {
                    null
                } else {
                    conversation.compressionWindow()
                },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memoryDisabled = conversation.memoryDisabled,
                memories = loadedMemories.also { loaded ->
                    // 每回合一条：注入条数与实际占用（按当前预算选出后的结果）。
                    // 与工具 schema 日志配套——两者是「固定开销」的两大项，合起来看才知道
                    // 上下文被谁吃掉（实测 MCP schema 可达 3 万余 token，远超记忆注入）。
                    // 用 Log.i 而非 Logging.log()：见 ChatToolFactory.logSchemaBreakdown 的说明。
                    //
                    // **丢到后台线程**：AppScope 是 Dispatchers.Main，而这段是**纯日志**——
                    // 让主线程为了写一条日志去数 token，是拿输入响应当代价（真机上撞出过 ANR）。
                    appScope.launch(Dispatchers.Default) {
                        runCatching {
                            val selected = selectMemoriesForPrompt(loaded, settings.memoryInjectBudgetTokens)
                            val used = selected.sumOf { estimateTokens(it.content) }
                            Log.i(
                                TAG,
                                "memory inject: ${selected.size}/${loaded.size} memories ≈${used} tokens " +
                                    "(budget=${settings.memoryInjectBudgetTokens})",
                            )
                        }.onFailure { Log.w(TAG, "memory inject log failed", it) }
                    }
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                // 工具装配集中到 ChatToolFactory（记忆 + 搜索 + 本地 + 对话 + 工作区 + 技能 + MCP）；
                // 已在上面取好并计时（assembledTools）
                tools = assembledTools,
                initialRecoveryHint = initialRecoveryHint,
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                // 逐块处理也在主线程（AppScope=Main）：把最慢的一块记下来，
                // 长回复/带附件的回复里真正吃主线程的往往是某一块（模板、附件搬运、落库）。
                val chunkStartedAt = SystemClock.elapsedRealtime()
                try {
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 流式检查点：节流落库（崩溃/被杀时不丢断点前的内容）。
                        // 索引取本次回写的末位消息（= 生成中的那个节点，见 checkpointGeneratingNode）。
                        // 失败只记日志——检查点是尽力而为的兜底，不能反过来打断生成。
                        val now = System.currentTimeMillis()
                        if (now - lastCheckpointAt >= STREAM_CHECKPOINT_INTERVAL_MS) {
                            lastCheckpointAt = now
                            val nodeIndex = chunk.messages.lastIndex
                            runCatching {
                                // 写库的那份副本标记「未完成」：进程被杀时流收尾那行永远不会执行，
                                // 不标记则恢复出来的半截内容看起来像正常完成的回复（见该函数注释）。
                                // 内存态不标——它由流收尾的 markLastFinish 写真实状态。
                                conversationRepo.checkpointGeneratingNode(
                                    conversation = updatedConversation.markGeneratingNodeIncomplete(nodeIndex),
                                    nodeIndex = nodeIndex,
                                )
                            }.onFailure { Log.w(TAG, "stream checkpoint failed", it) }
                        }

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }

                    is GenerationChunk.Notice -> when (val notice = chunk.notice) {
                        // 工具调用失控：本轮已**正常结束**（内容会照常落库），这里只补一条用户可见提示，
                        // 让「为什么突然停了」有解释。用 addError 而非写进对话：后者会被持久化并在
                        // 下一轮当上下文发给模型，变成自己教自己的噪音。
                        is GenerationNotice.ToolLoopStopped -> {
                            Log.w(
                                TAG,
                                "tool loop stopped: ${notice.toolName} ×${notice.repeats} (conv=$conversationId)",
                            )
                            addError(
                                ToolCallLoopStoppedException(notice.toolName, notice.repeats),
                                conversationId,
                                title = context.getString(R.string.chat_tool_loop_title),
                            )
                        }
                        is GenerationNotice.TooManyDenials -> {
                            // 聊天路径不会产生（denialGuard 只由无头 run 传入）；
                            // 分支仅为 when 穷尽，防御性记录。
                            Log.w(TAG, "too many denials: ${notice.denials} (conv=$conversationId)")
                            addError(
                                IllegalStateException("Too many tool denials (${notice.denials})"),
                                conversationId,
                                title = context.getString(R.string.chat_tool_loop_title),
                            )
                        }
                        is GenerationNotice.WorkspaceBudgetExhausted -> {
                            Log.w(
                                TAG,
                                "workspace budget exhausted: ${notice.usedChars}/${notice.budgetChars} chars, " +
                                    "${notice.steps} steps (conv=$conversationId)",
                            )
                            addError(
                                WorkspaceBudgetExhaustedException(
                                    usedChars = notice.usedChars,
                                    budgetChars = notice.budgetChars,
                                    steps = notice.steps,
                                    by = notice.by,
                                ),
                                conversationId,
                                title = context.getString(R.string.chat_workspace_budget_title),
                            )
                        }
                        is GenerationNotice.ThinkingDegenerationStopped -> {
                            Log.w(
                                TAG,
                                "thinking degeneration stopped: stalled=${notice.stalledCalls} " +
                                    "restated=${notice.restated} cut=${notice.cutRule}/${notice.repeated} " +
                                    "(conv=$conversationId)",
                            )
                            addError(
                                ThinkingDegenerationStoppedException(
                                    stalledCalls = notice.stalledCalls,
                                    restated = notice.restated,
                                    cutRule = notice.cutRule,
                                    repeated = notice.repeated,
                                ),
                                conversationId,
                                title = context.getString(R.string.chat_thinking_loop_title),
                            )
                        }
                        is GenerationNotice.ContextBudgetExhausted -> {
                            // 上下文预算守卫只由无头 run 传入（聊天路径有压缩 + 条数上限兜底），
                            // 这个分支同样只为 when 穷尽，防御性记录。
                            Log.w(
                                TAG,
                                "context budget exhausted: ${notice.usedTokens}/${notice.windowTokens} tokens " +
                                    "(conv=$conversationId)",
                            )
                            addError(
                                IllegalStateException(
                                    "Context budget exhausted (${notice.usedTokens}/${notice.windowTokens})"
                                ),
                                conversationId,
                                title = context.getString(R.string.agent_run_error_context_budget),
                            )
                        }
                    }
                }
                } finally {
                    val took = SystemClock.elapsedRealtime() - chunkStartedAt
                    if (took > 200) {
                        Log.w(
                            TAG,
                            "slow chunk ${chunk::class.simpleName} took ${took}ms on " +
                                "${Thread.currentThread().name} (conv=$conversationId)",
                        )
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            if (it is GenerationPartialFailureException) {
                // #1812：半截输出失败 → 保留为草稿候选并立即落库。
                // 这是失败路径的第一个写库点（正常流程只在 onSuccess 落库），
                // 同时缓解 #1820（切对话后内存态丢失）。
                val annotated = it.partialMessages.mapIndexed { index, message ->
                    if (index == it.partialMessages.lastIndex && message.role == MessageRole.ASSISTANT) {
                        message.copy(
                            annotations = message.annotations + UIMessageAnnotation.PartialFailure
                        )
                    } else {
                        message
                    }
                }
                val updatedConversation = getConversationFlow(conversationId).value
                    .updateCurrentMessages(annotated)
                updateConversation(conversationId, updatedConversation)
                // D1：落库防护——超大内容(>2MB blob)等写入失败时降级为可见错误，
                // 不能让草稿保存异常在 onFailure 内静默上抛
                runCatching { saveConversation(conversationId, updatedConversation) }
                    .onFailure { saveError ->
                        addError(
                            saveError,
                            conversationId,
                            title = context.getString(R.string.error_title_generation),
                        )
                        Logging.log(TAG, "handleMessageComplete: partial draft save failed: $saveError")
                    }
                Logging.log(TAG, "handleMessageComplete: partial generation kept as draft")
                return@onFailure
            }

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            // D1：落库防护——与 onFailure 半截落库同款降级，防止成功路径写入静默失败
            runCatching { saveConversation(conversationId, finalConversation) }
                .onFailure { saveError ->
                    addError(
                        saveError,
                        conversationId,
                        title = context.getString(R.string.error_title_generation),
                    )
                    Logging.log(TAG, "handleMessageComplete: save failed: $saveError")
                }

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    /**
     * 把一个**尚未执行**的工具收尾成「被用户打断」。
     *
     * ## 为什么同时写 `output` 与 `approvalState = Denied`
     *
     * 两者各管一件事，缺一不可：
     * - `output` 非空 ⇒ `isExecuted = true`、`isPending = false` ⇒ **审批闸门放开**
     *   （`canResumeToolExecution()` 对 `Pending` 为假），后续消息才发得出去；
     * - `approvalState = Denied(reason)` ⇒ 审批卡片显示成「已拒绝 + 理由」。
     *
     * **与历史行为的差异（2026-10-13 用户拍板）**：原先这里不写 `Denied`，注释理由是
     * 「cancel 后 output 非空 ⇒ 不会误标成拒绝待处理」——那确实避免了误标，代价是**卡片静默消失**、
     * 用户看不出这个工具是被拒绝还是从未被请求。现在明确写 `Denied` 并带上**区分来源**的理由
     * （用户取消 / 插队发送），因为在「插队」这个新路径上，「我这个工具到底怎么了」是需要回答的问题。
     */
    private fun cancelToolByUser(
        tool: UIMessagePart.Tool,
        reason: InterruptReason,
    ): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"${reason.toolOutputError}"}"""
                )
            ),
            approvalState = ToolApprovalState.Denied(reason = reason.approvalReason),
        )
    }

    private suspend fun finishInterruptedPendingTools(
        conversationId: Uuid,
        reason: InterruptReason,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools { tool -> cancelToolByUser(tool, reason) }
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ): Boolean {
        // 开关关着时不生成自动标题（判定是纯函数，见 TitleGenerationPolicy）。
        // runCatching + 默认 true：读设置失败时保持"照旧尝试"的老行为，不让读盘抖动改变语义。
        val autoTitleEnabled = runCatching { settingsStore.settingsFlow.first().enableAutoTitle }
            .getOrDefault(true)
        val shouldGenerate = shouldGenerateTitle(
            force = force,
            autoEnabled = autoTitleEnabled,
            currentTitle = conversation.title,
        )
        if (!shouldGenerate) return true

        // 「模型没配好」必须**给出反馈**（#1975）：此前这两处是静默 `return`，用户点
        // 「重新生成标题」后「app 没有任何动静」。返回 false 让调用方能再补一条即时 toast
        // （抽屉路径下错误卡被抽屉挡住，见 ChatDrawer 的 onRegenerateTitle）。
        var started = false
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId)
            if (model == null) {
                Log.w(
                    TAG,
                    "generateTitle: no title model (titleModelId=${settings.titleModelId}, " +
                        "fastModelId=${settings.fastModelId}); 提示用户去配置",
                )
                addConfigErrorOnce(
                    conversationId = conversationId,
                    // 消息用**本地化**的可操作说明；技术细节（哪个 id 缺失）已在上面 Log.w
                    error = IllegalStateException(
                        context.getString(R.string.error_title_model_unconfigured_desc),
                    ),
                    title = context.getString(R.string.error_title_model_unconfigured),
                    solution = ChatErrorSolution.CheckTitleModelSettings,
                )
                return@runCatching
            }
            val provider = model.findProvider(settings.providers)
            if (provider == null) {
                Log.w(TAG, "generateTitle: model ${model.id} has no provider; 提示用户去配置")
                addConfigErrorOnce(
                    conversationId = conversationId,
                    error = IllegalStateException(
                        context.getString(R.string.error_title_model_unconfigured_desc),
                    ),
                    title = context.getString(R.string.error_title_model_unconfigured),
                    solution = ChatErrorSolution.CheckTitleModelSettings,
                )
                return@runCatching
            }

            started = true
            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
        return started
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId)
            if (model == null) {
                // 与标题共用同一个 ChatErrorSolution ⇒ addConfigErrorOnce 会把两者的提示
                // 合成一张卡（同一轮里标题先报，这里就被去重掉），不会因为「建议每轮都试」
                // 而把用户刷屏（#1975）。
                Log.w(TAG, "generateSuggestion: no suggestion model; 已按去重规则提示用户")
                addConfigErrorOnce(
                    conversationId = conversationId,
                    error = IllegalStateException(
                        context.getString(R.string.error_title_model_unconfigured_desc),
                    ),
                    title = context.getString(R.string.error_title_model_unconfigured),
                    solution = ChatErrorSolution.CheckTitleModelSettings,
                )
                return@runCatching
            }
            val provider = model.findProvider(settings.providers)
            if (provider == null) {
                Log.w(TAG, "generateSuggestion: model ${model.id} has no provider; 已按去重规则提示用户")
                addConfigErrorOnce(
                    conversationId = conversationId,
                    error = IllegalStateException(
                        context.getString(R.string.error_title_model_unconfigured_desc),
                    ),
                    title = context.getString(R.string.error_title_model_unconfigured),
                    solution = ChatErrorSolution.CheckTitleModelSettings,
                )
                return@runCatching
            }

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    /**
     * 检查点式压缩（design-auto-compaction §4.1 三边界对齐 + §4.4 独立表）：
     *
     * 1. 保留策略 token 驱动：从尾部往前累计，预算 = 模型窗口 × [COMPRESS_RETAIN_RATIO]（至少 2 条）；
     * 2. 其余发送窗口历史分块并行摘要，与旧检查点（prior）合并为单一新摘要（避免层层累积）；
     * 3. **轨迹只追加**：不改动 message_node，只往 compression_checkpoint 表追加一行
     *    （content + boundaryNodeId），并 trimToLatest 清理旧行；
     * 4. 更新内存态 conversation.compressionSummaries（发送窗口与 UI 据此生效）。
     *
     * @param windowTokens 模型上下文窗口 token 数（调用方用 effectiveContextLength 算好）
     * @param additionalPrompt 用户附加指令（与 prior 检查点拼接后注入 {additional_context}）
     * @param targetTokens 摘要目标 token（注入 {target_tokens}）
     * @param retainRatio 保留窗口占比（0.05~0.9 钳制；默认 COMPRESS_RETAIN_RATIO = 0.4）
     * @return 成功时 Success(新检查点)，无可压缩内容时 Success(null)，失败时 Failure
     */
    suspend fun compressToCheckpoint(
        conversationId: Uuid,
        windowTokens: Int,
        additionalPrompt: String = "",
        targetTokens: Int = COMPRESS_TARGET_TOKENS,
        retainRatio: Double = COMPRESS_RETAIN_RATIO,
        /**
         * 「跟随对话模型」时使用的模型（由调用方传入**该会话**实际的生成模型）。
         * 传 null 时回退全局当前对话模型。
         */
        followModel: Model? = null,
    ): Result<CompressionSummary?> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.resolveCompressionModel(followModel)
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        val providerHandler = providerManager.getProviderByType(provider)

        // 当前会话快照（含内存态最新）
        val conversation = getConversationFlow(conversationId).value
        // 只看发送窗口内的历史：边界前的旧内容已由 prior 检查点覆盖
        val window = conversation.windowNodes()
        val budget = (windowTokens * retainRatio.coerceIn(0.05, 0.9)).toInt()
        val plan = estimateCompressionPlan(window, budget)
        if (!plan.shouldCompress) return@runCatching null  // 无可压缩内容（非错误）

        val messagesToCompress = plan.toCompress.map { it.currentMessage }
        val priorSummary = conversation.compressionSummaries.lastOrNull()?.content.orEmpty()
        val priorContext = if (priorSummary.isNotBlank()) {
            "PRIOR CHECKPOINT (merge this with the new conversation into one consolidated checkpoint):\n$priorSummary"
        } else {
            ""
        }

        suspend fun compressChunk(chunk: List<UIMessage>): String {
            val contentToCompress = chunk.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val additional = buildString {
                if (additionalPrompt.isNotBlank()) {
                    appendLine("Additional instructions from user: $additionalPrompt")
                    appendLine()
                }
                append(priorContext)
            }.trim()
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to additional,
                "locale" to Locale.getDefault().displayName,
            )
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )
            return result.choices[0].message?.toText()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val combined = coroutineScope {
            splitIntoChunks(messagesToCompress)
                .map { chunk -> async { compressChunk(chunk) } }
                .awaitAll()
        }.joinToString("\n\n")

        val boundaryNode = plan.toCompress.lastOrNull() ?: return@runCatching null
        val boundaryNodeId = boundaryNode.id.toString()
        // 区域指纹：记录被摘要区域（含边界）的节点/选中消息状态，供 activeCompression 检测
        // 后续改动（编辑/删除/重生成/切分支）使检查点失效
        val boundaryIndex = conversation.messageNodes.indexOfFirst { it.id == boundaryNode.id }
        val areaFingerprint = conversation.areaFingerprintFor(boundaryIndex)
        val priorCount = conversation.compressionSummaries.lastOrNull()?.messageCount ?: 0
        val checkpoint = CompressionSummary(
            conversationId = conversationId,
            content = combined,
            messageCount = priorCount + messagesToCompress.size,
            boundaryNodeId = boundaryNodeId,
            createdAt = Instant.now(),
            areaFingerprint = areaFingerprint,
        )

        // 轨迹只追加：落库新检查点（含自增 id），清理更早的检查点行（摘要已合并）
        val saved = conversationRepo.addCompressionCheckpoint(checkpoint)
        conversationRepo.trimCompressionCheckpoints(conversationId)

        // 更新内存态：发送窗口与 UI 据此生效（不改 messageNodes——历史可回溯）
        updateConversationState(conversationId) { latest ->
            latest.copy(compressionSummaries = listOf(saved))
        }
        saved
    }.onFailure {
        Log.e(TAG, "compressToCheckpoint failed for $conversationId", it)
    }

    /**
     * 自动压缩：占用达到阈值时调用 [compressToCheckpoint]。
     *
     * - 未启用（默认关）或未达阈值时不动作
     * - 失败/无可压缩内容时静默（不打断发送；`limitContext` 仍是同步兜底）
     * - 占用估算复用 [estimateWindowTokens]（usage 优先 + 本地兜底），与 UI 进度条同源
     */
    private suspend fun maybeAutoCompress(
        conversationId: Uuid,
        settings: Settings,
        model: Model,
    ) {
        if (!settings.autoCompressEnabled) return
        val conversation = getConversationFlow(conversationId).value
        // 已有检查点时仍按窗口占用判断（检查点已把边界前内容移出窗口，不会重复压缩同一段）
        val windowTokens = model.effectiveContextLength(settings.contextLengthOverride)
        val usedTokens = conversation.estimateWindowTokens(model)
        if (!shouldAutoCompress(usedTokens, windowTokens, settings.autoCompressThresholdPercent)) return

        Log.i(TAG, "auto compress triggered: $usedTokens / $windowTokens tokens")
        compressToCheckpoint(
            conversationId = conversationId,
            windowTokens = windowTokens,
            targetTokens = settings.autoCompressTargetTokens,
            retainRatio = settings.autoCompressRetainPercent / 100.0,
            followModel = model,
        ).onFailure {
            // 失败静默：不打断发送（limitContext 兜底），仅记录
            Log.w(TAG, "auto compress failed for $conversationId", it)
        }
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 元数据编辑的**统一落地入口**（置顶 / 移动助手等）。
     *
     * 为什么必须走这里而不是「读 DB → 改字段 → `saveConversation` 整对象写回」：
     * 后者对**活跃 session** 会用 DB 里的旧快照覆盖内存态 ⇒ 生成中途打星标/切助手
     * 会把已在流式接收的内容换掉（与「停止生成丢内容」同族，见 `ConversationSession.updateMetadata` 的说明）。
     *
     * 三条约束：
     * - [update] 作用在**当前内存值**上（不接收外部快照）；
     * - [persist] 只写对应列（单列 UPDATE），不整对象写回；
     * - 会话**没有活跃 session 时**直接从 DB 读一次作为基线（此时内存态不存在，无覆盖之虞）。
     */
    private suspend fun updateConversationMetadata(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        val session = sessions[conversationId]
        if (session == null) {
            val current = conversationRepo.getConversationById(conversationId) ?: return
            persist(update(current))
            return
        }
        session.updateMetadata(update, persist)
    }

    /**
     * 切换会话置顶状态。
     *
     * 走统一元数据入口：`update` 作用在内存最新态（在途内容不丢），`persist` 只写 `is_pinned` 单列
     * ——避免历史上「只改 DB 单列、内存仍旧值，随后被整对象保存覆盖回旧值」的星标静默丢失。
     */
    suspend fun togglePinStatus(conversationId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(isPinned = !it.isPinned) },
            persist = { conversationRepo.updateConversationPinStatus(conversationId, it.isPinned) },
        )
    }

    /**
     * 切换会话级「不使用记忆」（隐身对话；见 `docs/design/design-memory-discipline.md` §4.4）。
     *
     * 与 [togglePinStatus] 同款：内存态与库各写一次、用同一个新值。
     * 生效面 = **不注入记忆（含清单层）+ 不装配记忆工具**（下一次请求起）。
     */
    suspend fun setConversationMemoryDisabled(conversationId: Uuid, disabled: Boolean) {
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(memoryDisabled = disabled) },
            persist = { conversationRepo.updateConversationMemoryDisabled(conversationId, disabled) },
        )
    }

    /**
     * 把会话移动到另一个助手。
     *
     * `folderId = null`：文件夹是**助手内**分组，换了助手后原文件夹在新助手下不可见，
     * 不清空会让会话在列表里「消失」。两者在同一原语内一次落地（单列 UPDATE 也一并写 folder_id）。
     */
    suspend fun moveConversationToAssistant(conversationId: Uuid, assistantId: Uuid) {
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(assistantId = assistantId, folderId = null) },
            persist = { conversationRepo.updateConversationAssistant(conversationId, it.assistantId) },
        )
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.mapIndexed { index, node ->
            if (!node.messages.any { it.id == messageId }) {
                return@mapIndexed node
            }
            edited = true

            // 被编辑的那条（而非 node.currentMessage——编辑候选时别串到别的分支）
            val editedSource = node.messages.find { it.id == messageId }
            val editedMessage = UIMessage(
                role = node.role,
                parts = processedParts,
                annotations = editedSource?.annotations.orEmpty(),
            ).let { base ->
                // #1991 现象 B：编辑只改**内容**，不改**来源**——新分支必须继承「谁生成的」，
                // 否则切到该分支时模型名/图标全空（上游实测，fork 同款）。
                // 刻意**不继承** usage / finishedAt：它们描述的是被替换掉的那次生成，
                // 套在用户手改后的文本上会把「手动编辑」误报成模型产出。
                editedSource?.let { source ->
                    base.copy(
                        modelId = source.modelId,
                        modelNameSnapshot = source.modelNameSnapshot,
                        providerNameSnapshot = source.providerNameSnapshot,
                        createdAt = source.createdAt,
                    )
                } ?: base
            }

            node.copy(
                messages = node.messages + editedMessage,
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        // 编辑区域内的消息后，检查点由 activeCompression 的指纹校验自动失效（无需显式处理）
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        // 标题避让：取该助手下已有会话的标题集合，让分叉标题拿到第一个空出的序号。
        // 注意作用域是**助手**而非「所有会话」——分叉不改变 assistantId，同助手内唯一即可
        // （与上游 458c16df 同口径）。
        val existingTitles = conversationRepo
            .getConversationsOfAssistant(currentConversation.assistantId)
            .first()
            .mapTo(mutableSetOf()) { it.title }
        val forkConversation = createForkConversation(currentConversation, copiedNodes, existingTitles)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        // 删除区域内的消息后，检查点由 activeCompression 的指纹校验自动失效（无需显式处理）
        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    /**
     * #1929 编辑思考过程：按 messageId + Reasoning part 的创建时刻**原地**替换推理文本。
     *
     * 刻意**不复用** [editMessage]——它有三个对「只改推理」而言错误的副作用：新建分支、
     * 跑 USER 作用域预处理（正则）、按 [MAX_USER_INPUT_CHARS] 截断（助手长推理会被悄悄剪短）。
     * 这里（照 `updateTranslationField` 模板）：
     * - **原地改**：分支结构与 `selectIndex` 不动；
     * - 只替换目标 Reasoning part 的文本，**createdAt/finishedAt 保留**（“思考了 X 秒”不失真）；
     * - 其余 part 一个不动；
     * - 目标消息/part 找不到时是**无操作**（不是错误——界面与数据并发变化时的兜底）。
     *
     * 代价（有意识接受）：编辑会改动已发送历史的某个前缀 ⇒ 该处之后的 prompt cache
     * 失效。用户主动动作、可接受。
     */
    suspend fun updateMessageReasoning(
        conversationId: Uuid,
        messageId: Uuid,
        reasoningCreatedAt: kotlin.time.Instant,
        reasoningText: String,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = updateMessageReasoningInNodes(
            nodes = currentConversation.messageNodes,
            messageId = messageId,
            reasoningCreatedAt = reasoningCreatedAt,
            reasoningText = reasoningText,
        ) ?: return
        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
        saveConversation(conversationId, getConversationFlow(conversationId).value)
    }

    // 停止当前会话生成任务（不清理会话缓存）
    //
    // @param persistStoppedContent 停止后是否把**已生成的部分**落库（默认 true）。
    //   调用方若紧接着会自己写库（如撤销截断要覆盖成 restored），传 false 免一次多余写入。
    // @param reason 谁打断了这一轮（2026-10-13 用户拍板：**两条路径都写 `Denied`**，只是理由不同）。
    //   默认 `UserCancelled` 保持「点停止」的既有语义；插队发送走 `QueueJumped`。
    //   ⚠️ 注意这与历史行为**有意不同**：`cancelToolByUser` 原先注释写明「不再标 Denied」，
    //   结果是审批卡片静默收掉、只留 `status:"cancelled"` 的工具结果；现在会显示成「已拒绝 + 理由」。
    suspend fun stopGeneration(
        conversationId: Uuid,
        persistStoppedContent: Boolean = true,
        reason: InterruptReason = InterruptReason.UserCancelled,
    ) {
        val session = sessions[conversationId] ?: return
        val jobs = synchronized(session) {
            session.cancelJobs()
        }
        if (jobs.isNotEmpty()) {
            jobs.forEach { it.join() }
        }
        // 注意：不因 jobs 为空而早退——生成可能刚结束而收尾保存尚未落库，
        // 此时同样需要下面的持久化（见 persistStoppedGeneration 的说明）。
        finishInterruptedPendingTools(conversationId, reason)
        // 用户主动停止：把**已生成的部分**落库（吸收上游 4a3eefc1 的 finishGeneration 语义）。
        //
        // 为什么必需：流式内容只走 updateConversation（**纯内存**），落库点只有两处——
        // onSuccess（正常完成）与 GenerationPartialFailureException（#1812，重试耗尽且配置门控）。
        // 取消走的是 onFailure，拿到的 CancellationException 被 addError 直接 return，
        // 于是**没有任何写库**；而 session 在离开页面 5s 后即被回收，
        // 再次打开时 initializeConversation 会无条件用 DB 覆盖内存 ⇒ 半截内容静默消失。
        //
        // 真机复现（2026-09-20）：生成中途停止，内存里是新的半截内容（屏幕可见），
        // DB 仍是上一次的旧内容；退到桌面再进入，界面回到旧内容。
        // 修复后同一操作：DB 里 node 1 的候选数 1→2、select_index 指向新候选，内容保留。
        //
        // 必须放在 finishInterruptedPendingTools 之后：那一步把 pending 工具标记为「已取消」，
        // 若先落库会把未执行工具原样持久化。
        if (persistStoppedContent) persistStoppedGeneration(conversationId)
    }

    /**
     * 把当前内存态落库。
     *
     * `NonCancellable`：调用方（UI / undo / web）的 scope 可能同时被取消，
     * 不能因「取消在飞」把这次写入一并丢掉——那正是本方法要修的问题。
     */
    /**
     * 给「被打断的那条消息」追加可见提示（见 [appendInterruptionNotice]）。
     *
     * **替换选中分支而不再追加一条消息**：`MessageNode.messages` 是**同节点的多个候选分支**
     * （重新生成产生），push 一条新消息会凭空多出一个分支；正确做法是把 `selectIndex` 指向的
     * 那条原地替换成「原文 + 提示」。
     */
    private fun messageNodesWithInterruptionNotice(
        nodes: List<MessageNode>,
    ): List<MessageNode> {
        val lastIndex = nodes.lastIndex
        if (lastIndex < 0) return nodes
        val node = nodes[lastIndex]
        val selected = node.messages.getOrNull(node.selectIndex) ?: return nodes
        val (updated, appended) = listOf(selected)
            .appendInterruptionNotice(context.getString(R.string.chat_generation_interrupted_notice))
        if (!appended) return nodes
        val newMessages = node.messages.toMutableList().also { it[node.selectIndex] = updated.first() }
        return nodes.toMutableList().also { it[lastIndex] = node.copy(messages = newMessages) }
    }

    private suspend fun persistStoppedGeneration(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        withContext(NonCancellable) {
            runCatching {
                // 收尾未结束的推理块（与正常完成的 onCompletion 一致）：用户停止时推理块常常
                // 还带 finishedAt=null，落库后重进会话，思考卡片会按「进行中」渲染并在一条
                // 已死的消息上永远走计时器（每 50ms 自增）。
                // 生成被打断：给半截回复追加**可见**提示（幂等）——否则回看时那段戛然而止的
                // 回复看起来就像「卡住了」，用户无从判断是模型抽风、网络断了还是自己点了停止。
                // 提示带 uiNotice 元数据 ⇒ 发请求前会被过滤掉，不污染模型上下文。
                val withNotice = messageNodesWithInterruptionNotice(conversation.messageNodes)
                saveConversation(
                    conversationId,
                    conversation.copy(
                        messageNodes = withNotice.map { node ->
                            node.copy(messages = node.messages.map { it.finishReasoning() })
                        },
                    ),
                )
            }.onFailure { Logging.log(TAG, "persistStoppedGeneration failed: $it") }
        }
    }
}
