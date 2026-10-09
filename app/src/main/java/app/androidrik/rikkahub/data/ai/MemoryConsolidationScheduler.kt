package app.androidrik.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.androidrik.rikkahub.data.model.Conversation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** 一个对话里还没整理进记忆库 [memoryId] 的轮数。 */
data class PendingMemoryConversation(
    val conversationId: Uuid,
    val memoryId: String,
    val turns: Int,
)

/**
 * 一次整理的结果。
 *
 * [changedMemories] 由 worker 填（新建 / 改写 / 退役 / 合并的合计）：调度器与状态面板只需要
 * 「这次有没有改动」，**更细的明细（改的是哪几条、为什么）属于 worker 自己的日志**——框架层不替
 * 存储层定义变更清单的粒度，否则 V4 换存储时要连带改这里。
 */
data class MemoryConsolidationRun(
    val memoryId: String,
    val finishedAt: Long,
    /** 这次整理了哪些对话的新轮次 */
    val conversationIds: Set<Uuid>,
    /** 是否顺带把整个记忆库清理了一遍（用户手动整理） */
    val fullReview: Boolean,
    val changedMemories: Int = 0,
    /**
     * 审批开着时攒下的**待办**（这一轮只分析、没动库）。
     *
     * 为什么跟着运行结果走而不是写库：本批没有能「应用建议」的界面，写库只会留下无处可点的数据。
     * 持久化 + 审批入口是 V3-b3 的事——**在那之前不要接自动触发**（否则等于白烧一次模型调用）。
     */
    val proposals: List<MemoryConsolidationProposal> = emptyList(),
    val error: String? = null,
) {
    val hasChanges: Boolean get() = changedMemories > 0
}

/**
 * 一条「本该如何改」的建议（审批开着时做梦的产出）。
 *
 * 结构化而不是一行文字：将来的「逐条批准」界面要能直接回放成一次工具调用（action + 目标 + 载荷）。
 */
data class MemoryConsolidationProposal(
    /** `create` / `edit` / `delete` */
    val action: String,
    /** `edit` / `delete` 的目标记录 id；`create` 为 null */
    val targetId: String? = null,
    val content: String = "",
    val importance: Float? = null,
    val description: String? = null,
)

/**
 * 建议在记忆页上的一行（**纯函数**：判据不藏在 Composable 里，好单测）。
 *
 * 只给「动作 + 目标 + 内容摘要」：用户要按它改，得知道改哪条、改成什么；
 * 详细字段（importance/description）不铺在这里——记忆页上每条的编辑入口本来就有。
 */
fun MemoryConsolidationProposal.describe(maxContent: Int = 60): String {
    val content = content.replace('\n', ' ').trim()
    val preview = if (content.length > maxContent) content.take(maxContent) + "…" else content
    return when (action) {
        "create" -> "create · $preview"
        "edit" -> "edit · ${targetId.orEmpty()} → $preview"
        "delete" -> "delete · ${targetId.orEmpty()}"
        else -> "$action · $preview"
    }
}

/** 某个记忆库的整理状态（状态面板/设置页只读它）。 */
data class MemoryConsolidationStatus(
    val pendingTurns: Int = 0,
    val running: Boolean = false,
    val lastRun: MemoryConsolidationRun? = null,
)

/**
 * 调度器依赖的整理能力，由 worker（V3 的下一批：`MemoryConsolidator`）实现。
 *
 * **契约：框架只认 `memoryId` + 本接口，不认识任何存储形态。** 四条不许破：
 *
 * 1. `memoryId` 是「一个记忆库」的**不透明**标识。本仓当前语义 = `assistantId`（含 `__global__`），
 *    但调度器不解释它、也不拼 SQL；换存储（V4 的文件库）时这层不动。
 * 2. [pendingOf] 是**唯一的「要不要整理」判据**：隐身对话（`Conversation.memoryDisabled`）、
 *    停用的助手、没开记忆的助手都必须在这里返回 null。「不整理」的语义在这层落地，调度器不重复判断。
 * 3. [findPending] 返回**一次整理能处理的数量**——轮次切分与分批由 worker 定（框架不猜一次能塞多少
 *    上下文），没处理完的部分下次再被 [findPending] 捞出来即可。[findPending] 的 `manual` 参数
 *    用来区分「用户手动整理」与「调度器自动补扫」：自动路径要服从频次上限（否则活跃会话会变成
 *    「聊两句就后台整理一次」），手动路径不受约束。
 * 4. [consolidate] 没可做的事返回 null、**失败抛异常**（调度器记成 `error` 并停止自动重试，见
 *    [MemoryConsolidationScheduler.run]）。整理必须**幂等**：同 [conversationIds] 重复调用不该重复动作。
 */
