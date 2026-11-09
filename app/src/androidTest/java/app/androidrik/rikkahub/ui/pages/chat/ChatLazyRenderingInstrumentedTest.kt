package app.androidrik.rikkahub.ui.pages.chat

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.Room
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.*
import app.androidrik.rikkahub.data.repository.FilesRepository
import app.androidrik.rikkahub.perf.BaselineAsr
import app.androidrik.rikkahub.perf.BaselineTts
import app.androidrik.rikkahub.ui.components.message.*
import app.androidrik.rikkahub.ui.components.richtext.SelectableContent
import app.androidrik.rikkahub.ui.components.richtext.SharedSelectableContent
import app.androidrik.rikkahub.ui.context.*
import app.androidrik.rikkahub.utils.SoundEffectPlayer
import com.dokar.sonner.rememberToasterState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.bettertext.text.BetterText
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.uuid.Uuid

/** Isolated baseline runner, real production list and real pointer/clipboard operations. */
@RunWith(AndroidJUnit4::class)
class ChatLazyRenderingInstrumentedTest {
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
    private val assistant = Assistant()
    private val settings = mutableStateOf(Settings(assistantId = assistant.id, assistants = listOf(assistant)))
    private val conversation = mutableStateOf(Conversation.ofId(Uuid.random(), assistant.id, emptyList()))
    private val controller = ChatListScrollController()
    private val loading = mutableStateOf(false)
    private val state = LazyListState()
    private val toolbar = CapturingToolbar()
    private val clipboardBackend = CapturingClipboard()
    private val text = (0..249).joinToString("\n\n") { "paragraph_$it alpha beta gamma " + "正文与段落。".repeat(18) }

    @Before fun dependencies() {
        check(context.applicationContext.javaClass == Application::class.java)
        // This OEM can launch ActivityScenario behind the previously focused task. Semantics still
        // work there, but Android correctly denies clipboard reads. Bring the existing test task
        // forward without clearing data or recreating the activity, then verify actual focus.
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_USE_ACCESSIBILITY)
        automation.executeShellCommand("am start --activity-reorder-to-front --activity-single-top -n ${context.packageName}/app.androidrik.rikkahub.SketchTestHostActivity")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        val activity = compose.activity
        compose.waitUntil(10_000) { activity.window.decorView.hasWindowFocus() }
        val files = File(context.cacheDir, "p3-list-${Uuid.random()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) { override fun getFilesDir() = files }
        settingsScope.cancel()
        val store = SettingsStore(isolated, settingsScope, File(files, "settings.preferences_pb"))
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val manager = FilesManager(isolated, FilesRepository(database.managedFileDao()), scope, store)
        sound = SoundEffectPlayer(context)
        org.koin.core.context.startKoin { modules(org.koin.dsl.module { single { manager }; single { sound }; single { store } }) }
    }
    @After fun close() {
        org.koin.core.context.stopKoin()
        scope.cancel(); settingsScope.cancel()
        if (::sound.isInitialized) sound.release()
        if (::database.isInitialized) database.close()
    }

    private fun mountList(nodes: List<MessageNode>, restoration: StateRestorationTester? = null, internalController: Boolean = false,
        initiallyLoading: Boolean = false, expectSplit: Boolean = true) {
        conversation.value = Conversation.ofId(conversation.value.id, assistant.id, nodes)
        loading.value = initiallyLoading
        val content: @Composable () -> Unit = {
            MaterialTheme {
                CompositionLocalProvider(LocalSettings provides settings.value,
                    LocalNavController provides Navigator(mutableListOf()), LocalToaster provides rememberToasterState(),
                    LocalTTSState provides BaselineTts, LocalASRState provides BaselineAsr, LocalTextToolbar provides toolbar,
                    LocalClipboard provides clipboardBackend) {
                    ChatList(PaddingValues(), conversation.value, state, loading = loading.value, previewMode = false,
                        settings = settings.value, hazeState = remember { HazeState() }, glassHazeState = remember { HazeState() },
                        scrollController = if (internalController) null else controller)
                }
            }
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
        if (expectSplit) compose.waitUntil(20_000) { state.layoutInfo.totalItemsCount > 20 }
        compose.waitForIdle()
    }
    private fun scroll(index: Int, offset: Int = 0) {
        compose.runOnIdle { scope.launch { state.scrollToItem(index, offset) } }
        compose.waitForIdle()
    }
    private fun rows() = compose.onAllNodes(SemanticsMatcher("chat rows") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("chat_row:") == true
    }, useUnmergedTree = true).fetchSemanticsNodes()
    private fun clipboard(): String? {
        return clipboardBackend.entry?.clipData?.getItemAt(0)?.text?.toString()
    }
    private fun awaitClipboard(expected: String) {
        try { compose.waitUntil(5_000) { clipboard() == expected } }
        catch (e: Throwable) {
            val actual = clipboard()
            val activity = compose.activity
            val diagnostic = "focus=${activity.window.decorView.hasWindowFocus()}"
            throw AssertionError("clipboard expected length=${expected.length}, actual length=${actual?.length}; $diagnostic", e)
        }
    }

