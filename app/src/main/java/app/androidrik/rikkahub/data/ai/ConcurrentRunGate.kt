package app.androidrik.rikkahub.data.ai

/**
 * **并发 run 闸**（设计 §十二 / §5.1）：同时最多允许 `limit()` 个活跃 run。
 *
 * 为什么是"闸"而不是"信号量集合"：本仓 run 只有"启动 / 结束"两个动作，没有排队语义
 * ——满了就**如实拒绝**（调用方把"忙"转达给模型或用户），而不是排进队列等人放行。
 * 排队会让模型在派发工具里无限等，而手机上一次 run 本来就可能跑十几分钟。
 *
 * **上限为什么是 2**（设计 §八的取舍：手机 RAM / 电量 / 发热）：并行 5 个在桌面 agent 平台
 * 是常态，在这里会同时压三份模型流 + 各自的工具进程。2 的意义是"一个在等审批时另一个能跑"，
 * 不是吞吐量。
 *
 * **上限为什么是"每次实时读"而不是构造期固定**：上限来自用户设置（设置 → 通用 → 护栏与预算），
 * 旧实现让调用方在 `by lazy` 里 `ConcurrentRunGate(limit)` 冻结首读值——用户改完设置必须重启
 * 进程才生效，且首次读取若恰好发生在 DataStore 加载前，就会把默认值焊死。这里改成
 * [limitProvider]：`tryAcquire` / `canAcquire` 每次现读，设置一变**下一个 run 立即按新上限裁决**
 * （P0 设计明确要求"改设置立即生效"）。
 *
 * 无 Android 依赖、纯状态机，可 JVM 单测。
 */
class ConcurrentRunGate(
    /**
     * 当前上限的**实时**来源（`coerceAtLeast(1)` 在读取处统一做，provider 返回 0/负数不会把闸焊死）。
     */
    private val limitProvider: () -> Int = { DEFAULT_MAX_CONCURRENT_RUNS },
) {
    /**
     * 固定上限的旧形态（保留给单测与"确实不需要动态"的调用方）。
     *
     * 新代码请用主构造的 [limitProvider]，否则又回到了"首读冻结"。
     */
    constructor(limit: Int) : this({ limit })

    private val active = mutableSetOf<String>()

    /**
     * 尝试占一个位置。
     *
     * @return false = 已达上限（调用方应拒绝本次启动，而不是等待）
     */
    @Synchronized
    fun tryAcquire(runId: String): Boolean {
        if (runId in active) return true
        if (active.size >= effectiveLimit()) return false
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

    /**
     * 此刻生效的上限（UI 显示"2/3"、任务中心设备视图用）。
     *
     * 每次调用都现读 provider，所以它可能比上一次 acquire 时看到的值新——这正是本类的目的。
     */
    fun limit(): Int = effectiveLimit()

    /** 还能不能再开一个（UI 侧按钮态、派发工具的先手判断）。 */
    @Synchronized
    fun canAcquire(): Boolean = active.size < effectiveLimit()

    /**
     * 一致性快照（P0 `deviceLoad`，设计 §5.1/附录 D）：活跃数与上限在同一把锁下读取，
     * 避免 UI 先读 `activeCount()` 再读 `limit()` 时两者来自不同瞬间，显示成不可能的 "3/2"。
     */
    @Synchronized
    fun load(): Load = Load(activeCount = active.size, limit = effectiveLimit())

    /** [load] 的结果；[canAcquire] 与闸门自身的裁决同源（`activeCount < limit`）。 */
    data class Load(val activeCount: Int, val limit: Int) {
        val canAcquire: Boolean get() = activeCount < limit
    }

    /** provider 返回 0/负数时的兜底：闸至少允许一个 run，否则整个功能静默失效。 */
    private fun effectiveLimit(): Int = limitProvider().coerceAtLeast(1)

    companion object {
        /** 同时活跃 run 上限（设计 §八：手机端 2 已属激进）。 */
        const val DEFAULT_MAX_CONCURRENT_RUNS = 2
    }
}
