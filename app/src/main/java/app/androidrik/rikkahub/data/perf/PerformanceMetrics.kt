package app.androidrik.rikkahub.data.perf

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 轻量性能统计（CPU/线程/GPU），供 Debug Mode 的性能页在真机上排查卡顿/耗时问题。
 *
 * 采样模型借鉴 CapFrameX（https://github.com/CXWorld/CapFrameX）的「开始采集 → 逐指标采样 →
 * 停止后聚合统计」思路：CapFrameX 采集逐帧时间与 CPU/GPU 传感器数据，聚合出平均、峰值与低分位；
 * Android 上没有 PresentMon/Afterburner，这里把指标换成 `/proc` 与 GPU sysfs：
 * - 单线程负载：`/proc/self/task/<tid>/stat` 的 utime+stime 差分（占满一个核 = 100%）；
 * - 进程多核总负载：所有线程差分之和（多核并行时超过 100%，如 350% = 3.5 核）；
 * - 系统总负载：`/proc/stat` 全核 busy/total 差分；
 * - GPU 负载：Adreno/Mali/MTK/Exynos 常见 sysfs 入口（见 [GpuLoadSource]）。
 *
 * 本文件是纯 Kotlin（不依赖 Android），便于 JVM 单测；文件读取在
 * [PerformanceSampler] 里。
 */

/** Linux 的 `USER_HZ`；`/proc/stat` 的 jiffies 恒按 100 Hz 折算（与内核 CONFIG_HZ 无关）。 */
const val PROC_TICKS_PER_SECOND = 100

data class CpuTimes(
    val user: Long = 0L,
    val nice: Long = 0L,
    val system: Long = 0L,
    val idle: Long = 0L,
    val iowait: Long = 0L,
    val irq: Long = 0L,
    val softirq: Long = 0L,
    val steal: Long = 0L,
) {
    val total: Long get() = user + nice + system + idle + iowait + irq + softirq + steal
    val busy: Long get() = (total - idle - iowait).coerceAtLeast(0L)
}

data class ThreadTimes(val tid: Long, val comm: String, val utime: Long, val stime: Long) {
    val ticks: Long get() = utime + stime
}

/** `/proc` 文本解析（纯函数，可单测）。 */
object ProcStatParser {
    /** 解析 `/proc/stat`：返回 `cpu`（全核汇总）与 `cpu0`/`cpu1`…（逐核）。 */
    fun parseProcStat(text: String): Map<String, CpuTimes> {
        val result = LinkedHashMap<String, CpuTimes>()
        for (line in text.lineSequence()) {
            if (!line.startsWith("cpu")) continue
            val parts = line.split(' ').filter { it.isNotEmpty() }
            if (parts.size < 5) continue
            val key = parts[0]
            if (key != "cpu" && !key.removePrefix("cpu").all { it.isDigit() }) continue
            fun value(index: Int): Long = parts.getOrNull(index)?.toLongOrNull() ?: 0L
            result[key] = CpuTimes(
                user = value(1),
                nice = value(2),
                system = value(3),
                idle = value(4),
                iowait = value(5),
                irq = value(6),
                softirq = value(7),
                steal = value(8),
            )
        }
        return result
    }

    /**
     * 解析 `/proc/<pid>/task/<tid>/stat`。
     *
     * `comm` 可能含空格甚至括号，因此用**最后一个** `)` 定界（与 procps 的做法一致）；
     * `comm` 之后从 `state` 起数，utime/stime 分别是第 14/15 个字段（下标 11/12）。
     */
    fun parseThreadStat(text: String): ThreadTimes? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val tid = text.substring(0, open).trim().toLongOrNull() ?: return null
        val comm = text.substring(open + 1, close)
        val rest = text.substring(close + 1).trim().split(' ').filter { it.isNotEmpty() }
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return ThreadTimes(tid, comm, utime, stime)
    }

    /** 把某个线程/进程在 `intervalMs` 内消耗的 jiffies 折算成「占单核百分比」。 */
    fun ticksToPercent(ticks: Long, intervalMs: Long): Double {
        if (intervalMs <= 0L) return 0.0
        return ticks * 100.0 * 1000.0 / (intervalMs * PROC_TICKS_PER_SECOND)
    }
}