    @Test fun staticListVirtualizesKeepsReadingAnchorAndJumpsByMessage() {
        val first = UIMessage.user("first_marker").toMessageNode()
        val long = UIMessage.assistant(text).toMessageNode()
        val last = UIMessage.user("last_marker").toMessageNode()
        mountList(listOf(first, long, last))
        assertTrue(rows().size < 30)
        scroll(10)
        val key = state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key
        val offset = state.firstVisibleItemScrollOffset
        compose.runOnIdle { conversation.value = conversation.value.copy(messageNodes =
            listOf(UIMessage.user("inserted").toMessageNode()) + conversation.value.messageNodes) }
        compose.waitForIdle()
        assertEquals(key, state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key)
        assertEquals(offset, state.firstVisibleItemScrollOffset)
        compose.runOnIdle { settings.value = settings.value.copy(displaySetting = settings.value.displaySetting.copy(fontSizeRatio = 1.1f)) }
        compose.waitForIdle()
        assertEquals(key, state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key)
        compose.runOnIdle { controller.scrollToMessage(last.currentMessage.id) }
        compose.waitUntil(10_000) { state.layoutInfo.visibleItemsInfo.any { it.key.toString() == last.id.toString() } }
        compose.onNodeWithText("last_marker").assertExists()
        compose.runOnIdle { controller.scrollToMessage(first.id) }
        compose.waitUntil(10_000) { state.layoutInfo.visibleItemsInfo.any { it.key.toString() == first.id.toString() } }
        compose.onNodeWithText("first_marker").assertExists()
    }

    @Test fun savedLogicalAnchorSurvivesAsyncDocumentPreparationOnRecreation() {
        val restoration = StateRestorationTester(compose)
        val node = UIMessage.assistant(text).toMessageNode()
        mountList(listOf(node, UIMessage.assistant("background output").toMessageNode()), restoration,
            internalController = true, initiallyLoading = true)
        scroll(10, 29)
        val key = state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key
        val offset = state.firstVisibleItemScrollOffset
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(20_000) { state.layoutInfo.totalItemsCount > 20 &&
            state.layoutInfo.visibleItemsInfo.any { it.index == state.firstVisibleItemIndex && it.key == key } }
        compose.waitForIdle()
        assertEquals(offset, state.firstVisibleItemScrollOffset)
    }

