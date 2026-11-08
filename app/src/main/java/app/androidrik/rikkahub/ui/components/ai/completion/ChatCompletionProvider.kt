package app.androidrik.rikkahub.ui.components.ai.completion

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange

data class ChatCompletionContext(
    val text: String,
    /** Selection in the complete document; [text] is a bounded window ending at the cursor. */
    val selection: TextRange,
    val textOffset: Int = 0,
) {
    val cursor: Int = selection.max - textOffset
    val hasSelection: Boolean = selection.min != selection.max
    fun documentRange(range: TextRange) = TextRange(range.start + textOffset, range.end + textOffset)
}

internal const val MAX_COMPLETION_CONTEXT = 1024

/** Copy only the cursor's neighborhood, never materialize the complete editor buffer. */
internal fun completionContext(text: CharSequence, selection: TextRange): ChatCompletionContext? {
    if (!selection.collapsed || selection.max !in 0..text.length) return null
    val cursor = selection.max
    var start = (cursor - MAX_COMPLETION_CONTEXT - 1).coerceAtLeast(0)
    if (start > 0 && start < cursor && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start++
    val window = buildString(cursor - start) {
        for (index in start until cursor) append(text[index])
    }
    return ChatCompletionContext(window, selection, start)
}

data class ChatCompletionList(
    val providerId: String,
    val replacementRange: TextRange,
    val items: List<ChatCompletionItem>,
)

data class ChatCompletionItem(
    val label: String,
    val insertText: String,
    val detail: String? = null,
    val icon: ImageVector? = null,
    val sortScore: Int = 0,
)

interface ChatCompletionProvider {
    val id: String

    suspend fun complete(context: ChatCompletionContext): ChatCompletionList?
}
