package app.androidrik.rikkahub.ui.theme

import android.content.Intent
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.ui.components.ui.AppButton
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Debug-device regression budget, real frame clock. Not a release Macrobenchmark or an FPS promise. */
class SkinPerformanceAcceptanceTest {
    @Test fun repeatedSkinSwitchAndMaterialScrollStayWithinDebugBudget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val handler = HandlerThread("SkinFrameMetrics").apply { start() }
        val frames = Collections.synchronizedList(mutableListOf<Long>())
        val dropped = AtomicInteger()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION))
            dropped.addAndGet(lost)
        }
        val rows = JSONArray()
        var skin by mutableStateOf(AppSkin())
        var run by mutableIntStateOf(0)
        var dark by mutableStateOf(false)
        var finished = CountDownLatch(1)
        val skins = listOf(AppSkin(), builtInAppSkins.last().skin,
            AppSkin(typography = AppSkinTypography(sizeRatio = 1.5f), cards = AppSkinCards(tool = AppSkinPanel(
                background = ChatBubble(stack = BubbleStack(layers = listOf(
                    BubbleLayer(kind = BubbleLayerKind.SHADER), BubbleLayer(kind = BubbleLayerKind.NOISE, opacity = .12f),
                ))), paddingHorizontalDp = 12f, paddingVerticalDp = 8f))))
        var scenario: ActivityScenario<SketchTestHostActivity>? = null
        try {
            scenario = ActivityScenario.launch(Intent(context, SketchTestHostActivity::class.java))
            scenario.onActivity { activity ->
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(handler.looper))
                activity.setContent {
                    AppSkinTheme(ResolvedAppSkin(skin), if (dark) darkColorScheme() else lightColorScheme()) {
                        val list = rememberLazyListState()
                        LaunchedEffect(run) {
                            list.scrollToItem(0)
                            repeat(150) { withFrameNanos { }; list.scrollBy(16f) }
                            finished.countDown()
                        }
                        Surface(Modifier.fillMaxSize()) {
                            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(120, key = { it }) { index ->
                                    AppSkinPanelSurface(LocalAppSkin.current.skin.cards.tool,
                                        Modifier.fillMaxWidth(), defaultShape = MaterialTheme.shapes.large) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text("Card $index · 皮肤材质滚动验收", style = MaterialTheme.typography.titleMedium)
                                            Text("明暗配色、字体、圆角与静态 Shader / 颗粒叠加。".repeat(3))
                                            AppButton(onClick = {}) { Text("操作") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            assertTrue("Warmup must complete", finished.await(30, TimeUnit.SECONDS))
            var warmedPss = 0L
            var finalPss = 0L
            for (iteration in 0..3) for ((index, value) in skins.withIndex()) {
                val done = CountDownLatch(1)
                frames.clear()
                dropped.set(0)
                val start = SystemClock.elapsedRealtime()
                instrumentation.runOnMainSync { finished = done; skin = value; dark = iteration % 2 == 1; run++ }
                assertTrue("Scroll must finish", done.await(30, TimeUnit.SECONDS))
                val snapshot = synchronized(frames) { frames.toList().sorted() }
                assertTrue("Real rendered frames required", snapshot.size >= 60)
                val p95 = snapshot[((snapshot.size - 1) * .95).toInt()] / 1_000_000.0
                val pss = Debug.getPss()
                val row = JSONObject().put("iteration", iteration).put("skin", index).put("dark", dark)
                    .put("device", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("frames", snapshot.size).put("p95Ms", p95).put("maxMs", snapshot.last() / 1_000_000.0)
                    .put("over32Ms", snapshot.count { it > 32_000_000 }).put("pssKiB", pss)
                    .put("droppedSamples", dropped.get()).put("elapsedMs", SystemClock.elapsedRealtime() - start)
                if (index == skins.lastIndex && iteration in listOf(1, 3)) {
                    // Retention uses quiescent, collected memory. Raw PSS includes transient uncollected allocations.
                    val settled = settledPss()
                    row.put("settledPssKiB", settled)
                    if (iteration == 1) warmedPss = settled else finalPss = settled
                }
                rows.put(row)
                File(context.filesDir, "skin-performance.json").writeText(rows.toString(2))
                // Debug instrumentation is intentionally given headroom; record the actual distribution.
                if (iteration > 0) {
                    assertTrue("skin=$index p95=$p95 ms", p95 < 32.0)
                    assertTrue("No frame should stall >250 ms", snapshot.last() < 250_000_000)
                    assertEquals("Frame metrics listener must not drop samples", 0, dropped.get())
                }
            }
            assertTrue("Warm skin cycles must not retain >32 MiB", finalPss - warmedPss < 32 * 1024)
        } finally {
            scenario?.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener); it.setContent {} }
            scenario?.close()
            handler.quitSafely()
        }
    }

    private fun settledPss(): Long {
        repeat(3) { Runtime.getRuntime().gc(); System.runFinalization(); SystemClock.sleep(100) }
        return Debug.getPss()
    }
}
