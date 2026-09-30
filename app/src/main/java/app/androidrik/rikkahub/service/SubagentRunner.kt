package app.androidrik.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.data.ai.APPROVAL_TIMEOUT_REASON
import app.androidrik.rikkahub.data.ai.ConcurrentRunGate
import app.androidrik.rikkahub.data.ai.ContextBudgetGuard
import app.androidrik.rikkahub.data.ai.DenialGuard
import app.androidrik.rikkahub.data.ai.GenerationChunk
import app.androidrik.rikkahub.data.ai.GenerationHandler
import app.androidrik.rikkahub.data.ai.GenerationNotice
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.ai.ToolCallLoopGuard
import app.androidrik.rikkahub.data.ai.USER_DENIED_REASON
import app.androidrik.rikkahub.data.ai.applyApprovalDecisions
import app.androidrik.rikkahub.data.ai.approvalRequestSummary
import app.androidrik.rikkahub.data.ai.pendingApprovalTools
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
import app.androidrik.rikkahub.data.model.SubagentToolApproval
import app.androidrik.rikkahub.data.model.toRunProfile
import app.androidrik.rikkahub.utils.effectiveContextLength
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.NotificationAction
import app.androidrik.rikkahub.utils.cancelNotification
import app.androidrik.rikkahub.utils.NotificationUtil
import app.androidrik.rikkahub.utils.sendNotification
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import kotlin.coroutines.coroutineContext
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
    /**
     * 并发闸：上限可配（设置 → 通用 → 护栏与预算）。lazy 而非构造期读取——
     * runner 是 Koin 单例，构造时 DataStore 可能还没加载完（settingsFlow.value 是
     * 全默认值的 dummy），首次派发时读取则必然已加载。
     */
    private val gate by lazy {
        ConcurrentRunGate(settingsStore.settingsFlow.value.subagentMaxConcurrentRuns)
    }

    /** runId → 它所在的协程 Job（取消用；两条入口都登记，这样才能"只取消这一个 run"）。 */
    private val activeJobs = ConcurrentHashMap<Uuid, Job>()

    private val _progress = MutableStateFlow<List<RunProgress>>(emptyList())

    /**
     * **活跃 run 的进度列表**（对话内工具卡据此显示"跑到第几步"）。
     *
     * 并行上限 2 之后不能再假设"活跃的就是我这一个"，所以这里给的是列表，卡片按
     * **预设名 + 任务目标**匹配自己那一条（两者都在卡片的入参里，无需额外接线）；
     * 匹配不到且只有一条时退回那一条，多于一条且匹配不到时只显示"并行运行中"的聚合态。
     */
    val progress: StateFlow<List<RunProgress>> = _progress.asStateFlow()

    /** 还能不能再开一个 run（UI 按钮态与派发工具的先手判断）。 */
    fun canStartRun(): Boolean = gate.canAcquire()

    /** 当前活跃 run 数（诊断/UI 用）。 */
    fun activeRunCount(): Int = gate.activeCount()

    /** 有没有在跑的 run（列表页提示用；语义与"能否再开一个"分开，别混用）。 */
    fun hasActiveRun(): Boolean = gate.activeCount() > 0

    /** 该 run 进度条目的起始时间（已存在则沿用，避免每步重置计时）。 */
    private fun progressStartedAt(runId: String): Long =
        _progress.value.firstOrNull { it.runId == runId }?.startedAt ?: System.currentTimeMillis()

    /** 进度列表的写入助手：按 runId 覆盖或移除（并行下不能整体赋值）。 */
    private fun upsertProgress(progress: RunProgress) {
        _progress.value = _progress.value.filterNot { it.runId == progress.runId } + progress
    }

    private fun clearProgress(runId: String) {
        _progress.value = _progress.value.filterNot { it.runId == runId }
    }

    /**
     * 派发一个 run（**即发即忘**，运行页用）。有活跃 run 时返回 null（Phase 1 串行，UI 提示后重试）。
     *
     * @param profile 复用现有 [Assistant] 当模板（系统提示/模型/工作区绑定来自它）
     * @param goal 任务目标，原样成为首条 user 消息
     * @param allowedTools 能力信封：**允许装配**的工具名集合（派发时快照；
     *   信封外工具不装配——`needsApproval` 无人头时拦不住，见 §9.2 第 2 条）
     */
    fun dispatchRun(profile: Assistant, goal: String, allowedTools: Set<String>): Uuid? {
        if (goal.isBlank()) return null
        val runId = Uuid.random()
        // 先占位置再落库：占不到就如实返回 null（调用方提示"已达并行上限"），不排队
        if (!gate.tryAcquire(runId.toString())) return null
        val run = newRunEntity(
            entryPoint = AgentRunEntity.ENTRY_POINT_SUBAGENT,
            profile = profile,
            goal = goal,
            allowedTools = allowedTools,
            runId = runId,
            parentConversationId = null,
        )

        val job = appScope.launch {
            try {
                agentRunDAO.insertRun(run)
                executeRun(run, profile, allowedTools, RUN_TIMEOUT_MS, SubagentToolApproval.DENY)
            } finally {
                gate.release(runId.toString())
                activeJobs.remove(runId)
                // 进度只服务"跑的时候看一眼"，结束即清（订阅者据此回到结果态）
                clearProgress(runId.toString())
            }
        }
        activeJobs[runId] = job
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
        parentConversationId: String?,
    ): SubagentRunResult? {
        if (goal.isBlank()) return null

        val profile = preset.toRunProfile()
        val runId = Uuid.random()
        if (!gate.tryAcquire(runId.toString())) return null
        val run = newRunEntity(
            entryPoint = AgentRunEntity.ENTRY_POINT_CHAT,
            profile = profile,
            goal = goal,
            allowedTools = preset.allowedTools,
            runId = runId,
            parentConversationId = parentConversationId,
        )

        // **挂在调用方 Job 之下的子 Job**：既能"只取消这一个 run"（详情页的取消按钮），
        // 又能让"用户中断本轮对话"照旧级联取消它（父 Job 取消 → 子 Job 取消）。
        // 用 Job(parent) 而不是直接复用父 Job：前者取消不会反向杀掉父生成。
        val runJob = Job(coroutineContext[Job])
        activeJobs[runId] = runJob
        return try {
            withContext(runJob) {
                agentRunDAO.insertRun(run)
                val outcome = executeRun(
                    initial = run,
                    profile = profile,
                    allowedTools = preset.allowedTools,
                    timeoutMillis = timeoutMillis,
                    toolApproval = preset.toolApproval,
                )
                // 状态/失败原因以**落库结果**为准：超时、取消、异常这些路径只有账本知道
                val stored = agentRunDAO.getRun(run.id)
                SubagentRunResult(
                    runId = run.id,
                    status = stored?.status ?: AgentRunEntity.STATUS_FAILED,
                    summary = (outcome as? RunOutcome.Completed)?.summary,
                    error = stored?.error,
                )
            }
        } finally {
            gate.release(runId.toString())
            activeJobs.remove(runId)
            clearProgress(runId.toString())
        }
    }

    /** 账本行的**初始**快照（信封列在装配后用实际结果覆盖，见 [executeRun]）。 */
    private fun newRunEntity(
        entryPoint: String,
        profile: Assistant,
        goal: String,
        allowedTools: Set<String>?,
        runId: Uuid,
        parentConversationId: String?,
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
            parentConversationId = parentConversationId,
            createdAt = System.currentTimeMillis(),
            startedAt = null,
            finishedAt = null,
        )
    }

    /**
     * 填入一个待批工具的决定（**运行详情页的批准/拒绝按钮**走这里；通知栏按钮走
     * [SubagentApprovalReceiver]，两者都落到同一个队列）。
     *
     * @return true = 确实有 run 在等这个工具（已生效）；false = 无人在等（已超时/进程重启）
     */
    fun resolveApproval(toolCallId: String, approved: Boolean): Boolean =
        SubagentApprovalQueue.resolve(toolCallId, approved)

    /**
     * 取消运行中的 run（run 行标记 CANCELLED；已结束的 run 无效果）。
     *
     * 并行下必须**按 id 精确取消**：以前只有一个 `activeJob` 字段，两个 run 并行时
     * "取消 A"会打到 B 的 Job 上（静默取消错对象）。
     */
    fun cancelRun(id: Uuid) {
        if (!gate.isActive(id.toString())) return
        activeJobs[id]?.cancel()
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
        /** 工具级审批策略（预设给；运行页手动派发恒为 [SubagentToolApproval.DENY]）。 */
        toolApproval: SubagentToolApproval,
    ): RunOutcome? {
        val runId = Uuid.parse(initial.id)

        suspend fun update(transform: (AgentRunEntity) -> AgentRunEntity) {
            val current = agentRunDAO.getRun(initial.id) ?: return
            agentRunDAO.updateRun(transform(current))
        }

        update { it.copy(status = AgentRunEntity.STATUS_RUNNING, startedAt = System.currentTimeMillis()) }
        upsertProgress(
            RunProgress(
                runId = initial.id,
                label = initial.profileName,
                goal = initial.goal,
                status = AgentRunEntity.STATUS_RUNNING,
                steps = 0,
                lastTool = null,
                startedAt = System.currentTimeMillis(),
            )
        )
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

            // 护栏实例**建在 run 上、跨"审批续跑"累积**：ASK 策略下每次批准都是一次新的
            // generateText 调用（与聊天路径的审批续跑同形），建在调用内会每次重置——
            // LoopGuard 真机踩过这个坑（见 ToolCallLoopGuard 类注释），这里预先避开。
            val loopGuard = ToolCallLoopGuard()
            val workspaceBudget = WorkspaceTurnBudget()
            val denialGuard = DenialGuard()
            // 上下文预算（设计 §十二）：run 的轨迹单调增长，而它既无压缩也无 contextMessageLimit
            // ⇒ 必须有自己的刹车，否则撞满窗口时 provider 直接报错、整轮白跑
            val contextGuard = ContextBudgetGuard(
                windowTokens = model.effectiveContextLength(settings.contextLengthOverride),
            )
            val autoDenyApproval = toolApproval != SubagentToolApproval.ASK
            // 用 Log.i 而非 Logging.log：策略是排查审批问题的第一现场，要能在 logcat 里直接看到
            android.util.Log.i(TAG, "run $runId toolApproval=" + if (autoDenyApproval) "DENY" else "ASK")

            var outcome: RunOutcome? = null
            try {
                withTimeout(timeoutMillis) {
                    var approvalRounds = 0
                    while (true) {
                        var stopNotice: GenerationNotice? = null

                        generationHandler.generateText(
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
                            loopGuard = loopGuard,
                            workspaceBudget = workspaceBudget,
                            autoDenyApproval = autoDenyApproval,
                            denialGuard = denialGuard,
                            contextGuard = contextGuard,
                            conversationSystemPrompt = null,
                            conversationModeInjectionIds = emptySet(),
                            conversationLorebookIds = emptySet(),
                            workspaceCwd = null,
                        ).collect { chunk ->
                            when (chunk) {
                                is GenerationChunk.Messages -> {
                                    currentMessages = chunk.messages
                                    // 每个 step 落一次轨迹：详情页近实时可见，崩溃也不丢全程
                                    update { it.copy(transcript = newTranscript(currentMessages)) }
                                    // 同一步顺带更新进度（对话内工具卡据此显示"跑到第几步"）
                                    val snapshot = snapshotProgress(currentMessages)
                                    upsertProgress(
                                        RunProgress(
                                            runId = initial.id,
                                            label = initial.profileName,
                                            goal = initial.goal,
                                            status = AgentRunEntity.STATUS_RUNNING,
                                            steps = snapshot.steps,
                                            lastTool = snapshot.lastTool,
                                            startedAt = progressStartedAt(initial.id),
                                        )
                                    )
                                }

                                is GenerationChunk.Notice -> {
                                    // 护栏停止：flow 会自行 break，这里只把原因映射成 run 的失败细分
                                    stopNotice = chunk.notice
                                }
                            }
                        }

                        // 停在"等人批"上（ASK 策略下 needsApproval 工具被置为 Pending）⇒ 去问人，
                        // 拿到决定后回填消息**再喂回同一个 generateText 续跑**——与聊天路径的
                        // handleToolApproval 同一手法（canResumeExecution 就是为此设的）。
                        val pending = pendingApprovalTools(currentMessages)
                        if (pending.isEmpty()) {
                            outcome = evaluateCompletion(currentMessages, stopNotice)
                            break
                        }
                        if (approvalRounds >= MAX_APPROVAL_ROUNDS) {
                            // 防御：正常路径每轮都会把 Pending 全部答完，不该走到这里；
                            // 真走到说明回填没生效，宁可失败也不要无限问下去
                            outcome = RunOutcome.Failed(
                                AgentRunEntity.ERROR_EXCEPTION,
                                "too many approval rounds (" + approvalRounds + ")",
                            )
                            break
                        }
                        approvalRounds += 1

                        update { it.copy(status = AgentRunEntity.STATUS_AWAITING_APPROVAL) }
                        _progress.value = _progress.value.map { entry ->
                            if (entry.runId == initial.id) {
                                entry.copy(status = AgentRunEntity.STATUS_AWAITING_APPROVAL)
                            } else {
                                entry
                            }
                        }
                        val decisions = awaitHumanDecision(
                            runId = initial.id,
                            label = initial.profileName,
                            pending = pending,
                        )
                        currentMessages = applyApprovalDecisions(currentMessages, decisions)
                        update {
                            it.copy(
                                status = AgentRunEntity.STATUS_RUNNING,
                                transcript = newTranscript(currentMessages),
                            )
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                withContext(NonCancellable) {
                    failRun(runId, AgentRunEntity.ERROR_EXCEPTION, "run timeout (" + timeoutMillis + "ms)")
                }
                return null
            } catch (e: CancellationException) {
                // 用户取消（cancelRun / 中断对话）：withContext(NonCancellable) 保证取消路径能落库
                withContext(NonCancellable) {
                    finishRun(runId, AgentRunEntity.STATUS_CANCELLED, resultSummary = null, error = null)
                    cancelApprovalNotification()
                }
                throw e
            }

            when (outcome) {
                null -> Unit
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

    /**
     * 等人批准（设计 §十一）：登记 → 弹通知 → 等（有时间上限）→ 回填决定。
     *
     * **超时按拒绝处理而**不是**让 run 失败**：run 的预算（`timeoutSeconds`）是给"跑"用的，
     * 人不在却把整个 run 判死，等于把一次没看见的通知升级成任务失败——
     * 而拒绝后模型还能继续用别的办法把任务做完（这正是 §9.2 的 denied 语义）。
     * 超时的措辞与用户手拒**分开**（见 `APPROVAL_TIMEOUT_REASON`）。
     *
     * 无通知权限时不弹通知：此时只能靠运行详情页的卡片（前台场景），等待照旧。
     */
    private suspend fun awaitHumanDecision(
        runId: String,
        label: String,
        pending: List<UIMessagePart.Tool>,
    ): Map<String, ToolApprovalState> {
        val waiters = pending.associate { it.toolCallId to SubagentApprovalQueue.register(it.toolCallId) }
        postApprovalNotification(runId, label, pending)
        try {
            withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
                // 逐个 await：各工具的决定互相独立，谁先答谁先回填
                waiters.values.forEach { it.await() }
            }
        } finally {
            pending.forEach { SubagentApprovalQueue.forget(it.toolCallId) }
            cancelApprovalNotification()
        }

        return pending.associate { tool ->
            val waiter = waiters.getValue(tool.toolCallId)
            when {
                !waiter.isCompleted -> tool.toolCallId to ToolApprovalState.Denied(APPROVAL_TIMEOUT_REASON)
                waiter.getCompleted() -> tool.toolCallId to ToolApprovalState.Approved
                else -> tool.toolCallId to ToolApprovalState.Denied(USER_DENIED_REASON)
            }
        }
    }

    private fun postApprovalNotification(
        runId: String,
        label: String,
        pending: List<UIMessagePart.Tool>,
    ) {
        if (!NotificationUtil.hasNotificationPermission(context)) {
            Logging.log(TAG, "no notification permission; approval can only be answered in the app")
            return
        }
        runCatching {
            context.sendNotification(
                channelId = SUBAGENT_APPROVAL_NOTIFICATION_CHANNEL_ID,
                notificationId = APPROVAL_NOTIFICATION_ID,
            ) {
                title = context.getString(
                    R.string.subagent_approval_title,
                    label.ifBlank { context.getString(R.string.subagent_page_title) },
                )
                content = pending.joinToString("\n") { approvalRequestSummary(it) }
                useBigTextStyle = true
                autoCancel = true
                useDefaults = true
                category = NotificationCompat.CATEGORY_REMINDER
                contentIntent = runDetailPendingIntent(runId)
                actions += NotificationAction(
                    icon = R.drawable.ic_stat_rikkahub,
                    title = context.getString(R.string.subagent_approval_approve),
                    intent = approvalDecisionIntent(runId, pending, approved = true),
                )
                actions += NotificationAction(
                    icon = R.drawable.ic_stat_rikkahub,
                    title = context.getString(R.string.subagent_approval_deny),
                    intent = approvalDecisionIntent(runId, pending, approved = false),
                )
            }
        }.onFailure { Logging.log(TAG, "approval notification failed: $it") }
    }

    private fun cancelApprovalNotification() {
        runCatching { context.cancelNotification(APPROVAL_NOTIFICATION_ID) }
    }

    /** 点通知主体 → 运行详情页（在那里能看到入参全文与轨迹）。 */
    private fun runDetailPendingIntent(runId: String): PendingIntent {
        val intent = Intent(context, app.androidrik.rikkahub.RouteActivity::class.java)
            .putExtra("runId", runId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            APPROVAL_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 通知按钮的 PendingIntent：**显式组件 + exported=false 的 Receiver**。
     *
     * requestCode 必须区分（批准所有 / 拒绝所有），否则 `FLAG_UPDATE_CURRENT` 会让后者覆盖前者
     * （两个按钮变成同一个动作——这是 PendingIntent 的经典坑）。
     */
    private fun approvalDecisionIntent(
        runId: String,
        pending: List<UIMessagePart.Tool>,
        approved: Boolean,
    ): PendingIntent {
        val intent = Intent(context, SubagentApprovalReceiver::class.java)
            .putStringArrayListExtra(
                SubagentApprovalReceiver.EXTRA_TOOL_CALL_IDS,
                ArrayList(pending.map { it.toolCallId }),
            )
            .putExtra(SubagentApprovalReceiver.EXTRA_APPROVED, approved)
        return PendingIntent.getBroadcast(
            context,
            if (approved) APPROVAL_APPROVE_REQUEST_CODE else APPROVAL_DENY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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

        /**
         * 等人批准的时间上限：**5 分钟**。
         *
         * 取值理由：审批是"人可能马上要答"的交互，不是异步任务——手机用户要么几秒内看到
         * 通知，要么已经离开。超时按拒绝处理并让 run 继续（见 `awaitHumanDecision`）；
         * 想要更长的等待，应该修的是"通知没被看到"，而不是把等待时间拉长。
         */
        const val APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * 审批轮数上限（防御性）。
         *
         * 正常情况下每轮都把 Pending 全部答完，轮数只受模型行为影响；设上限是为了让
         * "回填没生效"这类自身 bug 表现为**一次失败**，而不是无限问下去。
         */
        const val MAX_APPROVAL_ROUNDS = 20

        private const val APPROVAL_NOTIFICATION_ID = 4242
        private const val APPROVAL_APPROVE_REQUEST_CODE = 4243
        private const val APPROVAL_DENY_REQUEST_CODE = 4244

        private fun newTranscript(messages: List<UIMessage>): String =
            JsonInstant.encodeToString(ListSerializer(serializer<UIMessage>()), messages)
    }
}

/**
 * 当前活跃 run 的进度快照（对话内工具卡的数据源）。
 *
 * [label] 是 profile/预设名，[steps] 是**模型步数**（助手消息条数——一步里可以并行下发多个
 * 工具调用，计成一次更贴近“跑到第几步”的直觉），[lastTool] 是最近一次工具调用的名字。
 */
data class RunProgress(
    val runId: String,
    val label: String,
    /** 任务目标：并行时卡片靠 (label, goal) 认领自己那一条（两者都在卡片入参里）。 */
    val goal: String,
    val status: String,
    val steps: Int,
    val lastTool: String?,
    val startedAt: Long,
)

/** [snapshotProgress] 的结果（纯数据，便于单测）。 */
internal data class RunProgressSnapshot(val steps: Int, val lastTool: String?)

/**
 * 从 run 的实时消息列表里取进度（**纯函数**）。
 *
 * 刻意从 `currentMessages` 派生而不是自己记账：轨迹是**多步共享的唯一事实来源**，
 * 另记一份计数一定会跟它漂移（例：审批续跑、工具结果回填都会重算 messages）。
 */
internal fun snapshotProgress(messages: List<UIMessage>): RunProgressSnapshot {
    val steps = messages.count { it.role == MessageRole.ASSISTANT }
    val lastTool = messages.asReversed().firstNotNullOfOrNull { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()?.toolName
    }
    return RunProgressSnapshot(steps = steps, lastTool = lastTool)
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
        is GenerationNotice.ContextBudgetExhausted ->
            return RunOutcome.Failed(
                AgentRunEntity.ERROR_CONTEXT_BUDGET,
                "${stopNotice.usedTokens}/${stopNotice.windowTokens} tokens",
            )
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
