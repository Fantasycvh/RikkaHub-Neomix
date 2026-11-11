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
import app.androidrik.rikkahub.data.ai.DeliverableChecker
import app.androidrik.rikkahub.data.ai.LoopGateConfig
import app.androidrik.rikkahub.data.ai.forcePassNote
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.ai.ThinkingDegenerationGuard
import app.androidrik.rikkahub.data.ai.UsageSource
import app.androidrik.rikkahub.data.ai.UsageTag
import app.androidrik.rikkahub.data.ai.ToolCallLoopGuard
import app.androidrik.rikkahub.data.ai.USER_DENIED_REASON
import app.androidrik.rikkahub.data.ai.applyApprovalDecisions
import app.androidrik.rikkahub.data.ai.approvalRequestSummary
import app.androidrik.rikkahub.data.ai.pendingApprovalTools
import app.androidrik.rikkahub.data.ai.WorkspaceTurnBudget
import app.androidrik.rikkahub.data.ai.tools.ChatToolFactory
import app.androidrik.rikkahub.data.ai.transformers.defaultOutputTransformers
import app.androidrik.rikkahub.data.task.backgroundApprovalDecision
import app.androidrik.rikkahub.data.task.awaitRunApproval
import app.androidrik.rikkahub.data.ai.tools.SubagentDispatchContext
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
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.workspace.ExecutionScope
import app.androidrik.workspace.WorkspaceStorageArea
import app.androidrik.rikkahub.utils.effectiveContextLength
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.NotificationAction
import app.androidrik.rikkahub.utils.cancelNotification
import app.androidrik.rikkahub.utils.NotificationUtil
import app.androidrik.rikkahub.utils.sendNotification
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * 无头 agent run 的**独立后台入口**（设计见 `docs/design/design-subagent-context.md` §9.4）。
 *
 * **为什么另开入口而不挂 ChatService**：①`backgroundTextGenerationParams` 的「无工具」
 * 后台旁路契约不可破坏（§六.7）——子代理需要工具，只能另开；②不碰
 * [ConversationSession]——它的 5 秒空闲回收 / 消息队列 / 审批 UI 都是聊天路径的形状，
 * run 自带护栏实例（[ToolCallLoopGuard] / [WorkspaceTurnBudget] / [DenialGuard]），
 * **run 自身就是「一轮」的边界**，跨审批累积的问题在这里不存在（无审批续跑）。
 *
 * **当前形态**：并发可配（设置 `subagentMaxConcurrentRuns`，默认 2、范围 1–8；本类各入口共用
 * 同一个进程级并发闸，满了**拒绝不排队**）、单层、无升级到人的审批（信封外的工具
 * **不装配**，`needsApproval` 工具自动策略拒绝，见 §9.2）。
 *
 * **§十（对话内派发）起的入口**：①运行页**手动派发**（[dispatchRun]，即发即忘）；
 * ②对话里模型通过 `dispatch_subagent` 工具**按预设调度**（[dispatchAndAwait]，阻塞到跑完）；
 * ③P4b 定时任务可选的 headless 派发（[dispatchScheduledAwait]，默认关）。
 * 各入口共用同一份执行体 [executeRun]，因此账本、护栏、信封、完成判定完全一致——
 * 差别只在"谁在等结果"：UI 用 `appScope` 起独立 job，对话路径用父生成的协程，
 * 定时路径用调度服务的协程。
 */
private const val TAG = "SubagentRunner"

/** 结果摘要的行内截断（完整文本在 transcript 末条消息里，摘要只服务列表显示）。 */
private const val RESULT_SUMMARY_MAX_CHARS = 1000

