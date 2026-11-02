package app.androidrik.rikkahub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

/**
 * 定时任务「运行中」注册表（P0.5 定时取消，`docs/design/in-progress/design-agent-task-runtime.md`
 * §5.2 第 4 条）。
 *
 * ## 为什么需要一张表
 *
 * 定时任务的取消入口是 FGS 通知上的「停止」动作（[ScheduledTaskRunService.ACTION_STOP]），
 * 它由系统通知栏触发，能拿到的只有 `runId`——既没有 `ConversationSession` 引用，也不该去
 * 猜「现在哪个会话在生成」。而真正要停的东西是**这一轮注入的生成 Job**。所以执行层在注入
 * 后把 Job 登记进来，取消方按 runId 反查。
 *
 * ## 为什么取消用 `Job.cancel()` 而不是 `ChatService.stopGeneration`
 *
 * `stopGeneration` 会 `messageQueue.pause()`（`ChatService.kt` 停止路径），把 **EXISTING**
 * 目标会话里用户自己的待发队列一起暂停——用户点「停止定时任务」不该冻结他正在排队的消息。
 * `Job.cancel()` 只停这一轮，顺带触发 `ConversationSession.onTurnCancelled(owner)` 收掉
 * 该轮起的后台 shell 任务（owner = [scheduledRunTurnOwner]）。
 *
 * ## 先取消后 attach
 *
 * 取消动作可能早于 Job 登记：注入消息排在用户当前轮次之后时，派发要等当前轮结束，
 * 这段时间里没有 Job 可取消。因此「已取消」标记**先于 Job 存在**，[attachJob] 见到标记会
 * 当场取消传入的 Job——取消不依赖时序。
 *
 * 线程安全：执行（Runner）在后台线程，取消来自 Service 的 `onStartCommand`（主线程），
 * 因此每个入口都在同一把锁下读写；[activeRunIds]/[cancelledRunIds] 供通知与等待循环观察。
 */
class ScheduledRunRegistry {
    private class Entry(val conversationId: Uuid?) {
        /** 本轮 generation Job；null = 已登记但 Job 还没出现（排队中/尚未派发） */
        var job: Job? = null

        /** 用户已请求取消；**置位后不回退**（直到 [unregister] 移除条目） */
        var cancelled: Boolean = false
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    private val _activeRunIds = MutableStateFlow<List<String>>(emptyList())

    /** 当前已登记（尚未 [unregister]）的 runId，按登记顺序；供 FGS 通知挂「停止」动作。 */
    val activeRunIds: StateFlow<List<String>> = _activeRunIds.asStateFlow()

    private val _cancelledRunIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * 已请求取消的 runId 集合。等待循环把它作为第二个「结束」条件合并进 `combine`——
     * 取消后不必等生成自然结束，立刻走终态记账（否则用户点了停止，界面看不到任何反应）。
     */
    val cancelledRunIds: StateFlow<Set<String>> = _cancelledRunIds.asStateFlow()

    /** 登记一轮执行。重复登记同一 runId 视为无操作（保留原条目与取消标记）。 */
    fun register(runId: String, conversationId: Uuid? = null): Boolean {
        if (runId.isBlank()) return false
        return synchronized(lock) {
            if (entries.containsKey(runId)) return@synchronized false
            entries[runId] = Entry(conversationId)
            publishLocked()
            true
        }
    }

    /**
     * 登记本轮 generation Job。
     *
     * @return true = 该 run 在登记**之前**已被取消，传入的 Job 已当场取消；调用方应直接用
     *   取消终态收尾，不必再等生成结束。
     */
    fun attachJob(runId: String, job: Job?): Boolean {
        if (job == null) return isCancelled(runId)
        val cancelledImmediately = synchronized(lock) {
            val entry = entries[runId] ?: return false
            if (entry.cancelled) {
                true
            } else {
                entry.job = job
                false
            }
        }
        if (cancelledImmediately) job.cancel()
        return cancelledImmediately
    }

    /**
     * 请求取消一轮：置取消标记并取消已登记的 Job。
     *
     * 幂等：重复调用仍返回 `true`（该 run 处于取消态），此时 Job 已取消或尚不存在。
     *
     * @return false = 该 runId 不在表中（从未注册或已 [unregister]），调用方无需再等它
     */
    fun cancel(runId: String): Boolean {
        val job = synchronized(lock) {
            val entry = entries[runId] ?: return false
            entry.cancelled = true
            publishLocked()
            entry.job
        }
        // Job.cancel() 会同步触发完成回调（onTurnCancelled / 队列派发），放到锁外执行，
        // 避免「registry 锁里等 session 锁」这种跨锁嵌套。
        job?.cancel()
        return true
    }

    fun isCancelled(runId: String): Boolean = synchronized(lock) {
        entries[runId]?.cancelled == true
    }

    /** 本轮登记过的 generation Job（诊断/测试用；取消请走 [cancel]） */
    fun jobOf(runId: String): Job? = synchronized(lock) { entries[runId]?.job }

    /**
     * 一轮执行结束（终态已落库）后移除条目。
     *
     * **不取消 Job**：正常结束时 Job 已完成，取消是空操作；调用方（Runner 的 `finally`）
     * 负责保证终态先写入，避免「取消到达 → 先 unregister → 状态被记成 OK」。
     */
    fun unregister(runId: String): Boolean = synchronized(lock) {
        if (entries.remove(runId) == null) {
            false
        } else {
            publishLocked()
            true
        }
    }

    /** 必须在持锁时调用：把当前快照推给两个观察流（StateFlow 赋值非阻塞）。 */
    private fun publishLocked() {
        _activeRunIds.value = entries.keys.toList()
        _cancelledRunIds.value = entries.filterValues { it.cancelled }.keys.toSet()
    }
}

/**
 * 定时任务注入轮的后台任务归属令牌（`sched:<runId>`）。
 *
 * 为什么带 `sched:` 前缀：归属令牌同时被 `workspace_run_background` 的 `turnOwner` 使用，
 * 前缀让「这一轮起的后台进程」在日志/账本里能区分来源（对话轮 vs 定时 run），也避免与
 * `turn-<uuid>` 形态碰撞。
 */
internal fun scheduledRunTurnOwner(runId: String): String = "sched:$runId"
