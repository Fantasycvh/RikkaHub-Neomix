package app.androidrik.rikkahub.data.perf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * 真机验证 [PerformanceSampler]：读 `/proc` 与 GPU sysfs 不崩、能采到数据，
 * 并把完整报告打到 logcat（`tag=PerfSamplerTest`），便于确认目标设备 GPU 入口是否可读。
 *
 * 注意：本测试会主动制造约 1.2 s 的 CPU 负载（SHA-256 哈希），只在手动指定 class 时运行。
 */
@RunWith(AndroidJUnit4::class)
class PerformanceSamplerInstrumentedTest {
    private val tag = "PerfSamplerTest"

    @Test
    fun samplerCollectsRealDeviceMetrics() {
        runBlocking {
            val sampler = PerformanceSampler(intervalMs = 200)
            sampler.start()
            try {
                // 制造 CPU 负载，确保能观察到非零线程/进程负载。
                val deadline = System.nanoTime() + 1_200_000_000L
                while (System.nanoTime() < deadline) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    repeat(200) { digest.update(ByteArray(4096)) }
                    digest.digest()
                }
            } finally {
                sampler.stop()
            }

            val state = sampler.state.value
            Log.i(tag, sampler.reportText())
            Log.i(tag, "gpuSource=${sampler.gpuSourceLabel} peakGpuPercent=${state.peakGpuPercent}")

            assertTrue("expected at least 3 samples, got ${state.samples}", state.samples >= 3)
            assertTrue("expected threads to be sampled", state.threads.isNotEmpty())
            assertTrue("expected non-zero process CPU, got ${state.peakProcessPercent}", state.peakProcessPercent > 0.0)
            val peakThread = state.peakSingleThread
            assertTrue("expected a peak thread", peakThread != null)
            assertTrue("expected non-zero single-thread load", peakThread!!.peakPercent > 0.0)
            assertTrue("expected system CPU sampled", state.peakSystemPercent >= 0.0)
        }
    }
}
