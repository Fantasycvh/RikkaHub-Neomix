package app.androidrik.rikkahub.ui.pages.chat

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.model.Conversation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Regression coverage for upstream 9ce1ae1: a tall header must not starve the short list. */
@RunWith(AndroidJUnit4::class)
class ConversationListHeaderTest {
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
    private val rows = List(30) { index ->
        ConversationListItem.Item(
            Conversation(assistantId = assistantId, title = "Conversation $index", messageNodes = emptyList()),
        )
    }

    private fun showList(
        state: LazyListState,
        selected: Conversation,
        headerEnabled: androidx.compose.runtime.State<Boolean> = mutableStateOf(true),
        data: List<ConversationListItem> = rows,
    ) {
        val flow = MutableStateFlow(PagingData.from(data))
        rule.setContent {
            MaterialTheme {
                val pagingItems = flow.collectAsLazyPagingItems()
                Column(Modifier.height(220.dp).fillMaxWidth()) {
                    ConversationList(
                        current = selected,
                        conversations = pagingItems,
                        conversationJobs = emptyList(),
                        listState = state,
                        modifier = Modifier.weight(1f).testTag("conversations"),
                        header = if (headerEnabled.value) {
                            { Text("Drawer header", Modifier.height(280.dp).testTag("header")) }
                        } else null,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun currentConversationAutoScrollIncludesHeaderOffset() {
        val state = LazyListState()
        showList(state, rows[15].conversation)
        rule.onNodeWithText("Conversation 15").assertIsDisplayed()
        rule.runOnIdle { assertEquals(16, state.firstVisibleItemIndex) }
    }

    @Test
    fun currentConversationAutoScrollWithoutHeaderHasNoOffset() {
        val state = LazyListState()
        showList(state, rows[15].conversation, mutableStateOf(false))
        rule.onNodeWithText("Conversation 15").assertIsDisplayed()
        rule.runOnIdle { assertEquals(15, state.firstVisibleItemIndex) }
    }

    @Test
    fun headerTallerThanViewportScrollsAwayAndConversationRemainsReachable() {
        val state = LazyListState()
        // An unsaved/current conversation absent from paging prevents initial auto-scroll.
        showList(state, Conversation(assistantId = assistantId, messageNodes = emptyList()))
        rule.onNodeWithTag("header").assertIsDisplayed()
        rule.onNodeWithTag("conversations").performScrollToIndex(1)
        rule.onNodeWithText("Conversation 0").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, state.firstVisibleItemIndex) }
    }

    @Test
    fun insertingAndRemovingHeaderKeepsCurrentConversationVisible() {
        val state = LazyListState()
        val headerEnabled = mutableStateOf(false)
        showList(state, rows[15].conversation, headerEnabled)
        rule.onNodeWithText("Conversation 15").assertIsDisplayed()
        rule.runOnIdle { headerEnabled.value = true }
        rule.waitForIdle()
        rule.onNodeWithText("Conversation 15").assertIsDisplayed()
        rule.runOnIdle { assertEquals(16, state.firstVisibleItemIndex) }
        rule.runOnIdle { headerEnabled.value = false }
        rule.waitForIdle()
        rule.onNodeWithText("Conversation 15").assertIsDisplayed()
        rule.runOnIdle { assertEquals(15, state.firstVisibleItemIndex) }
    }
    @Test
    fun deletingVisibleAnchorNeverDrawsIntermediateRefreshWindow() {
        val data = List(100) { index ->
            ConversationListItem.Item(Conversation(assistantId = assistantId, title = "Atomic $index", messageNodes = emptyList()))
        }
        val flow = MutableStateFlow(PagingData.from<ConversationListItem>(data))
        val state = LazyListState()
        val restore = mutableStateOf<ConversationViewportRestore?>(null)
        val measuredKeys = mutableListOf<Any>()
        var track = false
        rule.setContent {
            MaterialTheme {
                val paging = flow.collectAsLazyPagingItems()
                Column(Modifier.height(220.dp)) {
                    ConversationList(
                        current = data[0].conversation, conversations = paging, conversationJobs = emptyList(),
                        listState = state, autoScrollToCurrent = false, animateChanges = false,
                        viewportRestore = restore.value, retainedItems = restore.value?.retainedItems.orEmpty(),
                        onViewportRestored = { restore.value = null },
                        modifier = Modifier.weight(1f).onGloballyPositioned {
                            if (track) state.layoutInfo.visibleItemsInfo.firstOrNull()?.let { measuredKeys.add(it.key) }
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { state.requestScrollToItem(60, 17) }
        rule.waitForIdle()
        rule.runOnIdle {
            val deleted = setOf(data[60].conversation.id)
            val anchor = captureConversationScrollAnchor(data, state.layoutInfo.visibleItemsInfo, deleted)!!
            restore.value = ConversationViewportRestore(anchor, deleted, data)
            track = true
        }
        rule.waitForIdle()
        // Paging can first present a window that does not contain the saved anchor.
        rule.runOnIdle { flow.value = PagingData.from(data.subList(10, 30)) }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(data[60].conversation.id.toString(), state.layoutInfo.visibleItemsInfo.first().key)
            flow.value = PagingData.from(data.subList(40, 90).filter { it.conversation.id != data[60].conversation.id })
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(data[61].conversation.id.toString(), state.layoutInfo.visibleItemsInfo.first().key)
            assertEquals(17, state.firstVisibleItemScrollOffset)
            val allowed = setOf(data[60].conversation.id.toString(), data[61].conversation.id.toString())
            org.junit.Assert.assertTrue("Unexpected drawn window $measuredKeys", measuredKeys.isNotEmpty() && measuredKeys.all { it in allowed })
        }
    }

}
