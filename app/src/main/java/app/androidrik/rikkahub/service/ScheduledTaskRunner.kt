package app.androidrik.rikkahub.service

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.conversationIntent
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.awaitLoaded
import app.androidrik.rikkahub.data.datastore.getAssistantById
import app.androidrik.rikkahub.data.ai.DeliverableChecker
import app.androidrik.rikkahub.data.ai.LoopGateConfig
import app.androidrik.rikkahub.data.ai.forcePassNote
import app.androidrik.rikkahub.data.ai.tools.SubagentRunResult
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity
import app.androidrik.rikkahub.data.db.entity.TargetMode
import app.androidrik.rikkahub.data.db.entity.TaskRunStatus
import app.androidrik.rikkahub.data.db.entity.targetModeOrDefault
import app.androidrik.rikkahub.data.model.SubagentPreset
import app.androidrik.rikkahub.data.model.SubagentToolApproval
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.rikkahub.utils.sendNotification
import app.androidrik.workspace.WorkspaceStorageArea
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 定时任务的执行层（docs/design/design-scheduled-ai-tasks.md §六）。
 *
 * 默认只做「注入 + 等结束 + 报结果」，生成本体走 [ChatService] 老管线——transformer、工具、
 * MCP、流式落库全部复用，这里没有任何一条旁路生成通道。P4b 的可选 headless 路径（默认关）
 * 改为派发独立 agent run（[SubagentRunner]），仍复用同一账本/取消/通知控制面。
 *
 * 后台入口的两个**冷启动**前提（2026-10-30 真机事故的根因，缺一不可）：
 * 1. [SettingsStore.awaitLoaded]：`settingsFlow` 起手是 `Settings.dummy()`，进程刚被
 *    WorkManager 拉起时直接注入会拿到空 providers / 随机 chatModelId ⇒ 生成侧解析不到模型；
 * 2. [ChatService.ensureConversationLoaded]：不先载入 DB 会话就发消息，会在一个空
 *    `Conversation.ofId` 上跑一轮，随后 `saveConversation` 把目标会话整行覆盖。
 */
