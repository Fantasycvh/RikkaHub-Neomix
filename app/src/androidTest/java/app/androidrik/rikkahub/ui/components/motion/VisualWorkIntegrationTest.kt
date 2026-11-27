package app.androidrik.rikkahub.ui.components.motion

import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.BackEventCompat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.ui.components.haze.recoverableBlurEnabled
import app.androidrik.rikkahub.ui.components.haze.rememberRecoverableHazeState
import app.androidrik.rikkahub.utils.BackgroundMemoryTrimController
import app.androidrik.rikkahub.utils.SingletonImageLoaderGuard
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run in the isolated test package with a plain Application; no production settings or data. */
@RunWith(AndroidJUnit4::class)
class VisualWorkIntegrationTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(target, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test fun scrollClippingStopsAndRestartsAnOtherwiseOnScreenComponent() {
        lateinit var gate: DecorativeAnimationGate
        var scrollTo: (Int) -> Unit = {}
        compose.setContent {
            CompositionLocalProvider(LocalSystemReduceMotion provides false) {
                val scroll = rememberScrollState()
                val probe = rememberDecorativeAnimationGate()
                SideEffect { gate = probe; scrollTo = { runBlocking { scroll.scrollTo(it) } } }
                Box(Modifier.padding(top = 160.dp)) {
                    Column(Modifier.size(80.dp).verticalScroll(scroll)) {
                        Box(Modifier.size(16.dp).then(probe.modifier))
                        Spacer(Modifier.height(300.dp))
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(gate.active) }
        compose.runOnIdle { scrollTo(80) }
        compose.runOnIdle { assertFalse("Still inside window, but outside scroll clip", gate.active) }
        compose.runOnIdle { scrollTo(0) }
        compose.runOnIdle { assertTrue(gate.active) }
    }

    @Test fun resizingAnAncestorClipUpdatesVisibilityWithoutMovingTheChild() {
        val height = mutableStateOf(80.dp)
        lateinit var gate: DecorativeAnimationGate
        compose.setContent {
            CompositionLocalProvider(LocalSystemReduceMotion provides false) {
                val probe = rememberDecorativeAnimationGate()
                SideEffect { gate = probe }
                Box(Modifier.padding(top = 120.dp)) {
                    Box(Modifier.size(80.dp, height.value).clipToBounds()) {
                        Box(Modifier.offset(y = 40.dp).size(16.dp).then(probe.modifier))
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(gate.active); height.value = 20.dp }
        compose.runOnIdle { assertFalse(gate.active); height.value = 80.dp }
        compose.runOnIdle { assertTrue(gate.active) }
    }

    @Test fun unplacedRetainedNodesStopAndResume() {
        val placed = mutableStateOf(true)
        lateinit var gate: DecorativeAnimationGate
        compose.setContent {
            CompositionLocalProvider(LocalSystemReduceMotion provides false) {
                val probe = rememberDecorativeAnimationGate()
                SideEffect { gate = probe }
                Layout(content = { Box(Modifier.size(16.dp).then(probe.modifier)) }) { measurables, constraints ->
                    val child = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
                    layout(100, 100) { if (placed.value) child.place(0, 0) }
                }
            }
        }
        compose.runOnIdle { assertTrue(gate.active); placed.value = false }
        compose.runOnIdle { assertFalse(gate.active); placed.value = true }
        compose.runOnIdle { assertTrue(gate.active) }
    }

    @Test fun hiddenPageSkipsDrawingAndRetainsItsComposition() {
        val visible = mutableStateOf(true)
        val tick = mutableIntStateOf(0)
        var draws = 0
        var identity: Any? = null
        var latestIdentity: Any? = null
        var blurEnabled = true
        compose.setContent {
            PageVisualWorkHost(active = visible.value, visible = visible.value) {
                val remembered = remember { Any() }
                val state = rememberRecoverableHazeState(sdkInt = 35)
                val enabled = recoverableBlurEnabled(state)
                SideEffect { latestIdentity = remembered; blurEnabled = enabled }
                Box(Modifier.size(32.dp).drawWithContent {
                    tick.intValue
                    draws++
                    drawRect(Color.Red)
                })
            }
        }
        var before = 0
        compose.runOnIdle { identity = latestIdentity; assertTrue(draws > 0); visible.value = false }
        compose.runOnIdle { assertFalse(blurEnabled); before = draws; tick.intValue++ }
        compose.runOnIdle { assertEquals(before, draws); visible.value = true }
        compose.runOnIdle {
            assertTrue(blurEnabled)
            assertTrue(draws > before)
            assertSame(identity, latestIdentity)
        }
    }

    private data class DrawFrame(val page: String, val active: Boolean, val visible: Boolean, val blur: Boolean)

    @Test fun pushPopAndPredictiveBackKeepMaterialsOnVisibleFrames() {
        val stack = mutableStateListOf("first")
        val drawTick = mutableIntStateOf(0)
        val frames = Collections.synchronizedList(mutableListOf<DrawFrame>())
        compose.setContent {
            val topKey = remember { derivedStateOf { NavEntry(stack.last()) {}.contentKey } }
            val decorator = rememberPageVisualWorkNavEntryDecorator<String>(topKey)
            NavDisplay(
                backStack = stack,
                entryDecorators = listOf(decorator),
                onBack = { if (stack.size > 1) stack.removeAt(stack.lastIndex) },
                transitionSpec = {
                    slideInHorizontally(tween(800)) { it } togetherWith fadeOut(tween(800))
                },
                popTransitionSpec = {
                    fadeIn(tween(800)) togetherWith slideOutHorizontally(tween(800)) { it }
                },
                predictivePopTransitionSpec = {
                    fadeIn(tween(800)) togetherWith slideOutHorizontally(tween(800)) { it }
                },
                entryProvider = entryProvider {
                    entry<String> { page ->
                        val state = rememberRecoverableHazeState(sdkInt = 35)
                        val active = LocalPageActive.current
                        val visible = LocalPageVisible.current
                        val blur = recoverableBlurEnabled(state)
                        Box(Modifier.fillMaxSize().drawWithContent {
                            // 模拟流式内容的持续失效，确保每个采样帧都实际检查绘制状态。
                            drawTick.intValue
                            frames.add(DrawFrame(page, active, visible, blur))
                            drawContent()
                        }.background(if (blur) Color.Cyan else Color.Magenta))
                    }
                },
            )
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        fun sampleFrames() {
            repeat(8) {
                compose.mainClock.advanceTimeBy(48)
                compose.runOnIdle { drawTick.intValue++ }
                compose.waitForIdle()
            }
        }
        fun assertMaterials() = compose.runOnIdle {
            assertTrue("No visible frames sampled", frames.isNotEmpty())
            assertTrue("Visible material fell back during transition: $frames", frames.all { it.visible && it.blur })
        }
        compose.runOnIdle { frames.clear(); stack.add("second") }
        sampleFrames()
        compose.runOnIdle {
            assertTrue("Outgoing page was not sampled", frames.any { it.page == "first" && !it.active })
        }
        assertMaterials()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()

        // Drive the real back dispatcher: peek, cancel, then peek and commit.
        fun startBack() = compose.runOnIdle {
            frames.clear()
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        startBack()
        sampleFrames()
        compose.runOnIdle { assertTrue("Predictive back did not expose first page", frames.any { it.page == "first" }) }
        assertMaterials()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("first", "second"), stack.toList()) }
        startBack()
        sampleFrames()
        assertMaterials()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        sampleFrames()
        assertMaterials()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("first"), stack.toList()) }
    }

    @Suppress("DEPRECATION")
    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Test fun coilAndApplicationPressureHandlingTrimImagesOnlyOnce() {
        val cache = MemoryCache.Builder().maxSizeBytes(1024 * 1024L).build()
        val loader = ImageLoader.Builder(target).memoryCache(cache).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = BackgroundMemoryTrimController(target, scope) { null }
        val previousLoader = SingletonImageLoader.get(target)
        SingletonImageLoader.setUnsafe(loader)
        SingletonImageLoaderGuard.markInitialized()
        try {
            // A real request registers Coil's pressure callback.
            val result = runBlocking { loader.execute(ImageRequest.Builder(target).data(
                Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            ).build()) }
            assertTrue(result is SuccessResult)
            listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL).forEach { level ->
                cache.clear()
                repeat(8) { index -> cache[MemoryCache.Key("$index")] = MemoryCache.Value(
                    Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImage()
                ) }
                val before = cache.size
                compose.runOnIdle {
                    // Same order as RikkaHubApp: Application.super first, then our controller.
                    compose.activity.application.onTrimMemory(level)
                    controller.onTrimMemory(level)
                }
                assertEquals("level=$level must not trim twice", before / 2, cache.size)
            }
        } finally {
            scope.cancel()
            SingletonImageLoader.setUnsafe(previousLoader)
            loader.shutdown()
        }
    }
}
