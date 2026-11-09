package app.androidrik.rikkahub.perf

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.BuildConfig
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.datastore.DisplaySetting
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.ui.components.ai.ChatInput
import app.androidrik.rikkahub.ui.components.ai.FullScreenEditor
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import app.androidrik.rikkahub.ui.pages.chat.ChatList
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/** Real frame clock + real production widgets. No Compose test clock, production DB writes or API calls. */
@RunWith(AndroidJUnit4::class)
class LongTextBaselineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private var activity: SketchTestHostActivity? = null
    private var scene by mutableStateOf<Scene?>(null)
    private lateinit var automation: android.app.UiAutomation
    private val scope = app.androidrik.rikkahub.AppScope()
    private val settingsScope = app.androidrik.rikkahub.AppScope()
    private var database: app.androidrik.rikkahub.data.db.AppDatabase? = null
    private var sound: app.androidrik.rikkahub.utils.SoundEffectPlayer? = null

    @Before fun isolateDependencies() {
        check(context.applicationContext.javaClass == android.app.Application::class.java) {
            "LongTextBaselineRunner must prevent production startup before measurement"
        }
        check(org.koin.core.context.GlobalContext.getOrNull() == null)
        val files = File(context.cacheDir, "longtext-dependencies-${Uuid.random()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) { override fun getFilesDir() = files }
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, app.androidrik.rikkahub.data.db.AppDatabase::class.java).build()
        database = db
        // UI settings are supplied explicitly through LocalSettings. Do not start background
        // settings migrations/template updates just to satisfy FilesManager's constructor.
        settingsScope.cancel()
        val settings = app.androidrik.rikkahub.data.datastore.SettingsStore(isolated, settingsScope, File(files, "settings.preferences_pb"))
        val manager = app.androidrik.rikkahub.data.files.FilesManager(isolated,
            app.androidrik.rikkahub.data.repository.FilesRepository(db.managedFileDao()), scope, settings)
        val player = app.androidrik.rikkahub.utils.SoundEffectPlayer(context).also { sound = it }
        org.koin.core.context.startKoin { modules(org.koin.dsl.module {
            single { manager }; single { player }; single { settings }
        }) }
    }

    @After fun closeDependencies() {
        org.koin.core.context.stopKoin()
        scope.cancel()
        settingsScope.cancel()
        sound?.release()
        database?.close()
    }

    private class Scene(val kind: String, val text: String, val mode: String, val animation: Boolean) {
        val assistant = Assistant(id = Uuid.parse("00000000-0000-4000-8000-000000000001"))
        val settings = Settings(
            assistantId = assistant.id, assistants = listOf(assistant), providers = emptyList(),
            displaySetting = DisplaySetting(enableGenerationSizeAnimation = animation),
        )
        val input = ChatInputState().apply { setMessageText(text) }
        val list = LazyListState()
        val haze = HazeState()
        val glass = HazeState()
        var focusManager: FocusManager? = null
        private val reply = UIMessage.assistant(if (mode == "stream") text.dropLast(3_200) else text)
        var conversation by mutableStateOf(Conversation.ofId(
            Uuid.parse("00000000-0000-4000-8000-000000000002"), assistant.id,
            listOf(UIMessage.user("固定长文测试样本").toMessageNode(), reply.toMessageNode()),
        ))
        fun replay(end: Int) {
            conversation = conversation.updateCurrentMessages(listOf(
                conversation.currentMessages.first(), reply.copy(parts = listOf(UIMessagePart.Text(text.take(end)))),
            ))
        }
    }

    @Test fun recordBaseline() {
        check(BuildConfig.APPLICATION_ID in setOf("app.androidrik.rikkahub.longtextbaseline.debug", "app.androidrik.rikkahub.debug")) {
            "Build with -PlongTextBaseline=true (or shared with the isolated runner)"
        }
        check(Build.VERSION.SDK_INT >= 31) { "P0 requires Android 12+ for per-frame deadlines" }
        automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
        val run = arguments.getString("reportRun", "manual").also { require(it.matches(Regex("[a-zA-Z0-9_-]+"))) }
        val output = File(context.filesDir, "longtext-baseline/$run.jsonl").apply { parentFile!!.mkdirs() }
        check(!output.exists()) { "Use a fresh reportRun to avoid mixing runs" }
        val sizes = csv("sizes", "10000,50000,100000").map(String::toInt)
        val kinds = csv("kinds", "prose,dense,mixed")
        val modes = csv("scenarios", "scroll,stream,edit_inline,edit_full")
        val iterations = arguments.getString("iterations", "3").toInt().also { require(it in 1..10) }
        val animation = arguments.getString("animation", "true").toBooleanStrict()
        require(kinds.all { it in LongTextFixtures.kinds } && sizes.all { it in LongTextFixtures.sizes })
        require(modes.all { it in setOf("scroll", "stream", "edit_inline", "edit_full", "edit_handoff_full") })
        ActivityScenario.launch<SketchTestHostActivity>(android.content.Intent(context, SketchTestHostActivity::class.java)).use { scenario ->
            scenario.onActivity {
                activity = it
                it.window.attributes = it.window.attributes.apply { preferredRefreshRate = 60f }
                it.setContent {
                    MaterialTheme {
                        scene?.let { current -> key(current) { Content(current) } }
                    }
                }
            }
            for (size in sizes) for (kind in kinds) {
                val text = LongTextFixtures.text(kind, size)
                assertEquals(size, text.length)
                assertEquals(text, LongTextFixtures.text(kind, size))
                for (mode in modes) repeat(iterations) { iteration ->
                    val current = Scene(kind, text, mode, animation)
                    val opened = System.nanoTime()
                    instrumentation.runOnMainSync { scene = current }
                    awaitDraw(requireNotNull(activity).window) {}
                    val openMs = (System.nanoTime() - opened) / 1e6
                    SystemClock.sleep(750) // settle initial parsing/window animations outside the measured workload
                    // P3 can prepare a static document off-thread. Do not mix its placeholder swap
                    // into steady scrolling; P2's first host draw already waited for sync parsing.
                    val preparationStarted = System.nanoTime()
                    if (mode == "scroll") {
                        val deadline = SystemClock.uptimeMillis() + 30_000
                        while (current.list.layoutInfo.visibleItemsInfo.any { it.key.toString().endsWith(":preparing") }) {
                            check(SystemClock.uptimeMillis() < deadline) { "static document preparation timed out" }
                            SystemClock.sleep(50)
                        }
                    }
                    val preparationWaitMs = (System.nanoTime() - preparationStarted) / 1e6
                    if (mode == "edit_handoff_full") {
                        focusEditor(current, requireNotNull(activity).window)
                        openFullScreen()
                        SystemClock.sleep(750)
                    }
                    val window = currentWindow(mode)
                    instrumentation.runOnMainSync {
                        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
                        if (!mode.startsWith("edit")) window.insetsController?.hide(android.view.WindowInsets.Type.ime())
                    }
                    if (mode.startsWith("edit")) focusEditor(current, window) else SystemClock.sleep(200)
                    var connection: android.view.inputmethod.InputConnection? = null
                    if (mode.startsWith("edit")) instrumentation.runOnMainSync {
                        connection = window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                        checkNotNull(connection) { "focused editor did not provide an InputConnection" }
                    }
                    val before = memory()
                    val latencies = JSONArray()
                    val selections = JSONArray()
                    val streamTimes = java.util.Collections.synchronizedList(mutableListOf<Long>())
                    var peakHeap = before.getLong("java_heap_bytes")
                    val row = LongTextFrameRecorder(window).use { recorder ->
                        recorder.start()
                        when (mode) {
                            "scroll" -> repeat(6) { swipe(window, it % 2 == 0); peakHeap = maxOf(peakHeap, heap()) }
                            "stream" -> {
                                val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
                                val replay = Thread({
                                    try {
                                        repeat(40) { index ->
                                            instrumentation.runOnMainSync { current.replay(size - 3_200 + (index + 1) * 80) }
                                            streamTimes += System.nanoTime()
                                            SystemClock.sleep(50)
                                        }
                                    } catch (error: Throwable) { failure.set(error) }
                                }, "longtext-replay").apply { start() }
                                try {
                                    repeat(6) { swipe(window, it % 2 == 0); peakHeap = maxOf(peakHeap, heap()) }
                                } finally {
                                    replay.join(60_000)
                                }
                                check(!replay.isAlive) { "stream replay timed out" }
                                failure.get()?.let { throw it }
                                assertEquals(text, current.conversation.currentMessages.last().toText())
                            }
                            else -> repeat(18) { index ->
                                val length = current.input.textContent.text.length
                                val position = when (index % 3) { 0 -> 0; 1 -> length / 2; else -> length }
                                selections.put(awaitDraw(window) {
                                    instrumentation.runOnMainSync { check(connection!!.setSelection(position, position)) }
                                })
                                assertEquals(position, current.input.textContent.selection.start)
                                latencies.put(awaitDraw(window) {
                                    instrumentation.runOnMainSync { check(connection!!.commitText("x", 1)) }
                                })
                                assertEquals(length + 1, current.input.textContent.text.length)
                                peakHeap = maxOf(peakHeap, heap())
                            }
                        }
                        recorder.stop()
                    }
                    val after = memory()
                    row.put("schema_version", 2).put("fixture_version", LongTextFixtures.VERSION)
                        .put("driver_protocol", "input_connection_no_accessibility_v1")
                        .put("kind", kind).put("length_codepoints", size).put("scenario", mode)
                        .put("iteration", iteration).put("animation", animation)
                        .put("fixture_sha256", LongTextFixtures.sha256(text))
                        .put("scope", "production_components_debug_local_replay")
                        .put("scene_switch_to_host_draw_ms", openMs)
                        .put("static_preparation_wait_ms", preparationWaitMs)
                        .put("stream_update_at_ns", JSONArray(streamTimes.toList()))
                        .put("input_connection_commit_to_draw_ms", latencies).put("selection_to_draw_ms", selections)
                        .put("memory_before", before).put("memory_after", after)
                        .put("sampled_peak_java_heap_bytes", peakHeap)
                        .put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                        .put("build_fingerprint", Build.FINGERPRINT)
                        .put("font_scale", context.resources.configuration.fontScale.toDouble())
                        .put("density_dpi", context.resources.displayMetrics.densityDpi)
                        .put("application_id", BuildConfig.APPLICATION_ID)
                        .put("process_pid", android.os.Process.myPid())
                        .put("refresh_request_scope", "measured_window")
                        .put("refresh_rate_hz", window.decorView.display.refreshRate.toDouble())
                        .put("requested_refresh_rate_hz", 60)
                        .put("thermal_status", context.getSystemService(PowerManager::class.java).currentThermalStatus)
                    output.appendText(row.toString() + "\n")
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "P0 $kind/$size/$mode iteration=$iteration saved\n")
                    })
                    instrumentation.runOnMainSync { scene = null }
                    SystemClock.sleep(150)
                }
            }
        }
    }

    @Composable private fun Content(current: Scene) {
        val navigator = remember { Navigator(mutableListOf()) }
        val focus = LocalFocusManager.current
        SideEffect { current.focusManager = focus }
        CompositionLocalProvider(
            LocalSettings provides current.settings,
            LocalNavController provides navigator,
            LocalToaster provides rememberToasterState(),
            app.androidrik.rikkahub.ui.context.LocalTTSState provides BaselineTts,
            app.androidrik.rikkahub.ui.context.LocalASRState provides BaselineAsr,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                when (current.mode) {
                    "edit_full" -> FullScreenEditor(current.input) {}
                    "edit_inline", "edit_handoff_full" -> ChatInput(
                        state = current.input, loading = false, settings = current.settings,
                        hazeState = current.haze, enableSearch = false, onToggleSearch = {},
                        onUpdateChatModel = {}, onUpdateAssistant = {}, onUpdateSearchService = {},
                        onMoreClick = {}, onCancelClick = {}, onSendClick = {}, onLongSendClick = {},
                    )
                    else -> ChatList(
                        innerPadding = PaddingValues(), conversation = current.conversation,
                        state = current.list, loading = current.mode == "stream", previewMode = false,
                        settings = current.settings, hazeState = current.haze, glassHazeState = current.glass,
                    )
                }
            }
        }
    }

    private fun csv(name: String, default: String) = arguments.getString(name, default).split(',')
    private fun heap() = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return JSONObject().put("pss_kib", info.totalPss).put("java_heap_bytes", heap())
            .put("art", JSONObject(Debug.getRuntimeStats() as Map<*, *>))
    }

    private fun focusEditor(current: Scene, window: Window) {
        if (current.mode == "edit_full" || (current.mode == "edit_handoff_full" && window !== activity?.window)) {
            // The full-screen editor's body occupies this point, below its Save toolbar.
            val view = window.decorView
            val location = IntArray(2)
            instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    location[0] + view.width * .3f, location[1] + view.height * .4f, 0)
                check(view.hasWindowFocus()) { "test editor window lost focus" }
                check(automation.injectInputEvent(event, true))
                event.recycle()
            }
        }
        repeat(64) {
            var connected = false
            instrumentation.runOnMainSync {
                connected = window.decorView.findFocus()?.onCreateInputConnection(android.view.inputmethod.EditorInfo()) != null
                if (!connected && current.mode in setOf("edit_inline", "edit_handoff_full")) current.focusManager!!.moveFocus(FocusDirection.Next)
            }
            if (connected) { SystemClock.sleep(750); return }
            SystemClock.sleep(50)
        }
        error("editor did not acquire a native input connection")
    }

    private fun currentWindow(mode: String): Window {
        if (mode !in setOf("edit_full", "edit_handoff_full")) return requireNotNull(activity).window
        fun find(view: View): Window? {
            if (view is DialogWindowProvider) return view.window
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        var found: Window? = null
        instrumentation.runOnMainSync {
            found = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
        }
        return checkNotNull(found) { "Compose dialog window missing; activity-window metrics would be invalid" }
    }

    /** In-process semantics only, outside measurement; never export a long text through accessibility. */
    private fun openFullScreen() {
        fun root(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) root(view.getChildAt(index))?.let { return it }
            return null
        }
        fun nodes(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            for (child in node.children) yieldAll(nodes(child))
        }
        instrumentation.runOnMainSync {
            val semantics = checkNotNull(root(requireNotNull(activity).window.decorView)).semanticsOwner
            val input = nodes(semantics.unmergedRootSemanticsNode)
                .single { it.config.getOrNull(SemanticsProperties.TestTag) == "chat_input" }
            // With no quick messages, the field has exactly one button: Expand. Works on P0 too.
            val expand = nodes(input).single {
                it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    it.config.getOrNull(SemanticsActions.OnClick)?.action != null
            }
            check(expand.config[SemanticsActions.OnClick].action!!.invoke())
        }
    }

    /** Returns action dispatch -> end of next draw, an input proxy, not IME composition/display photon latency. */
    private fun awaitDraw(window: Window, action: () -> Unit): Double {
        val latch = CountDownLatch(1)
        val view = window.decorView
        lateinit var listener: android.view.ViewTreeObserver.OnDrawListener
        listener = android.view.ViewTreeObserver.OnDrawListener {
            view.post { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(listener); latch.countDown() }
        }
        instrumentation.runOnMainSync { view.viewTreeObserver.addOnDrawListener(listener) }
        val start = System.nanoTime()
        action()
        // A selection at an unchanged offset may not invalidate; still measure a real subsequent frame.
        instrumentation.runOnMainSync { view.invalidate() }
        assertTrue("draw did not arrive within 15s", latch.await(15, TimeUnit.SECONDS))
        return (System.nanoTime() - start) / 1e6
    }

    private fun swipe(window: Window, up: Boolean, durationMs: Long = 300) {
        val view = window.decorView
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
        val x = location[0] + view.width * .5f
        val startY = location[1] + view.height * if (up) .72f else .3f
        val endY = location[1] + view.height * if (up) .3f else .72f
        val start = SystemClock.uptimeMillis()
        for (index in 0..12) {
            val eventTime = start + durationMs * index / 12
            SystemClock.sleep((eventTime - SystemClock.uptimeMillis()).coerceAtLeast(0))
            val event = MotionEvent.obtain(start, eventTime, when (index) {
                0 -> MotionEvent.ACTION_DOWN; 12 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE
            }, x, startY + (endY - startY) * index / 12, 0)
            check(view.hasWindowFocus()) { "test chat window lost focus" }
            check(automation.injectInputEvent(event, true))
            event.recycle()
        }
        SystemClock.sleep(100)
    }
}
