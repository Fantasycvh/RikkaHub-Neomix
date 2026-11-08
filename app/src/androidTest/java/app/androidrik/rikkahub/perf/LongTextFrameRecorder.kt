package app.androidrik.rikkahub.perf

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Test-only Window metrics; callbacks copy primitive values immediately on a dedicated thread. */
internal class LongTextFrameRecorder(private val window: Window) : AutoCloseable {
    private val thread = HandlerThread("longtext-frames").apply { start() }
    private val handler = Handler(thread.looper)
    private val frames = mutableListOf<JSONObject>()
    @Volatile private var measuring = false
    private var startNs = 0L
    private var endNs = 0L
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
        if (measuring && intended >= startNs) {
            val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1
            frames += JSONObject()
                .put("intended_vsync_ns", intended)
                .put("total_ns", metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                .put("layout_ns", metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION))
                .put("draw_ns", metrics.getMetric(FrameMetrics.DRAW_DURATION))
                .put("deadline_ns", deadline)
                .put("first_draw", metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L)
                .put("dropped_callbacks", dropped)
        }
    }

    init { window.addOnFrameMetricsAvailableListener(listener, handler) }

    fun start() {
        drain()
        frames.clear()
        startNs = System.nanoTime()
        measuring = true
    }

    fun stop(): JSONObject {
        endNs = System.nanoTime()
        // Allow the RenderThread's final asynchronous report to reach the listener.
        SystemClock.sleep(100)
        measuring = false
        drain()
        return JSONObject().put("window_start_ns", startNs).put("window_end_ns", endNs)
            .put("frames", JSONArray(frames.filter { it.getLong("intended_vsync_ns") <= endNs }))
    }

    private fun drain() {
        val drained = CountDownLatch(1)
        handler.post { drained.countDown() }
        check(drained.await(5, TimeUnit.SECONDS)) { "frame recorder did not drain" }
    }

    override fun close() {
        measuring = false
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
        thread.join(5_000)
    }
}
