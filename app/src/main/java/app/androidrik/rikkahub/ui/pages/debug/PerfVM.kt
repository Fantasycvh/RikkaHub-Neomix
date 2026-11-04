package app.androidrik.rikkahub.ui.pages.debug

import androidx.lifecycle.ViewModel
import app.androidrik.rikkahub.data.perf.PerformanceSampler
import app.androidrik.rikkahub.data.perf.PerformanceState
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug Mode「性能」页的 ViewModel：持有采样器，页面销毁时停止采样。
 *
 * 性能页是排查工具（例如「FTS 重建 10 s 卡在哪」），采样器本身开销很低（每 500 ms 读一次
 * `/proc` 与 GPU sysfs），但只在用户主动「开始」后运行。
 */
class PerfVM(
    private val sampler: PerformanceSampler,
) : ViewModel() {

    val state: StateFlow<PerformanceState> = sampler.state

    val gpuSourceLabel: String? get() = sampler.gpuSourceLabel

    fun start() = sampler.start()

    fun stop() = sampler.stop()

    fun reset() = sampler.reset()

    fun reportText(): String = sampler.reportText()
}