interface MemoryConsolidationWorker {
    /** [conversation] 里还没整理的轮次；该对话不用整理（隐身/停用/无新轮次）时返回 null */
    suspend fun pendingOf(conversation: Conversation): PendingMemoryConversation?

    /** 可能有对话没整理完的记忆库。只按更新时间**粗筛**，不保证真有新的轮次 */
    suspend fun memoryIdsWithUpdates(): List<String>

    /**
     * 记忆库 [memoryId] 下还有轮次没整理的对话，最多返回一次整理能处理的数量。
     *
     * [manual] 区分这条路径是不是**用户手动整理**（`organize`）：自动路径要服从「每库自动频次上限」
     * （否则活跃会话会变成「聊两句就后台整理一次」），手动路径不受它约束——用户显式点击即授权。
     * 调度器每次整理成功后也会重扫（自动路径），所以这个区分不能省，否则限流形同虚设。
     */
    suspend fun findPending(memoryId: String, manual: Boolean = false): List<PendingMemoryConversation>
    /** 把 [conversation] 现有的内容都算作整理过；之后新增的轮次才会被整理（分叉时用） */
    suspend fun markConsolidated(conversation: Conversation)

    /** 整理 [conversationIds] 的新轮次，[fullReview] 时再把整个记忆库清理一遍 */
    suspend fun consolidate(memoryId: String, conversationIds: Set<Uuid>, fullReview: Boolean): MemoryConsolidationRun?
}

/**
 * 整理期间让进程不被系统冻结或回收。拿不到时返回的句柄什么都不做，**整理照常进行**——
 * 保活失败不该变成功能失败（V3 的下一批再接前台服务）。
 */
fun interface MemoryDreamKeepAlive {
    fun acquire(): AutoCloseable
}

/**
 * 决定什么时候整理记忆（**做梦框架**，V3；移植上游 #2130 的 `MemoryConsolidationScheduler`，
 * 见 `docs/design-memory-consolidation.md`）。
 *
 * 触发面（五个入口，都只做「什么时候」，不做「整理什么」）：
 * - **轮末空闲**：[onTurnFinished] 后等 [idleDelay]（应用不可见时先占住保活，否则进程可能在
 *   计时走完前被冻结）；
 * - **攒够轮数**：待整理轮数 ≥ [maxPendingTurns] 就不再等——否则一直聊下去永远不整理；
 * - **切后台**：[onAppHidden]（后台的计时不一定还能走完）；
 * - **离开对话**：[onConversationLeft]（短时间内不会再有新消息，不必等空闲）；
 * - **启动补漏 / 手动**：[resumePending] 与 [organize]。
 *
 * 整理以**记忆库**为单位，一次覆盖该库下所有待整理对话；同一个库同一时间只跑一次（[runLocks]）。
 */