    @Test fun streamingReplyKeepsItsPrefixAndReadingAnchorThroughCompletion() {
        val node = UIMessage.assistant(text).toMessageNode()
        mountList(listOf(node), initiallyLoading = true)
        compose.runOnIdle { controller.scrollToMessage(node.id) }
        compose.waitUntil(10_000) { controller.request == null && state.firstVisibleItemIndex == 0 }
        compose.waitForIdle()
        scroll(10, 29)
        val key = state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key
        val offset = state.firstVisibleItemScrollOffset
        val appended = text + "\n\nstream_tail_marker " + "新段落。".repeat(40)
        compose.runOnIdle { conversation.value = conversation.value.copy(messageNodes = listOf(
            node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(UIMessagePart.Text(appended))))))) }
        compose.waitForIdle()
        compose.runOnIdle { loading.value = false }
        compose.waitForIdle()
        assertEquals(key, state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key)
        assertEquals(offset, state.firstVisibleItemScrollOffset)
        compose.runOnIdle { controller.scrollToBottom() }
        compose.waitUntil(20_000) { controller.request == null }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("stream_tail_marker", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("stream_tail_marker", substring = true).assertExists()
    }

    @Test fun streamingReplyFollowsTailThroughFinalLayout() {
        val node = UIMessage.assistant(text).toMessageNode()
        mountList(listOf(node), initiallyLoading = true)
        val appended = text + "\n\nfinal_follow_marker " + "追加内容。".repeat(60)
        compose.runOnIdle { conversation.value = conversation.value.copy(messageNodes = listOf(
            node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(UIMessagePart.Text(appended))))))) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("final_follow_marker", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { loading.value = false }
        compose.waitForIdle()
        compose.waitUntil(20_000) { !state.canScrollForward &&
            state.layoutInfo.visibleItemsInfo.lastOrNull()?.key == "ScrollBottomKey" }
    }

    @Test fun firstSplitWaitsUntilReaderLeavesWholeMessageInterior() {
        val short = text.take(6500)
        val node = UIMessage.assistant(short).toMessageNode()
        mountList(listOf(node), initiallyLoading = true, expectSplit = false)
        compose.runOnIdle { controller.scrollToMessage(node.id) }
        compose.waitUntil(10_000) { controller.request == null && state.firstVisibleItemIndex == 0 }
        compose.waitForIdle()
        scroll(0, 180)
        val appended = text + "\n\npromotion_marker"
        compose.runOnIdle { conversation.value = conversation.value.copy(messageNodes = listOf(
            node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(UIMessagePart.Text(appended))))))) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("promotion_marker", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { loading.value = false }
        compose.waitForIdle()
        assertTrue(state.layoutInfo.totalItemsCount < 10)
        assertEquals(180, state.firstVisibleItemScrollOffset)
        compose.runOnIdle { controller.scrollToBottom() }
        compose.waitUntil(20_000) { state.layoutInfo.totalItemsCount > 20 && !state.canScrollForward }
    }

    @Test fun visibleStreamingAtomDoesNotCollapseWhenItCrossesPreviewThreshold() {
        val source = text + "\n\n```text\n" + "文".repeat(20_000)
        val node = UIMessage.assistant(source).toMessageNode()
        mountList(listOf(node), initiallyLoading = true)
        compose.runOnIdle { controller.scrollToBottom() }
        compose.waitUntil(20_000) { !state.canScrollForward }
        compose.runOnIdle { conversation.value = conversation.value.copy(messageNodes = listOf(
            node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(UIMessagePart.Text(source + "文".repeat(10_000)))))))) }
        compose.runOnIdle { loading.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("超长内容已折叠").assertDoesNotExist()
    }

    @Test fun giantBlockPreviewOpensOriginalAndCopiesAllOffscreenContent() {
        val source = "```text\n" + "原文".repeat(50_000) + "\n```"
        val node = UIMessage.assistant(source).toMessageNode()
        mountList(listOf(node), expectSplit = false)
        compose.waitUntil(20_000) { compose.onAllNodesWithText("超长内容已折叠").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("查看完整原文").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(R.string.copy_all)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.copy_all)).performClick()
        awaitClipboard(source)
    }

    @Test fun quoteJumpAndBranchCopyUseCompleteLogicalMessage() {
        val original = UIMessage.assistant(text)
        val alternate = UIMessage.assistant(text.replace("paragraph_", "alternate_"))
        val node = MessageNode(messages = listOf(original, alternate))
        val quote = UIMessage.user("quote_marker").copy(annotations = listOf(
            UIMessageAnnotation.QuotedMessage(original.id, MessageRole.ASSISTANT, "jump_marker"))).toMessageNode()
        mountList(listOf(node, quote))
        compose.runOnIdle { controller.scrollToMessage(quote.id) }
        compose.waitUntil(10_000) { state.layoutInfo.visibleItemsInfo.any { it.key.toString() == quote.id.toString() } }
        compose.onNodeWithText("jump_marker").performClick()
        compose.waitUntil(10_000) { state.firstVisibleItemIndex < 5 }
        compose.runOnIdle { conversation.value = conversation.value.copy(messageNodes = listOf(node.copy(selectIndex = 1))) }
        compose.waitUntil(20_000) { state.layoutInfo.totalItemsCount > 20 }
        compose.runOnIdle { controller.scrollToBottom() }
        compose.waitUntil(10_000) { state.layoutInfo.totalItemsCount > 20 && controller.request == null &&
            state.layoutInfo.visibleItemsInfo.any { it.key.toString() == "${node.id}:${alternate.id}:footer" } }
        compose.onNode(hasContentDescription(context.getString(R.string.copy)) and
            hasAnyAncestor(hasTestTag("chat_row:${node.id}:${alternate.id}:footer")))
            .performSemanticsAction(SemanticsActions.OnClick) { it.invoke() }
        awaitClipboard(alternate.toText())
        assertEquals(text, original.toText())
        assertTrue(rows().none { it.config.getOrNull(SemanticsProperties.TestTag)?.contains(":${original.id}:text:") == true })
    }

    @Test fun documentSelectAllCopiesOffscreenTextWithoutPinningAllItems() {
        val group = "complete_document"
        val paragraphs = (0..1999).map { "row_$it alpha beta gamma " + "文".repeat(24) }
        val full = paragraphs.joinToString("\n")
        assertTrue(full.length >= 100_000)
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalClipboard provides clipboardBackend) {
                SharedSelectableContent({ if (it == group) full else null }, Modifier.fillMaxSize()) {
                    LazyColumn(state = state) {
                        items(paragraphs.indices.toList(), key = { it }) { index ->
                            SelectableContent(group = group) {
                                BetterText(paragraphs[index], Modifier.fillMaxWidth().padding(8.dp).testTag("select_$index"),
                                    TextStyle(color = Color.Black, fontSize = 18.sp))
                            }
                        }
                    }
                }
            }
        }
        val before = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        compose.onNodeWithTag("select_0").performTouchInput { longClick(center) }
        compose.waitUntil(5_000) { toolbar.selectAll != null }
        compose.runOnIdle { toolbar.selectAll!!.invoke() }
        scroll(1000)
        compose.onNodeWithTag("select_0").assertDoesNotExist()
        compose.waitUntil(5_000) { toolbar.copy != null && toolbar.selectAll == null }
        compose.runOnIdle { toolbar.copy!!.invoke() }
        awaitClipboard(full)
        val active = compose.onAllNodes(SemanticsMatcher("selection rows") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("select_") == true
        }, useUnmergedTree = true).fetchSemanticsNodes().size
        assertTrue(active < 30)
        val after = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        val heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "P3 select_all chars=${full.length} total_rows=${paragraphs.size} active_rows=$active " +
                "heap_before=$heapBefore heap_after=$heapAfter pss_before_kib=${before.totalPss} pss_after_kib=${after.totalPss}\n")
        })
    }

    @Test fun rawChunksCopyAcrossBoundariesWithoutInventingNewlines() {
        val group = "raw"
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalClipboard provides clipboardBackend) {
                SharedSelectableContent({ null }, Modifier.fillMaxSize()) {
                    Column {
                        listOf("alpha ", "beta").forEachIndexed { index, part ->
                            SelectableContent(group = group, separatorBefore = "") {
                                BetterText(part, Modifier.width(160.dp).padding(10.dp).testTag("raw_$index"),
                                    TextStyle(color = Color.Black, fontSize = 20.sp))
                            }
                        }
                    }
                }
            }
        }
        val first = compose.onNodeWithTag("raw_0").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("raw_1").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(first.topLeft + androidx.compose.ui.geometry.Offset(first.width * .25f, first.height / 2))
            advanceEventTime(700)
            moveTo(last.topLeft + androidx.compose.ui.geometry.Offset(last.width - 12f, last.height / 2), 250)
            up()
        }
        compose.waitUntil(5_000) { toolbar.copy != null }
        compose.runOnIdle { toolbar.copy!!.invoke() }
        awaitClipboard("alpha beta")
    }

    @Test fun copySheetUsesLazySourceAndCopiesTheCompleteOriginal() {
        val message = UIMessage.assistant(text)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalClipboard provides clipboardBackend) { ChatMessageCopySheet(message) {} } } }
        compose.onNodeWithTag("message_copy_list").assertExists()
        compose.onNodeWithText(context.getString(R.string.copy_all)).performClick()
        awaitClipboard(message.toText())
    }

    @Test fun selectionDragDoesNotCrossUnrelatedMessageGroups() {
        compose.setContent {
            CompositionLocalProvider(LocalTextToolbar provides toolbar, LocalClipboard provides clipboardBackend) {
                SharedSelectableContent({ null }, Modifier.fillMaxSize()) {
                    Column {
                        listOf("alpha", "beta").forEach { word ->
                            SelectableContent(group = word) {
                                BetterText(word, Modifier.width(160.dp).padding(10.dp).testTag(word),
                                    TextStyle(color = Color.Black, fontSize = 20.sp))
                            }
                        }
                    }
                }
            }
        }
        val first = compose.onNodeWithTag("alpha").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("beta").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(first.topLeft + androidx.compose.ui.geometry.Offset(first.width * .25f, first.height / 2))
            advanceEventTime(700)
            moveTo(second.bottomRight - androidx.compose.ui.geometry.Offset(12f, second.height / 2), 250)
            up()
        }
        compose.waitUntil(5_000) { toolbar.copy != null }
        compose.runOnIdle { toolbar.copy!!.invoke() }
        awaitClipboard("alpha")
    }

    @Test fun solidSegmentsKeepOneBackgroundAndOnlyRoundTheOuterCorners() {
        val split = mutableStateOf(false)
        val style = ChatBubble(colorHex = "#FF0000", geometry = ChatBubbleGeometry(16f, 8f))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(160.dp).background(Color.White).testTag("bubble")) {
                    if (split.value) Column {
                        ChatBubbleSurface(style, Color.Red, Modifier.fillMaxWidth(), segment = BubbleSegment(true, false, 0.dp)) { Spacer(Modifier.height(24.dp)) }
                        ChatBubbleSurface(style, Color.Red, Modifier.fillMaxWidth(), segment = BubbleSegment(false, true, 0.dp)) { Spacer(Modifier.height(24.dp)) }
                    } else ChatBubbleSurface(style, Color.Red, Modifier.fillMaxWidth()) { Spacer(Modifier.height(48.dp)) }
                }
            }
        }
        val whole = compose.onNodeWithTag("bubble").captureToImage().toPixelMap()
        compose.runOnIdle { split.value = true }
        val parts = compose.onNodeWithTag("bubble").captureToImage().toPixelMap()
        assertEquals(whole.width, parts.width); assertEquals(whole.height, parts.height)
        for (y in 0 until whole.height) for (x in 0 until whole.width) {
            val a = whole[x, y]; val b = parts[x, y]
            // Uniform and per-corner rounded clips use different Android outline AA paths.
            val delta = maxOf(kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green), kotlin.math.abs(a.blue - b.blue))
            assertTrue("corner/edge $x,$y delta=$delta", delta <= .16f)
            if (y in whole.height / 2 - 2..whole.height / 2 + 2) assertEquals("seam $x,$y", a, b)
        }
    }

    private class CapturingToolbar : TextToolbar {
        @Volatile override var status = TextToolbarStatus.Hidden
        @Volatile var copy: (() -> Unit)? = null
        @Volatile var selectAll: (() -> Unit)? = null
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copy = onCopyRequested; selectAll = onSelectAllRequested; status = TextToolbarStatus.Shown
        }
        override fun hide() { status = TextToolbarStatus.Hidden; copy = null; selectAll = null }
    }
    private class CapturingClipboard : Clipboard {
        @Volatile var entry: ClipEntry? = null
        override suspend fun getClipEntry() = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
    }
}
