package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.TextRange
import app.androidrik.rikkahub.ui.components.message.plainTextChunks

/** Explicit editing transaction. Only the selected page has a TextField host; drafts never enter Bundle. */
class SegmentedEditSession(source: TextFieldState, val owner: kotlin.uuid.Uuid? = null) {
    private val original = source.text.toString()
    val pages = mutableStateListOf<TextFieldState>().apply {
        plainTextChunks(original).forEach { add(TextFieldState(original.substring(it.start, it.end))) }
        if (isEmpty()) add(TextFieldState())
    }
    var index by mutableIntStateOf(0)
    init {
        var offset = source.selection.start
        while (index < pages.lastIndex && offset > pages[index].text.length) { offset -= pages[index].text.length; index++ }
        pages[index].edit { selection = TextRange(offset.coerceIn(0, length)) }
    }
    fun insertPage() { pages.add(index + 1, TextFieldState()); index++ }
    /** An external source update must never be overwritten by an older draft. */
    fun applyTo(target: TextFieldState): Boolean {
        if (!target.text.contentEquals(original)) return false
        val text = pages.joinToString("") { it.text.toString() }
        val offset = pages.take(index).sumOf { it.text.length }
        val selected = pages[index].selection
        target.edit {
            if (!toString().contentEquals(text)) replace(0, length, text)
            selection = TextRange(offset + selected.start, offset + selected.end)
        }
        return true
    }
}