class MemoryConsolidationScheduler(
    private val scope: CoroutineScope,
    private val worker: MemoryConsolidationWorker,
    private val keepAlive: MemoryDreamKeepAlive = MemoryDreamKeepAlive { AutoCloseable {} },
    /** 应用当前是否有界面在前台 */
    private val isAppVisible: () -> Boolean = { true },
    private val idleDelay: Duration = DEFAULT_IDLE_DELAY,
    private val maxPendingTurns: Int = DEFAULT_MAX_PENDING_TURNS,
) {
    companion object {
        /** 轮末到整理的等待时长；期间又有新轮次就重新计时（连续对话只在停下来之后整理一次） */
        val DEFAULT_IDLE_DELAY: Duration = 2.minutes

        /** 攒够这么多未整理轮数就不再等空闲——否则「一直聊」会永远不整理 */
        const val DEFAULT_MAX_PENDING_TURNS: Int = 6
    }

    /**
     * 状态与待整理列表的锁。
     *
     * 用 `synchronized` 而不是 Mutex：这些都只是**内存里的短操作**（没有挂起点），用它才能保证
     * 跨协程的读改写是原子的；真正的互斥（一次只跑一个整理）用 [runLocks] 的 Mutex。
     */
    private val lock = Any()

    /** memoryId -> (conversationId -> 待整理轮数) */
    private val pending = mutableMapOf<String, Map<Uuid, Int>>()
    private val timers = mutableMapOf<String, Job>()
    private val runLocks = mutableMapOf<String, Mutex>()

    /** 上次整理失败、之后还没有新轮次的记忆库（失败后不自动重试，见 [run]） */
    private val failed = mutableSetOf<String>()

    private val status = MutableStateFlow<Map<String, MemoryConsolidationStatus>>(emptyMap())

    fun statusOf(memoryId: String): Flow<MemoryConsolidationStatus> =
        status.map { it[memoryId] ?: MemoryConsolidationStatus() }.distinctUntilChanged()

    private val _automaticRuns = MutableSharedFlow<MemoryConsolidationRun>(extraBufferCapacity = 8)

    /** 自动触发的整理结果（手动触发的结果体现在 [statusOf] 里）；给「整理完了」的一次性提示用 */
    val automaticRuns: SharedFlow<MemoryConsolidationRun> = _automaticRuns.asSharedFlow()

    /** 一轮对话结束：空闲一段时间后整理，攒够轮数就不再等 */
    fun onTurnFinished(conversation: Conversation) {
        // 应用不在前台时，进程随时可能被冻结、空闲计时走不完。所以趁生成用的前台服务还没释放，
        // 先把保活占住，一直占到这次整理结束（在前台时不需要，别白占）
        val hold = if (isAppVisible()) null else keepAlive.acquire()
        scope.launch {
            var handedOver = false
            try {
                val item = worker.pendingOf(conversation) ?: return@launch
                markPending(item)
                if (item.turns >= maxPendingTurns) {
                    runAutomatically(item.memoryId)
                } else {
                    restartTimer(item.memoryId, hold)
                    handedOver = true
                }
            } finally {
                // 保活要么交给定时器管，要么现在就放开——不能因为提前 return 泄漏
                if (!handedOver) hold?.close()
            }
        }
    }

    /** [fork] 是从另一个对话分叉出来的：复制过来的轮次由原对话负责整理，这里不再整理一遍 */
    suspend fun onConversationForked(fork: Conversation) {
        worker.markConsolidated(fork)
    }

    /** 应用被切到后台：之后空闲计时不一定还能走完，有待整理的就现在开始 */
    fun onAppHidden() {
        synchronized(lock) { pending.filterValues { it.isNotEmpty() }.keys.toList() }.forEach(::launchRun)
    }

    /** 用户离开了对话，不会马上再有新消息，不用等到空闲 */
    fun onConversationLeft(conversationId: Uuid) {
        val memoryId = synchronized(lock) {
            pending.entries.firstOrNull { conversationId in it.value }?.key
        } ?: return
        launchRun(memoryId)
    }

    /** 应用启动时补上进程被杀前没来得及整理的对话 */
    suspend fun resumePending() {
        // 每个记忆库各查各的：一起查再截断的话，对话少的记忆库会被对话多的挤掉
        worker.memoryIdsWithUpdates().forEach { memoryId ->
            if (mergePending(memoryId, worker.findPending(memoryId))) restartTimer(memoryId)
        }
    }

    /** 用户手动整理整个记忆库：先处理所有待整理的对话，再把记忆库清理一遍 */
    fun organize(memoryId: String) {
        // 查数据库要一会儿，先标成进行中，免得这期间连点几下排上好几次整理
        if (status.value[memoryId]?.running == true) return
        updateStatus(memoryId) { it.copy(running = true) }
        cancelTimer(memoryId)
        scope.launch {
            var started = false
            try {
                // 进程重启后内存里的待整理列表可能是空的，把数据库里的也算上
                // （manual = true：手动整理不受自动频次上限约束）
                mergePending(memoryId, worker.findPending(memoryId, manual = true))
                started = true
                run(memoryId, fullReview = true)
            } finally {
                // run 自己会收尾（含 running=false），这里只管没走到它的情况
                if (!started) updateStatus(memoryId) { it.copy(running = false) }
            }
        }
    }

    private fun launchRun(memoryId: String) {
        scope.launch { runAutomatically(memoryId) }
    }

    private suspend fun runAutomatically(memoryId: String) {
        cancelTimer(memoryId)
        run(memoryId, fullReview = false)?.let { _automaticRuns.emit(it) }
    }

    /** 整理 [memoryId] 下所有待整理的对话；没得做返回 null */
    private suspend fun run(memoryId: String, fullReview: Boolean): MemoryConsolidationRun? =
        synchronized(lock) { runLocks.getOrPut(memoryId) { Mutex() } }.withLock {
            val ids = synchronized(lock) {
                // **失败后不自动重试**：模型一直出错时，切后台/离开对话会一次次重打模型（花钱、烧配额）。
                // 待整理内容留着，等下一轮对话结束（用户又有新动作）或用户手动触发
                if (!fullReview && memoryId in failed) return null
                pending[memoryId]?.keys.orEmpty()
            }
            if (ids.isEmpty() && !fullReview) return null

            updateStatus(memoryId) { it.copy(running = true) }
            var run: MemoryConsolidationRun? = null
            val hold = keepAlive.acquire()
            try {
                run = try {
                    worker.consolidate(memoryId, ids, fullReview)
                } catch (e: CancellationException) {
                    // 取消（进程要走了/用户停掉）不是「整理失败」：不记 failed，否则下次启动会被拦住
                    throw e
                } catch (e: Exception) {
                    MemoryConsolidationRun(
                        memoryId = memoryId,
                        finishedAt = System.currentTimeMillis(),
                        conversationIds = ids,
                        fullReview = fullReview,
                        error = e.message ?: e.toString(),
                    )
                }
                if (run?.error != null) {
                    synchronized(lock) { failed += memoryId }
                } else {
                    synchronized(lock) {
                        failed -= memoryId
                        // 只减掉这次整理的对话；期间新结束的轮次不能被一起清掉
                        pending[memoryId] = pending[memoryId].orEmpty() - ids
                    }
                    // 有对话可能因超出长度预算被留到下一次，从数据库里再找一遍
                    if (mergePending(memoryId, worker.findPending(memoryId))) restartTimer(memoryId)
                }
            } finally {
                hold.close()
                updateStatus(memoryId) { it.copy(running = false, lastRun = run ?: it.lastRun) }
            }
            run
        }

    /** [hold] 交给定时器管：一直占到整理结束；定时器被取消或被新的顶掉时放开 */
    private fun restartTimer(memoryId: String, hold: AutoCloseable? = null) {
        val timer = scope.launch(start = CoroutineStart.LAZY) {
            delay(idleDelay)
            // 整理开始时会取消还在等的定时器；先把自己摘出来，免得被自己取消
            val self = currentCoroutineContext().job
            synchronized(lock) { if (timers[memoryId] === self) timers.remove(memoryId) }
            runAutomatically(memoryId)
        }
        if (hold != null) timer.invokeOnCompletion { hold.close() }
        synchronized(lock) { timers.put(memoryId, timer) }?.cancel()
        timer.start()
    }

    private fun cancelTimer(memoryId: String) {
        synchronized(lock) { timers.remove(memoryId) }?.cancel()
    }

    private fun markPending(item: PendingMemoryConversation) {
        // 又有新轮次了：把「失败过」摘掉，这次该再试
        synchronized(lock) { failed -= item.memoryId }
        mergePending(item.memoryId, listOf(item))
    }

    /**
     * 把 [items] 并进待整理列表，返回这个记忆库现在是否有待整理的对话。
     *
     * **不能整个换掉**：[items] 是从数据库查出来的，查询期间结束的轮次不在里面——直接赋值会把
     * 它们刚记下的待整理弄丢（单测 `a turn that finishes while the store is being rescanned is not lost`）。
     */
    private fun mergePending(memoryId: String, items: List<PendingMemoryConversation>): Boolean {
        val conversations = synchronized(lock) {
            (pending[memoryId].orEmpty() + items.associate { it.conversationId to it.turns })
                .also { pending[memoryId] = it }
        }
        updateStatus(memoryId) { it.copy(pendingTurns = conversations.values.sum()) }
        return conversations.isNotEmpty()
    }

    private fun updateStatus(
        memoryId: String,
        transform: (MemoryConsolidationStatus) -> MemoryConsolidationStatus,
    ) {
        status.update { it + (memoryId to transform(it[memoryId] ?: MemoryConsolidationStatus())) }
    }
}
