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
import kotlinx.coroutines.withContext
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