class ScheduledTaskRunner(
    private val context: Context,
    private val chatService: ChatService,
    private val conversationRepository: ConversationRepository,
    private val taskRepository: ScheduledTaskRepository,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
    /** P0.5 定时取消：FGS 通知的「停止」动作只有 runId，靠它反查并取消本轮 generation Job */
    private val runRegistry: ScheduledRunRegistry,
    /** P4b 可选 headless 执行：定时任务走独立 agent_run（默认关，见 Settings.scheduledHeadlessEnabled）。 */
    private val subagentRunner: SubagentRunner,
) {
    /**
     * 执行一个**已认领**的任务，返回落 `last_status` 的状态值。
     *
     * 完成判定：`job == null && 队列空`。不能等 `generationDoneFlow`——它的 emit 全部在生成
     * job 体**内**发出（`ChatService.kt:956` 等五处），等信号时 job 还没结束，「emit + job 空闲」
     * 永远凑不齐。而「发送后 job 立即非空或队列非空」（[ChatService.sendMessage] 同步入队 +
     * 派发），所以首个观测必然是活跃态，不会把「还没开始」误判成「已结束」。
     *
     * 取消（P0.5，D4）：本轮在注入前登记到 [ScheduledRunRegistry]，FGS 通知的「停止」按
     * runId 反查 generation Job 并 `Job.cancel()`——**不走 [ChatService.stopGeneration]**，
     * 后者会暂停目标会话里用户自己的消息队列。被取消的轮记 [TaskRunStatus.CANCELLED]，
     * 绝不回落 OK/FAILED（取消不是故障）。
     *
     * 已知边界：任务消息排在用户当前轮次之后（FIFO），若前面排着长队，[GENERATION_TIMEOUT]
     * 从注入时刻起算，可能不够等到自己的轮次——此时记 [TaskRunStatus.TIMEOUT] 但**不取消**
     * 生成（消息还在队列里正常走完，只是不再等/不再报状态）。
     */
    suspend fun runClaimed(task: ScheduledTaskEntity, source: String): String {
        // 运行账本（P2）：本轮开账。conversationId 先按任务锚点填（NEW_EACH_RUN 建出会话后补记）；
        // 之后的每条 return 路径都必须 finishRun（终态必达），否则 finished_at 留空 = 进程死亡的直接证据。
        val mode0 = targetModeOrDefault(task.targetMode)
        val runId = taskRepository.startRun(
            taskId = task.id,
            source = source,
            conversationId = if (mode0 == TargetMode.NEW_EACH_RUN) null else task.conversationId.takeIf { it.isNotBlank() },
        )

        // 冷启动前提 1：等真实设置就位（超时兜底——读不出来也不能把 worker 挂死；
        // 此时下游会以「未配置模型」的可见错误收尾，状态记 FAILED）。
        // 放在目标解析之前：NEW_EACH_RUN 建会话就要用 settings 里的助手（含预设消息）。
        val settingsLoaded = withTimeoutOrNull(SETTINGS_AWAIT_TIMEOUT) {
            settingsStore.awaitLoaded()
        } != null

        val mode = targetModeOrDefault(task.targetMode)
        val conversationId = when (mode) {
            // 每轮新建：没有存量锚点，本轮现建一条（按选中助手）
            TargetMode.NEW_EACH_RUN -> {
                val assistantId = task.assistantId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                val created = assistantId?.let {
                    chatService.createTaskConversation(
                        assistantId = it,
                        title = scheduledRunConversationTitle(task.title, System.currentTimeMillis()),
                    )
                }
                if (created == null) {
                    taskRepository.finishRun(runId, TaskRunStatus.ASSISTANT_GONE)
                    taskRepository.markAssistantGone(task.id)
                    return TaskRunStatus.ASSISTANT_GONE
                }
                taskRepository.recordLastConversation(task.id, created.toString())
                taskRepository.updateRunConversation(runId, created.toString())
                created
            }
            // 已有会话 / 专属会话：都是存量锚点，会话被删即失效
            TargetMode.EXISTING, TargetMode.DEDICATED -> {
                val parsed = runCatching { Uuid.parse(task.conversationId) }.getOrNull()
                if (parsed == null || !conversationRepository.existsConversationById(parsed)) {
                    taskRepository.finishRun(runId, TaskRunStatus.TARGET_GONE)
                    taskRepository.markTargetGone(task.id)
                    return TaskRunStatus.TARGET_GONE
                }
                parsed
            }
        }

        // 冷启动前提 2：后台没有 session，必须先按 DB 载入会话（历史/助手/工作区），
        // 否则注入建立在一个空 Conversation 上，落库会覆盖整行会话。
        if (!chatService.ensureConversationLoaded(conversationId)) {
            taskRepository.finishRun(runId, TaskRunStatus.TARGET_GONE)
            taskRepository.markTargetGone(task.id)
            return TaskRunStatus.TARGET_GONE
        }

        // P0.5 定时取消（design-agent-task-runtime §5.2 第 4 条）：**注入之前**登记本轮。
        // 取消入口（FGS 通知的「停止」）只有 runId，必须先能反查到这一轮；登记早于注入
        // ⇒ 取消窗口覆盖整个执行期。条目在 finally 移除，避免泄漏成「永远有运行中任务」。
        runRegistry.register(runId, conversationId, task.id)
        return try {
            val settings = settingsStore.settingsFlow.value
            // P4b：headless 是可选路径，且依赖后台子代理的 notice 投递通道；任一条件不满足
            // 都退回历史行为（会话注入），而不是把任务记成成功/失败——回退必须是静默且完整的。
            val headless = shouldUseHeadlessExecution(settingsLoaded, settings)
            if (settings.scheduledHeadlessEnabled && !headless) {
                Log.w(
                    TAG,
                    "runClaimed: headless requested but unavailable " +
                        "(settingsLoaded=$settingsLoaded, background=${settings.enableBackgroundSubagent}); " +
                        "fallback to conversation injection",
                )
            }
            if (headless) {
                runHeadless(task, mode, conversationId, runId)
            } else {
                runInjected(task, mode, conversationId, runId, settingsLoaded)
            }
        } finally {
            runRegistry.unregister(runId)
        }
    }

    /**
     * P4b：定时任务 headless 执行（默认关）。
     *
     * 与 [runInjected] 的区别只在「生成本体从哪里跑」：
     * - 不往目标会话塞用户消息，也不等目标会话的 generation Job；
     * - 派发独立 `agent_run`（`entry_point=scheduled`、`delivery_mode=notice`），结果由 P1a
     *   投递泵写成目标会话的 SYSTEM notice，不触发父生成；
     * - 取消仍走同一 [ScheduledRunRegistry]：attach 到 agent run 的 Job，`Job.cancel()` 会走
     *   `executeRun` 的取消路径收掉它起的 shell task。
     *
     * 目标语义不变：先在 [runClaimed] 里完成 EXISTING/DEDICATED/NEW_EACH_RUN 的会话解析与
     * 载入，因此 headless 仍能校验「目标会话/助手是否还在」；只是结果不再以 assistant 消息出现。
     */
    private suspend fun runHeadless(
        task: ScheduledTaskEntity,
        mode: TargetMode,
        conversationId: Uuid,
        runId: String,
    ): String {
        if (runRegistry.isCancelled(runId)) return finishCancelled(task, runId)

        val settings = settingsStore.settingsFlow.value
        val conversation = chatService.getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(conversation.assistantId)
        if (assistant == null) {
            Log.i(TAG, "runHeadless: assistant gone for task=${task.title}")
            taskRepository.recordStatus(task.id, TaskRunStatus.ASSISTANT_GONE)
            taskRepository.finishRun(runId, TaskRunStatus.ASSISTANT_GONE)
            taskRepository.markAssistantGone(task.id)
            return TaskRunStatus.ASSISTANT_GONE
        }

        val goal = buildTaskPrompt(task.prompt, task.withGoal, conversation.goal)
        val deliverables = task.deliverables
            ?.let { raw ->
                runCatching { JsonInstant.decodeFromString<List<String>>(raw) }
                    .onFailure { Log.w(TAG, "deliverables parse failed for task=${task.id}", it) }
                    .getOrDefault(emptyList())
            }
            ?.takeIf { it.isNotEmpty() }
        // 用现有 Assistant 当模板：模型/系统提示/联网/工作区能力沿用目标会话的助手；
        // 不装配 task_manage/记忆/正则等助手专属段（SubagentPreset.toRunProfile 只保留
        // run 需要的字段），headless 定时任务没有「管理任务」的入口。
        val preset = SubagentPreset(
            name = assistant.name,
            modelId = assistant.chatModelId,
            systemPrompt = assistant.systemPrompt,
            enableWebSearch = assistant.enableWebSearch,
            workspaceId = assistant.workspaceId,
            toolApproval = SubagentToolApproval.DENY,
            deliverables = deliverables,
        )

        // 先取消后 attach：registry.cancel 可能发生在 dispatch 的账本行落库之前，回调里
        // attachJob 见到取消标记会当场 cancel Job；dispatchScheduledAwait 会把账本收成 CANCELLED。
        var agentRunId: String? = null
        val result = try {
            subagentRunner.dispatchScheduledAwait(
                preset = preset,
                goal = goal,
                timeoutMillis = GENERATION_TIMEOUT.inWholeMilliseconds,
                parentConversationId = conversationId.toString(),
                scopeToken = scheduledRunTurnOwner(runId),
                onStarted = { id, job ->
                    agentRunId = id.toString()
                    runRegistry.attachJob(runId, job)
                },
            )
        } catch (e: CancellationException) {
            if (runRegistry.isCancelled(runId)) return finishCancelled(task, runId)
            // 从 run 详情/任务中心取消了**这条 agent run**（不是定时注册表）：账本已有
            // CANCELLED 终态，定时账本也必须同轮收尾；服务/进程级取消没有终态写入，
            // 继续抛出让 finished_at 留空，由任务中心按 INTERRUPTED 展示。
            val externallyCancelled = agentRunId?.let { id ->
                withContext(NonCancellable) {
                    subagentRunner.runStatus(id) == AgentRunEntity.STATUS_CANCELLED
                }
            } == true
            if (externallyCancelled) return finishCancelled(task, runId)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "runHeadless failed for task=${task.title}", e)
            null
        }

        // 取消可能在 result 返回之后、记账之前到达：以取消标记为准，绝不回落 OK。
        if (runRegistry.isCancelled(runId)) return finishCancelled(task, runId)

        val status = scheduledStatusForAgentRun(result)
        Log.i(
            TAG,
            "runHeadless: task=${task.title} -> $status " +
                "(runId=${result?.runId}, error=${result?.error})",
        )
        taskRepository.recordStatus(task.id, status)
        taskRepository.finishRun(runId, status)
        // 有 run 结果时完成通知由 RunNotifications 按 agent run 发（带摘要）；
        // 只有「闸门忙/异常，根本没跑起来」才退回定时任务的通用失败通知，避免两条通知。
        if (result == null) {
            notifyResult(task, conversationId, status, loopGate = null)
        }

        // 自动清理与注入路径同规则：仅 NEW_EACH_RUN 且成功 + 保留数 > 0。
        if (status == TaskRunStatus.OK && mode == TargetMode.NEW_EACH_RUN &&
            task.keepLastConversations > 0
        ) {
            cleanupOldRunConversations(task)
        }
        return status
    }

    /**
     * [runClaimed] 登记之后的执行主体：注入 → 等结束 → 记终态。
     *
     * 取消有两个落点：
     * 1. 等待循环把 [ScheduledRunRegistry.cancelledRunIds] 并进 `combine`——用户点了停止就不必
     *    等生成自然跑完（那还在烧钱），也不必等满 [GENERATION_TIMEOUT]；
     * 2. 收场时再查一次 `isCancelled` 兜底（取消可能与「自然结束」同时到达）——
     *    **取消一旦置位绝不回落 OK**。
     */
    private suspend fun runInjected(
        task: ScheduledTaskEntity,
        mode: TargetMode,
        conversationId: Uuid,
        runId: String,
        settingsLoaded: Boolean,
    ): String {
        // 取消在「登记」与「注入」之间到达（通知动作先于执行层就位）：不发消息，直接记终态。
        if (runRegistry.isCancelled(runId)) {
            return finishCancelled(task, runId)
        }

        val assistantCountBefore = assistantMessageCount(conversationId)
        val errorsBefore = chatService.errors.value.count { it.conversationId == conversationId }
        Log.i(
            TAG,
            "runClaimed: inject task=${task.title} conversation=$conversationId " +
                "(settingsLoaded=$settingsLoaded, assistantsSeen=$assistantCountBefore)",
        )
        // 目标语义（purrcat A4.2）：withGoal 且会话 goal 非空 → 头部拼「长期目标」，
        // 多次自唤醒围绕同一目标；goal 为空按普通 prompt 走，不报错（§六.2）。
        // 会话已由 ensureConversationLoaded 载入，直接读内存态的 goal。
        val conversationGoal = chatService.getConversationFlow(conversationId).value.goal
        // 收尾闸门（purrcat A1，design-deliverable-gate.md §三.2）：任务声明了交付物
        // （deliverables 非空）才挂闸；null = 行为与过去逐位一致。
        val loopGate = buildTaskLoopGate(task, conversationId)
        // 归属令牌显式指定（P0.5）：取消入口只知道 runId，这一轮的令牌必须可被它反查；
        // 同时它决定本轮起的后台 shell 任务的 owner（轮取消时由 onTurnCancelled 一并收掉）。
        val turnOwner = scheduledRunTurnOwner(runId)
        chatService.sendMessage(
            conversationId,
            listOf(UIMessagePart.Text(buildTaskPrompt(task.prompt, task.withGoal, conversationGoal))),
            loopGate = loopGate,
            turnOwner = turnOwner,
        )

        // 把本轮 generation Job 登记进 registry：取消入口只有 runId，反查到 Job 才停得下来。
        //
        // **归属判据**：会话空闲时 `sendMessage` 同步派发，`beginNewTurn(sched:…)` 已把当前轮
        // 令牌写成我们的，Job 就是本 run 的；消息排在用户当前轮次之后（FIFO）时，此刻 session
        // 的 Job 是**用户那一轮**的——登记它会让「停止定时任务」把用户的生成一并停掉，绝不能登记。
        // 排队场景不丢取消：取消标记先到，等待循环靠取消信号提前收场，届时再按归属反查（见下）。
        if (chatService.getTurnOwner(conversationId) == turnOwner) {
            withTimeoutOrNull(JOB_ATTACH_TIMEOUT) {
                chatService.getGenerationJobStateFlow(conversationId).first { it != null }
            }?.let { runRegistry.attachJob(runId, it) }
        }

        // 等待结束期间打心跳：若进程被 ROM 的 cached-app freezer 冻住，日志会断一条明显的
        // 空档、回前台后继续——这是「后台不回复、切前台才续上」的判据（2026-10-30 真机）。
        val waitStartedAt = System.currentTimeMillis()
        val reachedEnd = coroutineScope {
            val heartbeat = launch {
                while (true) {
                    delay(HEARTBEAT_INTERVAL)
                    Log.d(TAG, "runClaimed: waiting ${System.currentTimeMillis() - waitStartedAt}ms")
                }
            }
            val done = withTimeoutOrNull(GENERATION_TIMEOUT.inWholeMilliseconds) {
                combine(
                    chatService.getGenerationJobStateFlow(conversationId),
                    chatService.getMessageQueueFlow(conversationId),
                    runRegistry.cancelledRunIds,
                ) { job, queue, cancelledRunIds ->
                    // 取消 = 立刻收场：用户点了停止，不该等这一轮自然跑完，也不该等满超时。
                    runId in cancelledRunIds || (job == null && queue.messages.isEmpty())
                }.first { it }
            } != null
            heartbeat.cancel()
            done
        }

        // 收场兜底：等待循环可能因「取消」与「自然结束」同时满足而退出，这里以取消标记为准。
        if (runRegistry.isCancelled(runId)) {
            // 消息可能还排在用户轮次之后（那时没有 Job 可登记）：撤下这条待发消息，否则用户
            // 点了停止，它仍会在用户轮次结束后被派发、照样烧一整轮。
            chatService.removeQueuedMessagesOfTurn(conversationId, turnOwner)
            // 已派发且确实是本 run 的轮：补一次 Job 反查并取消（[ScheduledRunRegistry.attachJob]
            // 见到取消标记会当场 cancel，所以先取消后 attach 也生效）。归属不符 = 当前是用户
            // 自己的轮次，绝不能碰（见上面登记处的归属说明）。
            if (chatService.getTurnOwner(conversationId) == turnOwner) {
                chatService.getGenerationJobStateFlow(conversationId).first()?.let { job ->
                    runRegistry.attachJob(runId, job)
                }
            }
            return finishCancelled(task, runId)
        }

        val quiesced = reachedEnd
        val hasNewError = chatService.errors.value.count { it.conversationId == conversationId } > errorsBefore
        val producedReply = assistantMessageCount(conversationId) > assistantCountBefore
        val status = evaluateRunStatus(
            quiesced = quiesced,
            hasNewError = hasNewError,
            producedReply = producedReply,
        )
        Log.i(
            TAG,
            "runClaimed: task=${task.title} -> $status (quiesced=$quiesced, hasNewError=$hasNewError, " +
                "producedReply=$producedReply)",
        )
        taskRepository.recordStatus(task.id, status)
        taskRepository.finishRun(runId, status, gateRetries = loopGate?.retries ?: 0)
        notifyResult(task, conversationId, status, loopGate)

        // 自动清理（§4.2）：仅 NEW_EACH_RUN 且开关开启，仅成功轮次后执行——失败轮的会话
        // 带错误卡，用户可能要排查，不参与清理。清理失败只记日志，不影响本轮结果。
        if (status == TaskRunStatus.OK && mode == TargetMode.NEW_EACH_RUN &&
            task.keepLastConversations > 0
        ) {
            cleanupOldRunConversations(task)
        }
        return status
    }

    /**
     * 用户经 FGS 通知「停止」主动取消的终态记账。
     *
     * [TaskRunStatus.CANCELLED] 与 FAILED/TIMEOUT 分开：这是**用户意志**而非故障，不参与失败率
     * 口径；也**不发结果通知**——操作者就在场，再推一条「失败」只会误导。
     */
    private suspend fun finishCancelled(task: ScheduledTaskEntity, runId: String): String {
        Log.i(TAG, "runClaimed: task=${task.title} -> ${TaskRunStatus.CANCELLED} (stopped by user)")
        taskRepository.recordStatus(task.id, TaskRunStatus.CANCELLED)
        taskRepository.finishRun(runId, TaskRunStatus.CANCELLED)
        return TaskRunStatus.CANCELLED
    }

    /**
     * 按运行账本清理历史轮会话：保留最近 [ScheduledTaskEntity.keepLastConversations] 轮，
     * 更旧的删除（CASCADE 连带消息节点与附件文件）。「本轮结束后用户又发过消息」的会话
     * 跳过——那是用户接手的对话，不是任务产物（lastMessageAt > 本轮 finishedAt）。
     */
    private suspend fun cleanupOldRunConversations(task: ScheduledTaskEntity) {
        runCatching {
            val runs = taskRepository.allRunsOfTask(task.id)
            val withConversation = runs.filter { !it.conversationId.isNullOrBlank() }
            val candidates = withConversation.drop(task.keepLastConversations)
            if (candidates.isEmpty()) return
            val lastMessageAt = candidates.associate { candidate ->
                val id = runCatching { Uuid.parse(candidate.conversationId!!) }.getOrNull()
                val lastMsg = id?.let { conversationRepository.getConversationById(it)?.lastMessageAt }
                candidate.conversationId!! to lastMsg?.toEpochMilli()
            }
            val targets = pickAutoCleanupTargets(withConversation, task.keepLastConversations, lastMessageAt)
            targets.forEach { id ->
                val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return@forEach
                conversationRepository.getConversationById(uuid)?.let {
                    Log.i(TAG, "cleanup: delete old run conversation=$id (task=${task.title})")
                    conversationRepository.deleteConversation(it)
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "cleanup failed for task=${task.title}", e)
        }
    }

    private fun assistantMessageCount(conversationId: Uuid): Int =
        chatService.getConversationFlow(conversationId).value.currentMessages
            .count { it.role == MessageRole.ASSISTANT }

    /**
     * 装配任务的收尾闸门（purrcat A1，`docs/design/design-deliverable-gate.md` §三.2）。
     *
     * - `deliverables` 列 NULL/空（含解析后剔光）→ null = 不挂闸，行为与过去逐位一致；
     * - 会话助手没绑 workspace → 降级为**自检**（`deliverables` 置空只留自检提示，
     *   与子代理侧同款，§二.3.5——无从查文件，不硬失败）；
     * - 正常路径 → 确定性校验：`fileSize` 对缺失/目录抛错（`WorkspaceManager.fileSize`
     *   的 `require(file.isFile)`），`runCatching` 归一成「缺失」；size ≤ 0 视为空交付。
     *   校验中途工作区不可用按 fail-open 放行（理由见 [SubagentRunner.buildSubagentLoopGate]）。
     */
    private fun buildTaskLoopGate(
        task: ScheduledTaskEntity,
        conversationId: Uuid,
    ): LoopGateConfig? {
        val declared = task.deliverables
            ?.let { raw ->
                runCatching { JsonInstant.decodeFromString<List<String>>(raw) }
                    .onFailure { Log.w(TAG, "deliverables parse failed for task=${task.id}", it) }
                    .getOrDefault(emptyList())
            }
            ?.let(DeliverableChecker::normalizeAll)
            .orEmpty()
        if (declared.isEmpty()) return null
        val assistantId = chatService.getConversationFlow(conversationId).value.assistantId
        val workspaceId = settingsStore.settingsFlow.value
            .getAssistantById(assistantId)?.workspaceId
        if (workspaceId == null) return LoopGateConfig(deliverables = emptyList())
        val wsId = workspaceId.toString()
        return LoopGateConfig(
            deliverables = declared,
            checkDeliverables = { paths ->
                paths.filter { path ->
                    val size = runCatching {
                        workspaceRepository.fileSize(wsId, WorkspaceStorageArea.FILES, path)
                    }.onFailure { e ->
                        Log.w(TAG, "deliverable check failed: $path (${e.message})")
                    }.getOrNull()
                    size == null || size <= 0L
                }
            },
        )
    }

    private fun notifyResult(
        task: ScheduledTaskEntity,
        conversationId: Uuid,
        status: String,
        loopGate: LoopGateConfig?,
    ) {
        // 取消不发结果通知：是用户自己点的「停止」（见 finishCancelled 的 KDoc）。
        // 这里再拦一次是防御：将来若有别的取消来源，也不能被归到 else 分支冒充「失败」。
        if (status == TaskRunStatus.CANCELLED) return
        val (titleRes, contentRes) = when (status) {
            TaskRunStatus.OK ->
                R.string.scheduled_task_notif_done_title to R.string.scheduled_task_notif_done_content

            TaskRunStatus.TIMEOUT ->
                R.string.scheduled_task_notif_timeout_title to R.string.scheduled_task_notif_timeout_content

            else ->
                R.string.scheduled_task_notif_failed_title to R.string.scheduled_task_notif_failed_content
        }
        // 闸门强制放行 = OK 但有缺失：通知里必须说出来，不能让「带瑕疵交付」伪装成全绿
        val contentText = buildString {
            append(context.getString(contentRes, task.title))
            if (loopGate?.forcePassed == true) {
                append('\n').append(forcePassNote(loopGate.missingAtForcePass))
            }
        }
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = task.id.hashCode() + NOTIFICATION_ID_OFFSET,
        ) {
            title = context.getString(titleRes)
            content = contentText
            autoCancel = true
            useDefaults = true
            useBigTextStyle = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = PendingIntent.getActivity(
                context,
                task.id.hashCode(),
                conversationIntent(context, task.conversationId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskRunner"

        /** 等待生成的上限；超时按失败记但不取消生成（设计稿 §六） */
        val GENERATION_TIMEOUT = 10.minutes

        /** 冷启动等设置落盘的上限（与 `ChatService.SETTINGS_AWAIT_TIMEOUT` 同量级） */
        val SETTINGS_AWAIT_TIMEOUT = 30.seconds

        /** 等待生成期间的诊断心跳间隔（debug 级日志；只用于排查「进程被冻」类问题） */
        val HEARTBEAT_INTERVAL = 30.seconds

        /**
         * 注入后等 generation Job 出现的上限（P0.5 取消登记）。
         *
         * 只在「当前轮归属令牌已是本 run」时才等——会话空闲时 `sendMessage` 已同步派发，几乎
         * 立即命中；消息排在用户当前轮次之后时归属不符，**不会**在这里等（更不能登记用户那一轮
         * 的 Job）。等不到并不丢取消：取消标记先到，等待循环的取消信号会提前收场并补一次按归属
         * 的 Job 反查（见 `runInjected`）。
         */
        val JOB_ATTACH_TIMEOUT = 5.seconds

        /** 与 ChatNotificationManager 的会话通知 id（+10000）错开 */
        const val NOTIFICATION_ID_OFFSET = 30000
    }
}

/**
 * 「每轮新建」产出的会话标题：任务名 + 触发时刻（同一任务多轮产生的会话在列表里可区分）。
 *
 * 纯函数，单测覆盖（时区与格式不属于业务判断，所以参数化时区）。
 */
internal fun scheduledRunConversationTitle(
    taskTitle: String,
    atMillis: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String {
    val stamp = java.time.Instant.ofEpochMilli(atMillis)
        .atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    return if (taskTitle.isBlank()) stamp else "$taskTitle · $stamp"
}

/**
 * 定时任务执行结果的判定（纯函数，单测覆盖「静默收场不能记 OK」）。
 *
 * `OK` 必须同时满足三件事：生成**结束**了、没有新增错误、**确实产出了新的 assistant 消息**。
 * 第三条是 2026-10-30 真机事故的补丁：`ChatService.handleMessageComplete` 曾有一条
 * 「模型解析失败 → 静默 return」的分支，job 秒结、无错误、队列空 ⇒ 旧判定记 OK，用户看到的
 * 是「后台触发了，但没有回复」，而且没有任何可诊断线索。把「产出」当必要证据后，任何
 * 静默收场都会落到 FAILED（并带失败通知），配合 handleMessageComplete 的显式报错完成闭环。
 */
internal fun evaluateRunStatus(
    quiesced: Boolean,
    hasNewError: Boolean,
    producedReply: Boolean,
): String = when {
    !quiesced -> TaskRunStatus.TIMEOUT
    hasNewError -> TaskRunStatus.FAILED
    !producedReply -> TaskRunStatus.FAILED
    else -> TaskRunStatus.OK
}

/**
 * P4b：headless agent run 终态 → 定时账本状态（纯函数，单测覆盖）。
 *
 * `null` = 并发闸忙或派发异常，run 根本没起来，按失败记账；超时以 `error` 的机器前缀
 * 区分（agent 层的超时也是 FAILED，只有错误串知道自己为什么失败）。
 */
internal fun scheduledStatusForAgentRun(result: SubagentRunResult?): String = when {
    result == null -> TaskRunStatus.FAILED
    result.status == AgentRunEntity.STATUS_COMPLETED -> TaskRunStatus.OK
    result.status == AgentRunEntity.STATUS_CANCELLED -> TaskRunStatus.CANCELLED
    result.status == AgentRunEntity.STATUS_INTERRUPTED -> TaskRunStatus.INTERRUPTED
    result.error?.startsWith(AgentRunEntity.ERROR_TIMEOUT) == true -> TaskRunStatus.TIMEOUT
    else -> TaskRunStatus.FAILED
}

/**
 * P4b：是否走 headless 定时执行（纯函数，单测覆盖）。
 *
 * 三个条件缺一不可：设置已从 DataStore 载入（否则读到的是 dummy）、用户显式开启、
 * 后台子代理通道可用（notice 投递泵与完成通知都以它为门）。任一不满足 = 保持历史注入。
 */
internal fun shouldUseHeadlessExecution(
    settingsLoaded: Boolean,
    settings: Settings,
): Boolean = settingsLoaded && settings.scheduledHeadlessEnabled && settings.enableBackgroundSubagent

/**
 * 自动清理的目标挑选（纯函数，单测覆盖，§4.2）。
 *
 * [runs] 必须新→旧有序（DAO 保证）。保留最近 [keep] 行（带会话的行），其余行里：
 * - 未收尾（finishedAt == null）→ 跳过（宁可少删——行未收尾意味着时间线不可信）；
 * - 会话在本轮结束后又有新消息（lastMessageAt > finishedAt）→ 跳过（用户接手了的对话）。
 * 每轮新建的会话各只出现一行，无需去重。
 */
internal fun pickAutoCleanupTargets(
    runs: List<ScheduledTaskRunEntity>,
    keep: Int,
    lastMessageAt: Map<String, Long?>,
): List<String> {
    if (keep <= 0) return emptyList()
    return runs.asSequence()
        .filter { !it.conversationId.isNullOrBlank() }
        .drop(keep)
        .mapNotNull { run ->
            val conversationId = run.conversationId!!
            val finishedAt = run.finishedAt
            val lastMsg = lastMessageAt[conversationId]
            when {
                finishedAt == null -> null
                lastMsg != null && lastMsg > finishedAt -> null
                else -> conversationId
            }
        }
        .toList()
}

/**
 * 注入 prompt 的组装（纯函数，单测覆盖）：
 * `withGoal = true` 且会话目标非空 → `## 长期目标\n{goal}\n\n{prompt}`；
 * 开关没开、或会话没写目标（含 NEW_EACH_RUN 每轮新建的空会话）→ 原样返回，不报错。
 */
internal fun buildTaskPrompt(prompt: String, withGoal: Boolean, goal: String?): String {
    if (!withGoal || goal.isNullOrBlank()) return prompt
    return "## 长期目标\n$goal\n\n$prompt"
}
