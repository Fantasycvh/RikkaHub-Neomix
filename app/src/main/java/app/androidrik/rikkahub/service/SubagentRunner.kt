package app.androidrik.rikkahub.service

import android.content.Context
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.data.ai.DenialGuard
import app.androidrik.rikkahub.data.ai.GenerationChunk
import app.androidrik.rikkahub.data.ai.GenerationHandler
import app.androidrik.rikkahub.data.ai.GenerationNotice
import app.androidrik.rikkahub.data.ai.ToolCallLoopGuard
import app.androidrik.rikkahub.data.ai.WorkspaceTurnBudget
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import app.androidrik.rikkahub.data.ai.tools.SubagentDispatcher
import app.androidrik.rikkahub.data.ai.tools.SubagentRunResult
import app.androidrik.rikkahub.data.ai.tools.resolveSubagentEnvelope
import kotlinx.coroutines.withTimeout
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.db.dao.AgentRunDAO
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.SubagentPreset
import app.androidrik.rikkahub.data.model.toRunProfile
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.utils.JsonInstant
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
 * **Phase 1 形态**：串行（同时至多一个活跃 run）、单层、无升级到人的审批（信封外的工具
 * **不装配**，`needsApproval` 工具自动策略拒绝，见 §9.2）。
 *
 * **§十（对话内派发）起的两个入口**：①运行页**手动派发**（[dispatchRun]，即发即忘）；
 * ②对话里模型通过 `dispatch_subagent` 工具**按预设调度**（[dispatchAndAwait]，阻塞到跑完）。
 * 两条路径共用同一份执行体 [executeRun]，因此账本、护栏、信封、完成判定完全一致——
 * 唯一差别是"谁在等结果"：UI 用 `appScope` 起独立 job，对话路径用父生成的协程。
 */
private const val TAG = "SubagentRunner"