class SubagentRunner(
    private val context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val chatToolFactory: ChatToolFactory,
    private val agentRunDAO: AgentRunDAO,
    private val workspaceRepository: WorkspaceRepository,
    private val taskHandoffs: TaskHandoffRegistry = TaskHandoffRegistry(),
) : SubagentDispatcher {
    private val runNotifications = RunNotifications(context)

    private val approvalNotifications = SubagentApprovalNotifications(context, settingsStore, runNotifications)

    fun dismissCompletionNotification(runId: String) = runNotifications.cancel(runId)

    /**
     * 并发闸：上限可配（设置 → 通用 → 护栏与预算），且**每次 acquire 现读设置**。
     *
     * 为什么不用旧的 `by lazy { ConcurrentRunGate(limit) }`：`lazy` 把上限冻结在"第一次
     * 派发那一刻"，用户改完设置必须重启进程才生效（P0 设计 §5.1 要求"改设置立即生效"）。
     * 构造 runner 时 DataStore 可能还没加载完（settingsFlow.value 是全默认值的 dummy），
     * 但读取发生在 `tryAcquire`/`canAcquire` 调用时；设置加载完成后读到的是真实值，
     * 加载完成前用默认值 2——与旧行为一致，不会把 dummy 永久焊死。
     */
    private val gate = ConcurrentRunGate(
        limitProvider = {
            val settings = settingsStore.settingsFlow.value
            // 回滚开关关闭时回到旧 `by lazy` 语义：上限冻结在首次读取，改设置需重启。
            if (settings.dynamicRunGate) {
                settings.subagentMaxConcurrentRuns
            } else {
                frozenGateLimit
            }
        },
    )

    /** `dynamicRunGate=false` 时冻结用的首读值（只在回滚路径被读取）。 */
    private val frozenGateLimit by lazy { settingsStore.settingsFlow.value.subagentMaxConcurrentRuns }

    /**
     * 并发闸的一致性快照（P0 `deviceLoad`）：任务中心「run 2/3」一类的读数直接取这里，
     * 不要在 UI 侧分别调 `activeCount()` 与设置值——两者可能来自不同瞬间。
     */
    fun gateLoad(): ConcurrentRunGate.Load = gate.load()

    /** 进度注册表（活跃 Job 登记 + 进度列表，见 [RunProgressTracker]）。 */
    private val progressTracker = RunProgressTracker()

    /** **活跃 run 的进度列表**（对话内工具卡据此显示"跑到第几步"）。 */
    val progress: StateFlow<List<RunProgress>> get() = progressTracker.progress

    /** 还能不能再开一个 run（UI 按钮态与派发工具的先手判断）。 */
    fun canStartRun(): Boolean = gate.canAcquire()

    /** 当前活跃 run 数（诊断/UI 用）。 */
    fun activeRunCount(): Int = gate.activeCount()

    /** 有没有在跑的 run（列表页提示用；语义与"能否再开一个"分开，别混用）。 */
    fun hasActiveRun(): Boolean = gate.activeCount() > 0

    /** 某个 run 是否仍在跑（任务中心据此决定「取消」按钮是否可用/是否已结束）。 */
    fun isRunActive(id: Uuid): Boolean = gate.isActive(id.toString())

    /**
     * 派发一个 run（**即发即忘**，运行页用）。
     *
     * 准入与 [dispatchAndAwait] / [dispatchScheduledAwait] 共用同一个进程级并发闸：上限取设置
     * `subagentMaxConcurrentRuns`（默认 2、范围 1–8；`dynamicRunGate` 开时每次现读）。**达到
     * 上限时返回 null**（调用方提示"已达并行上限"），不排队；`goal` 为空也返回 null。
     *
     * @param profile 复用现有 [Assistant] 当模板（系统提示/模型/工作区绑定来自它）
     * @param goal 任务目标，原样成为首条 user 消息
     * @param allowedTools 能力信封：**允许装配**的工具名集合（派发时快照；
     *   信封外工具不装配——`needsApproval` 无人头时拦不住，见 §9.2 第 2 条）
     * @return 新 run 的 id；目标为空或已达并行上限时为 null
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
        ).copy(deliveryMode = AgentRunEntity.DELIVERY_MODE_NOTIFY)

        val job = appScope.launch {
            try {
                agentRunDAO.insertRun(run)
                executeRun(run, profile, allowedTools, RUN_TIMEOUT_MS, SubagentToolApproval.DENY)
            } finally {
                gate.release(runId.toString())
                progressTracker.unregister(runId)
                progressTracker.clear(runId.toString())
            }
        }
        progressTracker.register(runId, job)
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
     * @return `null` = 并发闸满（或目标为空）；调用方把"忙"如实转达给模型，不要重试
     */
    override suspend fun dispatchAndAwait(
        preset: SubagentPreset,
        goal: String,
        timeoutMillis: Long,
        parentConversationId: String?,
        context: SubagentDispatchContext,
    ): SubagentRunResult? = dispatchAwaitInternal(
        preset = preset,
        goal = goal,
        timeoutMillis = timeoutMillis,
        parentConversationId = parentConversationId,
        context = context,
        entryPoint = AgentRunEntity.ENTRY_POINT_CHAT,
        deliveryMode = AgentRunEntity.DELIVERY_MODE_AWAIT,
        scopeToken = context.scopeToken,
        onStarted = { _, _ -> },
    )

    /**
     * 定时任务 headless 派发（P4b，`design-agent-task-runtime.md` §7 P4，默认关闭）。
     *
     * 与 [dispatchAndAwait] 共用同一套执行/账本/取消语义，差别只在账本归属：
     * - `entry_point = scheduled`，与手动/对话内 run 可区分；
     * - `delivery_mode = notice`：结果由 P1a 投递泵写成目标会话的 SYSTEM notice，
     *   不冒充用户消息，也不触发父生成；
     * - `scope_token = sched:<scheduledRunId>`：任务中心/任务树按定时轮次反查。
     *
     * [onStarted] 在账本行落库后回调（拿得到 agent runId 与 Job），让
     * [ScheduledRunRegistry] 能把「定时 run」的停止动作转成对这个 Job 的取消；
     * 若回调当场取消 Job（先取消后 attach），本方法会把账本行收成 CANCELLED 再返回。
     */
    suspend fun dispatchScheduledAwait(
        preset: SubagentPreset,
        goal: String,
        timeoutMillis: Long,
        parentConversationId: String?,
        scopeToken: String,
        onStarted: (Uuid, Job) -> Unit = { _, _ -> },
    ): SubagentRunResult? = dispatchAwaitInternal(
        preset = preset,
        goal = goal,
        timeoutMillis = timeoutMillis,
        parentConversationId = parentConversationId,
        context = SubagentDispatchContext(scopeToken = scopeToken),
        entryPoint = AgentRunEntity.ENTRY_POINT_SCHEDULED,
        deliveryMode = AgentRunEntity.DELIVERY_MODE_NOTICE,
        scopeToken = scopeToken,
        onStarted = onStarted,
    )

    /**
     * 只读账本状态（P4b 定时执行器用）：run 从**别的入口**被取消时，调用方只拿到
     * `CancellationException`，无法区分「用户取消了这条 run」与「服务/进程级取消」；
     * 读一下终态才知道该把定时账本收成 CANCELLED 还是留给启动对账显示 INTERRUPTED。
     */
    suspend fun runStatus(runId: String): String? = agentRunDAO.getRun(runId)?.status

    /**
     * Shared awaited execution. Ordinary waiter cancellation cancels the run; explicit chat handoff
     * retains the independent worker and promotes its ledger to durable notice delivery.
     */
    private suspend fun dispatchAwaitInternal(
        preset: SubagentPreset,
        goal: String,
        timeoutMillis: Long,
        parentConversationId: String?,
        context: SubagentDispatchContext,
        entryPoint: String,
        deliveryMode: String,
        /** null = 沿用 `run:<runId>`；定时入口传 `sched:<scheduledRunId>`。 */
        scopeToken: String?,
        onStarted: (Uuid, Job) -> Unit,
    ): SubagentRunResult? {
        if (goal.isBlank()) return null

        val profile = preset.toRunProfile()
        val runId = Uuid.random()
        if (!gate.tryAcquire(runId.toString())) return null
        val run = newRunEntity(
            entryPoint = entryPoint,
            profile = profile,
            goal = goal,
            allowedTools = preset.allowedTools,
            runId = runId,
            parentConversationId = parentConversationId,
        ).copy(
            deliveryMode = deliveryMode,
            scopeToken = scopeToken ?: "run:$runId",
        )

        // A run remains independently cancellable. Chat waits can explicitly hand off ownership.
        val inserted = CompletableDeferred<Unit>()
        suspend fun execute(): SubagentRunResult {
            val runJob = Job(coroutineContext[Job])
            progressTracker.register(runId, runJob)
            return try {
                withContext(runJob) {
                    try {
                        agentRunDAO.insertRun(run)
                    } finally {
                        inserted.complete(Unit)
                    }
                    // Register only after the ledger exists. Scheduled cancellation may precede attachment.
                    onStarted(runId, runJob)
                    if (!runJob.isActive) {
                        withContext(NonCancellable) {
                            finishRun(runId, AgentRunEntity.STATUS_CANCELLED, null,
                                "scheduled run cancelled before start")
                        }
                        return@withContext SubagentRunResult(runId.toString(),
                            AgentRunEntity.STATUS_CANCELLED, null, null)
                    }
                    val outcome = executeRun(
                        initial = run,
                        profile = profile,
                        allowedTools = preset.allowedTools,
                        timeoutMillis = timeoutMillis,
                        toolApproval = preset.toolApproval,
                        deliverables = preset.deliverables,
                        parentToolEnvelope = context.parentToolEnvelope,
                        parentWorkspaceId = context.parentWorkspaceId,
                    )
                    val stored = agentRunDAO.getRun(run.id)
                    SubagentRunResult(
                        runId = run.id,
                        status = stored?.status ?: AgentRunEntity.STATUS_FAILED,
                        summary = (outcome as? RunOutcome.Completed)?.summary,
                        error = stored?.error,
                    )
                }
            } finally {
                inserted.complete(Unit)
                // A manually created Job must complete or its parent remains Completing indefinitely.
                if (runJob.isActive) runJob.complete()
                gate.release(runId.toString())
                progressTracker.unregister(runId)
                progressTracker.clear(runId.toString())
            }
        }
        // Scheduled runs retain their existing parent/registry cancellation contract.
        return if (entryPoint == AgentRunEntity.ENTRY_POINT_CHAT && scopeToken != null) {
            taskHandoffs.await(scopeToken, run.id, appScope, onRetained = {
                inserted.await()
                agentRunDAO.retainAwaitedRun(run.id)
            }) { execute() }
        } else {
            execute()
        }
    }

    /** 独立于父生成的 Job；先持久化后接受，后台 ASK 必须有可达的通知路径。 */
    override suspend fun dispatchBackground(
        preset: SubagentPreset,
        goal: String,
        timeoutMillis: Long,
        parentConversationId: String,
        context: SubagentDispatchContext,
    ): SubagentRunResult? {
        val resumable = settingsStore.settingsFlow.value.workspaceTaskNoticeEnabled && context.noticeTarget?.allowWake == true
        if ((!settingsStore.settingsFlow.value.enableBackgroundSubagent && !resumable) || goal.isBlank()) return null
        val parentId = runCatching { Uuid.parse(parentConversationId) }.getOrNull() ?: return null
        val runId = Uuid.random()
        if (!gate.tryAcquire(runId.toString())) return null
        val profile = preset.toRunProfile()
        val approval = backgroundApprovalDecision(preset.toolApproval, runNotifications.approvalRouteAvailable())
        val initialMessages = buildList {
            approval.note?.let { add(UIMessage.system(it)) }
            add(UIMessage.user(goal))
        }
        val run = newRunEntity(
            AgentRunEntity.ENTRY_POINT_CHAT, profile, goal, preset.allowedTools, runId, parentId.toString(),
        ).copy(deliveryMode = if (resumable) AgentRunEntity.DELIVERY_MODE_RESUME else AgentRunEntity.DELIVERY_MODE_NOTICE,
            scopeToken = context.scopeToken ?: "run:$runId", transcript = newTranscript(initialMessages),
            noticeTarget = if (resumable) JsonInstant.encodeToString(context.noticeTarget) else null)
        try {
            // 调用方取消不会留下已占位但未登记/未启动的任务。
            withContext(NonCancellable) {
                agentRunDAO.insertRun(run)
                val job = appScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    executeRun(run, profile, preset.allowedTools, timeoutMillis, approval.policy,
                        preset.deliverables, context.parentToolEnvelope, context.parentWorkspaceId)
                }
                progressTracker.register(runId, job)
                job.invokeOnCompletion { cause ->
                    if (cause != null) appScope.launch {
                        withContext(NonCancellable) {
                            finishRun(runId, if (cause is CancellationException) AgentRunEntity.STATUS_CANCELLED
                                else AgentRunEntity.STATUS_FAILED, null,
                                if (cause is CancellationException) null else "exception: background run stopped")
                        }
                    }
                    gate.release(run.id)
                    progressTracker.unregister(runId)
                    progressTracker.clear(run.id)
                }
                job.start()
            }
        } catch (error: Throwable) {
            gate.release(run.id)
            throw error
        }
        return SubagentRunResult(run.id, AgentRunEntity.STATUS_PENDING, null, null)
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
            scopeToken = "run:$runId",
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
        progressTracker.cancelJob(id)
        // 后台 shell 任务不在这里收：`killBackgroundTasksByOwner` 是 suspend，而本方法是 UI/通知
        // 直接调用的非挂起入口。取消 → CancellationException 路径会在 NonCancellable 里按
        // `run:<id>` 级联收掉（见 executeRun 的取消分支），超时路径同款处理。
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
        /** 交付物契约（预设给；运行页手动派发恒为 null = 不设闸），见 design-deliverable-gate.md §二。 */
        deliverables: List<String>? = null,
        parentToolEnvelope: Set<String>? = null,
        parentWorkspaceId: Uuid? = null,
    ): RunOutcome? {
        val runId = Uuid.parse(initial.id)

        suspend fun update(transform: (AgentRunEntity) -> AgentRunEntity) {
            val current = agentRunDAO.getRun(initial.id) ?: return
            val updated = transform(current)
            agentRunDAO.updateProgress(updated.id, updated.status, updated.startedAt,
                updated.transcript, updated.toolEnvelope)
        }

        update { it.copy(status = AgentRunEntity.STATUS_RUNNING, startedAt = System.currentTimeMillis()) }
        progressTracker.upsert(
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
            // 交付物闸门（purrcat A3）：声明了 deliverables 才挂，null/空 = 行为不变
            val loopGate = buildSubagentLoopGate(profile.workspaceId, deliverables)

            // 分段装配（不是扁平列表）：信封的 `null` 语义（"按能力开关"）要知道工具的**来源**，
            // 否则 MCP / 技能安装这些无条件装配的来源会顺着 `null` 静默流进来。
            // explicitlyAllowedToolNames：信封显式点名的工具不得被 MCP schema 折叠静默摘掉
            // （折叠只服务 token 预算，不能改信封语义——见 createToolSegments 参数注释）
            val segments = chatToolFactory.createToolSegments(
                settings,
                runProfile,
                model,
                workspaceCwd = null,
                explicitlyAllowedToolNames = allowedTools.orEmpty(),
                // run 内起的后台 shell 任务归属到本 run（设计 §5.2 第 1 条）：以前这里不传，
                // owner 落成 null ⇒ run 被取消后它的后台命令变孤儿——没人轮询输出、没人收进程，
                // 却继续吃 CPU/网络直到 30 分钟看门狗。取消 scope 时据此级联 killByScope。
                turnOwner = ExecutionScope.run(runId.toString()),
                // 子代理无头执行：不装配 Phone Use（design-phone-use.md §2.3）
                allowPhoneUse = false,
            )
            val envelope = resolveSubagentEnvelope(segments, allowedTools, parentToolEnvelope,
                profile.workspaceId, parentWorkspaceId)
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

            var currentMessages = runCatching {
                JsonInstant.decodeFromString<List<UIMessage>>(initial.transcript)
            }.getOrDefault(listOf(UIMessage.user(initial.goal)))

            // 护栏实例**建在 run 上、跨"审批续跑"累积**：ASK 策略下每次批准都是一次新的
            // generateText 调用（与聊天路径的审批续跑同形），建在调用内会每次重置——
            // LoopGuard 真机踩过这个坑（见 ToolCallLoopGuard 类注释），这里预先避开。
            val loopGuard = ToolCallLoopGuard()
            val workspaceBudget = WorkspaceTurnBudget()
            val denialGuard = DenialGuard()
            // 思考退化护栏（家族第四员）：与上面三个同款，**建在 run 上**跨审批续跑累积
            val thinkingGuard = ThinkingDegenerationGuard()
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
                            // 子代理 run 的花费单独归到 SUBAGENT：和用户直接聊天区分开，
                            // 「跑一阵子代理花掉多少」才看得见（父会话 id 有就一并带上）
                            usageTag = UsageTag(
                                UsageSource.SUBAGENT,
                                assistantId = runProfile.id,
                                conversationId = initial.parentConversationId
                                    ?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                            ),
                            processingStatus = MutableStateFlow(null),
                            messages = currentMessages,
                            compressionWindow = null,
                            inputTransformers = emptyList(),
                            outputTransformers = defaultOutputTransformers,
                            assistant = runProfile,
                            memories = emptyList(),
                            tools = tools,
                            loopGuard = loopGuard,
                            workspaceBudget = workspaceBudget,
                            thinkingGuard = thinkingGuard,
                            autoDenyApproval = autoDenyApproval,
                            denialGuard = denialGuard,
                            contextGuard = contextGuard,
                            conversationSystemPrompt = null,
                            conversationModeInjectionIds = emptySet(),
                            conversationLorebookIds = emptySet(),
                            workspaceCwd = null,
                            loopGate = loopGate,
                        ).collect { chunk ->
                            when (chunk) {
                                is GenerationChunk.Messages -> {
                                    currentMessages = chunk.messages
                                    // 每个 step 落一次轨迹：详情页近实时可见，崩溃也不丢全程
                                    update { it.copy(transcript = newTranscript(currentMessages)) }
                                    // 同一步顺带更新进度（对话内工具卡据此显示"跑到第几步"）
                                    val snapshot = snapshotProgress(currentMessages)
                                    progressTracker.upsert(
                                        RunProgress(
                                            runId = initial.id,
                                            label = initial.profileName,
                                            goal = initial.goal,
                                            status = AgentRunEntity.STATUS_RUNNING,
                                            steps = snapshot.steps,
                                            lastTool = snapshot.lastTool,
                                            startedAt = progressTracker.startedAt(initial.id),
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
                            // 强制放行：达打回上限仍缺交付物——宁可带瑕疵交付，不可无限烧钱
                            // （设计稿 §二.3.4）。注记同时给父模型与账本。
                            val completed = outcome as? RunOutcome.Completed
                            if (completed != null && loopGate?.forcePassed == true) {
                                outcome = RunOutcome.Completed(
                                    completed.summary + "\n\n" + forcePassNote(loopGate!!.missingAtForcePass),
                                )
                            }
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
                        progressTracker.update(initial.id) { entry ->
                            entry.copy(status = AgentRunEntity.STATUS_AWAITING_APPROVAL)
                        }
                        val decisions = awaitHumanDecision(
                            runId = initial.id,
                            label = initial.profileName,
                            pending = pending,
                            parentConversationId = initial.parentConversationId,
                            background = agentRunDAO.getRun(initial.id)?.deliveryMode in setOf(
                                AgentRunEntity.DELIVERY_MODE_NOTICE, AgentRunEntity.DELIVERY_MODE_HANDOFF,
                                AgentRunEntity.DELIVERY_MODE_RESUME),
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
                    // 截止时间到 = 超时终止（不是 provider 异常）：用独立前缀，
                    // 让 TaskStateMappings 能还原成 TIMED_OUT。
                    failRun(
                        runId,
                        AgentRunEntity.ERROR_TIMEOUT,
                        "run timeout (" + timeoutMillis + "ms)",
                    )
                    // run 已经结束，它起的后台 shell 任务不应再无限跑下去（与取消同款级联）。
                    killShellTasksOfRun(runId)
                }
                return null
            } catch (e: CancellationException) {
                // 用户取消（cancelRun / 中断对话）：withContext(NonCancellable) 保证取消路径能落库
                withContext(NonCancellable) {
                    finishRun(runId, AgentRunEntity.STATUS_CANCELLED, resultSummary = null, error = null)
                    // 只撤**本 run** 的审批通知：固定 id 时代这里会把并行 run 的待批通知一起撤掉
                    approvalNotifications.cancelApprovalNotification(runId.toString())
                    // run 被取消后没人再轮询这些后台命令的结果，继续跑只是烧 CPU/网络；
                    // 与 cancelRun 的直杀互为兜底（覆盖超时/协程取消等旁路）。
                    killShellTasksOfRun(runId)
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
     * 装配子代理的收尾闸门（purrcat A3，`docs/design/design-deliverable-gate.md` §二）。
     *
     * - `deliverables` null/空（含规范化后剔光）→ null = 不挂闸，行为与过去逐位一致；
     * - 声明了但预设没绑 workspace → 降级为**自检**（`deliverables` 置空只留自检提示，
     *   §二.3.5——无从查文件，不硬失败）；
     * - 正常路径 → 确定性校验：`fileSize` 对缺失/目录抛错（`WorkspaceManager.fileSize`
     *   的 `require(file.isFile)`），`runCatching` 归一成「缺失」；size ≤ 0 视为空交付。
     *   校验中途工作区不可用（如被删）按 fail-open 放行——闸门是验收器不是刹车，
     *   把「环境坏了」打成「交付缺失」只会烧光重试预算后强制放行，还污染注记。
     */
    private fun buildSubagentLoopGate(
        workspaceId: Uuid?,
        deliverables: List<String>?,
    ): LoopGateConfig? {
        val declared = deliverables?.let(DeliverableChecker::normalizeAll).orEmpty()
        if (declared.isEmpty()) return null
        if (workspaceId == null) return LoopGateConfig(deliverables = emptyList())
        val wsId = workspaceId.toString()
        return LoopGateConfig(
            deliverables = declared,
            checkDeliverables = { paths ->
                paths.filter { path ->
                    val size = runCatching {
                        workspaceRepository.fileSize(wsId, WorkspaceStorageArea.FILES, path)
                    }.onFailure { e ->
                        android.util.Log.w(TAG, "deliverable check failed: $path (${e.message})")
                    }.getOrNull()
                    size == null || size <= 0L
                }
            },
        )
    }

    /**
     * 等人批准（设计 §十一）：登记 → 弹通知 → 等（有时间上限）→ 回填决定。
     *
     * **超时按拒绝处理而**不是**让 run 失败**：run 的预算（`timeoutSeconds`）是给"跑"用的，
     * 人不在却把整个 run 判死，等于把一次没看见的通知升级成任务失败——
     * 而拒绝后模型还能继续用别的办法把任务做完（这正是 §9.2 的 denied 语义）。
     * 超时的措辞与用户手拒**分开**（见 `APPROVAL_TIMEOUT_REASON`）。
     *
     * 后台通知不可达立即拒绝；对话内阻塞派发仍允许在运行详情页答复。
     */
    private suspend fun awaitHumanDecision(
        runId: String,
        label: String,
        pending: List<UIMessagePart.Tool>,
        parentConversationId: String?,
        background: Boolean,
    ): Map<String, ToolApprovalState> {
        return awaitRunApproval(runId, label, pending, parentConversationId, background, APPROVAL_TIMEOUT_MS,
            routeAvailable = { runNotifications.approvalRouteAvailable() },
            postNotification = { approvalNotifications.postApprovalNotification(runId, label, pending) },
            cancelNotification = { approvalNotifications.cancelApprovalNotification(runId) })
    }

    /**
     * 级联收掉本 run 起的 shell task（P0 行为，`killShellTasksOnRunCancel` 可回滚）。
     *
     * 失败只记日志：run 的终态已经落库，清理失败不能反过来把主流程标成失败。
     */
    private suspend fun killShellTasksOfRun(runId: Uuid) {
        if (!settingsStore.settingsFlow.value.killShellTasksOnRunCancel) return
        runCatching {
            workspaceRepository.killBackgroundTasksByScope(ExecutionScope.run(runId.toString()))
        }.onFailure { Logging.log(TAG, "killShellTasksOfRun($runId) failed: $it") }
    }

    /** 待批通知 id：默认按 runId 命名空间（并行不覆盖）；回滚开关关闭时回旧固定 4242。 */
    private suspend fun finishRun(runId: Uuid, status: String, resultSummary: String?, error: String?) {
        val changed = agentRunDAO.finishRun(
            id = runId.toString(),
            status = status,
            resultSummary = resultSummary,
            error = error,
            finishedAt = System.currentTimeMillis(),
            cancelReason = if (status == AgentRunEntity.STATUS_CANCELLED) "Run cancelled." else null,
        )
        if (changed == 1) {
            agentRunDAO.getRun(runId.toString())?.let { run ->
                runCatching { runNotifications.completed(run) }
                    .onFailure { Logging.log(TAG, "run completion notification failed: $it") }
            }
        }
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

        // 审批通知的 id / PendingIntent requestCode 不再放常量：固定值会让并行 run
        // 互相覆盖、互相误撤。现由 NotificationIds 按 runId + 动作生成（设计 §4.4）。

        private fun newTranscript(messages: List<UIMessage>): String =
            JsonInstant.encodeToString(ListSerializer(serializer<UIMessage>()), messages)
    }
}
