package app.androidrik.baselineprofile

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.StartupTimingMetric

/**
 * 统一的指标集合，所有基准类共用，避免各写一份。
 *
 * 只用稳定 API：
 * - [FrameTimingMetric]：逐帧 `frameDurationCpuMs` / `frameOverrunMs`（>0 即丢帧），
 *   由 benchmark 库按 P50/P90/P99 汇总，`perf/summarize.py` 再从 JSON 算出总丢帧率；
 * - [StartupTimingMetric]：TTID/TTFD。本仓 App 没有调用 `reportFullyDrawn()`（Compose `ReportDrawn` 也未接），
 *   所以 TTFD 恒缺，只读 TTID——这是后续工作，不是本管线能补的（需要改 App）。
 *
 * 刻意不用：
 * - `FrameTimingGfxInfoMetric`：`@ExperimentalMetricApi`，且模拟器软件渲染下数值无意义；
 * - `TraceSectionMetric`：需要 App 里埋 `Trace.beginSection`，本仓聊天/列表路径没有埋点，
 *   加埋点属 App 侧改动（后续工作）。
 */
internal object PerfMetrics {
    /** 只看帧：交互型 journey（滚动、发送、打开对话）用。 */
    fun frames(): List<Metric> = listOf(FrameTimingMetric())

    /** 启动 + 帧：启动型 journey 用。 */
    fun startup(): List<Metric> = listOf(StartupTimingMetric()) + frames()
}
