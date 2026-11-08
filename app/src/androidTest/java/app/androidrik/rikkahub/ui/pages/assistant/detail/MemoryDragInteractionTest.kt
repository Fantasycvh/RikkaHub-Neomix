package app.androidrik.rikkahub.ui.pages.assistant.detail

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.SketchTestHostActivity
import app.androidrik.rikkahub.data.ai.MemoryConsolidationStatus
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.context.LocalToaster
import androidx.compose.material3.MaterialTheme
import com.dokar.sonner.rememberToasterState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MemoryDragInteractionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val rule = AndroidComposeTestRule(
        ActivityScenarioRule<SketchTestHostActivity>(Intent(context, SketchTestHostActivity::class.java)),
    ) { activityRule ->
        var activity: SketchTestHostActivity? = null
        activityRule.scenario.onActivity { activity = it }
        requireNotNull(activity)
    }

    @Test fun dragCommitsOnReleaseAndAnEmptyDragDoesNotWrite() {
        val memories = mutableStateOf((1..3).map {
            AssistantMemory(id = "$it", content = "memory-$it", sortKey = it, displayOrder = it * 1_000_000L)
        })
        var commits = 0
        rule.setContent {
            CompositionLocalProvider(LocalSettings provides Settings(), LocalToaster provides rememberToasterState()) {
                MaterialTheme {
                    AssistantMemoryContent(
                        innerPadding = PaddingValues(), assistant = Assistant(), memories = memories.value,
                        onMoveMemory = { id, beforeId ->
                            commits++
                            val order = memories.value.toMutableList()
                            val moved = order.first { it.id == id }
                            order.remove(moved)
                            order.add(if (beforeId == null) order.size else order.indexOfFirst { it.id == beforeId }, moved)
                            memories.value = order.mapIndexed { index, memory -> memory.copy(displayOrder = (index + 1) * 1_000_000L) }
                        },
                        onUpdateAssistant = {}, onAddMemory = {}, onUpdateMemory = {}, onDeleteMemory = {},
                        onTaskDoneChange = { _, _ -> }, onDropTask = {}, consolidationStatus = MemoryConsolidationStatus(),
                        onOrganize = {}, onFreezeAnchors = {}, onApplyProposals = {}, onRejectProposals = {},
                    )
                }
            }
        }
        rule.onNode(hasScrollAction()).performScrollToIndex(1)
        rule.waitForIdle()
        val handles = rule.onAllNodesWithContentDescription("Drag to reorder")
        val first = handles[0].fetchSemanticsNode().boundsInRoot
        val second = handles[1].fetchSemanticsNode().boundsInRoot
        handles[0].performTouchInput {
            down(center)
            advanceEventTime(700)
            val start = center
            for (step in 1..10) moveTo(start + Offset(0f, (second.center.y - first.center.y + 15f) * step / 10), delayMillis = 70)
            advanceEventTime(200)
            up()
        }
        rule.waitUntil(5000) { commits == 1 }
        rule.runOnIdle { assertEquals(listOf("2", "1", "3"), memories.value.map { it.id }) }
        rule.onAllNodesWithContentDescription("Drag to reorder")[0].performTouchInput {
            down(center); advanceEventTime(700); up()
        }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(1, commits) }
    }
}
