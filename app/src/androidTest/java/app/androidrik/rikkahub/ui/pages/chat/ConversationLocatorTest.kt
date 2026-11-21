package app.androidrik.rikkahub.ui.pages.chat

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.Conversation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationLocatorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val rule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { activityRule ->
        var activity: SketchTestHostActivity? = null
        activityRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    private val assistantId = Uuid.random()
    private val state = LazyListState()
    private val result = mutableStateOf<Boolean?>(null)
    private fun conversation(index: Int, folderId: Uuid? = null) = Conversation(
        assistantId = assistantId, title = "Locate row $index", messageNodes = emptyList(), folderId = folderId,
    )

    @Test
    fun loadsBeyondInitialPageAndScrollsIncludingHeaders() = checkDeepTarget(header = true)

    @Test
    fun loadsBeyondInitialPageWithoutDrawerHeader() = checkDeepTarget(header = false)

    @Test
    fun findsTargetBeforeRefreshAnchor() = checkDeepTarget(header = true, initialKey = 60, targetIndex = 15)

    @Test
    fun restoresPixelOffsetAfterLoadingAnchor() = checkDeepTarget(header = true, scrollOffset = 19)

    private fun checkDeepTarget(header: Boolean, initialKey: Int = 0, targetIndex: Int = 75, scrollOffset: Int = 0) {
        val rows = List(90) { conversation(it) }
        val data = buildList {
            add(ConversationListItem.PinnedHeader)
            rows.forEachIndexed { index, row ->
                if (index % 10 == 0) add(ConversationListItem.DateHeader(LocalDate.of(2026, 1, 1).plusDays(index.toLong()), "Group $index"))
                add(ConversationListItem.Item(row))
            }
        }
        val flow = Pager(
            config = PagingConfig(pageSize = 8, initialLoadSize = 8, prefetchDistance = 1, enablePlaceholders = false),
            initialKey = initialKey,
        ) {
            object : PagingSource<Int, ConversationListItem>() {
                override fun getRefreshKey(state: PagingState<Int, ConversationListItem>): Int? = null
                override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ConversationListItem> {
                    delay(10)
                    val key = params.key ?: 0
                    val start = if (params is LoadParams.Prepend) (key - params.loadSize).coerceAtLeast(0) else key
                    val end = if (params is LoadParams.Prepend) key else (start + params.loadSize).coerceAtMost(data.size)
                    return LoadResult.Page(
                        data.subList(start, end),
                        start.takeIf { it > 0 },
                        end.takeIf { it < data.size },
                    )
                }
            }
        }.flow
        showLocator(flow, rows[targetIndex], header, scrollOffset)
        rule.onNodeWithText("Locate").performClick()
        rule.waitUntil(15_000) { result.value != null }
        rule.runOnIdle {
            assertTrue(result.value == true)
            org.junit.Assert.assertEquals(scrollOffset, state.firstVisibleItemScrollOffset)
        }
        rule.onNodeWithText(rows[targetIndex].title).assertIsDisplayed()
    }

    @Test
    fun waitsForTargetFolderInsteadOfUsingPreviousFolderSnapshot() {
        val oldRow = conversation(0)
        val target = conversation(1, Uuid.random())
        val flow = MutableStateFlow(PagingData.from<ConversationListItem>(listOf(ConversationListItem.Item(oldRow))))
        showLocator(flow, target, header = false)
        rule.onNodeWithText("Locate").performClick()
        rule.runOnIdle {
            assertTrue(result.value == null)
            flow.value = PagingData.from(listOf(ConversationListItem.Item(target)))
        }
        rule.waitUntil(5_000) { result.value != null }
        rule.runOnIdle { assertTrue(result.value == true) }
        rule.onNodeWithText(target.title).assertIsDisplayed()
    }

    private fun showLocator(flow: Flow<PagingData<ConversationListItem>>, target: Conversation, header: Boolean, scrollOffset: Int = 0) {
        // An unrelated current ID keeps the list's automatic initial positioning out of this test.
        val unsaved = conversation(-1)
        rule.setContent {
            MaterialTheme {
                val items = flow.collectAsLazyPagingItems()
                val scope = rememberCoroutineScope()
                Column(Modifier.height(280.dp).fillMaxWidth()) {
                    TextButton(onClick = {
                        scope.launch { result.value = locateConversationInPaging(items, state, target, if (header) 1 else 0, scrollOffset) }
                    }) { Text("Locate") }
                    ConversationList(
                        current = unsaved, conversations = items, conversationJobs = emptyList(), listState = state,
                        modifier = Modifier.weight(1f), header = if (header) ({ Text("Drawer header") }) else null,
                    )
                }
            }
        }
        rule.waitForIdle()
    }
}
