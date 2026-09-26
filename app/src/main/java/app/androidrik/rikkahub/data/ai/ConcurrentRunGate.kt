package app.androidrik.rikkahub.data.ai

/**
 * **并发 run 闸**（设计 §十二）：同时最多允许 [limit] 个活跃 run。
 *
 * 为什么是"闸"而不是"信号量集合"：本仓 run 只有"启动 / 结束"两个动作，没有排队语义
 * ——满了就**如实拒绝**（调用方把"忙"转达给模型或用户），而不是排进队列等人放行。
 * 排队会让模型在派发工具里无限等，而手机上一次 run 本来就可能跑十几分钟。
 *
 * **上限为什么是 2**（设计 §八的取舍：手机 RAM / 电量 / 发热）：并行 5 个在桌面 agent 平台
 * 是常态，在这里会同时压三份模型流 + 各自的工具进程。2 的意义是"一个在等审批时另一个能跑"，
 * 不是吞吐量。
 *
 * 无 Android 依赖、纯状态机，可 JVM 单测。
 */
class ConcurrentRunGate(
    private val limit: Int = DEFAULT_MAX_CONCURRENT_RUNS,
) {
    private val active = mutableSetOf<String>()

    /**
     * 尝试占一个位置。
     *
     * @return false = 已达上限（调用方应拒绝本次启动，而不是等待）
     */
    @Synchronized
    fun tryAcquire(runId: String): Boolean {
        if (runId in active) return true
        if (active.size >= limit) return false
        active += runId
        return true
    }

    /** 释放（run 结束/失败/被取消都要走到，否则位置会被永久占住）。 */
    @Synchronized
    fun release(runId: String) {
        active -= runId
    }

    /** 这个 run 当前是否在跑（取消路径据此判断"是不是我管的那个"）。 */
    @Synchronized
    fun isActive(runId: String): Boolean = runId in active

    @Synchronized
    fun activeCount(): Int = active.size

    @Synchronized
    fun activeIds(): Set<String> = active.toSet()

    /** 还能不能再开一个（UI 侧按钮态、派发工具的先手判断）。 */
    @Synchronized
    fun canAcquire(): Boolean = active.size < limit

    companion object {
        /** 同时活跃 run 上限（设计 §八：手机端 2 已属激进）。 */
        const val DEFAULT_MAX_CONCURRENT_RUNS = 2
    }
}
