package app.androidrik.rikkahub.data.ai

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * **待批队列**：无头 run 等在"人的决定"上，由**通知按钮**或**运行详情页**来填。
 *
 * **为什么是进程内的静态对象而不是 DI 单例**：填的一方是 `BroadcastReceiver`
 * （通知栏的「批准 / 拒绝」按钮），它在没有 Activity、也不保证能拿到 Koin 容器的场景下被拉起；
 * 而**等的一方（run）本来就在本进程内**——run 不跨进程存活（Phase 1/2 都没有 work-manager 化的
 * 持久运行），所以"进程内队列"与"run 的生命周期"正好同界。
 *
 * 进程被杀 ⇒ 队列清空 + run 也没了 ⇒ 之后点通知按钮只会得到 `resolve == false`（无人在等），
 * 这是**正常降级**而不是错误：不崩、不报错，UI 侧的 run 行会停在 AWAITING_APPROVAL 上成为历史痕迹。
 *
 * 无 Android 依赖，可直接 JVM 单测。
 */
object SubagentApprovalQueue {

    /** toolCallId → 等待者。一个 run 的一批待批工具各占一个条目。 */
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /**
     * 登记一个待批工具并拿到等待句柄。
     *
     * 同一 toolCallId 重复登记**覆盖**旧的（并对旧句柄补一个"拒绝"，让旧的等待者不会永远挂着）。
     */
    fun register(toolCallId: String): CompletableDeferred<Boolean> {
        val fresh = CompletableDeferred<Boolean>()
        waiters.put(toolCallId, fresh)?.complete(false)
        return fresh
    }

    /**
     * 填入决定。
     *
     * @return true = 确实有 run 在等（已填）；false = 无人在等（进程重启后点旧通知、或已超时）
     */
    fun resolve(toolCallId: String, approved: Boolean): Boolean {
        val waiter = waiters[toolCallId] ?: return false
        return waiter.complete(approved)
    }

    /**
     * 撤销登记（run 侧等待结束/超时后调用）。
     *
     * **必须由等待方在 `finally` 里调**：否则超时后队列里会留一个永远没人查的条目
     * （内存泄漏 + 之后点通知会误报"已填"）。
     */
    fun forget(toolCallId: String) {
        waiters.remove(toolCallId)
    }

    /** 当前待批数量（诊断/测试用）。 */
    fun pendingCount(): Int = waiters.size
}
