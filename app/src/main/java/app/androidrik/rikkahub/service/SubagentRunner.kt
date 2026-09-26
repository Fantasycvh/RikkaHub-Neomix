package app.androidrik.rikkahub.service

import android.content.Context
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.DenialGuard
import app.androidrik.rikkahub.data.ai.GenerationChunk
import app.androidrik.rikkahub.data.ai.GenerationHandler
import app.androidrik.rikkahub.data.ai.GenerationNotice
import app.androidrik.rikkahub.data.ai.ToolCallLoopGuard
import app.androidrik.rikkahub.data.ai.WorkspaceTurnBudget
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import kotlinx.coroutines.withTimeout
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.db.dao.AgentRunDAO
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * 无头 agent run 的**独立后台入口**（设计见 `docs/design-subagent-context.md` §9.4）。
 *
 * **为什么另开入口而不挂 ChatService**：①`backgroundTextGenerationParams` 的「无工具」
 * 后台旁路契约不可破坏（§六.7）——子代理需要工具，只能另开；②不碰
 * [ConversationSession]——它的 5 秒空闲回收 / 消息队列 / 审批 UI 都是聊天路径的形状，
 * run 自带护栏实例（[ToolCallLoopGuard] / [WorkspaceTurnBudget] / [DenialGuard]），
 * **run 自身就是「一轮」的边界**，跨审批累积的问题在这里不存在（无审批续跑）。
 *
 * **Phase 1 形态**：串行（同时至多一个活跃 run）、单层、手动 UI 派发（无 dispatch 工具）、
 * 无升级到人的审批（信封外的工具**不装配**，`needsApproval` 工具自动策略拒绝，见 §9.2）。
 * 诚实定位：无父会话集成时它是「独立后台 agent」而非「子代理」——Phase 1 验证的是
 * run 表/账本/入口/观测底座。
 */
