package app.androidrik.rikkahub.data.task

import java.util.concurrent.atomic.AtomicLong

/**
 * **本次进程启动时刻**（墙钟毫秒），供启动对账判断「哪些 run 是上一个进程留下的」。
 *
 * 为什么需要它：`agent_run` 行里只有 `created_at` / `started_at` / `finished_at`，
 * 没有任何一列写「产生这行的进程是谁」。没有进程启动时刻，就只能猜「running 但很久没动」
 * ——设备时间被改、Doze 冻结、长任务都会误判。有了这个时刻，判据变成确定性的
 * 「创建于本次进程启动之前」。
 *
 * 为什么用 [AtomicLong] 而不是简单 `@Volatile var`：`onCreate` 与后台对账协程可能
 * 不同线程先后来读；`compareAndSet` 保证**只有第一次 mark 生效**（重试/多入口不会把
 * 启动时刻改晚，改晚会让本次进程早期创建的 run 被误判成遗留）。
 *
 * 无 Android 依赖，可 JVM 单测。
 */
object ProcessStartClock {

    private val startedAtMillis = AtomicLong(0L)

    /** 在 `Application.onCreate` 尽早调用；重复调用只有第一次生效。 */
    fun markStarted(now: Long = System.currentTimeMillis()) {
        startedAtMillis.compareAndSet(0L, now)
    }

    /** 本次进程启动时刻；未 mark（如 JVM 单测）时为 0，调用方应视作「无法对账」。 */
    val startedAt: Long get() = startedAtMillis.get()

    /** 仅供单测：重置到未 mark 状态。 */
    internal fun resetForTest() {
        startedAtMillis.set(0L)
    }
}
