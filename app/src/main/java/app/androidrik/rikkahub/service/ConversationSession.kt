package app.androidrik.rikkahub.service

import android.util.Log
import app.androidrik.common.android.Logging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.data.ai.LoopGuardSensitivity
import app.androidrik.rikkahub.data.ai.RuleEngine
import app.androidrik.rikkahub.data.ai.RuleReminderSensitivity
import app.androidrik.rikkahub.data.ai.ThinkingDegenerationGuard
import app.androidrik.rikkahub.data.ai.ToolCallLoopGuard
import app.androidrik.rikkahub.data.ai.guardThresholds
import app.androidrik.rikkahub.data.datastore.DEFAULT_WORKSPACE_TURN_BUDGET_CHARS
import app.androidrik.rikkahub.data.datastore.DEFAULT_WORKSPACE_TURN_MAX_STEPS
import app.androidrik.rikkahub.data.ai.WorkspaceTurnBudget
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onGenerationFinished: (Uuid, Throwable?) -> Unit = { _, _ -> },
    /**
     * 某一轮**被取消**（而不是正常结束）时回调，参数是该轮的后台任务归属令牌。
     *
     * 挂在这里而不是挂 `stopGeneration`：取消生成协程的入口不止一个（点停止、插队发送、
     * 重新生成、继续生成、`setJob(cancelPrevious = true)` 顶掉前任、会话清理），
     * 但它们**都会取消 Job**；Job 完成回调是唯一同时覆盖全部路径且发生在「确实停了之后」
     * 的挂点（在取消发起处收尾会漏掉「工具正在启动进程」那个窗口）。
     */
    private val onTurnCancelled: (String) -> Unit = {},
    /** LoopGuard 灵敏度档位（设置 → 通用 → 护栏与预算）；标准档 = 原写死行为 */
    loopGuardSensitivity: LoopGuardSensitivity = LoopGuardSensitivity.STANDARD,
    /** 规则提醒（探索/落地守卫）开关与档位（设置 → 通用 → 护栏与预算），默认开 */
    ruleReminderEnabled: Boolean = true,
    ruleReminderSensitivity: RuleReminderSensitivity = RuleReminderSensitivity.STANDARD,
    /** workspace_* 单轮输出预算（字符）与步数上限 */
    turnBudgetChars: Int = DEFAULT_WORKSPACE_TURN_BUDGET_CHARS,
    turnBudgetSteps: Int = DEFAULT_WORKSPACE_TURN_MAX_STEPS,
) {
    // 待发消息队列（生成期间入队，当前轮次结束后自动派发）
    val messageQueue = MessageQueue()

    // 从队列取出到写入会话历史之间，附件仍需作为有效引用保留。
    @Volatile
    var submittingMessage: QueuedMessage? = null
        internal set
    // 会话状态
    val state = MutableStateFlow(initial)

    /**
     * **已完成工具结果的取消安全落点**（见 `GenerationHandler.generateText` 的
     * `onMessagesCommitted` 与 `ChatService.stopGeneration`）。
     *
     * 为什么需要：工具结果回填后要靠 `emit` 才能进入 [state]，而这条链路（`flow`/`flowOn`
     * 的 channel → collect 端）在生成协程被取消时必然断掉——`withContext(NonCancellable)`
     * 包 `emit` 不行（`flow {}` 会抛 Flow invariant），`channelFlow` 的缓冲值也不会再被
     * 已取消的 collector 取走。于是「工具已算完、随即被取消」的那一份结果会从内存态里消失，
     * `persistStoppedGeneration` 落库的仍是旧内容。
     *
     * 语义：**最近一次工具回填的快照**（每次回填覆盖；新一轮生成开始时清空）。
     * 停止路径把它交给 `ChatService.withRestoredToolOutputs`——那里**只补**快照里已完成、
     * 而内存态还没执行的工具结果，绝不覆盖正文，因此「快照旧 / 内存新」的混合状态也安全。
     * 所以这里不需要「送达即清空」的时序配合：快照只增补，不会把正文改回去。
     */
    val lastCommittedMessages = MutableStateFlow<List<UIMessage>?>(null)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 按消息 id 定位本轮回复，重生成旧节点/切换分支时也不能误显示末条消息。
    val replyMessageId = MutableStateFlow<Uuid?>(null)
    val generationStartedAt = MutableStateFlow<Long?>(null)
    val turnRevision = MutableStateFlow(0L)

    /**
     * 工具调用失控护栏（见 `data/ai/ToolCallLoopGuard.kt`）。
     *
     * 放在 session 上而不是 `generateText` 内部：一次「轮」会跨多次 `generateText`——
     * 工具审批会让循环 break 等用户，点批准后是**新的**调用续跑同一轮（真机实测每次都
     * 重新 `start step #0`）。挂在 session 上才能跨审批累积，由 ChatService 在
     * **新一轮用户消息 / 重新生成**时 [ToolCallLoopGuard.reset]。
     * session 空闲回收时随之丢弃，不会跨轮残留。
     */
    val toolLoopGuard = loopGuardSensitivity.guardThresholds().let { (window, success, failure) ->
        ToolCallLoopGuard(
            windowSize = window,
            successThreshold = success,
            failureThreshold = failure,
        )
    }

    /**
     * 规则提醒引擎（见 `data/ai/RuleEngine.kt`）。挂会话的理由与 [toolLoopGuard] **完全同款**：
     * 一次「轮」跨多次 `generateText`（审批续跑），效果窗口必须跨审批累积、
     * 并在**新一轮用户消息 / 重新生成**时由 ChatService [RuleEngine.reset]。
     */
    val ruleEngine = RuleEngine(
        enabled = ruleReminderEnabled,
        sensitivity = ruleReminderSensitivity,
    )

    /**
     * workspace（rootfs）工具的**单轮总预算**（见 `data/ai/WorkspaceTurnBudget.kt`）。
     *
     * 与 [toolLoopGuard] 同款挂载理由与重置时机：堵的是 LoopGuard 防不住的形态
     * （每步换参数的 workspace 调用，累计输出/步数无上限）。挂在 session 上跨审批续跑
     * 累积，由 ChatService 在**新一轮用户消息 / 重新生成**时 reset。
     */
    val workspaceTurnBudget = WorkspaceTurnBudget(
        outputBudgetChars = turnBudgetChars,
        maxSteps = turnBudgetSteps,
    )

    /**
     * **思考退化护栏**（护栏家族第四员，见 `data/ai/ThinkingDegenerationGuard.kt`）。
     *
     * 与 [toolLoopGuard] 同款挂载理由与重置时机：一次「轮」跨多次 generateText（审批续跑），
     * 「连续几次纯思考空转」必须在同一轮内累计才有意义；新一轮用户消息 / 重新生成时 reset。
     * 堵的是家族前三员的前提都落空时的形态：**零工具调用的退化**（纯思考空转 / 复述上一步 /
     * 单次调用内逐字重复）——那次调用不结束的话，`maxSteps` 根本走不到下一步。
     */
    val thinkingGuard = ThinkingDegenerationGuard()

    /**
     * **本轮后台任务归属令牌**（`workspace_run_background` 起的进程）。
     *
     * 存在理由：本轮被打断（点停止 / 插队 / 重新生成 / 继续生成都会取消生成协程）时，
     * 这一轮起的后台进程必须一起收掉——否则它们继续吃 CPU/网络、继续写文件，要等 30 分钟
     * 时长看门狗才停，而模型已经拿不到结果、用户也不知道还有东西在跑。
     *
     * 为什么不靠工具结果里的 `taskId`：任务可能在「进程已启动、结果还没回填给模型」的窗口里
     * 就被取消，那时 id 根本没进对话。归属在**启动那一刻**记下才覆盖得住。
     *
     * 换新时机 = 「轮」的边界（[beginNewTurn]）；跨审批续跑**不**换（那是同一轮的第二次
     * `generateText`），否则审批前起的任务会在审批后失去归属。
     */
    @Volatile
    var backgroundTaskOwner: String = newTurnToken()
        private set

    /**
     * 「轮」的边界：重置三个护栏 + 换新后台任务归属令牌。
     *
     * 抽成一个函数，是因为这四件事原本在三个调用点各写一遍——少写一项就是一个静默缺陷
     * （护栏额度跨轮累积，或者上一轮的取消杀掉这一轮的任务）。调用点仅三处：队列派发
     * （新一轮用户消息）、[ChatService.regenerateAtMessage]、[ChatService.continueMessage]。
     * **必须在 `appScope.launch` 之前同步调用**：`setJob` 注册取消回调时要捕获令牌，
     * 令牌若在生成协程体内才换新，注册就可能先拿到上一轮的令牌。
     */
    fun beginNewTurn() {
        replyMessageId.value = null
        toolLoopGuard.reset()
        workspaceTurnBudget.reset()
        thinkingGuard.reset()
        ruleEngine.reset()
        backgroundTaskOwner = newTurnToken()
        turnRevision.value++
    }

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true

    val isInUse: Boolean
        get() = refCount.get() > 0 || isGenerating ||
                messageQueue.state.value.messages.isNotEmpty()

    @Volatile
    private var diagnosticStage = "idle"

    /** Persist only lifecycle metadata; never include message/tool bodies or exception messages. */
    internal fun logGenerationStage(stage: String) {
        diagnosticStage = stage
        runCatching {
            Logging.log(TAG, "generation-diag conv=$id stage=$stage thread=${Thread.currentThread().name} " +
                "job=${jobSummary(_generationJob.value)} ${generationStateSummary()}")
        }
    }

    private fun generationStateSummary(): String {
        val conversation = state.value
        val message = replyMessageId.value?.let { replyId ->
            conversation.messageNodes.firstNotNullOfOrNull { node -> node.messages.firstOrNull { it.id == replyId } }
        } ?: conversation.currentMessages.lastOrNull()
        return "revision=${turnRevision.value} processing=${processingStatus.value != null} " +
            "messageId=${message?.id} finishedAt=${message?.finishedAt} finishReason=${message?.finishReason?.take(48)} " +
            "incomplete=${message?.finishIncomplete} partCount=${message?.parts?.size} " +
            "parts=${message?.parts?.take(32)?.map { it::class.simpleName }}"
    }

    private fun jobSummary(job: Job?): String = job?.let {
        "${it::class.simpleName}@${Integer.toHexString(System.identityHashCode(it))}" +
            "(active=${it.isActive},completed=${it.isCompleted},cancelled=${it.isCancelled})"
    } ?: "null"

    private fun boundedJobTree(root: Job): String = buildString {
        var remaining = 32
        fun appendJob(job: Job, depth: Int) {
            if (remaining-- <= 0) return
            append("\n").append("  ".repeat(depth)).append(jobSummary(job))
            if (depth >= 5) {
                if (job.children.iterator().hasNext()) append(" children=<depth-limit>")
                return
            }
            val children = job.children.iterator()
            while (remaining > 0 && children.hasNext()) appendJob(children.next(), depth + 1)
            if (children.hasNext()) append("\n<node-limit>")
        }
        appendJob(root, 0)
    }

    // 所有活动生成/审批协程（含排队等待前任的）：stopGeneration 全链取消
    private val activeJobs = mutableSetOf<Job>()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    /**
     * 注册生成协程。连续审批/连续发送时 [cancelPrevious]=false 不清前任——
     * 审批决策要串行落地（前任的保存不被后任打断），停时由 [cancelJobs] 全链取消。
     */
    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        val previous = _generationJob.value
        if (job != null) generationStartedAt.value = System.currentTimeMillis()
        if (cancelPrevious) previous?.cancel()
        _generationJob.value = job
        if (job != null) activeJobs.add(job)
        // 归属**在注册这一刻**捕获：取消回调可能晚于下一轮换新令牌才执行
        // （regenerate/continue 是先 cancel 前任、再 beginNewTurn、最后注册新 job），
        // 那时再读 backgroundTaskOwner 已经是下一轮的令牌，会漏掉真正的孤儿。
        val turnOwner = backgroundTaskOwner
        // Launch on the injected application/session scope, never on the generation Job.
        // This observer is a sibling, so it cannot keep the observed Job in Completing.
        val diagnosticWatchdog = job?.takeIf { Logging.isFileLoggingEnabled() }?.let { observed ->
            scope.launch(Dispatchers.Default) {
                var coroutineDumped = false
                while (!observed.isCompleted) {
                    delay(30_000L)
                    if (!observed.isCompleted) runCatching {
                        Logging.log(TAG, "generation-watchdog conv=$id stage=$diagnosticStage " +
                            "thread=${Thread.currentThread().name} ${generationStateSummary()}" +
                            boundedJobTree(observed))
                        // 首次卡死疑似时 dump 全量协程挂起栈（DebugProbes 仅 debug 包可用，
                        // 反射失败静默跳过）。install 在 App 启动时完成，此处只读。
                        if (!coroutineDumped) {
                            coroutineDumped = true
                            runCatching {
                                val dump = Class.forName("kotlinx.coroutines.debug.DebugProbes")
                                    .getMethod("dumpCoroutinesToString").invoke(null) as? String
                                if (!dump.isNullOrBlank()) {
                                    Logging.log(TAG, "coroutine-dump-begin\n$dump\ncoroutine-dump-end")
                                }
                            }
                        }
                    }
                }
            }
        }
        job?.invokeOnCompletion { cause ->
            diagnosticWatchdog?.cancel()
            runCatching {
                Logging.log(TAG, "generation-job-completed conv=$id cause=${cause?.javaClass?.simpleName ?: "none"} " +
                    "observed=${jobSummary(job)} current=${jobSummary(_generationJob.value)} " +
                    "thread=${Thread.currentThread().name} ${generationStateSummary()}")
            }
            synchronized(this) {
                activeJobs.remove(job)
                // 排队等待前任的协程从未进入体即被取消时，把取消传播给前任——
                // 避免"等待新审批 → 前任永远留在 activeJobs / 后继不启动"的悬挂
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // 旧 job 的完成回调可能晚于新 job 设置执行（连续 sendMessage / approve 重启生成时）：
                // 无条件清 null 会误清新 job 的引用 → isGenerating 假 false、stopGeneration 找不到
                // job、空闲检查提前清理会话。按 identity 判断只清自己。
                // （移植自 rikkahub-turbo c897647）
                if (_generationJob.value === job) {
                    _generationJob.value = null
                    // 队列派发钩子：失败暂停队列，结束后取下一个
                    onGenerationFinished(id, cause)
                }
            }
            // 被取消（而不是正常走完）⇒ 这一轮不再继续，收掉它起的后台任务。
            // **只在取消时收**：正常结束后后台任务继续跑正是 `workspace_run_background` 的契约。
            if (cause is CancellationException) {
                runCatching { onTurnCancelled(turnOwner) }
            }
            if (refCount.get() <= 0) {
                scheduleIdleCheck()
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    /** @Synchronized 返回全部活动协程并逆序取消（先取消等待者，防止前任完成启动下一个审批） */
    @Synchronized
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        jobs.asReversed().forEach { it.cancel() }
    }

    /**
     * 元数据编辑（置顶 / 移动助手 / 归属文件夹等）的**统一落地原语**。
     *
     * 为什么需要：这类编辑只改会话的几个标量字段，但历史上的写法是
     * 「从 DB 读整对象 → 改字段 → 整对象写回」。对**活跃 session** 而言那会覆盖内存态——
     * 生成中途切助手/打星标会把已在流式接收的 messageNodes 换成 DB 里的旧快照
     * （与「停止生成丢内容」同族，见 `docs/upstream-analysis.md` 对 `4a3eefc1` 的评估）。
     *
     * 两条不变量：
     * 1. [update] 作用在**当前内存值**上（而非外部传入的快照）⇒ 在途内容不丢；
     * 2. [persist] 只写对应列（`updatePinStatus`/`updateConversationAssistant` 等单列 UPDATE），
     *    不整对象写回 ⇒ 落库侧也不会用旧快照盖掉新内容。
     *
     * `mutex` 串行化内存改写与落库：连续编辑（如快速连点置顶）时两次 `persist` 不会交错，
     * 落库值必然等于最后一次内存值。
     */
    private val metadataMutex = Mutex()

    suspend fun updateMetadata(
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        metadataMutex.withLock {
            val updated = update(state.value)
            state.value = updated
            persist(updated)
        }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        cancelJobs()
        _generationJob.value = null
        idleCheckJob?.cancel()
        idleCheckJob = null
        processingStatus.value = null
        replyMessageId.value = null
        generationStartedAt.value = null
    }
}

/**
 * 元数据回写的**唯一决策点**（可单测，见 `ConversationSessionMetadataTest`）：
 * **有活跃 session 时作用在内存最新值上，没有 session 时才允许从 DB 读一次作基线。**
 *
 * 为什么必须共用一处：标题 / 聊天建议 / 置顶 / 移动助手这些回写都发生在「几秒的网络请求
 * 或一次点击之后」，基线若取 DB 快照，就会把这段时间里收到的流式内容整份盖掉——标题回来的
 * 那一刻正文当场消失（生成建议、自动起名都**每轮自动触发**，是默认路径而不是边缘）。
 *
 * 生成中（[ConversationSession.isGenerating]）不特殊处理：`update` 是从**当前内存值**出发的
 * 纯函数变换（只改一个元数据字段），因此只会改动那一个字段，`messageNodes` 原样保留。
 *
 * @param session 活跃会话；null = 该会话没有内存态，此时 `loadCurrent` 是唯一可用的基线
 * @param loadCurrent 无 session 时的基线来源（从 DB 读一次）
 * @param update 纯函数变换，只改元数据字段
 * @param persist 落库；**只写对应单列**（`updateTitle` / `updateChatSuggestions` /
 *   `updateConversationPinStatus` 等），绝不整对象写回
 */
internal suspend fun updateMetadataSafely(
    session: ConversationSession?,
    loadCurrent: suspend () -> Conversation?,
    update: (Conversation) -> Conversation,
    persist: suspend (Conversation) -> Unit,
) {
    if (session == null) {
        val current = loadCurrent() ?: return
        persist(update(current))
        return
    }
    session.updateMetadata(update, persist)
}

/** 串行化审批保存：等前任完成后再落地本决策，避免连续审批互相打断（不主动 cancel 前任）。 */
internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        // 后继被 stopGeneration 取消时不把 CancellationException 吞掉
        throw e
    }
}

/** 新一轮后台任务归属令牌。只要求「同一会话内的两轮不同」，故用随机串而非计数（无跨进程状态）。 */
private fun newTurnToken(): String = "turn-${Uuid.random()}"
