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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlin.time.Duration.Companion.seconds
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
import app.androidrik.rikkahub.data.ai.ContextBudgetGuard
import app.androidrik.rikkahub.data.ai.GenerationChunk
import app.androidrik.rikkahub.data.ai.GenerationNotice
import app.androidrik.rikkahub.data.ai.GenerationPartialFailureException
import app.androidrik.rikkahub.data.ai.GenerationHandler
import app.androidrik.rikkahub.data.ai.withProviderErrorHint
import app.androidrik.rikkahub.data.ai.parseLoopGuardSensitivity
import app.androidrik.rikkahub.data.ai.parseRuleReminderSensitivity
import app.androidrik.rikkahub.data.ai.PromptInspection
import app.androidrik.rikkahub.data.ai.refineTokens
import app.androidrik.rikkahub.data.ai.PromptInspectionLabels
import app.androidrik.rikkahub.data.ai.anchorIdsOf
import app.androidrik.rikkahub.data.ai.inspectPrompt
import app.androidrik.rikkahub.data.ai.WorkspaceTurnBudget
import app.androidrik.rikkahub.data.ai.shouldGenerateTitle
import app.androidrik.rikkahub.data.ai.ThinkingDegenerationGuard
import app.androidrik.rikkahub.data.ai.UsageRecorder
import app.androidrik.rikkahub.data.ai.UsageSource
import app.androidrik.rikkahub.data.ai.UsageTag
import app.androidrik.rikkahub.data.ai.mcp.McpManager
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import app.androidrik.rikkahub.data.ai.tools.shouldUseExternalWebSearch
import app.androidrik.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import app.androidrik.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import app.androidrik.rikkahub.data.ai.transformers.InputMessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.OcrTransformer
import app.androidrik.rikkahub.data.ai.transformers.OutputMessageTransformer
import app.androidrik.rikkahub.data.ai.transformers.PlaceholderTransformer
import app.androidrik.rikkahub.data.ai.transformers.PromptInjectionTransformer
import app.androidrik.rikkahub.data.ai.transformers.RegexOutputTransformer
import app.androidrik.rikkahub.data.ai.transformers.QuoteTransformer
import app.androidrik.rikkahub.data.ai.transformers.TemplateTransformer
import app.androidrik.rikkahub.data.ai.transformers.ThinkTagTransformer
import app.androidrik.rikkahub.data.ai.transformers.TavernMacroTransformer
import app.androidrik.rikkahub.data.ai.transformers.TimeReminderTransformer
import app.androidrik.rikkahub.data.ai.transformers.ToolResultBalancingTransformer
import app.androidrik.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import app.androidrik.rikkahub.data.event.AppEvent
import app.androidrik.rikkahub.data.event.AppEventBus
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.awaitLoaded
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.datastore.getAssistantById
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.datastore.getCurrentChatModel
import app.androidrik.rikkahub.data.datastore.resolveCompressionModel
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.plugin.PluginInputTransformer
import app.androidrik.rikkahub.data.plugin.PluginOutputTransformer
import app.androidrik.plugin.api.PluginCapability
import app.androidrik.plugin.manager.PluginManager
import app.androidrik.rikkahub.utils.DEFAULT_CONTEXT_LENGTH
import app.androidrik.rikkahub.utils.conversationTokenTexts
import app.androidrik.rikkahub.utils.countTokensInParallel
import app.androidrik.rikkahub.utils.effectiveContextLength
import app.androidrik.rikkahub.utils.estimateWindowTokens
import app.androidrik.rikkahub.data.ai.compression.shouldAutoCompress
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_RETAIN_RATIO
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_SINGLE_STEP_MAX_CHARS
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_TARGET_TOKENS
import app.androidrik.rikkahub.data.ai.compression.CompressionTier
import app.androidrik.rikkahub.data.ai.compression.buildTruncatedFallback
import app.androidrik.rikkahub.data.ai.compression.effectiveMinKeep
import app.androidrik.rikkahub.data.ai.compression.estimateCompressionPlan
import app.androidrik.rikkahub.data.ai.compression.runCompressionLadder
import app.androidrik.rikkahub.data.ai.compression.splitIntoChunks
import app.androidrik.rikkahub.data.model.markGeneratingNodeIncomplete
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.service.QueuedMessage
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.data.model.localFileUrls
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
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
    /** 写进工具结果 JSON 的 status 字段（模型据此区分「取消」与「跳过」） */
    val toolStatus: String = "cancelled",
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

    /**
     * AI 反问（ask_user）待答或工具审批待批时用户跳过（design-ask-user-interrupt §3.1/§6）。
     *
     * status 用 `skipped` 而非 `cancelled`：模型要知道「用户没等这一步、该继续推进」，
     * 而不是「被拒绝」——否则下一轮它可能反复重新提问/重试同一调用。
     */
    SkippedQuestion(
        approvalReason = "用户已跳过此步骤",
        toolOutputError = "User skipped this step without providing a result.",
        toolStatus = "skipped",
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

/**
 * 拼装预览 / 上下文容量面板的**重活统一调度器**。
 *
 * 这两处会跑完整 prompt 组装（transformer 链，可能触发 OCR 等 IO）+ 对每个分段做真 BPE
 * 分词（[app.androidrik.rikkahub.utils.estimateTokens]）——纯 CPU 且与全文长度线性相关。
 * 此前调用方分别在 `viewModelScope`（Main）和 `produceState`（组合上下文 = Main）里直接调用，
 * 长会话打开面板会卡住主线程（同子系统已有 main 线程 97s CPU 全在 ByteLevelBpe 的 ANR trace，
 * 见 `ChatPage.kt` 上下文占用估算处的注释）。
 *
 * 在 [ChatService.inspectPromptAssembly] **入口**统一切到后台，所有调用方（拼装预览页、
 * 聊天页容量面板、未来新增调用）自动覆盖，不需要调用方各自记得切线程；`withContext` 双向
 * 传播取消，面板关闭/页面销毁时分词会被中断。
 *
 * 注意分工：入口只把 **snapshot**（settings / conversation）留在调用方上下文读——session
 * 创建（`getOrCreateSession`）原本都发生在 Main，而 `_sessionsVersion.value++` 是非原子 RMW
 * （与其它线程的自增并发可能丢更新），不能随重活一起搬到 Default；重活（组装/分词/记忆读取/
 * 工具装配）才进本 helper。
 *
 * 抽成 dispatcher 可注入的 helper 只为**可测**：JVM 测试用受控单线程 dispatcher 断言
 * 「真的换了线程 + 取消能传播」与「返回后回到调用方上下文」，而不是写一个只 grep 源码的
 * 镜像测试（见 `PromptInspectionDispatchTest`）。
 */
internal suspend fun <T> withPromptInspectionDispatcher(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    block: suspend () -> T,
): T = withContext(dispatcher) { block() }

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    /** 插件注册表:转换管线接入点(见 [buildInputTransformerChain] / [pluginOutputTransformers]) */
    private val pluginManager: PluginManager,
    private val providerManager: ProviderManager,
    private val chatToolFactory: ChatToolFactory,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
    /** 标题 / 建议 / 压缩这些不走 GenerationHandler 的请求，在这里包一层记用量流水 */
    private val usageRecorder: UsageRecorder,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    /**
     * 插件提供的**输入**转换器。
     *
     * 两道门:插件已启用 + 持有 [PluginCapability.PROMPT_TRANSFORM]。
     * 能力在调用点校验(而非启用时一次性判断),撤销能力后立即从管线消失。
     */
    private fun pluginInputTransformers(): List<InputMessageTransformer> =
        pluginManager.plugins.value
            .filter { it.enabled && it.hasCapability(PluginCapability.PROMPT_TRANSFORM) }
            .mapNotNull { it.plugin as? PluginInputTransformer }

    /** 插件提供的**输出**转换器;门槛同 [pluginInputTransformers]。 */
    private fun pluginOutputTransformers(): List<OutputMessageTransformer> =
        pluginManager.plugins.value
            .filter { it.enabled && it.hasCapability(PluginCapability.OUTPUT_TRANSFORM) }
            .mapNotNull { it.plugin as? PluginOutputTransformer }

    /**
     * 输入转换链(实发与拼装预览共用,保证预览与真实请求一致)。
     *
     * 顺序:内置 → 模板 → 工作区提醒 → 插件 → 工具结果配平(必须是最后一环)。
     */
    private fun buildInputTransformerChain(): List<InputMessageTransformer> = buildList {
        addAll(inputTransformers)
        add(templateTransformer)
        add(workspaceReminderTransformer)
        addAll(pluginInputTransformers())
        // 配平必须看到最终形态:前面任何一环(含插件)都可能改动消息。
        add(ToolResultBalancingTransformer)
    }

    /**
     * 与 [buildInputTransformerChain] 相同但**不含插件段**。
     *
     * 只给"进会话预热"用：预热不该替用户触发插件（可能发网络/写存储），而内置链才是文本量的主体。
     */
    private fun buildInputTransformerChainWithoutPlugins(): List<InputMessageTransformer> = buildList {
        addAll(inputTransformers)
        add(templateTransformer)
        add(workspaceReminderTransformer)
        add(ToolResultBalancingTransformer)
    }

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
        // 线路侧拒绝（如中转重建 Gemini contents 丢 tool 消息）补一条可执行提示；
        // 判据见 ProviderErrorText 的 KDoc（真机 18/18 配平仍被拒）
        val shown = error.withProviderErrorHint()
        _errors.update {
            it + ChatError(title = title, error = shown, conversationId = conversationId, solution = solution)
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

    @Synchronized
    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        sessions[conversationId]?.let { return it }
        val session = sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                loopGuardSensitivity = parseLoopGuardSensitivity(settings.loopGuardSensitivity),
                ruleReminderEnabled = settings.ruleReminderEnabled,
                ruleReminderSensitivity = parseRuleReminderSensitivity(settings.ruleReminderSensitivity),
                turnBudgetChars = settings.workspaceTurnBudgetChars,
                turnBudgetSteps = settings.workspaceTurnMaxSteps,
                onIdle = { removeSession(it) },
                onTurnCancelled = { owner -> killBackgroundTasksOfCancelledTurn(owner) },
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
            )
        }
        // Publish only after computeIfAbsent inserted it: background flow collectors need the new session.
        _sessionsVersion.value++
        Log.i(TAG, "createSession: $conversationId (total: ${sessions.size})")
        return session
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

    fun getReplyMessageIdFlow(conversationId: Uuid): StateFlow<Uuid?> =
        getOrCreateSession(conversationId).replyMessageId

    fun getGenerationStartedAtFlow(conversationId: Uuid): StateFlow<Long?> =
        getOrCreateSession(conversationId).generationStartedAt

    /** 待审批时可能已经没有 Job，仍须允许切后台后继续审批。 */
    fun getFloatingConversationIds(): Flow<Map<Uuid, Long>> = _sessionsVersion.flatMapLatest {
        val current = sessions.values.toList()
        if (current.isEmpty()) flowOf(emptyMap()) else combine(current.map { session ->
            combine(session.generationJob, session.state, session.turnRevision) { job, conversation, turn ->
                (session.id to turn).takeIf {
                    job != null || conversation.currentMessages.any { message ->
                        message.parts.any { it is UIMessagePart.Tool && it.isPending }
                    }
                }
            }
        }) { ids -> ids.filterNotNull().toMap() }
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

    /**
     * 定时任务「专属新会话 / 每轮新建」用：按**指定助手**新建一条会话，返回它的 id。
     *
     * 与 [initializeConversation] 的差别只有一点：助手由调用方给（定时任务选中的那个），
     * 不读「当前助手」指针——后台执行时那个指针属于用户正在看的会话，任务没有资格改它。
     *
     * 三步顺序刻意固定：**先落库、再建内存 session**。只建 session 不落库的话，后续
     * `ensureConversationLoaded` 会判定会话不存在（TARGET_GONE），而生成结束的
     * `saveConversation` 也没有行可写，回复会丢。助手预设消息随会话一起落库（与新建对话同款）。
     *
     * @return null 表示助手已不存在（调用方记 ASSISTANT_GONE）
     */
    suspend fun createTaskConversation(assistantId: Uuid, title: String): Uuid? {
        val settings = awaitSettings()
        val assistant = settings.assistants.find { it.id == assistantId } ?: return null
        val conversationId = Uuid.random()
        val conversation = Conversation.ofId(
            id = conversationId,
            assistantId = assistant.id,
            newConversation = true,
        )
            .updateCurrentMessages(assistant.presetMessages)
            .copy(title = title)
        conversationRepo.insertConversation(conversation)
        updateConversation(conversationId, conversation)
        Log.i(TAG, "createTaskConversation: $conversationId assistant=${assistant.name} title=$title")
        return conversationId
    }

    /**
     * 后台入口（定时任务）用：**session 尚未存在时**才从 DB 载入会话。
     *
     * 为什么不能直接复用 [initializeConversation]：那条路径会无条件用 DB 覆盖内存态，而
     * 「待审批工具」时 session 的 job 可能为 null（[initializeConversation] 的生成中守卫挡不住），
     * 覆盖会把内存里的待审批状态抹掉。定时任务是**非用户发起**的入口，没有资格重置用户
     * 正在看的会话，所以只在 session 缺席时载入——这正是后台冷启动的形状（进程被 WorkManager
     * 拉起，内存里没有任何会话）。
     *
     * 不载入的后果（2026-10-30 真机事故的一半）：`getOrCreateSession` 会给一个磁盘中不存在的
     * 空 `Conversation.ofId`，注入后的 `saveConversation` 再按这个空内存态整行写回 ⇒ 目标
     * 会话的历史消息、标题、工作区、注入绑定一起被抹掉。
     *
     * @return false 表示会话行已不存在（调用方据此记 TARGET_GONE）
     */
    suspend fun ensureConversationLoaded(conversationId: Uuid): Boolean {
        val stored = conversationRepo.getConversationById(conversationId) ?: return false
        val session = sessions[conversationId]
        if (session == null) {
            updateConversation(conversationId, stored)
            Log.i(TAG, "ensureConversationLoaded: $conversationId (nodes=${stored.messageNodes.size})")
            return true
        }
        // 已有 session：**只在内存态明显未初始化时**才用 DB 覆盖。判据取最保守的一侧——
        // 内存里一条消息都没有、DB 有内容、且没有在途生成/排队。这样既兜住「别处用
        // getConversationFlow 顺手建了个空 session」的漏洞，又不会覆盖用户正在看的会话
        // （待审批工具时内存有节点、生成中有守卫、队列非空说明有发起方）。
        val memory = session.state.value
        val uninitialized = memory.messageNodes.isEmpty() &&
            stored.messageNodes.isNotEmpty() &&
            !session.isGenerating &&
            session.messageQueue.state.value.messages.isEmpty()
        if (uninitialized) {
            updateConversation(conversationId, stored)
            Log.i(TAG, "ensureConversationLoaded: $conversationId (recover empty in-memory, nodes=${stored.messageNodes.size})")
        }
        return true
    }

    /**
     * 冷启动安全的设置读取（`settingsFlow` 起手是 `Settings.dummy()`）。
     *
     * 不加这道等待时，后台入口在进程刚起来的几百毫秒内拿到的是全默认配置：
     * `providers` 为空 ⇒ 生成侧 `findModelById` 恒 null；`assistants` 是内置默认助手 ⇒
     * 会话助手指针也跟着错。表现不是「用错模型」，而是**整轮静默不动**（定时任务真机复现）。
     *
     * 超时兜底：DataStore 持续读失败时不能把发送/生成协程挂死；超时退回 dummy，让下游
     * 以**可见错误**收尾（见 [handleMessageComplete] 的模型缺失分支）。
     */
    private suspend fun awaitSettings(): Settings =
        withTimeoutOrNull(SETTINGS_AWAIT_TIMEOUT) { settingsStore.awaitLoaded() }
            ?: settingsStore.settingsFlow.value

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
        /**
         * pending 收尾理由：生成中插队 = [InterruptReason.QueueJumped]（既有语义）；
         * ask_user/审批待答时跳过 = [InterruptReason.SkippedQuestion]
         * （design-ask-user-interrupt §3.1，工具结果 status=skipped）。
         */
        reason: InterruptReason = InterruptReason.QueueJumped,
    ) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        appScope.launch {
            // 先打断（含半截落库与待审批收尾），完成后再入队派发
            stopGeneration(
                conversationId = conversationId,
                persistStoppedContent = true,
                reason = reason,
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

    /**
     * 把某条待发消息**置顶**（下一条就发它）。只改队列顺序，**不打断**当前轮次——
     * 想立刻发走 [sendInterrupting]。
     *
     * 没派发失败也要调一次 `dispatchNextQueuedMessage`：置顶的常见场景正是「队列还没在跑」
     * （比如上一轮刚结束、队列因某条卡住而停），这时得靠它把队列重新推起来。
     * 与 [removeQueuedMessage] 不同，这里**不清理附件**——消息只是换了位置，仍被队列引用着。
     */
    fun moveQueuedMessageToFront(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.moveToFront(messageId)
        dispatchNextQueuedMessage(conversationId)
    }

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
        // 新一轮开始（新的用户消息）：重置三个护栏 + 换新后台任务归属令牌。
        // 这是「轮」的真正边界——工具审批的续跑不走这里，故护栏的历史跨审批保留。
        // **必须在这里同步调用**（不能挪进下面的生成协程体）：`setJob` 注册取消回调时要捕获
        // 令牌，而协程体可能晚于 `session.setJob(job)` 才开始执行。
        session.beginNewTurn()
        val job = appScope.launch {
            try {
                // 派发新消息前的兜底收尾：正常情况下闸门（getJob() != null / pending 工具）已经保证
                // 不该有残留 pending，这里防的是「gate 检查之后、派发之前」新出现的。
                // 用默认理由（UserCancelled）——这类残留不是插队造成的。
                finishInterruptedPendingTools(conversationId, InterruptReason.UserCancelled)

                val currentConversation = session.state.value
                // 冷启动（尤其后台进程刚被拉起）时 settingsFlow 仍是 dummy：用 awaitSettings 等真实配置
                val settings = awaitSettings()
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
        // 含换新后台任务归属令牌：被顶掉的那一轮若起过后台任务，它的取消回调用旧令牌收尾。
        session.beginNewTurn()

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
        // 续写是用户发起的**新一轮尝试**（与重生成同理）：护栏额度重置 + 换新归属令牌。
        // 必须在 launch 之前同步执行（`setJob` 注册取消回调时要捕获令牌，见 beginNewTurn）。
        session.beginNewTurn()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                val last = conversation.currentMessages.lastOrNull()
                if (last == null || last.role != MessageRole.ASSISTANT) {
                    throw IllegalStateException("No assistant message to continue")
                }

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
        /**
         * 跳过（design-ask-user-interrupt §6）：工具结果直接写 `status=skipped` 的
         * 合成输出（与 [cancelToolByUser] 同形态），卡片置为 Denied(用户已跳过此步骤)。
         * output 已填 ⇒ isExecuted=true ⇒ 续跑时不再执行该工具，直接带结果推进。
         */
        skip: Boolean = false,
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
                    val skipped = InterruptReason.SkippedQuestion
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
                                    if (skip) {
                                        part.copy(
                                            output = listOf(
                                                UIMessagePart.Text(
                                                    """{"status":"${skipped.toolStatus}","error":"${skipped.toolOutputError}"}"""
                                                )
                                            ),
                                            approvalState = ToolApprovalState.Denied(reason = skipped.approvalReason),
                                        )
                                    } else {
                                        part.copy(approvalState = newApprovalState)
                                    }
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

    private class InspectionReference(private val value: Any?) {
        override fun equals(other: Any?): Boolean = other is InspectionReference && value === other.value
        override fun hashCode(): Int = System.identityHashCode(value)
    }

    private val inspectionSingleFlight = InspectionSingleFlight<List<Any>, PromptInspection?>(appScope)

    /** 已预热过的会话及其内容签名；同一签名不重复预热。 */
    private val prewarmedTokenCounts = java.util.concurrent.ConcurrentHashMap<Uuid, String>()

    /** 每个会话的预热任务；预览打开时据此 join（无插件）或取消（有插件，避免抢核）。 */
    private val warmJobs = java.util.concurrent.ConcurrentHashMap<Uuid, Job>()

    /** 该会话的预热是否与预览共享同一次计算。 */
    private val warmSharedWithPreview = java.util.concurrent.ConcurrentHashMap<Uuid, Boolean>()

    /**
     * 进入会话时**只预热精确 token 计数**（不组装、不跑插件、不产生副作用）。
     *
     * 动机：拼装预览首次打开的大头是分词（长会话实测 12.7s），而进会话本身总要做一次加载。
     * 趁这个空档把同一批文本单元算热（[conversationTokenTexts] 与预览分段取法一致），
     * 用户随后打开预览/容量面板时就是缓存命中。
     *
     * 走 [appScope]：离开会话也不至于把已投入的计算丢掉；同一会话内容未变时不重复跑。
     */
    fun prewarmPromptTokenCounts(conversationId: Uuid, labels: PromptInspectionLabels) {
        warmJobs[conversationId]?.cancel()
        // 同步决定是否共享：预览可能在任务真正开跑前就点了进来，不能等 job 里再写这个标志
        val shared = !hasInputPluginTransformers()
        warmSharedWithPreview[conversationId] = shared
        val job = appScope.launch {
            val settings = runCatching { settingsStore.settingsFlow.first() }.getOrNull() ?: return@launch
            val conversation = runCatching { getConversationFlow(conversationId).value }.getOrNull()
                ?: return@launch
            val signature = "${conversation.messageNodes.size}:${conversation.currentMessages.lastOrNull()?.id}"
            // 空会话没有可预热的文本：启动时给「新聊天」白跑一次组装纯属浪费（实测 4.3s）
            if (conversation.currentMessages.isEmpty()) return@launch
            if (prewarmedTokenCounts[conversationId] == signature) return@launch
            // 没有输入插件时，预热与预览跑的是**同一次**计算（同 key、同 labels）：
            // 用户随后点开预览直接 join 这一份，而不是自己再算一遍。
            Log.d(PERF_TAG, "prewarm start shared=$shared")
            runCatching {
                if (shared) {
                    runSharedInspection(
                        inspectionKey(conversationId, settings, conversation, labels),
                        settings, conversation, labels,
                    )
                } else {
                    // 有插件时预热不跑插件（不替用户触发副作用），只把无插件的那部分算热
                    val preview = assemblePromptInspection(
                        settings = settings,
                        conversation = conversation,
                        labels = labels,
                        approximateTokens = true,
                        includePluginTransformers = false,
                    ) ?: return@launch
                    preview.refineTokens()
                }
            }
            prewarmedTokenCounts[conversationId] = signature
        }
        warmJobs[conversationId] = job
    }

    private fun hasInputPluginTransformers(): Boolean =
        pluginManager.plugins.value.any { it.enabled && it.hasCapability(PluginCapability.PROMPT_TRANSFORM) }

    private fun inspectionKey(
        conversationId: Uuid,
        settings: Settings,
        conversation: Conversation,
        labels: PromptInspectionLabels,
    ): List<Any> = listOf(conversationId, labels)

    /**
     * 组装期间会读插件注册表与 MCP 状态，但它们**每次重发都是新对象**，放进共享 key 会让
     * 预热与预览永远对不上（实测 join 落空、白跑两遍）。改为：key 只按稳定对象，算完再比对这些
     * 易变状态的**引用**，变了就重算一次。
     */
    private inner class AssemblyState(
        val settings: Any?,
        val conversation: Any?,
        val plugins: Any?,
        val mcp: Any?,
    ) {
        fun matchesCurrent() =
            settings === currentSettingsSnapshot &&
                conversation === currentConversationSnapshot &&
                plugins === pluginManager.plugins.value &&
                mcp === mcpManager.syncingStatus.value
    }

    /** 结果回来时用于比对的"当前快照"（由调用方在计算前写入，避免把大对象塞进 key）。 */
    @Volatile private var currentSettingsSnapshot: Any? = null
    @Volatile private var currentConversationSnapshot: Any? = null

    private fun currentAssemblyState(settings: Settings, conversation: Conversation): AssemblyState {
        currentSettingsSnapshot = settings
        currentConversationSnapshot = conversation
        return AssemblyState(settings, conversation, pluginManager.plugins.value, mcpManager.syncingStatus.value)
    }

    /** 共享的精确计算：命中在飞任务；结果回来若易变状态已变，则再算一次（最多两次）。 */
    private suspend fun runSharedInspection(
        key: List<Any>,
        settings: Settings,
        conversation: Conversation,
        labels: PromptInspectionLabels,
    ): PromptInspection? {
        repeat(2) { attempt ->
            val state = currentAssemblyState(settings, conversation)
            val result = inspectionSingleFlight.run(key) {
                assemblePromptInspection(settings, conversation, labels, approximateTokens = false)
            }
            if (state.matchesCurrent() || attempt == 1) return result
            Log.d(PERF_TAG, "inspection state changed during assembly, recomputing")
        }
        return null
    }

    private companion object {
        const val PERF_TAG = "PromptInspectionPerf"

        /**
         * 冷启动等设置落盘的兜底上限（见 [awaitSettings]）。正常 DataStore 首读是百毫秒级；
         * 超时说明读链异常，此时退回 dummy 并让下游报「未配置模型」的可见错误，而不是挂死。
         */
        val SETTINGS_AWAIT_TIMEOUT = 15.seconds

        /** 预热不展示任何文案；空标签可避免 service 层依赖 UI 的本地化构建器。 */
        val PREWARM_LABELS = PromptInspectionLabels(system = "", checkpoint = "", injection = "", history = "")
    }

    /** 极轻量的预览头信息：只读会话快照的条数，不触发任何组装/分词。 */
    fun promptInspectMessageCount(conversationId: Uuid): Int? =
        runCatching { getConversationFlow(conversationId).value.currentMessages.size }.getOrNull()

    /**
     * 组装一次「这次请求会发什么」的只读预览，按来源分段。
     *
     * 宿主不保存对话、不发送模型请求；输入插件自身仍可能访问网络/存储。
     * 与真实发送共用 [buildInternalMessages] 与
     * 同一个 transformer 链、同一套工具装配（[chatToolFactory]），因此预览与实际请求一致
     * ——避免预览自己另写一套组装而随时间漂移（`docs/peer-apps-benchmark.md` §6.7 ③）。
     *
     * 与 [handleMessageComplete] 的差异**仅**在于：不跑自动压缩（预览不产生副作用）、
     * 不注入重试/回退等生成期逻辑。
     *
     * **线程**：快照（settings / conversation）在**调用方上下文**读取，其余重活（transformer 链 +
     * 逐段真 BPE 分词 + 记忆读取 + 工具装配）经 [withPromptInspectionDispatcher] 在
     * `Dispatchers.Default` 上执行——调用方可以安全地在 Main / 组合上下文里直接 await，
     * 返回值会回到调用方线程（UI 状态就地更新即可）。新增调用方**不要**再自行 `withContext`。
     */
    suspend fun inspectPromptAssembly(
        conversationId: Uuid,
        labels: PromptInspectionLabels,
        /** 非 null 时走渐进式：先回一版近似计数，再回精确版本（预览页首屏秒开用）。 */
        onPartial: ((PromptInspection) -> Unit)? = null,
        /** 仅用于诊断日志：哪个入口发起的组装。 */
        caller: String = "unknown",
    ): PromptInspection? {
        Log.d(PERF_TAG, "inspect request caller=$caller")
        // [并发] 快照必须在调用方上下文（Main）读：getConversationFlow 会 getOrCreateSession，
        // 其 computeIfAbsent 里 `_sessionsVersion.value++` 是非原子 RMW（与其它线程的自增并发
        // 可能丢版本号 ⇒ 会话列表不刷新）；该 session 的 scope/回调（appScope = Main）也按
        // 「在主线程创建」的既有约定设计。
        val settings = settingsStore.settingsFlow.first()
        val conversation = getConversationFlow(conversationId).value

        // 引用快照避免在 Main 对整段历史做深度 hash；只合并在飞任务，完成立即移除。
        val key = inspectionKey(conversationId, settings, conversation, labels)
        if (onPartial == null) {
            return runSharedInspection(key, settings, conversation, labels)
        }
        // 预热与预览兼容（无输入插件、且预热在飞）：直接 join，省掉第二次组装
        if (warmSharedWithPreview[conversationId] == true && warmJobs[conversationId]?.isActive == true) {
            Log.d(PERF_TAG, "joined warm task")
            val joined = runSharedInspection(key, settings, conversation, labels)
            Log.d(PERF_TAG, "joined warm task finished ok=${joined != null}")
            if (joined != null) onPartial(joined)
            return joined
        }
        // 不兼容时别让预热继续抢核：已填的缓存条目不会因取消丢失
        warmJobs[conversationId]?.cancel()
        Log.d(PERF_TAG, "join skipped shared=${warmSharedWithPreview[conversationId]} active=${warmJobs[conversationId]?.isActive}")
        // 渐进式路径**不并入在飞任务**：每个调用方都要拿到自己的中间结果。
        val fast = assemblePromptInspection(settings, conversation, labels, approximateTokens = true)
            ?: return null
        onPartial(fast)
        // 回调在调用方上下文（Main）触发：VM 直接写 Compose 状态，不必自己切线程
        val refineStarted = android.os.SystemClock.elapsedRealtime()
        val exact = withPromptInspectionDispatcher { fast.refineTokens() }
        Log.d(PERF_TAG, "refineMs=${android.os.SystemClock.elapsedRealtime() - refineStarted}")
        onPartial(exact)
        return exact
    }

    /** 预览的重活主体：助手/模型解析、记忆读取、工具装配、transformer 链与分段。 */
    private suspend fun assemblePromptInspection(
        settings: Settings,
        conversation: Conversation,
        labels: PromptInspectionLabels,
        approximateTokens: Boolean,
        includePluginTransformers: Boolean = true,
    ): PromptInspection? = withPromptInspectionDispatcher {
                val timing = app.androidrik.rikkahub.data.ai.PromptInspectionTiming()
                val tokenStatsBefore = app.androidrik.common.tokenizer.TokenizerRegistry.cacheStats()
                try {
                    val baseAssistant = settings.getAssistantById(conversation.assistantId)
                        ?: settings.getCurrentAssistant()
                    val assistant = conversation.upstreamConfig?.applyTo(baseAssistant) ?: baseAssistant
                    // `return@withPromptInspectionDispatcher`：helper 的 block 不是 inline lambda，
                    // 不能用裸 `return null`。
                    val baseModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                        ?: return@withPromptInspectionDispatcher null
                    val model = conversation.upstreamConfig?.applyToModel(baseModel) ?: baseModel

                    val memories = if (conversation.memoryDisabled) {
                        // 与实发一致：隐身对话不加载记忆（预览也要显示「没有记忆段」）
                        emptyList()
                    } else if (assistant.useGlobalMemory) {
                        memoryRepository.getGlobalMemories()
                    } else {
                        memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                    }
                    timing.mark("memories")
                    val tools = chatToolFactory.createTools(
                        settings = settings,
                        assistant = assistant,
                        model = model,
                        workspaceCwd = conversation.workspaceCwd,
                        conversationId = conversation.id.toString(),
                        memoryDisabled = conversation.memoryDisabled,
                    )

                    timing.mark("tools")
                    inspectPrompt(
                        context = context,
                        settings = settings,
                        assistant = assistant,
                        model = model,
                        conversation = conversation,
                        memories = memories,
                        tools = tools,
                        // 与实发链同款装配(含插件段),预览与真实请求一致
                        inputTransformers = if (includePluginTransformers) {
                            buildInputTransformerChain()
                        } else {
                            buildInputTransformerChainWithoutPlugins()
                        },
                        labels = labels,
                        timing = timing,
                        approximateTokens = approximateTokens,
                    )
                } finally {
                    val after = app.androidrik.common.tokenizer.TokenizerRegistry.cacheStats()
                    // 仅耗时/数量，不记录正文。全局缓存增量可能含同期其它请求，仅供诊断。
                    Log.d(PERF_TAG, timing.summary() +
                        " cacheHitsDelta=${after.hits - tokenStatsBefore.hits}" +
                        " cacheMissesDelta=${after.misses - tokenStatsBefore.misses}" +
                        " cacheEntries=${after.entries} cacheTextBytes=${after.retainedTextBytes}")
                }
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
        val activeSession = getOrCreateSession(conversationId)
        activeSession.replyMessageId.value = null
        // 冷启动安全的设置读取：起手值是 Settings.dummy()，直接 first() 会拿到空 providers /
        // 随机 chatModelId ⇒ 下面的模型解析必然失败（2026-10-30 定时任务真机事故）
        val settings = awaitSettings()
        val initialConversation = getConversationFlow(conversationId).value
        val baseAssistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val assistant = initialConversation.upstreamConfig?.applyTo(baseAssistant) ?: baseAssistant
        val baseModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
        if (baseModel == null) {
            // **不能静默 return**：这一轮的 job 会秒结、无错误、队列空，调用方（定时任务执行层）
            // 会把「什么都没发生」判成 OK，用户看到的就是「后台触发了却没有回复」。
            // 报成错误卡（并让定时任务据 errors 记 FAILED）才是可诊断的收尾。
            Log.w(TAG, "handleMessageComplete: no model for conversation=$conversationId (settings.init=${settings.init})")
            addError(
                IllegalStateException(context.getString(R.string.agent_run_error_no_model)),
                conversationId,
                title = context.getString(R.string.error_title_generation),
            )
            return
        }
        val model = initialConversation.upstreamConfig?.applyToModel(baseModel) ?: baseModel

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
            } else {
                // 与两个手动固化入口共用同一口径（见 currentMemoriesFor）
                currentMemoriesFor(assistant)
            }
            val loadedMemoriesAt = SystemClock.elapsedRealtime()
            val assembledTools = chatToolFactory.createTools(
                settings = settings,
                assistant = assistant,
                model = model,
                workspaceCwd = conversation.workspaceCwd,
                conversationId = conversationId.toString(),
                // 后台任务归属 = 本轮令牌（本轮被取消时按它精确收掉这一轮起的进程）
                turnOwner = session.backgroundTaskOwner,
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
            // L1 锚点：**会话开始冻结一次**（设计稿 §5）。首次生成时把当前锚点渲染成快照落库，
            // 此后本会话内锚点怎么改都不再漂移（手动刷新点 = 记忆页的「固化为锚点」，
            // 见 refreshFrozenAnchorsForAssistant）。为什么值得这么做：L1 在 system（位置 0），
            // 它一变其后整段前缀全部重算——真机实测命中率 98% → 0%（设计稿 §2）。
            val frozenAnchorIds = ensureFrozenAnchors(conversationId, conversation, loadedMemories)
            // 回写基线：**全量**消息（与 messageNodes 索引对齐，updateCurrentMessages 依赖此对齐）；
            // 重生成历史时取指定范围。注意不要把「发送窗口」当基线——检查点合成消息会被误持久化，
            // 且窗口与 messageNodes 索引错位会覆盖边界内的历史节点。
            // GenerationHandler 从实际发送窗口取得查询并记录共享预算计划，不再另选一份日志集合。
            val promptMessages = if (messageRange != null) {
                conversation.currentMessages.let {
                    it.subList(messageRange.start, messageRange.endInclusive + 1)
                }
            } else {
                conversation.currentMessages
            }
            // 真正续写助手消息才恢复 id；初始准备失败时不能遗留上一轮预览。
            session.replyMessageId.value = promptMessages.lastOrNull()
                ?.takeIf { it.role == MessageRole.ASSISTANT }?.id
            // 新一轮生成：清掉上一轮遗留的「未确认回填」快照（见 ConversationSession.lastCommittedMessages）。
            // 不清的话，本轮若在**任何工具回填之前**被取消，停止路径会把上一轮的旧快照并进内存态。
            session.lastCommittedMessages.value = null
            generationHandler.generateText(
                settings = settings,
                model = model,
                debugKey = conversationId.toString(),
                processingStatus = session.processingStatus,
                // 护栏取自 session（不是在此处新建）：一次「轮」会跨多次 generateText——
                // 工具审批让循环 break 等用户，批准后是新的调用续跑同一轮。挂在 session 上才能
                // 跨审批累积，并在新一轮用户消息/重新生成时由本类 reset（见 ToolCallLoopGuard）。
                loopGuard = session.toolLoopGuard,
                ruleEngine = session.ruleEngine,
                workspaceBudget = session.workspaceTurnBudget,
                // 思考退化护栏（家族第四员）：同上取 session 上的实例，跨审批续跑累积
                thinkingGuard = session.thinkingGuard,
                // 上下文预算守卫（design-tool-context-gate §6）：无累积状态，调用点直接新建。
                // 自动压缩/条数上限仍是第一道线；本守卫只管工具循环内部增长撞到
                // 「剩余 <4K」的优雅停止（与 B 闸 90% 硬限互补：B 管请求前，C 管执行中）
                contextGuard = ContextBudgetGuard(
                    windowTokens = model.effectiveContextLength(settings.contextLengthOverride),
                ),
                // 回写基线：**全量**消息（与 messageNodes 索引对齐，updateCurrentMessages 依赖此对齐）；
                // 重生成历史时取指定范围。注意不要把「发送窗口」当基线——检查点合成消息会被误持久化，
                // 且窗口与 messageNodes 索引错位会覆盖边界内的历史节点。
                messages = promptMessages,
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
                frozenAnchorIds = frozenAnchorIds,
                // GenerationHandler logs the actual shared plan; no second selection/tokenization for logs.
                memories = loadedMemories,
                // 内置 → 模板 → 工作区提醒 → 插件 → 工具结果配平(末环);见 buildInputTransformerChain
                inputTransformers = buildInputTransformerChain(),
                // 插件输出转换器接在末尾:内置的 ThinkTag 等先规整,插件处理最终文本
                outputTransformers = outputTransformers + pluginOutputTransformers(),
                // 工具装配集中到 ChatToolFactory（记忆 + 搜索 + 本地 + 对话 + 工作区 + 技能 + MCP）；
                // 已在上面取好并计时（assembledTools）
                tools = assembledTools,
                initialRecoveryHint = initialRecoveryHint,
                // 已完成工具结果的**取消安全落点**（见 GenerationHandler 的 `onMessagesCommitted`）：
                // emit 走 flow/flowOn，生成协程一被取消就送不到本方法的 collect 端
                // ⇒ 把「已回填」的快照先记在 session 上，停止路径能补进内存态。
                onMessagesCommitted = { committed ->
                    session.lastCommittedMessages.value = committed
                },
                // 用量流水的归属：聊天主流程（含工具调用的每一步、每次重试）
                usageTag = UsageTag(
                    UsageSource.CHAT,
                    assistantId = assistant.id,
                    conversationId = conversationId,
                ),
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
                        session.replyMessageId.value = chunk.messages.lastOrNull()
                            ?.takeIf { it.role == MessageRole.ASSISTANT }?.id

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
                            // 上下文预算守卫（design-tool-context-gate §6）：聊天路径也传入，
                            // 工具循环内部增长撞到「剩余 <4K」时优雅停止并在此浮出错误条目
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
                // Failure skips onGenerationFinish: convert inline images before saving the draft,
                // so an oversized/invalid image cannot prevent the already generated text from persisting.
                val partialMessages = it.partialMessages.map { message ->
                    filesManager.convertBase64ImagePartToLocalFile(message.finishReasoning())
                }
                val annotated = partialMessages.mapIndexed { index, message ->
                    if (index == partialMessages.lastIndex && message.role == MessageRole.ASSISTANT) {
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
                    """{"status":"${reason.toolStatus}","error":"${reason.toolOutputError}"}"""
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
            // 标题生成同样计费：包一层流水，source=TITLE（统计页来源拆分里单独一项）
            val providerHandler = usageRecorder.wrap(
                providerManager.getProviderByType(provider),
                UsageTag(UsageSource.TITLE, assistantId = conversation.assistantId, conversationId = conversationId),
            )
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

            // 生成完，回写**只写 title 一列**、且基线是**当前内存值**：
            // 起名是数秒的网络请求，期间可能还在流式接收；旧写法「重新读 DB 整对象 → 改标题 →
            // saveConversation」会把请求发出那一刻的 messageNodes 盖回内存态（在途正文消失），
            // 而本入口每轮自动触发 ⇒ 是默认路径上的数据丢失。
            updateConversationMetadata(
                conversationId = conversationId,
                update = { it.copy(title = result.choices[0].message?.toText()?.trim().orEmpty()) },
                persist = { conversationRepo.updateTitle(conversationId, it.title) },
            )
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

            // 清掉旧建议：这是内存态的**字段变换**（不是整对象替换），生成中的正文不受影响；
            // 落库留到拿到新建议时一起写（旧建议在生成期间短暂留在库里的行为保持不变）。
            sessions[conversationId]?.let { session ->
                session.updateMetadata(
                    update = { it.copy(chatSuggestions = emptyList()) },
                    persist = { },
                )
            }

            // 建议生成是每轮都可能发生的后台请求，同样计入用量（source=SUGGESTION）
            val providerHandler = usageRecorder.wrap(
                providerManager.getProviderByType(provider),
                UsageTag(
                    UsageSource.SUGGESTION,
                    assistantId = conversation.assistantId,
                    conversationId = conversationId,
                ),
            )
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

            // 回写**只写 suggestions 一列**、基线是当前内存值：与 generateTitle 同款理由
            // （数秒网络请求 + 每轮自动触发 ⇒ 整对象写回会盖掉在途的流式正文）。
            val finalized = suggestions.take(10)
            updateConversationMetadata(
                conversationId = conversationId,
                update = { it.copy(chatSuggestions = finalized) },
                persist = { conversationRepo.updateChatSuggestions(conversationId, finalized) },
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    /**
     * 检查点式压缩（design-auto-compaction §4.1 三边界对齐 + §4.4 独立表）：
     *
     * 1. 保留策略 token 驱动：从尾部往前累计，预算 = 模型窗口 × [COMPRESS_RETAIN_RATIO]（至少 2 条）；
     *    用户可再指定「保留最近 N 条」作为**下限**（[keepRecentMessages]），token 预算不足时也不会少留；
     * 2. 其余发送窗口历史分块并行摘要，与旧检查点（prior）合并为单一新摘要（避免层层累积）；
     * 3. **轨迹只追加**：不改动 message_node，只往 compression_checkpoint 表追加一行
     *    （content + boundaryNodeId），并 trimToLatest 清理旧行；
     * 4. 更新内存态 conversation.compressionSummaries（发送窗口与 UI 据此生效）。
     *
     * @param windowTokens 模型上下文窗口 token 数（调用方用 effectiveContextLength 算好）
     * @param additionalPrompt 用户附加指令（与 prior 检查点拼接后注入 {additional_context}）
     * @param targetTokens 摘要目标 token（注入 {target_tokens}）
     * @param retainRatio 保留窗口占比（0.05~0.9 钳制；默认 COMPRESS_RETAIN_RATIO = 0.4）
     * @param keepRecentMessages 「保留最近 N 条」下限：这些最近节点不参与压缩、原样留在摘要之后；
     *   0 = 不额外指定（只按 [retainRatio] token 预算切分，默认下限 [COMPRESS_MIN_KEEP_MESSAGES] 条）
     * @return 成功时 Success(新检查点)，无可压缩内容时 Success(null)，失败时 Failure
     */
    suspend fun compressToCheckpoint(
        conversationId: Uuid,
        windowTokens: Int,
        additionalPrompt: String = "",
        targetTokens: Int = COMPRESS_TARGET_TOKENS,
        retainRatio: Double = COMPRESS_RETAIN_RATIO,
        keepRecentMessages: Int = 0,
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
        // 压缩摘要（可能被切成多块并发请求）也计入用量，source=COMPRESSION
        val providerHandler = usageRecorder.wrap(
            providerManager.getProviderByType(provider),
            UsageTag(UsageSource.COMPRESSION, conversationId = conversationId),
        )
        // 当前会话快照（含内存态最新）
        val conversation = getConversationFlow(conversationId).value
        // 只看发送窗口内的历史：边界前的旧内容已由 prior 检查点覆盖
        val effectiveWindow = conversation.compressionWindow()
        val window = effectiveWindow?.let { conversation.messageNodes.drop(it.boundaryIndex + 1) }
            ?: conversation.messageNodes
        val budget = (windowTokens * retainRatio.coerceIn(0.05, 0.9)).toInt()
        val plan = estimateCompressionPlan(
            nodes = window,
            budgetTokens = budget,
            minKeep = effectiveMinKeep(keepRecentMessages),
        )
        if (!plan.shouldCompress) return@runCatching null  // 无可压缩内容（非错误）

        val messagesToCompress = plan.toCompress.map { it.currentMessage }
        val priorSummary = conversation.displayCompression()?.content.orEmpty()
        val priorContext = if (priorSummary.isNotBlank()) {
            "PRIOR CHECKPOINT (merge this with the new conversation into one consolidated checkpoint):\n$priorSummary"
        } else {
            ""
        }

        // prior 只注入**第一个**分块：每个分块提示词都要求「与 prior 合并为单一检查点」，
        // 全部注入会让 prior 在 join 后的合并结果里出现 N 次，且随层层压缩滚雪球膨胀、
        // 稀释覆盖率闸的分母。单步路径（singleSummarize）恒注入。
        suspend fun compressChunk(chunk: List<UIMessage>, includePrior: Boolean): String {
            val contentToCompress = chunk.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val additional = buildString {
                if (additionalPrompt.isNotBlank()) {
                    appendLine("Additional instructions from user: $additionalPrompt")
                    appendLine()
                }
                if (includePrior) {
                    append(priorContext)
                }
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
            return result.choices.firstOrNull()?.message?.toText()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        // 失败阶梯 C3→C2→C4（design-multi-pass-compression.md §3.5）：现状分块并行产出
        // 过覆盖率闸（硬事实逐字命中 ≥0.8，代码侧），不过就退单步整体重摘一次，再不过
        // 落确定性兜底（原文截断 + incomplete 声明）——宁可明说没压好，不静默入档。
        // C2 在原文过长时跳过（单步请求自身会超窗）；各档同用上面的 model/providerHandler
        // （同一次压缩冻结模型，NMoi D76 同款原则）。
        val sourceText = messagesToCompress.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
        val ladder = runCompressionLadder(
            sourceText = sourceText,
            chunkedSummarize = {
                coroutineScope {
                    splitIntoChunks(messagesToCompress)
                        .mapIndexed { index, chunk ->
                            async { compressChunk(chunk, includePrior = index == 0) }
                        }
                        .awaitAll()
                }.joinToString("\n\n")
            },
            singleSummarize = if (sourceText.length <= COMPRESS_SINGLE_STEP_MAX_CHARS) {
                { compressChunk(messagesToCompress, includePrior = true) }
            } else {
                null
            },
            truncatedFallback = buildTruncatedFallback(sourceText),
        )
        if (ladder.tier != CompressionTier.CHUNKED) {
            Log.w(TAG, "compressToCheckpoint fell to tier=${ladder.tier} for $conversationId (coverage gate / upstream failure)")
        }
        val combined = ladder.summary

        val boundaryNode = plan.toCompress.lastOrNull() ?: return@runCatching null
        val boundaryNodeId = boundaryNode.id.toString()
        // 区域指纹：记录被摘要区域（含边界）的节点/选中消息状态，供 activeCompression 检测
        // 后续改动（编辑/删除/重生成/切分支）使检查点失效
        val boundaryIndex = conversation.messageNodes.indexOfFirst { it.id == boundaryNode.id }
        val areaFingerprint = conversation.areaFingerprintFor(boundaryIndex)
        // 被压缩条数按「有效窗口内普通消息」计：此前若是导入的原版检查点（其正文也是被摘要区），
        // 该计入累计数；仅取 compressionSummaries 会漏掉它，导致「已压缩 N 条」显示偏少。
        val priorCount = conversation.displayCompression()?.messageCount ?: 0
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
     * 元数据编辑的**统一落地入口**（置顶 / 移动助手 / **自动标题 / 聊天建议**等）。
     *
     * 为什么必须走这里而不是「读 DB → 改字段 → `saveConversation` 整对象写回」：
     * 后者对**活跃 session** 会用 DB 里的旧快照覆盖内存态 ⇒ 生成中途打星标/切助手
     * 会把已在流式接收的内容换掉（与「停止生成丢内容」同族，见 `ConversationSession.updateMetadata` 的说明）。
     * 自动标题与聊天建议是**数秒的网络请求**、且每轮自动触发——旧写法「生成完重新读 DB 再整对象
     * 写回」等于把请求发出那一刻的快照盖到内存态上，标题回来时在途正文消失。
     *
     * 三条约束：
     * - [update] 作用在**当前内存值**上（不接收外部快照）；
     * - [persist] 只写对应列（单列 UPDATE），不整对象写回；
     * - 会话**没有活跃 session 时**直接从 DB 读一次作为基线（此时内存态不存在，无覆盖之虞）。
     *
     * 决策本身在 [updateMetadataSafely]（顶层函数，便于单测覆盖「生成中回写不动消息」）。
     */
    suspend fun setConversationWorkspaceCwd(conversationId: Uuid, cwd: String?) {
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(workspaceCwd = cwd) },
            persist = { conversationRepo.updateConversationWorkspaceCwd(conversationId, it.workspaceCwd) },
        )
    }

    /** Update only an imported snapshot; never replace live message nodes while generating. */
    suspend fun updateConversationUpstreamConfig(
        conversationId: Uuid,
        update: (app.androidrik.rikkahub.data.model.UpstreamConversationConfig) ->
            app.androidrik.rikkahub.data.model.UpstreamConversationConfig,
    ) {
        updateConversationMetadata(
            conversationId = conversationId,
            update = { current -> current.copy(upstreamConfig = current.upstreamConfig?.let(update)) },
            persist = { conversationRepo.updateConversationUpstreamConfig(conversationId, it.upstreamConfig) },
        )
    }

    private suspend fun updateConversationMetadata(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) = updateMetadataSafely(
        session = sessions[conversationId],
        loadCurrent = { conversationRepo.getConversationById(conversationId) },
        update = update,
        persist = persist,
    )

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
     * **手动**改标题（Web/API 入口）。
     *
     * 与 [generateTitle] 共用同一个落地入口 [updateConversationMetadata]：
     * `update` 作用在**当前内存值**上（生成中的在途正文原样保留），`persist` 只写 `title` 一列。
     *
     * 历史写法是 Web 路由自己「读 DB 整对象 → 改标题 → `saveConversation`」：
     * 对活跃 session 那等于用 DB 旧快照覆盖内存态（在途正文消失），而且内存里仍是旧标题，
     * 下一次整对象保存还会把刚改好的标题**再写回去**（内存/库各持一版，静默回退）。
     */
    suspend fun setConversationTitle(conversationId: Uuid, title: String) {
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(title = title) },
            persist = { conversationRepo.updateTitle(conversationId, it.title) },
        )
    }

    /**
     * **手动**改会话级提示词注入绑定（Web/API 入口；同 [setConversationTitle] 的理由）。
     *
     * 两列由同一次编辑决定 ⇒ 内存态一次换好、库里一条语句写两列（[ConversationRepository.updateConversationInjections]）。
     */
    suspend fun setConversationInjections(
        conversationId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
    ) {
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(modeInjectionIds = modeInjectionIds, lorebookIds = lorebookIds) },
            persist = {
                conversationRepo.updateConversationInjections(
                    conversationId = conversationId,
                    modeInjectionIds = it.modeInjectionIds,
                    lorebookIds = it.lorebookIds,
                )
            },
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
     * **L1 锚点集合：会话开始冻结**（`docs/design/design-memory-injection-tiering.md` §5）。
     *
     * 已冻结（非 null）⇒ 原样返回，本会话内不再漂移；未冻结（null）⇒ 记下当前的锚点 id、
     * 落库并返回。调用点在首次生成之前（[handleMessageComplete]），因此**首次请求就用到的
     * 集合**与写进库的是同一份。
     *
     * 为什么值得冻结：L1 在 system（位置 0），它一变、其后整段前缀全部重算——真机实测
     * 命中率 98% → 0%（设计稿 §2）。冻结把这份代价从「每次改锚点」压到「每个会话一次」。
     *
     * **冻的是 id 集合、不是渲染好的文本**（`Conversation.frozenAnchors` 的注释里有完整理由）：
     * 记忆库有做梦整理与上限淘汰两条**自动**删除路径，冻文本会让已删的记忆永远留在 system 里。
     *
     * 为什么没有锚点也要写 `""` 而不是留 null：`null` 是「未冻结」的**有效取值**，留 null 会让
     * 下一轮再冻一次（每轮重算）且语义上永远"没冻过"。空串 = 已冻结且当时无锚点
     * ⇒ 不产生 system 块（不变式：无锚点 ⇒ 无块），输出与加 L1 之前逐字节一致。
     */
    private suspend fun ensureFrozenAnchors(
        conversationId: Uuid,
        conversation: Conversation,
        memories: List<AssistantMemory>,
    ): Set<String> {
        conversation.frozenAnchorIds?.let { return it }
        val ids = anchorIdsOf(memories)
        val frozen = ids.joinToString(",")
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(frozenAnchors = frozen) },
            persist = { conversationRepo.updateConversationFrozenAnchors(conversationId, frozen) },
        )
        return ids.toSet()
    }

    /**
     * **L1 锚点集合：助手指级的手动刷新点** —— 记忆页的「固化记忆」按钮。
     *
     * 作用域 = 该助手的**全部会话**，一条批量 UPDATE 换档（+ 同步活跃 session 的内存态）。
     * 与 [freezeAnchorsForConversation]（会话级）是**两个都显式**的入口，分工：
     * 这里一次改完所有，代价是该助手每条会话下次回来各吃一次前缀 miss；
     * 那里只让你选中的那一条付代价。
     *
     * **改锚点本身不再顺带刷新任何已有会话**（这一点是刻意的）：静默地作废一批会话的
     * 前缀缓存，正是这套分层设计要消灭的东西。改锚点只影响**新会话**——它们开始时按
     * 当时的集合冻结；已有会话要采纳就得有人**按下**这两个按钮之一。
     *
     * 两件事必须**一起**做，否则会分叉成「库已换档、内存态仍旧」：
     * 1. 先同步**活跃 session** 的内存态（沿用 [moveConversationToFolder] 的教训：只改库，
     *    内存里那份 `Conversation` 仍是旧快照，预览读的就是它）；
     * 2. 再一条批量 UPDATE 覆盖没有活跃 session 的会话。
     *
     * 顺序不能反：先写库的话，这中间任何一次整对象保存都会用内存里的旧值把库盖回去。
     *
     * @return 更新到的会话数（UI 用它给用户一个"作用面"的反馈——否则这个按钮看起来什么也没做）
     */
    suspend fun refreshFrozenAnchorsForAssistant(assistantId: Uuid): Int {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(assistantId) ?: return 0
        val frozen = anchorIdsOf(currentMemoriesFor(assistant)).joinToString(",")
        sessions.forEach { (conversationId, session) ->
            if (session.state.value.assistantId != assistantId) return@forEach
            session.updateMetadata(
                update = { it.copy(frozenAnchors = frozen) },
                persist = { conversationRepo.updateConversationFrozenAnchors(conversationId, frozen) },
            )
        }
        return conversationRepo.updateFrozenAnchorsForAssistant(assistantId, frozen)
    }

    /**
     * **L1 锚点集合：会话级的手动刷新点** —— 会话菜单的「本对话固化记忆」。
     *
     * 只让**这一条**会话采纳当前锚点集合：一次单列 UPDATE + 同步该会话的内存态。
     * 代价被限制在「你主动选中的那一条」上——这是与 [refreshFrozenAnchorsForAssistant]
     * 的关键差别（后者让该助手每条会话在下次回来时各吃一次前缀 miss，而那往往是冷启动、
     * 最需要命中的时刻）。
     *
     * @param assistantId 该会话所属助手（调用方从会话上取；本方法不自己去查会话，
     *   避免为了拿一个字段而把 session 建起来）
     * @return 固化进去的**锚点条数**（给用户的作用面反馈；0 = 该会话此后不含锚点块）
     */
    suspend fun freezeAnchorsForConversation(conversationId: Uuid, assistantId: Uuid): Int {
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(assistantId) ?: return 0
        val ids = anchorIdsOf(currentMemoriesFor(assistant))
        val frozen = ids.joinToString(",")
        updateConversationMetadata(
            conversationId = conversationId,
            update = { it.copy(frozenAnchors = frozen) },
            persist = { conversationRepo.updateConversationFrozenAnchors(conversationId, frozen) },
        )
        return ids.size
    }

    /**
     * 该助手锚点层的记忆来源（全局记忆助手看全局库）。
     *
     * **三处调用点共用它**：会话开始冻结（[handleMessageComplete]）、会话级固化
     * （[freezeAnchorsForConversation]）、助手指级固化（[refreshFrozenAnchorsForAssistant]）。
     * 抽出来是为了让「同一口径」是**结构上**成立的——否则同一个助手在不同入口下可能固化出
     * 不同的集合，而且是静默的。
     */
    private suspend fun currentMemoriesFor(assistant: Assistant): List<AssistantMemory> =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemories()
        } else {
            memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
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
            // 换助手 ⇒ 锚点来源换了人：一并清空冻结快照，下一次生成按**新助手**的锚点重新冻结。
            // 不清空就会把上一个助手的锚点继续当成本会话的「既定事实」（跨助手串味）。
            update = { it.copy(assistantId = assistantId, folderId = null, frozenAnchors = null) },
            persist = { conversationRepo.updateConversationAssistant(conversationId, it.assistantId) },
        )
        // 冻结快照单独清（同一原语：内存态上面已置 null，库侧单列补齐）
        conversationRepo.updateConversationFrozenAnchors(conversationId, null)
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

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation, force: Boolean = false) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!force && !exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
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
        val existingTitles = conversationRepo.getTitlesOfAssistant(currentConversation.assistantId)
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

    /**
     * 收掉「被打断的那一轮」起的后台任务（挂点见 `ConversationSession.onTurnCancelled`）。
     *
     * **为什么必须收**：`workspace_run_background` 的契约是「比一轮对话活得久」，所以它没有
     * 命令级超时；本轮被取消后模型再也拿不到它的输出、也不会再轮询，进程却在设备上继续跑
     * （吃 CPU/网络、继续写文件），最长要等 30 分钟时长看门狗才停。取消的语义是「这一轮停」，
     * 本轮起的进程属于这一轮，就该一起停。
     *
     * **只按 owner 收**：更早轮次起的任务（已经写进对话、模型还在 `workspace_task_status`
     * 轮询的）不受影响——那正是后台任务存在的意义。归属在任务启动那一刻记下，因此也覆盖
     * 「进程已启动、工具结果还没回填就被取消」这个靠 taskId 覆盖不到的窗口。
     */
    private fun killBackgroundTasksOfCancelledTurn(owner: String) {
        appScope.launch {
            runCatching { workspaceRepository.killBackgroundTasksByOwner(owner) }
                .onSuccess { count ->
                    if (count > 0) {
                        Log.i(TAG, "cancelled turn killed $count background task(s) of $owner")
                    }
                }
                .onFailure { Logging.log(TAG, "killBackgroundTasksOfCancelledTurn failed: $it") }
        }
    }

    /**
     * 最后一个节点里是否还有待审批工具（= 本轮停在审批上、且**没有活跃生成协程**）。
     *
     * 口径与 [finishInterruptedPendingTools] 一致（待审批工具总是挂在最后一步的回填上）。
     * 只服务 [stopGeneration] 的一个判断：这种状态下取消 Job 是空操作，本轮起的后台任务
     * 只能靠这条分支收掉。
     */
    private fun hasPendingToolApproval(conversationId: Uuid): Boolean =
        getConversationFlow(conversationId).value.messageNodes.lastOrNull()
            ?.currentMessage?.parts?.any { it is UIMessagePart.Tool && it.isPending } == true

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
        // 「停在工具审批上」也是被打断的一轮，但它**没有活跃 Job**（等用户决定时生成协程已经
        // 正常结束）⇒ 取消回调不会触发。这个状态要在收尾之前判定，收尾之后就看不到了。
        val awaitingApproval = hasPendingToolApproval(conversationId)
        val jobs = synchronized(session) {
            session.cancelJobs()
        }
        if (jobs.isNotEmpty()) {
            jobs.forEach { it.join() }
        }
        // 已完成、但没送达 collect 端的工具结果（见 GenerationHandler 的 `onMessagesCommitted`）：
        // 回填走的是 `emit` → flow/flowOn 的 channel → collect 端，而这三段都在被取消的
        // 生成 job 里 ⇒ 取消那一下正在回填的那一份结果会从内存态里消失。
        // 这里在**取消之后、落库之前**把快照里**已完成的结果**补进内存态（只补 output，
        // 不覆盖正文——快照与内存态可能各有胜负，见 withRestoredToolOutputs）；
        // 不补的话下面 persistStoppedGeneration 落库的就是缺了最后一个工具结果的旧内容。
        val pendingCommit = session.lastCommittedMessages.value
        session.lastCommittedMessages.value = null
        pendingCommit?.let { committed ->
            val current = getConversationFlow(conversationId).value
            val restored = current.withRestoredToolOutputs(committed)
            if (restored !== current) updateConversation(conversationId, restored)
        }
        // 注意：不因 jobs 为空而早退——生成可能刚结束而收尾保存尚未落库，
        // 此时同样需要下面的持久化（见 persistStoppedGeneration 的说明）。
        finishInterruptedPendingTools(conversationId, reason)
        // 审批中被放弃的这一轮同样收掉它起的后台任务（有活跃 Job 时取消回调已经收过了，
        // 这里只补「没有 Job 可取消」的那条路径；两次都调也安全——killByOwner 幂等）。
        if (jobs.isEmpty() && awaitingApproval) {
            killBackgroundTasksOfCancelledTurn(session.backgroundTaskOwner)
        }
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
                            node.copy(messages = node.messages.map {
                                filesManager.convertBase64ImagePartToLocalFile(it.finishReasoning())
                            })
                        },
                    ),
                )
            }.onFailure { Logging.log(TAG, "persistStoppedGeneration failed: $it") }
        }
    }
}