/** GPU sysfs 文本解析（纯函数）。 */
object GpuSysfsParser {
    /**
     * 解析 `busy total` 形式的累计计数（Adreno `gpubusy`、部分 devfreq `load`）。
     * 返回 null 表示格式不符（此时调用方再尝试百分数格式）。
     */
    fun parseBusyPair(text: String): Pair<Long, Long>? {
        val parts = text.trim().split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val busy = parts[0].toLongOrNull() ?: return null
        val total = parts[1].toLongOrNull() ?: return null
        // Adreno `gpubusy` 在 GPU 完全沉睡时读作 "0 0"：这是合法的「空闲」读数，不是格式错误。
        if (busy < 0 || total < 0) return null
        if (total == 0L && busy > 0L) return null
        return busy to total
    }

    /** 解析 `12` / `12 %` / `12.5%` 形式的负载百分数；超出 0..100 视为非法。 */
    fun parsePercent(text: String): Double? {
        val raw = text.trim().removeSuffix("%").trim()
        val value = raw.toDoubleOrNull() ?: return null
        if (value < 0.0 || value > 100.0) return null
        return value
    }
}

/** 一轮采样里某个线程在间隔内消耗的 jiffies。 */
data class ThreadSample(val tid: Long, val comm: String, val ticks: Long)

/** 一轮采样里某个核的 busy/total 差分。 */
data class CoreSample(val index: Int, val busyTicks: Long, val totalTicks: Long)

/** 一轮采样（均为相对上次采样的差分）。 */
data class RawPerformanceSample(
    val intervalMs: Long,
    val threads: List<ThreadSample>,
    val systemBusyTicks: Long,
    val systemTotalTicks: Long,
    val cores: List<CoreSample>,
    val gpuPercent: Double?,
)

data class ThreadLoad(
    val tid: Long,
    val name: String,
    val currentPercent: Double,
    val peakPercent: Double,
)

/** UI/报告使用的聚合结果；`running` 只影响展示，不影响数据。 */
data class PerformanceState(
    val running: Boolean = false,
    val samples: Int = 0,
    val elapsedMs: Long = 0L,
    val processPercent: Double = 0.0,
    val systemPercent: Double = 0.0,
    val gpuPercent: Double? = null,
    val corePercents: List<Double> = emptyList(),
    val peakProcessPercent: Double = 0.0,
    val avgProcessPercent: Double = 0.0,
    val peakSystemPercent: Double = 0.0,
    val avgSystemPercent: Double = 0.0,
    val peakGpuPercent: Double? = null,
    val avgGpuPercent: Double? = null,
    val peakSingleThread: ThreadLoad? = null,
    val threads: List<ThreadLoad> = emptyList(),
    val corePeakPercents: List<Double> = emptyList(),
    /** `/proc/stat` 是否可读；Android 14+ 部分机型 SELinux 禁止 App 读它 → 系统/逐核负载不可用。 */
    val systemCpuAvailable: Boolean = false,
)

/**
 * 把逐轮差分累积成「峰值 / 均值 / 每线程峰值」。
 *
 * 均值按采样间隔加权（间隔可因系统调度抖动略有差异）；单线程峰值天然取历史最大。
 */
class PerformanceAccumulator {
    private var samples = 0
    private var elapsedMs = 0L

    private var peakProcess = 0.0
    private var weightedProcess = 0.0
    private var weightProcess = 0.0

    private var peakSystem = 0.0
    private var weightedSystem = 0.0
    private var weightSystem = 0.0

    private var peakGpu: Double? = null
    private var weightedGpu = 0.0
    private var weightGpu = 0.0

