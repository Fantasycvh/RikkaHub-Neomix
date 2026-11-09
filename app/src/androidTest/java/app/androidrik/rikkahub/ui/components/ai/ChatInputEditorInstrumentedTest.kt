package app.androidrik.rikkahub.ui.components.ai

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.DialogWindowProvider
import androidx.room.Room
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.repository.FilesRepository
import app.androidrik.rikkahub.perf.BaselineAsr
import app.androidrik.rikkahub.perf.BaselineTts
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionContext
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionList
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import app.androidrik.rikkahub.ui.context.*
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import app.androidrik.rikkahub.utils.SoundEffectPlayer
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

/** Run using -PlongTextBaseline=shared: native editing checks with no production DB/application. */
@RunWith(AndroidJUnit4::class)
class ChatInputEditorInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val compose = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { rule ->
        var activity: SketchTestHostActivity? = null
        rule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }
    private val scope = AppScope()
    private val settingsScope = AppScope()
    private lateinit var database: AppDatabase
    private lateinit var sound: SoundEffectPlayer

    @Before fun dependencies() {
        check(context.applicationContext.javaClass == Application::class.java)
        val files = File(context.cacheDir, "p1-editor-${Uuid.random()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = files }
        settingsScope.cancel()
        val settings = SettingsStore(isolated, settingsScope, File(files, "settings.preferences_pb"))
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val manager = FilesManager(isolated, FilesRepository(database.managedFileDao()), scope, settings)
        sound = SoundEffectPlayer(context)
        startKoin { modules(module { single { manager }; single { sound }; single { settings } }) }
    }

    @After fun close() {
        stopKoin()
        scope.cancel()
        settingsScope.cancel()
        if (::sound.isInitialized) sound.release()
        if (::database.isInitialized) database.close()
    }

    private fun mount(state: ChatInputState, providers: List<ChatCompletionProvider> = emptyList()) {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalSettings provides Settings(), LocalNavController provides Navigator(mutableListOf()),
                    LocalToaster provides rememberToasterState(), LocalTTSState provides BaselineTts,
                    LocalASRState provides BaselineAsr,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        ChatInput(state = state, loading = false, settings = Settings(), hazeState = HazeState(),
                            enableSearch = false, onToggleSearch = {}, completionProviders = providers,
                            onUpdateChatModel = {}, onUpdateAssistant = {}, onUpdateSearchService = {},
                            onMoreClick = {}, onCancelClick = {}, onSendClick = {}, onLongSendClick = {})
                    }
                }
            }
        }
        compose.onNodeWithTag("chat_input").performClick()
    }

    private fun window(): Window {
        fun find(view: View): Window? {
            if (view is DialogWindowProvider) return view.window
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find) ?: compose.activity.window
    }

    private fun nativeEdit(block: (InputConnection) -> Unit) {
        compose.runOnIdle {
            val connection = checkNotNull(window().decorView.findFocus()?.onCreateInputConnection(EditorInfo()))
            block(connection)
        }
        compose.waitForIdle()
    }

    @Test fun handoffPreservesTextSelectionAndUndo() {
        val state = ChatInputState().apply { setMessageText("原文") }
        mount(state)
        nativeEdit { it.setSelection(2, 2); assertTrue(it.commitText("追加", 1)) }
        compose.runOnIdle { assertTrue(state.textContent.undoState.canUndo) }
        val selection = state.textContent.selection
        compose.onNodeWithTag("chat_input_expand").performClick()
        compose.onNodeWithTag("chat_input").assertDoesNotExist()
        compose.onNodeWithTag("chat_fullscreen_input").assertExists()
        compose.runOnIdle {
            assertEquals("原文追加", state.textContent.text.toString())
            assertEquals(selection, state.textContent.selection)
            assertTrue(state.textContent.undoState.canUndo)
        }
        compose.onNodeWithTag("chat_fullscreen_done").performClick()
        compose.onNodeWithTag("chat_input").assertExists()
        compose.onNodeWithTag("chat_fullscreen_input").assertDoesNotExist()
        nativeEdit { assertTrue(it.commitText("末", 1)) }
        compose.runOnIdle {
            assertEquals("原文追加末", state.textContent.text.toString())
            state.textContent.undoState.undo()
            assertNotEquals("原文追加末", state.textContent.text.toString())
            assertTrue(state.textContent.undoState.canRedo)
            state.textContent.undoState.redo()
            assertEquals("原文追加末", state.textContent.text.toString())
        }
    }

    @Test fun composingTextSurvivesHostSwitchAndCompletionStopsBehindDialog() {
        val calls = AtomicInteger()
        val observed = AtomicReference<ChatCompletionContext?>()
        val provider = object : ChatCompletionProvider {
            override val id = "context_probe"
            override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
                calls.incrementAndGet()
                observed.set(context)
                return null
            }
        }
        val original = "文".repeat(10_000) + " @src"
        val state = ChatInputState().apply { setMessageText(original) }
        mount(state, listOf(provider))
        nativeEdit { it.setSelection(original.length, original.length); it.setComposingText("拼音", 1) }
        compose.waitUntil(10_000) { observed.get()?.text?.endsWith("拼音") == true }
        assertTrue(observed.get()!!.text.length <= 1025)
        val expected = state.textContent.text.toString()
        compose.onNodeWithTag("chat_input_expand").performClick()
        compose.runOnIdle { assertEquals(expected, state.textContent.text.toString()) }
        val before = calls.get()
        nativeEdit { it.finishComposingText(); it.commitText("续", 1) }
        compose.runOnIdle { assertEquals(before, calls.get()) }
        compose.onNodeWithTag("chat_fullscreen_done").performClick()
        compose.runOnIdle { assertEquals(expected + "续", state.textContent.text.toString()) }
        compose.waitUntil(10_000) { calls.get() > before }
    }

    @Test fun segmentedDraftComposesAcrossPagesAndAppliesToCompleteSource() {
        val original = "正文".repeat(30_000)
        val state = ChatInputState().apply { setMessageText(original) }
        mount(state)
        compose.onNodeWithTag("chat_input_expand").performClick()
        compose.onNodeWithTag("chat_segmented_open").performClick()
        nativeEdit { it.setSelection(0, 0); it.setComposingText("组合", 1) }
        compose.onNodeWithText("下一段").performClick()
        nativeEdit { it.setSelection(0, 0); it.commitText("第二", 1) }
        compose.runOnIdle { assertEquals(original, state.textContent.text.toString()) }
        compose.onNodeWithTag("segmented_apply").performClick()
        compose.runOnIdle { assertEquals("组合" + original.take(4096) + "第二" + original.drop(4096), state.textContent.text.toString()) }
    }

    @Test fun cancellingSegmentedDraftKeepsOriginalDocument() {
        val original = "内容".repeat(14_000)
        val state = ChatInputState().apply { setMessageText(original) }
        mount(state)
        compose.onNodeWithTag("chat_input_expand").performClick()
        compose.onNodeWithTag("chat_segmented_open").performClick()
        nativeEdit { it.setComposingText("未应用", 1) }
        compose.onNodeWithText("取消分段编辑").performClick()
        compose.runOnIdle { assertEquals(original, state.textContent.text.toString()); assertNull(state.segmentedDraft) }
        compose.onNodeWithTag("chat_fullscreen_done").performClick()
    }

    @Test fun longDocumentMiddleEditSurvivesRoundTrip() {
        val original = "长文段落\n".repeat(2_000)
        val state = ChatInputState().apply { setMessageText(original) }
        mount(state)
        nativeEdit { it.setSelection(original.length, original.length); it.commitText("尾", 1) }
        compose.onNodeWithTag("chat_input_expand").performClick()
        val middle = original.length / 2
        nativeEdit { it.setSelection(middle, middle); it.commitText("中", 1) }
        compose.onNodeWithTag("chat_fullscreen_done").performClick()
        compose.runOnIdle {
            assertEquals(original.take(middle) + "中" + original.drop(middle) + "尾", state.textContent.text.toString())
            assertEquals(middle + 1, state.textContent.selection.start)
        }
        nativeEdit { it.commitText("续", 1) }
        compose.runOnIdle {
            assertEquals(original.take(middle) + "中续" + original.drop(middle) + "尾", state.textContent.text.toString())
        }
    }
}
