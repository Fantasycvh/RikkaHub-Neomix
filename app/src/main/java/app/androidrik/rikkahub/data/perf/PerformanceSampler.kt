package app.androidrik.rikkahub.data.perf

import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

/**
 * 设备性能采样器：按固定间隔读取 `/proc` 与 GPU sysfs，交给 [PerformanceAccumulator] 聚合。
 *
 * 数据源（Android 上无需 root，普通 App 即可读）：
 * - `/proc/stat`：全核与逐核累计 jiffies；
 * - `/proc/self/task/<tid>/stat`：每个线程的 utime+stime（差分 → 单核百分比）；
 * - GPU：[GpuLoadSource] 依次尝试 Adreno `kgsl-3d0/gpubusy`、Mali `misc/mali0/device/utilization`、
 *   MediaTek `kernel/gpu/gpu_busy`、devfreq 目录的 `load`/`utilization` 等常见入口。
 *
 * 采样间隔默认 500 ms（[DEFAULT_INTERVAL_MS]）；采样器自身线程也在统计里（开销很小，见报告说明）。
 */
class PerformanceSampler(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val gpuLoadSource: GpuLoadSource = GpuLoadSource(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val accumulator = PerformanceAccumulator()
    private val _state = MutableStateFlow(PerformanceState())
    val state: StateFlow<PerformanceState> = _state.asStateFlow()

    /** 采样器是 App 进程级单例：自带作用域，离开 Debug 页/切到其他界面也能继续采样。 */
    private val samplingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** GPU 数据源说明（null = 当前设备/系统没有可读入口）。 */
    val gpuSourceLabel: String? get() = gpuLoadSource.sourceLabel

    fun start() {
        if (job?.isActive == true) return
        reset()
        job = samplingScope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = accumulator.snapshot(running = false)
    }

    fun reset() {
        stop()
        accumulator.reset()
        gpuLoadSource.reset()
        _state.value = PerformanceState()
    }

    fun reportText(): String = PerformanceReportFormatter.format(
        state = _state.value,
        device = deviceInfo(),
        gpuSource = gpuSourceLabel,
        timestamp = PerformanceReportFormatter.defaultTimestamp(),
    )

    private suspend fun runLoop() {
        var previous = readCounters()
        var previousAt = clock()
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            val nowAt = clock()
            val now = readCounters()
            val elapsed = (nowAt - previousAt).coerceAtLeast(1L)
            accumulator.add(buildSample(previous, now, elapsed))
            _state.value = accumulator.snapshot(running = true)
            previous = now
            previousAt = nowAt
        }
    }

    private fun buildSample(previous: Counters, current: Counters, intervalMs: Long): RawPerformanceSample {
        val threadSamples = ArrayList<ThreadSample>(current.threads.size)
        for ((tid, times) in current.threads) {
            val before = previous.threads[tid]?.ticks ?: 0L
            val delta = max(0L, times.ticks - before)
            if (delta > 0L) threadSamples += ThreadSample(tid, times.comm, delta)
        }

        val beforeTotal = previous.system["cpu"]
        val nowTotal = current.system["cpu"]
        val systemBusy = if (beforeTotal != null && nowTotal != null) {
            max(0L, nowTotal.busy - beforeTotal.busy)
        } else 0L
        val systemTotal = if (beforeTotal != null && nowTotal != null) {
            max(0L, nowTotal.total - beforeTotal.total)
        } else 0L

        val cores = current.coreOrder.mapNotNull { key ->
            val before = previous.system[key]
            val now = current.system[key]
            if (before == null || now == null) return@mapNotNull null
            CoreSample(
                index = key.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null,
                busyTicks = max(0L, now.busy - before.busy),
                totalTicks = max(0L, now.total - before.total),
            )
        }

        return RawPerformanceSample(
            intervalMs = intervalMs,
            threads = threadSamples,
            systemBusyTicks = systemBusy,
            systemTotalTicks = systemTotal,
            cores = cores,
            gpuPercent = gpuLoadSource.readPercent(),
        )
    }

    private data class Counters(
        val system: Map<String, CpuTimes>,
        val coreOrder: List<String>,
        val threads: Map<Long, ThreadTimes>,
    )

    private fun readCounters(): Counters {
        val system = readProcStat()
        val coreOrder = system.keys.filter { it != "cpu" }.sortedBy { it.removePrefix("cpu").toIntOrNull() ?: Int.MAX_VALUE }
        return Counters(system, coreOrder, readThreadTimes())
    }

    private var procStatFailureLogged = false

    private fun readProcStat(): Map<String, CpuTimes> {
        val text = runCatching { File("/proc/stat").readText() }.getOrNull()
        if (text == null) {
            if (!procStatFailureLogged) {
                procStatFailureLogged = true
                Log.w(TAG, "cannot read /proc/stat: system-wide CPU metrics unavailable (SELinux/proc restriction)")
            }
            return emptyMap()
        }
        val parsed = ProcStatParser.parseProcStat(text)
        if (parsed.isEmpty() && !procStatFailureLogged) {
            procStatFailureLogged = true
            Log.w(TAG, "cannot parse /proc/stat: ${text.take(120)}")
        }
        return parsed
    }

    private fun readThreadTimes(): Map<Long, ThreadTimes> {
        val dir = File("/proc/self/task")
        val names = runCatching { dir.list() }.getOrNull() ?: return emptyMap()
        val result = HashMap<Long, ThreadTimes>(names.size * 2)
        for (name in names) {
            val tid = name.toLongOrNull() ?: continue
            val text = runCatching { File(dir, "$name/stat").readText() }.getOrNull() ?: continue
            val parsed = ProcStatParser.parseThreadStat(text) ?: continue
            result[tid] = parsed
        }
        return result
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 500L
        private const val TAG = "PerformanceSampler"
    }
}