    private val threads = LinkedHashMap<Long, MutableThreadLoad>()
    private var corePeaks = mutableListOf<Double>()

    private var sawSystemTicks = false
    private var lastProcessPercent = 0.0
    private var lastSystemPercent = 0.0
    private var lastGpuPercent: Double? = null
    private var lastCorePercents: List<Double> = emptyList()

    private data class MutableThreadLoad(val tid: Long, var name: String) {
        var current = 0.0
        var peak = 0.0
    }

    fun reset() {
        samples = 0
        elapsedMs = 0L
        peakProcess = 0.0
        weightedProcess = 0.0
        weightProcess = 0.0
        peakSystem = 0.0
        weightedSystem = 0.0
        weightSystem = 0.0
        peakGpu = null
        weightedGpu = 0.0
        weightGpu = 0.0
        threads.clear()
        corePeaks = mutableListOf()
        sawSystemTicks = false
        lastProcessPercent = 0.0
        lastSystemPercent = 0.0
        lastGpuPercent = null
        lastCorePercents = emptyList()
    }

    fun add(sample: RawPerformanceSample): PerformanceState {
        require(sample.intervalMs > 0L) { "intervalMs must be positive" }
        samples += 1
        elapsedMs += sample.intervalMs
        val weight = sample.intervalMs.toDouble()

        // 线程：本轮没出现的线程 current 归零，但保留历史峰值。
        threads.values.forEach { it.current = 0.0 }
        var processTicks = 0L
        for (thread in sample.threads) {
            if (thread.ticks <= 0L) continue
            processTicks += thread.ticks
            val percent = ProcStatParser.ticksToPercent(thread.ticks, sample.intervalMs)
            val entry = threads.getOrPut(thread.tid) { MutableThreadLoad(thread.tid, thread.comm) }
            entry.name = thread.comm
            entry.current = percent
            entry.peak = max(entry.peak, percent)
        }
        val processPercent = ProcStatParser.ticksToPercent(processTicks, sample.intervalMs)
        lastProcessPercent = processPercent
        peakProcess = max(peakProcess, processPercent)
        weightedProcess += processPercent * weight
        weightProcess += weight

        val systemPercent = if (sample.systemTotalTicks > 0L) {
            sample.systemBusyTicks * 100.0 / sample.systemTotalTicks
        } else 0.0
        lastSystemPercent = systemPercent
        if (sample.systemTotalTicks > 0L) sawSystemTicks = true
        peakSystem = max(peakSystem, systemPercent)
        weightedSystem += systemPercent * weight
        weightSystem += weight

        sample.gpuPercent?.let { gpu ->
            lastGpuPercent = gpu
            peakGpu = max(peakGpu ?: 0.0, gpu)
            weightedGpu += gpu * weight
            weightGpu += weight
        }

        while (corePeaks.size < sample.cores.size) corePeaks.add(0.0)
        lastCorePercents = sample.cores.map { core ->
            if (core.totalTicks > 0L) core.busyTicks * 100.0 / core.totalTicks else 0.0
        }
        sample.cores.forEachIndexed { position, core ->
            val percent = if (core.totalTicks > 0L) core.busyTicks * 100.0 / core.totalTicks else 0.0
            corePeaks[position] = max(corePeaks[position], percent)
        }
        return snapshot(running = true)
    }

