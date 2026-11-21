package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListItemInfo
import app.androidrik.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import kotlin.uuid.Uuid

class ConversationScrollAnchorTest {
    private val assistantId = Uuid.random()
    private val rows = List(5) {
        ConversationListItem.Item(Conversation(assistantId = assistantId, messageNodes = emptyList()))
    }

    private fun visible(row: Int, offset: Int = -17, staleIndex: Int = row) = object : LazyListItemInfo {
        override val index = staleIndex
        override val key = rows[row].conversation.id.toString()
        override val offset = offset
        override val size = 50
        override val contentType: Any? = "conversation"
    }

    @Test
    fun keepsStableIdAndPixelOffsetEvenWhenLayoutIndexIsStale() {
        val anchor = captureConversationScrollAnchor(rows.drop(1), listOf(visible(2, staleIndex = 80)))!!
        assertEquals(rows[2].conversation.id, anchor.conversation.id)
        assertEquals(17, anchor.scrollOffset)
    }

    @Test
    fun deletedFirstVisibleRowUsesNextSurvivorAtSamePosition() {
        val removed = setOf(rows[2].conversation.id, rows[3].conversation.id)
        val anchor = captureConversationScrollAnchor(rows, listOf(visible(2)), removed)!!
        assertEquals(rows[4].conversation.id, anchor.conversation.id)
        assertEquals(17, anchor.scrollOffset)
    }

    @Test
    fun deletingTailFallsBackToNearestPrecedingSurvivor() {
        val removed = setOf(rows[3].conversation.id, rows[4].conversation.id)
        val anchor = captureConversationScrollAnchor(rows, listOf(visible(3)), removed)!!
        assertEquals(rows[2].conversation.id, anchor.conversation.id)
    }

    @Test
    fun disappearingDateHeaderDoesNotChangeConversationAnchor() {
        val grouped = listOf(ConversationListItem.DateHeader(LocalDate.of(2026, 1, 1), "Today")) + rows
        val before = captureConversationScrollAnchor(grouped, listOf(visible(1, offset = 30)))!!
        val after = captureConversationScrollAnchor(rows, listOf(visible(1, offset = 30)))!!
        assertEquals(before, after)
        assertEquals(-30, after.scrollOffset)
    }

    @Test
    fun deletingAllLoadedConversationsHasNoAnchor() {
        assertNull(captureConversationScrollAnchor(rows, listOf(visible(0)), rows.map { it.conversation.id }.toSet()))
    }

    @Test
    fun emptyRefreshLayoutDoesNotReplaceSavedAnchorWithAnIndex() {
        assertNull(captureConversationScrollAnchor(emptyList(), listOf(visible(0))))
        assertNull(captureConversationScrollAnchor(rows, emptyList()))
    }
}
