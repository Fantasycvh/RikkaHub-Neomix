package app.androidrik.rikkahub.ui.components.message

import me.rerere.bettertext.markdown.MarkdownRenderDocument
import me.rerere.bettertext.markdown.MarkdownRenderUnit

/** Static list projection only. Message data/actions continue to belong to the complete node. */
sealed interface ChatMessageSlice {
    data object Header : ChatMessageSlice
    data object Footer : ChatMessageSlice
    data object Preparing : ChatMessageSlice
    data object Translation : ChatMessageSlice
    data class Part(val bodyIndex: Int, val prepared: Map<Int, MarkdownRenderDocument>) : ChatMessageSlice
    data class Prepared(val documents: Map<Int, MarkdownRenderDocument>) : ChatMessageSlice
    data class Markdown(
        val partIndex: Int,
        val unit: MarkdownRenderUnit,
        val first: Boolean,
        val last: Boolean,
        val group: Any,
        val preview: String? = null,
    ) : ChatMessageSlice
}
