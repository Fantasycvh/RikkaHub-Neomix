package app.androidrik.rikkahub.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class NavStatsData(
    val startedAt: Long = System.currentTimeMillis(),
    /** 路由类名 -> 进入次数（只记类名，不记实例参数，无会话 id 等 PII） */
    val pageCounts: Map<String, Long> = emptyMap(),
    /** 相邻两页转移 "From>To" -> 次数；back（pop）同样算一次进入 */
    val transitions: Map<String, Long> = emptyMap(),
)

/**
 * 页面进入统计（P0-4 埋点，docs/design/drafts/design-nav-stats.md）。
 *
 * 为什么不走 [app.androidrik.common.android.Logging]：那是排查用的环形日志（内存上限 100 条，
 * 与请求日志共享），页面事件会把请求日志挤掉；这里要的是**累计频次**，语义是计数器不是日志，
 * 故独立成 JSON 文件（filesDir/nav_stats.json），单线程后台合并写、失败静默。
 *
 * 纯本地、无上报通道；只记 `Screen::class.simpleName`（路由身份，不含 id 等实例参数）。
 */
object NavStats {
    private val json = Json
    private var file: File? = null
    private var data = NavStatsData()
    private var lastPage: String? = null
    private val writePending = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nav-stats").apply { isDaemon = true }
    }

    /** App 启动时调用一次；文件损坏或为空按全新统计处理（丢弃重建，等价于重置）。 */
    fun init(file: File) {
        synchronized(this) {
            this.file = file
            this.data = runCatching {
                file.takeIf { it.exists() && it.length() > 0 }
                    ?.let { json.decodeFromString<NavStatsData>(it.readText()) }
            }.getOrNull() ?: NavStatsData()
            this.lastPage = null
        }
    }

    /**
     * 记录一次页面进入。同名连续调用去重（nav3 手势补投/实例重建会把同一页连投多次，
     * 呼应 RouteActivity 空栈防线注释里的重复派发问题）；跨页往返会重新计数。
     */
    fun recordPageEnter(name: String?) {
        if (name.isNullOrBlank()) return
        val changed = synchronized(this) {
            if (name == lastPage) return
            val from = lastPage
            lastPage = name
            data = data.copy(
                pageCounts = data.pageCounts + (name to (data.pageCounts[name] ?: 0L) + 1L),
                transitions = if (from == null) data.transitions else data.transitions +
                    ("$from>$name" to (data.transitions["$from>$name"] ?: 0L) + 1L),
            )
            true
        }
        if (changed) scheduleFlush()
    }

    fun snapshot(): NavStatsData = synchronized(this) { data }

    fun reset() {
        synchronized(this) {
            data = NavStatsData()
            lastPage = null
        }
        scheduleFlush()
    }

    /** 同步落盘（测试与需要即时持久化的场景用）。 */
    fun flush() {
        val target = file ?: return
        val snapshot = synchronized(this) { data }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(json.encodeToString(snapshot))
        }
    }

    private fun scheduleFlush() {
        // 合并写：已有排队写未完成时只标记，写线程取最新快照，突发导航只产生一次 IO
        if (!writePending.compareAndSet(false, true)) return
        executor.execute {
            try {
                flush()
            } finally {
                writePending.set(false)
            }
        }
    }
}
