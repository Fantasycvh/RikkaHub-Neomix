package app.androidrik.rikkahub.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.androidrik.ai.provider.Model
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isProviderSigned
import app.androidrik.rikkahub.data.ai.ThinkingDegenerationGuard
import app.androidrik.rikkahub.data.ai.WorkspaceTurnBudget
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

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
