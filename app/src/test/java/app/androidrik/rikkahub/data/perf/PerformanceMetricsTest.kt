package app.androidrik.rikkahub.data.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceMetricsTest {
    // ---- /proc/stat ----

    @Test
    fun parseProcStatReadsAggregateAndPerCore() {
        val text = """
            cpu  100 0 50 800 20 0 10 0 0 0
            cpu0 40 0 10 400 10 0 5 0 0 0
            cpu1 60 0 40 400 10 0 5 0 0 0
            intr 123 456
            ctxt 7890
        """.trimIndent()
        val parsed = ProcStatParser.parseProcStat(text)
        assertEquals(setOf("cpu", "cpu0", "cpu1"), parsed.keys)
        val total = parsed.getValue("cpu")
        assertEquals(100L, total.user)
        assertEquals(800L, total.idle)
        assertEquals(980L, total.total) // 100+0+50+800+20+0+10+0
        assertEquals(160L, total.busy) // total - idle - iowait
        assertEquals(400L, parsed.getValue("cpu0").idle)
        assertEquals("cpu1" to 400L, "cpu1" to parsed.getValue("cpu1").idle)
    }

    @Test
    fun parseProcStatIgnoresMalformedLines() {
        val parsed = ProcStatParser.parseProcStat("cpu 1 2\ncpuX 1 2 3 4 5\n")
        assertTrue(parsed.isEmpty())
    }

    // ---- /proc/<pid>/task/<tid>/stat ----

    @Test
    fun parseThreadStatReadsUtimeStime() {
        // comm 之后：state(0) .. cmajflt(10), utime(11), stime(12)
        val line = "1234 (RenderThread) S 1 2 3 4 5 6 7 8 9 10 111 222 0 0"
        val parsed = ProcStatParser.parseThreadStat(line)
        assertNotNull(parsed)
        assertEquals(1234L, parsed!!.tid)
        assertEquals("RenderThread", parsed.comm)
        assertEquals(111L, parsed.utime)
        assertEquals(222L, parsed.stime)
        assertEquals(333L, parsed.ticks)
    }

    @Test
    fun parseThreadStatHandlesSpacesAndParenthesesInComm() {
        val line = "42 (my thread (x)) S 1 2 3 4 5 6 7 8 9 10 5 7"
        val parsed = ProcStatParser.parseThreadStat(line)
        assertNotNull(parsed)
        assertEquals("my thread (x)", parsed!!.comm)
        assertEquals(12L, parsed.ticks)
    }

    @Test
    fun parseThreadStatRejectsMalformed() {
        assertNull(ProcStatParser.parseThreadStat("garbage"))
        assertNull(ProcStatParser.parseThreadStat("12 (x) S 1 2"))
    }

    // ---- 百分比换算 ----

    @Test
    fun ticksToPercentUsesUserHz() {
        assertEquals(100.0, ProcStatParser.ticksToPercent(50, 500), 1e-9)
        assertEquals(50.0, ProcStatParser.ticksToPercent(25, 500), 1e-9)
        assertEquals(0.0, ProcStatParser.ticksToPercent(10, 0), 1e-9)
        assertEquals(800.0, ProcStatParser.ticksToPercent(400, 500), 1e-9) // 4.0 核
    }

    // ---- GPU sysfs ----

    @Test
    fun parseGpuBusyPair() {
        assertEquals(100L to 200L, GpuSysfsParser.parseBusyPair("100 200"))
        assertEquals(0L to 1L, GpuSysfsParser.parseBusyPair("0\t1\n"))
        assertNull(GpuSysfsParser.parseBusyPair("100"))
        assertEquals(0L to 0L, GpuSysfsParser.parseBusyPair("0 0")) // Adreno 沉睡读数合法
        assertNull(GpuSysfsParser.parseBusyPair("5 0"))
        assertNull(GpuSysfsParser.parseBusyPair("x 1"))
    }

    @Test
    fun parseGpuPercent() {
        assertEquals(12.0, GpuSysfsParser.parsePercent("12 %")!!, 1e-9)
        assertEquals(12.5, GpuSysfsParser.parsePercent("12.5%")!!, 1e-9)
        assertEquals(0.0, GpuSysfsParser.parsePercent("0")!!, 1e-9)
        assertNull(GpuSysfsParser.parsePercent("150"))
        assertNull(GpuSysfsParser.parsePercent("-1"))
        assertNull(GpuSysfsParser.parsePercent("abc"))
    }

    // ---- 聚合 ----

    @Test
    fun accumulatorComputesPeaksAndAverages() {
        val accumulator = PerformanceAccumulator()
        accumulator.add(
            RawPerformanceSample(
                intervalMs = 500,
                threads = listOf(
                    ThreadSample(1, "worker-a", 50), // 100%
                    ThreadSample(2, "worker-b", 10), // 20%
                ),
                systemBusyTicks = 300,
                systemTotalTicks = 1000, // 30%
                cores = listOf(CoreSample(0, 100, 200), CoreSample(1, 200, 800)),
                gpuPercent = 40.0,
            ),
        )
        val first = accumulator.snapshot(running = true)
        assertEquals(120.0, first.processPercent, 1e-9)
        assertEquals(120.0, first.peakProcessPercent, 1e-9)
        assertEquals("worker-a", first.peakSingleThread?.name)
        assertEquals(100.0, first.peakSingleThread!!.peakPercent, 1e-9)
        assertEquals(30.0, first.systemPercent, 1e-9)
        assertEquals(40.0, first.peakGpuPercent!!, 1e-9)
        assertEquals(listOf(50.0, 25.0), first.corePercents)

        accumulator.add(
            RawPerformanceSample(
                intervalMs = 500,
                threads = listOf(ThreadSample(2, "worker-b", 50)), // 100%，worker-a 本轮消失
                systemBusyTicks = 500,
                systemTotalTicks = 1000, // 50%
                cores = listOf(CoreSample(0, 50, 200), CoreSample(1, 400, 800)),
                gpuPercent = 60.0,
            ),
        )
        val second = accumulator.snapshot(running = false)
        assertEquals(100.0, second.processPercent, 1e-9)
        assertEquals(120.0, second.peakProcessPercent, 1e-9) // 峰值保留
        assertEquals(110.0, second.avgProcessPercent, 1e-9)
        assertTrue(second.systemCpuAvailable)
        assertEquals(50.0, second.peakSystemPercent, 1e-9)
        assertEquals(40.0, second.avgSystemPercent, 1e-9)
        assertEquals(60.0, second.peakGpuPercent!!, 1e-9)
        assertEquals(50.0, second.avgGpuPercent!!, 1e-9)
        assertEquals(listOf(50.0, 50.0), second.corePeakPercents) // 25% vs 50%
        val workerA = second.threads.first { it.tid == 1L }
        assertEquals(100.0, workerA.peakPercent, 1e-9) // 峰值仍保留
        assertEquals(0.0, workerA.currentPercent, 1e-9) // 本轮没跑 → 当前 0
    }

    @Test
    fun accumulatorResetClearsState() {
        val accumulator = PerformanceAccumulator()
        accumulator.add(
            RawPerformanceSample(
                500,
                listOf(ThreadSample(1, "a", 50)),
                100,
                200,
                listOf(CoreSample(0, 100, 200)),
                12.0,
            ),
        )
        accumulator.reset()
        val state = accumulator.snapshot(running = false)
        assertEquals(0, state.samples)
        assertEquals(0L, state.elapsedMs)
        assertTrue(state.threads.isEmpty())
        assertEquals(0.0, state.peakProcessPercent, 1e-9)
        assertNull(state.peakGpuPercent)
        assertNull(state.peakSingleThread)
    }

    // ---- 报告 ----

    @Test
    fun reportContainsSummaryAndThreads() {
        val accumulator = PerformanceAccumulator()
        accumulator.add(
            RawPerformanceSample(
                500,
                listOf(ThreadSample(7, "fts-worker", 50)),
                500,
                1000,
                listOf(CoreSample(0, 50, 100)),
                12.0,
            ),
        )
        val text = PerformanceReportFormatter.format(
            state = accumulator.snapshot(running = false),
            device = DeviceInfo("TestBrand", "TestModel", "15", 35, 8),
            gpuSource = "/sys/class/kgsl/kgsl-3d0/gpubusy (pair)",
            timestamp = "2026-11-04 20:00:00",
        )
        assertTrue(text.contains("TestBrand TestModel / Android 15 / SDK 35 / 8 核"))
        assertTrue(text.contains("单线程峰值(单核): 100.0% (fts-worker tid=7)"))
        assertTrue(text.contains("进程多核总负载峰值: 100.0%"))
        assertTrue(text.contains("系统多核总负载峰值: 50.0%"))
        assertTrue(text.contains("GPU 峰值: 12.0%"))
        assertTrue(text.contains("每核峰值: cpu0 50.0%"))
        assertTrue(text.contains("fts-worker (tid=7)"))
    }

    @Test
    fun reportShowsNaWhenNoGpu() {
        val text = PerformanceReportFormatter.format(
            state = PerformanceState(samples = 3),
            device = DeviceInfo("TestBrand", "TestModel", "15", 35, 8),
            gpuSource = null,
            timestamp = "",
        )
        assertTrue(text.contains("系统多核总负载: N/A（/proc/stat 被系统限制，App 读不到）"))
        assertTrue(text.contains("GPU: N/A（未找到可读的 GPU sysfs 入口，或系统限制）"))
    }
}