class SubagentRunner(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val chatToolFactory: ChatToolFactory,
    private val agentRunDAO: AgentRunDAO,
) {
    @Volatile private var activeRunId: Uuid? = null
    @Volatile private var activeJob: Job? = null

    /** 同时至多一个活跃 run（Phase 1 串行）。 */
    fun hasActiveRun(): Boolean = activeRunId != null

    /**
     * 派发一个 run。有活跃 run 时返回 null（Phase 1 串行，UI 提示后重试）。
     *
     * @param profile 复用现有 [Assistant] 当模板（系统提示/模型/工作区绑定来自它）
     * @param goal 任务目标，原样成为首条 user 消息
     * @param allowedTools 能力信封：**允许装配**的工具名集合（派发时快照；
     *   信封外工具不装配——`needsApproval` 无人头时拦不住，见 §9.2 第 2 条）
     */
    fun dispatchRun(profile: Assistant, goal: String, allowedTools: Set<String>): Uuid? {
        if (activeRunId != null) return null
        if (goal.isBlank()) return null

        val runId = Uuid.random()
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(profile.chatModelId ?: settings.chatModelId)

        val run = AgentRunEntity(
            id = runId.toString(),
            entryPoint = AgentRunEntity.ENTRY_POINT_SUBAGENT,
            status = AgentRunEntity.STATUS_PENDING,
            profileAssistantId = profile.id.toString(),
            profileName = profile.name,
            modelId = model?.id?.toString(),
            modelName = model?.displayName,
            goal = goal,
            toolEnvelope = JsonInstant.encodeToString(
                ListSerializer(serializer<String>()),
                allowedTools.sorted(),
            ),
            transcript = newTranscript(listOf(UIMessage.user(goal))),
            resultSummary = null,
            error = null,
            createdAt = System.currentTimeMillis(),
            startedAt = null,
            finishedAt = null,
        )

        activeRunId = runId
        val job = appScope.launch {
            try {
                agentRunDAO.insertRun(run)
                executeRun(run, profile, allowedTools)
            } finally {
                activeRunId = null
                activeJob = null
            }
        }
        activeJob = job
        return runId
    }

    /** 取消运行中的 run（run 行标记 CANCELLED；已结束的 run 无效果）。 */
    fun cancelRun(id: Uuid) {
        if (activeRunId == id) activeJob?.cancel()
    }

    /** 删除 run 记录（运行中的先取消）。 */
    suspend fun deleteRun(id: Uuid) {
        cancelRun(id)
        agentRunDAO.deleteRun(id.toString())
    }

    private suspend fun executeRun(initial: AgentRunEntity, profile: Assistant, allowedTools: Set<String>) {
        val runId = Uuid.parse(initial.id)

        suspend fun update(transform: (AgentRunEntity) -> AgentRunEntity) {
            val current = agentRunDAO.getRun(initial.id) ?: return
            agentRunDAO.updateRun(transform(current))
        }

        update { it.copy(status = AgentRunEntity.STATUS_RUNNING, startedAt = System.currentTimeMillis()) }
        // run 活跃期间请求前台窗口（fork 已有的后台保活）。**只 start 不 stop**：
        // 该服务归用户的全局偏好所有，run 结束就停会误杀用户开启的保活。
        KeepAliveService.start(context)

        try {
            val settings = settingsStore.settingsFlow.first()
            // memory 工具在 generateInternal（第二装配点）内部叠加，**信封过滤不到** ⇒
            // 在源头关闭（run 的 profile 副本禁用记忆），而不是放进来再拦。
            val runProfile = profile.copy(enableMemory = false)
            val model = settings.findModelById(runProfile.chatModelId ?: settings.chatModelId)
            if (model == null) {
                failRun(runId, AgentRunEntity.ERROR_NO_MODEL, null)
                return
            }

            val tools = chatToolFactory
                .createTools(settings, runProfile, model, workspaceCwd = null)
                .filter { it.name in allowedTools }

            var currentMessages = listOf(UIMessage.user(initial.goal))
            var stopNotice: GenerationNotice? = null

            val flow = generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = MutableStateFlow(null),
                messages = currentMessages,
                compressionWindow = null,
                inputTransformers = emptyList(),
                outputTransformers = outputTransformers,
                assistant = runProfile,
                memories = emptyList(),
                tools = tools,
                // run 自身就是「一轮」的边界：护栏实例建在 run 上，无审批续跑问题
                loopGuard = ToolCallLoopGuard(),
                workspaceBudget = WorkspaceTurnBudget(),
                autoDenyApproval = true,
                denialGuard = DenialGuard(),
                conversationSystemPrompt = null,
                conversationModeInjectionIds = emptySet(),
                conversationLorebookIds = emptySet(),
                workspaceCwd = null,
            )

            try {
                withTimeout(RUN_TIMEOUT_MS) {
                    flow.collect { chunk ->
                        when (chunk) {
                            is GenerationChunk.Messages -> {
                                currentMessages = chunk.messages
                                // 每个 step 落一次轨迹：详情页近实时可见，崩溃也不丢全程
                                update { it.copy(transcript = newTranscript(currentMessages)) }
                            }

                            is GenerationChunk.Notice -> {
                                // 护栏停止：flow 会自行 break，这里只把原因映射成 run 的失败细分
                                stopNotice = chunk.notice
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                withContext(NonCancellable) {
                    failRun(runId, AgentRunEntity.ERROR_EXCEPTION, "run timeout (${RUN_TIMEOUT_MS}ms)")
                }
                return
            } catch (e: CancellationException) {
                // 用户取消（cancelRun）：withContext(NonCancellable) 保证取消路径能落库
                withContext(NonCancellable) {
                    finishRun(runId, AgentRunEntity.STATUS_CANCELLED, resultSummary = null, error = null)
                }
                throw e
            }

            val outcome = evaluateCompletion(currentMessages, stopNotice)
            when (outcome) {
                is RunOutcome.Completed -> finishRun(
                    runId, AgentRunEntity.STATUS_COMPLETED,
                    resultSummary = outcome.summary, error = null,
                )
                is RunOutcome.Failed -> failRun(runId, outcome.errorKind, outcome.detail)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            t.printStackTrace()
            failRun(runId, AgentRunEntity.ERROR_EXCEPTION, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private suspend fun finishRun(runId: Uuid, status: String, resultSummary: String?, error: String?) {
        val current = agentRunDAO.getRun(runId.toString()) ?: return
        agentRunDAO.updateRun(
            current.copy(
                status = status,
                resultSummary = resultSummary,
                error = error,
                finishedAt = System.currentTimeMillis(),
            )
        )
    }

    private suspend fun failRun(runId: Uuid, errorKind: String, detail: String?) {
        finishRun(
            runId, AgentRunEntity.STATUS_FAILED, resultSummary = null,
            error = listOf(errorKind, detail).filterNotNull().joinToString(": "),
        )
    }

    companion object {
        /**
         * 单 run 硬超时：护栏（LoopGuard/预算/拒绝）都是「行为」护栏，防不住
         * 「模型慢慢磨」——一个挂在 run 上的整体截止时间兜底。
         */
        const val RUN_TIMEOUT_MS = 30 * 60 * 1000L

        private fun newTranscript(messages: List<UIMessage>): String =
            JsonInstant.encodeToString(ListSerializer(serializer<UIMessage>()), messages)
    }
}

/** run 的完成判定（**纯函数**，便于 JVM 单测——§9.2.1 ③：空响应不得记成成功）。 */
internal sealed interface RunOutcome {
    data class Completed(val summary: String) : RunOutcome
    data class Failed(val errorKind: String, val detail: String?) : RunOutcome
}

internal fun evaluateCompletion(
    messages: List<UIMessage>,
    stopNotice: GenerationNotice?,
): RunOutcome {
    // 护栏停止优先：run 的失败细分直接来自 notice 类型
    when (stopNotice) {
        is GenerationNotice.ToolLoopStopped ->
            return RunOutcome.Failed(
                AgentRunEntity.ERROR_LOOP_DETECTED,
                "tool=${stopNotice.toolName} ×${stopNotice.repeats}",
            )
        is GenerationNotice.WorkspaceBudgetExhausted ->
            return RunOutcome.Failed(
                AgentRunEntity.ERROR_BUDGET_EXHAUSTED,
                "${stopNotice.usedChars}/${stopNotice.budgetChars} chars, ${stopNotice.steps} steps",
            )
        is GenerationNotice.TooManyDenials ->
            return RunOutcome.Failed(AgentRunEntity.ERROR_PERMISSION_DENIED, "${stopNotice.denials} denials")
        null -> Unit
    }

    val lastAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    val text = lastAssistant?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim()
        .orEmpty()

    // §9.2.1 ③：空响应不得记成 COMPLETED——「看起来跑完了」与「真的完成了」由这里区分
    if (text.isEmpty()) {
        return RunOutcome.Failed(AgentRunEntity.ERROR_MODEL_GAVE_UP, null)
    }
    return RunOutcome.Completed(summary = text.take(RESULT_SUMMARY_MAX_CHARS))
}

/** 结果摘要的行内截断（完整文本在 transcript 末条消息里，摘要只服务列表显示）。 */
private const val RESULT_SUMMARY_MAX_CHARS = 1000
