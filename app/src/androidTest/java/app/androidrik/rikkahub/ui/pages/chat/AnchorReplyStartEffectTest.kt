package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.SketchTestHostActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AnchorReplyStartEffectTest {
    @get:Rule val compose = createAndroidComposeRule<SketchTestHostActivity>()
    private val loading = mutableStateOf(false)
    private val dragging = mutableStateOf(false)
    private val generation = mutableIntStateOf(0)
    private val replyIndex = mutableIntStateOf(-1)
    private val anchored = mutableListOf<Int>()
    private val list = LazyListState(firstVisibleItemIndex = 5)

    private fun mount() {
        compose.setContent {
            LazyColumn(state = list) { items(100) { Box(Modifier.height(40.dp)) } }
            AnchorReplyStartEffect("conversation", generation.intValue, loading.value, dragging.value,
                targetIndex = { replyIndex.intValue },
            ) { index -> anchored += index; list.requestScrollToItem(index) }
        }
    }

    @Test fun waitsForSlowReplyThenAnchorsOnceWithoutFollowingGrowth() {
        mount()
        compose.runOnIdle { loading.value = true }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { assertEquals(emptyList<Int>(), anchored); assertEquals(5, list.firstVisibleItemIndex) }
        compose.runOnIdle { replyIndex.intValue = 20 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(20), anchored); assertEquals(20, list.firstVisibleItemIndex) }
        compose.runOnIdle { replyIndex.intValue = 30 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(20), anchored); assertEquals(20, list.firstVisibleItemIndex) }
    }

    @Test fun dragAbandonsPendingAnchorAndReplacementGenerationCanAnchorAgain() {
        mount()
        compose.runOnIdle { loading.value = true }
        compose.runOnIdle { dragging.value = true }
        compose.waitForIdle()
        compose.runOnIdle { dragging.value = false; replyIndex.intValue = 20 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(emptyList<Int>(), anchored) }
        compose.runOnIdle { generation.intValue++ }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(20), anchored) }
    }

    @Test fun cancelledGenerationNeverAnchorsWhenReplyArrivesLater() {
        mount()
        compose.runOnIdle { loading.value = true }
        compose.runOnIdle { loading.value = false }
        compose.runOnIdle { replyIndex.intValue = 20 }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(emptyList<Int>(), anchored) }
    }
}
