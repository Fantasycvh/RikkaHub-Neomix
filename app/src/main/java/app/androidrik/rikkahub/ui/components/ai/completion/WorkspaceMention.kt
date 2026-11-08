package app.androidrik.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange

internal data class WorkspaceMention(val query: String, val range: TextRange)

internal fun findWorkspaceMention(context: ChatCompletionContext): WorkspaceMention? {
    if (context.hasSelection || context.cursor !in 0..context.text.length) return null
    val text = context.text
    val cursor = context.cursor
    var start = cursor - 1
    while (start >= 0 && cursor - start <= MAX_COMPLETION_CONTEXT && text[start] != '@') {
        if (text[start].isWhitespace()) return null
        start--
    }
    if (start < 0 || cursor - start > MAX_COMPLETION_CONTEXT || text[start] != '@') return null
    // A clipped window cannot prove the preceding boundary of its first character.
    if (start == 0 && context.textOffset > 0) return null
    if (start > 0 && !(text[start - 1].isWhitespace() || text[start - 1] in "([{<\"'")) return null
    return WorkspaceMention(text.substring(start + 1, cursor), context.documentRange(TextRange(start, cursor)))
}
