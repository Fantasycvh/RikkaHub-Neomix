package app.androidrik.rikkahub.ui.pages.favorite

import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.room.Room
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.NodeFavoriteTarget
import app.androidrik.rikkahub.data.repository.FavoriteRepository
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/**
 * 收藏页「左划删除 → 撤销恢复」的真机回归。
 *
 * 背景：撤销恢复失败曾两次出现（时序竞态 / 滑动状态残留，见 docs/design/design-favorite-management.md
 * §1.4），本次排查用户仍在报「撤销后恢复失败」。测试直接渲染真实 FavoritePage（内存 Room + 真实 VM），
 * 注入真实左划手势与 snackbar 点击，覆盖整条 UI 链路。
 *
 * 注意：SettingsStore 必须取 Koin 里应用已创建的实例——本类再 new 一个会在同一 DataStore 文件上
 * 开第二个实例（PreferenceDataStoreFactory 按文件独占，直接 IllegalStateException）。
 */
@RunWith(AndroidJUnit4::class)
class FavoriteUndoRestoreTest {
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

    private lateinit var repository: FavoriteRepository

    private fun titleOf(n: Int) = "Undo Repro Conversation $n"

    private fun setupFavorites(count: Int): FavoriteVM {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory())
            .allowMainThreadQueries()
            .build()
        database = db
        repository = FavoriteRepository(db.favoriteDao())
        val settingsStore: SettingsStore =
            org.koin.core.context.GlobalContext.get().get()
        val vm = FavoriteVM(repository, settingsStore)
        runBlocking {
            repeat(count) { n ->
                repository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = Uuid.random(),
                        conversationTitle = titleOf(n),
                        nodeId = Uuid.random(),
                        node = MessageNode.of(
                            UIMessage(
                                role = MessageRole.USER,
                                parts = listOf(UIMessagePart.Text("favorite body $n")),
                            )
                        ),
                    )
                )
            }
        }
        return vm
    }

    private lateinit var database: AppDatabase

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    private fun setContent(vm: FavoriteVM) {
        rule.setContent {
            RikkahubTheme {
                CompositionLocalProvider(LocalNavController provides Navigator(mutableListOf())) {
                    FavoritePage(vm = vm)
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

    @Test
    fun swipeDeleteThenUndoRestoresFavorite() {
        val vm = setupFavorites(1)
        val undoText = context.getString(R.string.history_page_undo)
        val title = titleOf(0)
        setContent(vm)

        awaitText(title, exists = true)
        swipeAway(title)

        // 撤销 snackbar 出现 → 点撤销 → 卡片回来
        awaitText(undoText, exists = true)
        rule.onAllNodesWithText(undoText).onFirst().performClick()
        awaitText(title, exists = true, timeout = 10_000)

        // 等 2 秒确认不是「闪现后被复活的状态再次删掉」
        Thread.sleep(2_000)
        assertTrue(rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())

        runBlocking {
            assertEquals(1, repository.listByType(app.androidrik.rikkahub.data.model.FavoriteType.NODE)
                .first().size)
        }
    }

    @Test
    fun undoAfterSuccessiveDeletesRestoresAllPending() {
        val vm = setupFavorites(2)
        val undoText = context.getString(R.string.history_page_undo)
        val title0 = titleOf(0)
        val title1 = titleOf(1)
        setContent(vm)

        awaitText(title0, exists = true)
        // 连续删除两条：第二条的 snackbar 会抢占第一条 → 第一条的撤销协程收到 Dismissed
        swipeAway(title0)
        swipeAway(title1)

        awaitText(undoText, exists = true)
        rule.onAllNodesWithText(undoText).onFirst().performClick()

        // 两条都应恢复（当前实现预期只有最后一条恢复 → 该测试暴露缺陷）
        awaitText(title0, exists = true, timeout = 10_000)
        awaitText(title1, exists = true, timeout = 10_000)

        runBlocking {
            assertEquals(2, repository.listByType(app.androidrik.rikkahub.data.model.FavoriteType.NODE)
                .first().size)
        }
    }

    @Test
    fun explicitDeleteButtonThenUndoRestoresFavorite() {
        val vm = setupFavorites(1)
        val undoText = context.getString(R.string.history_page_undo)
        val title = titleOf(0)
        setContent(vm)

        awaitText(title, exists = true)
        rule.onNodeWithTag("favorite_card_delete").performClick()
        awaitText(title, exists = false)

        awaitText(undoText, exists = true)
        rule.onAllNodesWithText(undoText).onFirst().performClick()
        awaitText(title, exists = true, timeout = 10_000)
    }
}