/**
 * 把「回填快照」里**已完成**的工具结果并回当前会话——**只补 `output`，绝不覆盖正文**。
 *
 * 这是取消安全落点的消费端（`stopGeneration` 用）：工具结果回填后本来要靠 `emit` →
 * `flow`/`flowOn` 的 channel → collect 端才进内存态，而生成协程被取消时这条链必断
 * ⇒「工具已算完、随即被取消」的那一份结果会消失。停止路径用本函数把它补回来。
 *
 * 为什么是「只补」而不是整份替换（`updateCurrentMessages`）：快照可能与内存态**各有胜负**——
 * 快照有最新回填的工具结果，内存里可能有快照之后才收到的流式正文。整份替换会拿旧正文盖掉
 * 新正文（另一种丢内容）；按 `toolCallId` 只补「当前还没执行、快照里已完成」的那些，
 * 两个方向都不会丢：
 * - 当前已执行的工具：一律保留当前值（当前更新）；
 * - 当前未执行、快照已执行：补上快照的结果（这正是要救的那份）；
 * - 正文/推理等其它 part：一个字都不动。
 *
 * 无可补时返回**同一个实例**（`===` 可判「没变化」，调用方据此跳过写回）。**纯函数**，便于单测。
 */
internal fun Conversation.withRestoredToolOutputs(committed: List<UIMessage>): Conversation {
    val completed = committed
        .flatMap { it.getTools() }
        .filter { it.isExecuted }
        .associateBy { it.toolCallId }
    if (completed.isEmpty()) return this

    var changed = false
    val restoredNodes = messageNodes.map { node ->
        var nodeChanged = false
        val messages = node.messages.map { message ->
            var messageChanged = false
            val parts = message.parts.map { part ->
                if (part !is UIMessagePart.Tool || part.isExecuted) {
                    part
                } else {
                    val committedPart = completed[part.toolCallId]
                    if (committedPart == null) {
                        part
                    } else {
                        messageChanged = true
                        committedPart
                    }
                }
            }
            if (messageChanged) {
                nodeChanged = true
                message.copy(parts = parts)
            } else {
                message
            }
        }
        if (nodeChanged) {
            changed = true
            node.copy(messages = messages)
        } else {
            node
        }
    }
    return if (changed) copy(messageNodes = restoredNodes) else this
}