class SubagentRunner(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val chatToolFactory: ChatToolFactory,
    private val agentRunDAO: AgentRunDAO,
) : SubagentDispatcher {
    @Volatile private var activeRunId: Uuid? = null
    @Volatile private var activeJob: Job? = null

    /** 同时至多一个活跃 run（Phase 1 串行）。 */
    fun hasActiveRun(): Boolean = activeRunId != null

    /**
     * 派发一个 run（**即发即忘**，运行页用）。有活跃 run 时返回 null（Phase 1 串行，UI 提示后重试）。
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
        val run = newRunEntity(
            entryPoint = AgentRunEntity.ENTRY_POINT_SUBAGENT,
            profile = profile,
            goal = goal,
            allowedTools = allowedTools,
            runId = runId,
        )

        activeRunId = runId
        val job = appScope.launch {
            try {
                agentRunDAO.insertRun(run)
                executeRun(run, profile, allowedTools, RUN_TIMEOUT_MS)
            } finally {
                activeRunId = null
                activeJob = null
            }
        }
        activeJob = job
        return runId
    }

    /**
     * **阻塞式**派发（对话内 `dispatch_subagent` 工具用，设计 §十）：跑完才返回。
     *
     * 与 [dispatchRun] 的三点差别，都是"父生成在等结果"带来的：
     * 1. **跑在调用方的协程里**（不是 `appScope`）——用户中断本轮对话即取消子代理
     *    （[executeRun] 的取消路径会把 run 落成 CANCELLED，不留假 running 行）；
     * 2. **超时由预设给**（默认 10 分钟，短于手工路径的 30 分钟），父生成不该被按住太久；
     * 3. 返回值带上**完整**最终文本，父模型直接消费。
     *
     * @return **null = 已有活跃 run**（串行闸；调用方把"忙"如实转达给模型，不要重试）
     */
    override suspend fun dispatchAndAwait(
        preset: SubagentPreset,
        goal: String,
        timeoutMillis: Long,
    ): SubagentRunResult? {
        if (activeRunId != null) return null
        if (goal.isBlank()) return null

        val profile = preset.toRunProfile()
        val runId = Uuid.random()
        val run = newRunEntity(
            entryPoint = AgentRunEntity.ENTRY_POINT_CHAT,
            profile = profile,
            goal = goal,
            allowedTools = preset.allowedTools,
            runId = runId,
        )

        activeRunId = runId
        return try {
            agentRunDAO.insertRun(run)
            val outcome = executeRun(run, profile, preset.allowedTools, timeoutMillis)
            // 状态/失败原因以**落库结果**为准：超时、取消、异常这些路径只有账本知道
            val stored = agentRunDAO.getRun(run.id)
            SubagentRunResult(
                runId = run.id,
                status = stored?.status ?: AgentRunEntity.STATUS_FAILED,
                summary = (outcome as? RunOutcome.Completed)?.summary,
                error = stored?.error,
            )
        } finally {
            activeRunId = null
        }
    }

    /** 账本行的**初始**快照（信封列在装配后用实际结果覆盖，见 [executeRun]）。 */
    private fun newRunEntity(
        entryPoint: String,
        profile: Assistant,
        goal: String,
        allowedTools: Set<String>?,
        runId: Uuid,
    ): AgentRunEntity {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(profile.chatModelId ?: settings.chatModelId)
        return AgentRunEntity(
            id = runId.toString(),
            entryPoint = entryPoint,
            status = AgentRunEntity.STATUS_PENDING,
            profileAssistantId = profile.id.toString(),
            profileName = profile.name,
            modelId = model?.id?.toString(),
            modelName = model?.displayName,
            goal = goal,
            toolEnvelope = JsonInstant.encodeToString(
                ListSerializer(serializer<String>()),
                allowedTools?.sorted().orEmpty(),
            ),
            transcript = newTranscript(listOf(UIMessage.user(goal))),
            resultSummary = null,
            error = null,
            createdAt = System.currentTimeMillis(),
            startedAt = null,
            finishedAt = null,
        )
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

    /**
     * 执行一个 run（**两条入口共用**）：装配 → 生成 → 落账本，返回值供阻塞路径回传结果。
     *
     * @param allowedTools **请求的**信封（`null` = 按预设能力开关，见 [resolveSubagentEnvelope]）。
     *   账本里存的 tool_envelope 列在装配之后覆盖为**实际生效**的那一份——账本要记"真的发生了什么"，
     *   而不是"当时想要什么"。
     * @return 完成判定结果；`null` = 中途异常/超时（终态已落库）
     */
    private suspend fun executeRun(
        initial: AgentRunEntity,
        profile: Assistant,
        allowedTools: Set<String>?,
        timeoutMillis: Long,
    ): RunOutcome? {
        val runId = Uuid.parse(initial.id)

        suspend fun update(transform: (AgentRunEntity) -> AgentRunEntity) {
            val current = agentRunDAO.getRun(initial.id) ?: return
            agentRunDAO.updateRun(transform(current))
        }

        update { it.copy(status = AgentRunEntity.STATUS_RUNNING, startedAt = System.currentTimeMillis()) }
        // run 活跃期间请求前台窗口（fork 已有的后台保活）。**只 start 不 stop**：
        // 该服务归用户的全局偏好所有，run 结束就停会误杀用户开启的保活。
        // ⚠️ 必须守卫：startForegroundService 要求服务**必须**随后调 startForeground，
        // 而 KeepAliveService 在「保活开关关」或「通知权限缺失」时会 stopSelf 不进前台
        // ⇒ 直接拉起必炸 ForegroundServiceDidNotStartInTimeException（真机实测崩溃）。
        // 仅在服务确实会进前台时拉起；失败也只是失去后台保活，不能崩掉整个 app。
        if (KeepAliveService.isEnabled(context) && KeepAliveService.canKeepAlive(context)) {
            runCatching { KeepAliveService.start(context) }
                .onFailure { Logging.log(TAG, "keep-alive start failed: $it") }
        }

        try {
            val settings = settingsStore.settingsFlow.first()
            // memory 工具在 generateInternal（第二装配点）内部叠加，**信封过滤不到** ⇒
            // 在源头关闭（run 的 profile 副本禁用记忆），而不是放进来再拦。
            val runProfile = profile.copy(enableMemory = false)
            val model = settings.findModelById(runProfile.chatModelId ?: settings.chatModelId)
            if (model == null) {
                failRun(runId, AgentRunEntity.ERROR_NO_MODEL, null)
                return null
            }

            // 分段装配（不是扁平列表）：信封的 `null` 语义（"按能力开关"）要知道工具的**来源**，
            // 否则 MCP / 技能安装这些无条件装配的来源会顺着 `null` 静默流进来。
            val segments = chatToolFactory.createToolSegments(settings, runProfile, model, workspaceCwd = null)
            val envelope = resolveSubagentEnvelope(segments, allowedTools)
            val tools = segments.flatMap { it.tools }.filter { it.name in envelope }
            update {
                it.copy(
                    toolEnvelope = JsonInstant.encodeToString(
                        ListSerializer(serializer<String>()),
                        envelope.sorted(),
                    )
                )
            }
            Logging.log(
                TAG,
                "run $runId envelope=${envelope.sorted()} (requested=${allowedTools?.sorted()})",
            )

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
                withTimeout(timeoutMillis) {
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
                    failRun(runId, AgentRunEntity.ERROR_EXCEPTION, "run timeout (${timeoutMillis}ms)")
                }
                return null
            } catch (e: CancellationException) {
                // 用户取消（cancelRun / 中断对话）：withContext(NonCancellable) 保证取消路径能落库
                withContext(NonCancellable) {
                    finishRun(runId, AgentRunEntity.STATUS_CANCELLED, resultSummary = null, error = null)
                }
                throw e
            }

            val outcome = evaluateCompletion(currentMessages, stopNotice)
            when (outcome) {
                is RunOutcome.Completed -> finishRun(
                    runId, AgentRunEntity.STATUS_COMPLETED,
                    // 摘要列只服务运行**列表**显示，故在这里截断；工具路径拿到的是完整文本
                    // （见 dispatchAndAwait：outcome.summary 原样回传）
                    resultSummary = outcome.summary.take(RESULT_SUMMARY_MAX_CHARS), error = null,
                )
                is RunOutcome.Failed -> failRun(runId, outcome.errorKind, outcome.detail)
            }
            return outcome
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            t.printStackTrace()
            failRun(runId, AgentRunEntity.ERROR_EXCEPTION, "${t.javaClass.simpleName}: ${t.message}")
            return null
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
    /** [summary] 是**完整**最终文本；列表行的截断由落库处负责（见 [RESULT_SUMMARY_MAX_CHARS]）。 */
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
    // 刻意**不截断**：阻塞路径要把全文回给父模型（§十），只有列表摘要列需要短文本，
    // 截断放在落库那一步（`finishRun` 的调用点）。
    return RunOutcome.Completed(summary = text)
}

/** 结果摘要的行内截断（完整文本在 transcript 末条消息里，摘要只服务列表显示）。 */
private const val RESULT_SUMMARY_MAX_CHARS = 1000
