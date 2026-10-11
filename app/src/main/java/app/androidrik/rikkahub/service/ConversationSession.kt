package app.androidrik.rikkahub.service

import android.util.Log
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
import app.androidrik.rikkahub.data.ai.ThinkingDegenerationGuard
import app.androidrik.rikkahub.data.ai.ToolCallLoopGuard
import app.androidrik.rikkahub.data.ai.guardThresholds
import app.androidrik.rikkahub.data.datastore.DEFAULT_WORKSPACE_TURN_BUDGET_CHARS
import app.androidrik.rikkahub.data.datastore.DEFAULT_WORKSPACE_TURN_MAX_STEPS
import app.androidrik.rikkahub.data.ai.WorkspaceTurnBudget
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
    /** LoopGuard 灵敏度档位（设置 → 通用 → 护栏与预算）；标准档 = 原写死行为 */
    loopGuardSensitivity: LoopGuardSensitivity = LoopGuardSensitivity.STANDARD,
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

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

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

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || isGenerating ||
                messageQueue.state.value.messages.isNotEmpty()

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
        if (cancelPrevious) previous?.cancel()
        _generationJob.value = job
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
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
     * （与「停止生成丢内容」同族，见 私有仓记录·上游分析 对 `4a3eefc1` 的评估）。
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
    }
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
