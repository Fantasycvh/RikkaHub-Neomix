package app.androidrik.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** Wait for this generation's reply and its render entry; a drag abandons this attempt. */
@Composable
internal fun AnchorReplyStartEffect(
    conversationKey: Any,
    generationKey: Any?,
    loading: Boolean,
    dragging: Boolean,
    targetIndex: () -> Int,
    onAnchor: (Int) -> Unit,
) {
    val dragged by rememberUpdatedState(dragging)
    val target by rememberUpdatedState(targetIndex)
    val anchor by rememberUpdatedState(onAnchor)
    LaunchedEffect(conversationKey, generationKey, loading) {
        if (!loading) return@LaunchedEffect
        val (cancelled, index) = snapshotFlow { dragged to target() }
            .first { (dragging, index) -> dragging || index >= 0 }
        if (!cancelled) anchor(index)
    }
}