/**
 * GPU 负载数据源：多厂商 sysfs 入口探测。
 *
 * Adreno（`kgsl-3d0/gpubusy`）是**累计 busy/total 计数**，必须像 CPU 一样做差分；
 * Mali/MTK 多半直接给百分比。所有探测失败时返回 null（UI 显示 N/A），不抛异常。
 */
class GpuLoadSource(
    private val candidates: List<Candidate> = defaultCandidates(),
) {
    data class Candidate(val path: String, val kind: Kind)

    enum class Kind { PAIR, PERCENT, AUTO }

    private var resolved: Candidate? = null
    private var resolvedKind: Kind = Kind.AUTO
    private var previousBusy = -1L
    private var previousTotal = -1L
    private var logged = false

    /** 已识别的数据源（路径 + 解析方式）；未识别为 null。 */
    val sourceLabel: String?
        get() = resolved?.let { "${it.path} (${resolvedKind.name.lowercase()})" }

    fun reset() {
        previousBusy = -1L
        previousTotal = -1L
    }

    fun readPercent(): Double? {
        val candidate = resolved ?: resolve() ?: return null
        val text = runCatching { File(candidate.path).readText() }.getOrNull() ?: return null
        return when (resolvedKind) {
            Kind.PERCENT -> GpuSysfsParser.parsePercent(text)
            Kind.PAIR -> {
                val pair = GpuSysfsParser.parseBusyPair(text) ?: return null
                var (busy, total) = pair
                // GPU 完全沉睡时 Adreno 读作 "0 0"：直接按 0% 计，同时重置基线。
                if (busy == 0L && total == 0L) {
                    previousBusy = 0L
                    previousTotal = 0L
                    return 0.0
                }
                // 第一次读 / reset 后 / 计数器回绕：只建立基线，本轮不出数。
                if (previousBusy < 0L || previousTotal < 0L || total < previousTotal) {
                    previousBusy = busy
                    previousTotal = total
                    return null
                }
                val busyDelta = busy - previousBusy
                val totalDelta = total - previousTotal
                previousBusy = busy
                previousTotal = total
                when {
                    busyDelta < 0L || totalDelta < 0L -> null
                    // GPU 空闲时计数器可能完全不前进：按 0% 计，而不是当作「读不到」。
                    totalDelta == 0L -> if (busyDelta == 0L) 0.0 else null
                    else -> (busyDelta * 100.0 / totalDelta).coerceIn(0.0, 100.0)
                }
            }
            Kind.AUTO -> null
        }
    }

    private fun resolve(): Candidate? {
        if (resolved != null) return resolved
        for (candidate in candidates) {
            val text = runCatching { File(candidate.path).readText() }.getOrNull() ?: continue
            val pair = if (candidate.kind == Kind.PERCENT) null else GpuSysfsParser.parseBusyPair(text)
            val percent = if (candidate.kind == Kind.PAIR) null else GpuSysfsParser.parsePercent(text)
            if (pair != null) {
                resolved = candidate
                resolvedKind = Kind.PAIR
                previousBusy = pair.first
                previousTotal = pair.second
                logResolution(candidate, Kind.PAIR)
                return candidate
            }
            if (percent != null) {
                resolved = candidate
                resolvedKind = Kind.PERCENT
                logResolution(candidate, Kind.PERCENT)
                return candidate
            }
        }
        if (!logged) {
            logged = true
            Log.i(TAG, "No readable GPU load source; GPU metrics disabled")
        }
        return null
    }

    private fun logResolution(candidate: Candidate, kind: Kind) {
        if (logged) return
        logged = true
        Log.i(TAG, "GPU load source: ${candidate.path} ($kind)")
    }

    companion object {
        private const val TAG = "GpuLoadSource"

        /** 常见入口，按命中概率排序；devfreq 目录会额外扫描一次。 */
        fun defaultCandidates(): List<Candidate> {
            val result = mutableListOf(
                Candidate("/sys/class/kgsl/kgsl-3d0/gpubusy", Kind.PAIR),
                Candidate("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", Kind.PERCENT),
                Candidate("/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load", Kind.PERCENT),
                Candidate("/sys/class/misc/mali0/device/utilization", Kind.PERCENT),
                Candidate("/sys/class/misc/mali0/device/gpu_busy", Kind.PERCENT),
                Candidate("/sys/kernel/gpu/gpu_busy", Kind.PERCENT),
                Candidate("/sys/kernel/gpu/gpu_utilization", Kind.PERCENT),
                Candidate("/sys/kernel/gpu/load", Kind.PERCENT),
            )
            runCatching {
                File("/sys/class/devfreq").listFiles()?.forEach { dir ->
                    val name = dir.name.lowercase()
                    if (name.contains("gpu") || name.contains("mali") || name.contains("kgsl") || name.contains("adreno")) {
                        result += Candidate("${dir.path}/load", Kind.AUTO)
                        result += Candidate("${dir.path}/utilization", Kind.PERCENT)
                    }
                }
            }
            return result
        }
    }
}

/** 当前设备信息（报告头用）。 */
fun deviceInfo(): DeviceInfo = DeviceInfo(
    manufacturer = Build.MANUFACTURER ?: "unknown",
    model = Build.MODEL ?: "unknown",
    androidRelease = Build.VERSION.RELEASE ?: "?",
    sdk = Build.VERSION.SDK_INT,
    cores = Runtime.getRuntime().availableProcessors(),
)
