package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import app.androidrik.rikkahub.data.model.Conversation
import kotlinx.coroutines.flow.first

/** Load through the target, then use its index in the actual presented list, including separators. */
internal suspend fun locateConversationInPaging(
    conversations: LazyPagingItems<ConversationListItem>,
    listState: LazyListState,
    target: Conversation,
    headerCount: Int,
    scrollOffset: Int = 0,
): Boolean {
    while (true) {
        loadConversationInPaging(conversations, target) ?: return false
        withFrameNanos { }
        val index = conversations.itemSnapshotList.items.indexOfFirst {
            (it as? ConversationListItem.Item)?.conversation?.id == target.id
        }
        if (index >= 0) {
            listState.scrollToItem(index + headerCount, scrollOffset)
            return true
        }
    }
}

/** Resolve the row without moving the viewport. Restoration is applied with the data's first measure. */
internal suspend fun loadConversationInPaging(
    conversations: LazyPagingItems<ConversationListItem>,
    target: Conversation,
): Int? {
    while (true) {
        val before = snapshotFlow { conversations.itemSnapshotList to conversations.loadState }
            .first { (snapshot, states) ->
                val first = snapshot.items.firstNotNullOfOrNull { it as? ConversationListItem.Item }
                // A folder switch can temporarily retain the previous PagingData in the presenter.
                states.refresh is LoadState.Error ||
                    (states.refresh !is LoadState.Loading && first != null &&
                        first.conversation.folderId == target.folderId &&
                        first.conversation.assistantId == target.assistantId)
            }
        val (snapshot, states) = before
        (states.refresh as? LoadState.Error)?.let { throw it.error }
        val index = snapshot.items.indexOfFirst {
            (it as? ConversationListItem.Item)?.conversation?.id == target.id
        }
        if (index >= 0) return index
        // A refresh after deleting rows may start around the previous scroll anchor.
        // Load the preceding pages as well, since the target can be on either side.
        val loadBefore = !states.prepend.endOfPaginationReached
        val direction = if (loadBefore) states.prepend else states.append
        (direction as? LoadState.Error)?.let { throw it.error }
        if (direction.endOfPaginationReached) return null

        // Access sends Paging a load hint. Snapshot/peek alone never requests new rows.
        val accessIndex = if (loadBefore) 0 else conversations.itemCount - 1
        if (accessIndex in 0 until conversations.itemCount) conversations[accessIndex]
        snapshotFlow { conversations.itemSnapshotList to conversations.loadState }
            .first { it != before }
    }
}