    fun snapshot(running: Boolean): PerformanceState {
        val threadLoads = threads.values
            .map { ThreadLoad(it.tid, it.name, it.current, it.peak) }
            .sortedWith(compareByDescending<ThreadLoad> { it.peakPercent }.thenByDescending { it.currentPercent })
        val peakSingleThread = threadLoads.firstOrNull { it.peakPercent > 0.0 }
        return PerformanceState(
            running = running,
            samples = samples,
            elapsedMs = elapsedMs,
            processPercent = lastProcessPercent,
            systemPercent = lastSystemPercent,
            gpuPercent = lastGpuPercent,
            corePercents = lastCorePercents,
            peakProcessPercent = peakProcess,
            avgProcessPercent = if (weightProcess > 0.0) weightedProcess / weightProcess else 0.0,
            peakSystemPercent = peakSystem,
            avgSystemPercent = if (weightSystem > 0.0) weightedSystem / weightSystem else 0.0,
            peakGpuPercent = peakGpu,
            avgGpuPercent = if (weightGpu > 0.0) weightedGpu / weightGpu else null,
            peakSingleThread = peakSingleThread,
            threads = threadLoads,
            corePeakPercents = corePeaks.toList(),
            systemCpuAvailable = sawSystemTicks,
        )
    }

}

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val cores: Int,
)

/** 报告文本：Debug 页「复制报告」直接用；也便于贴到 issue/聊天里排查。 */
object PerformanceReportFormatter {
    fun format(
        state: PerformanceState,
        device: DeviceInfo,
        gpuSource: String?,
        timestamp: String,
    ): String = buildString {
        appendLine("=== RikkaHub 性能采样报告 ===")
        if (timestamp.isNotBlank()) appendLine("时间: $timestamp")
        appendLine("设备: ${device.manufacturer} ${device.model} / Android ${device.androidRelease} / SDK ${device.sdk} / ${device.cores} 核")
        appendLine("采样: ${formatMs(state.elapsedMs)} / ${state.samples} 次")
        appendLine()
        val peakThread = state.peakSingleThread
        appendLine("单线程峰值(单核): ${percent(peakThread?.peakPercent ?: 0.0)}${peakThread?.let { " (${it.name} tid=${it.tid})" } ?: ""}")
        appendLine("进程多核总负载峰值: ${percent(state.peakProcessPercent)}  均值: ${percent(state.avgProcessPercent)}")
        if (state.systemCpuAvailable) {
            appendLine("系统多核总负载峰值: ${percent(state.peakSystemPercent)}  均值: ${percent(state.avgSystemPercent)}")
        } else {
            appendLine("系统多核总负载: N/A（/proc/stat 被系统限制，App 读不到）")
        }
        if (state.peakGpuPercent != null) {
            appendLine("GPU 峰值: ${percent(state.peakGpuPercent)}  均值: ${percent(state.avgGpuPercent ?: 0.0)}")
        } else if (gpuSource != null) {
            appendLine("GPU: N/A（数据源存在，但采样期间无增量/读数为空，通常表示 GPU 空闲）")
        } else {
            appendLine("GPU: N/A（未找到可读的 GPU sysfs 入口，或系统限制）")
        }
        appendLine("GPU 数据源: ${gpuSource ?: "无"}")
        if (state.corePeakPercents.isNotEmpty() && state.systemCpuAvailable) {
            appendLine("每核峰值: ${state.corePeakPercents.mapIndexed { index, value -> "cpu$index ${percent(value)}" }.joinToString("  ")}")
        }
        appendLine()
        appendLine("线程 Top（按峰值）:")
        state.threads.take(MAX_REPORT_THREADS).forEach { thread ->
            appendLine("  ${percent(thread.peakPercent).padStart(7)} 当前 ${percent(thread.currentPercent).padStart(7)}  ${thread.name} (tid=${thread.tid})")
        }
        if (state.threads.isEmpty()) appendLine("  (无数据)")
        appendLine()
        appendLine("说明: 单核占满 = 100%；进程 CPU 可超过 100%（如 350% = 3.5 核）。")
        appendLine("      数据来自 /proc/stat、/proc/self/task/<tid>/stat 与 GPU sysfs，不含 App 自身开销校正。")
    }

    private fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    private fun formatMs(ms: Long): String = String.format(Locale.US, "%.2f s", ms / 1000.0)

    /** 报告时间戳（local time），采样器停止时调用。 */
    fun defaultTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private const val MAX_REPORT_THREADS = 20
}
