package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListItemInfo
import app.androidrik.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

internal data class ConversationScrollAnchor(val conversation: Conversation, val scrollOffset: Int)

internal data class ConversationViewportRestore(
    val anchor: ConversationScrollAnchor?,
    val removedIds: Set<Uuid> = emptySet(),
    val retainedItems: List<ConversationListItem> = emptyList(),
)

/** Keep the first visible conversation in place, falling forward then backward if it is removed. */
internal fun captureConversationScrollAnchor(
    rows: List<ConversationListItem>,
    visibleItems: List<LazyListItemInfo>,
    removedIds: Set<Uuid> = emptySet(),
): ConversationScrollAnchor? {
    // Keys remain valid even if Paging presents another snapshot before the next list measure.
    val firstVisible = visibleItems.firstOrNull { visible ->
        rows.any { it is ConversationListItem.Item && it.conversation.id.toString() == visible.key }
    } ?: return null
    val firstIndex = rows.indexOfFirst {
        it is ConversationListItem.Item && it.conversation.id.toString() == firstVisible.key
    }
    val original = (rows[firstIndex] as ConversationListItem.Item).conversation
    if (original.id !in removedIds) return ConversationScrollAnchor(original, -firstVisible.offset)
    val candidates = rows.drop(firstIndex) + rows.take(firstIndex).asReversed()
    val survivor = candidates.firstNotNullOfOrNull {
        (it as? ConversationListItem.Item)?.conversation?.takeUnless { row -> row.id in removedIds }
    } ?: return null
    return ConversationScrollAnchor(survivor, -firstVisible.offset)
}
