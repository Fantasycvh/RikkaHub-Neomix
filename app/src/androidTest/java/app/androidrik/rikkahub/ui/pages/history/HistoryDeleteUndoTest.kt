package app.androidrik.rikkahub.ui.pages.history

import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/**
 * 历史页「左划删除会话 → 撤销恢复」的真机回归——与收藏页 `FavoriteUndoRestoreTest` 同族的
 * snackbar 抢占缺陷：连删多条时被抢占的撤销静默丢失，且原实现的 Dismissed 分支会把**附件文件**
 * 一并清掉（恢复不完整）。修复后撤销恢复队列里全部待撤销会话（`HistoryVM`）。
 *
 * VM 用 Koin 里应用已创建的单件（ConversationRepository/SettingsStore/ChatService）构造，
 * 会话插入当前助手的真实 debug DB，测试后清理；不触碰 release 数据。
 */
@RunWith(AndroidJUnit4::class)
class HistoryDeleteUndoTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { activityRule ->
        var activity: SketchTestHostActivity? = null
        activityRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    private val koin = org.koin.core.context.GlobalContext.get()
    private lateinit var vm: HistoryVM
    private val inserted = mutableListOf<Conversation>()

    private fun titleOf(n: Int) = "Undo Repro Conv $n"

    private fun setupConversations(count: Int) {
        val repo: ConversationRepository = koin.get()
        val settings: SettingsStore = koin.get()
        val chatService: ChatService = koin.get()
        val appScope: AppScope = koin.get()
        vm = HistoryVM(repo, settings, chatService, appScope)
        runBlocking {
            val assistant = settings.settingsFlow.first().getCurrentAssistant()
                ?: error("debug settings has no current assistant")
            repeat(count) { n ->
                val conv = Conversation(
                    id = Uuid.random(),
                    assistantId = assistant.id,
                    title = titleOf(n),
                    messageNodes = listOf(
                        MessageNode.of(
                            UIMessage(
                                role = MessageRole.USER,
                                parts = listOf(UIMessagePart.Text("body $n")),
                            )
                        )
                    ),
                )
                repo.insertConversation(conv)
                inserted += conv
            }
        }
    }

    @After
    fun tearDown() {
        if (!::vm.isInitialized) return
        val repo: ConversationRepository = koin.get()
        runBlocking {
            inserted.forEach { conv ->
                // 无论测试结束时它处于已删除/已恢复哪种状态，彻底删除并清文件
                runCatching { repo.deleteConversation(conv, deleteFiles = true) }
            }
        }
    }

    private fun setContent() {
        rule.setContent {
            RikkahubTheme {
                CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf())) {
                    HistoryPage(vm = vm)
                }
            }
        }
    }

    private fun awaitText(text: String, exists: Boolean, timeout: Long = 5_000) {
        rule.waitUntil(timeoutMillis = timeout) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().let { it.isNotEmpty() == exists }
        }
    }

    private fun swipeAway(title: String) {
        rule.onNodeWithText(title).performTouchInput {
            swipeLeft(startX = right - 5f, endX = left + 5f)
        }
        awaitText(title, exists = false)
    }

    private fun repo() = koin.get<ConversationRepository>()

    @Test
    fun swipeDeleteThenUndoRestoresConversation() = runBlocking {
        setupConversations(1)
        val undoText = context.getString(R.string.history_page_undo)
        val title = titleOf(0)
        setContent()

        awaitText(title, exists = true)
        swipeAway(title)

        awaitText(undoText, exists = true)
        rule.onAllNodesWithText(undoText).onFirst().performClick()
        awaitText(title, exists = true, timeout = 10_000)

        assertNotNull(repo().getConversationById(inserted[0].id))
    }

    @Test
    fun undoAfterSuccessiveDeletesRestoresAllPending() = runBlocking {
        setupConversations(2)
        val undoText = context.getString(R.string.history_page_undo)
        val title0 = titleOf(0)
        val title1 = titleOf(1)
        setContent()

        awaitText(title0, exists = true)
        // 连续删除两条：第二条 snackbar 抢占第一条 → 修复前第一条的撤销静默丢失
        swipeAway(title0)
        swipeAway(title1)

        awaitText(undoText, exists = true)
        rule.onAllNodesWithText(undoText).onFirst().performClick()

        awaitText(title0, exists = true, timeout = 10_000)
        awaitText(title1, exists = true, timeout = 10_000)

        assertNotNull(repo().getConversationById(inserted[0].id))
        assertNotNull(repo().getConversationById(inserted[1].id))
    }

    @Test
    fun expiredUndoIsNotRestorableAndFilesCleanedWithoutCrash() = runBlocking {
        setupConversations(1)
        val title = titleOf(0)
        vm.undoRetentionMs = 500
        setContent()

        awaitText(title, exists = true)
        swipeAway(title)
        // 不点撤销，等到期清理跑完：DB 已删、队列条目被到期任务摘除并清理文件
        Thread.sleep(1_500)

        assertNull(repo().getConversationById(inserted[0].id))
    }
}
