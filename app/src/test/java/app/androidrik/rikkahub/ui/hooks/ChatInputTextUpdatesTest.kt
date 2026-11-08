package app.androidrik.rikkahub.ui.hooks

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatInputTextUpdatesTest {
    @Test fun `identical programmatic text preserves selection`() {
        val state = ChatInputState()
        state.setMessageText("长文".repeat(50_000))
        state.textContent.edit { selection = TextRange(20, 40) }
        state.setMessageText("长文".repeat(50_000))
        assertEquals(TextRange(20, 40), state.textContent.selection)
    }

    @Test fun `append keeps existing content and moves caret to end`() {
        val state = ChatInputState()
        val original = "文".repeat(100_000)
        state.setMessageText(original)
        state.textContent.edit { selection = TextRange(25) }
        state.appendText("追加😀")
        assertEquals(original + "追加😀", state.textContent.text.toString())
        assertEquals(TextRange(original.length + "追加😀".length), state.textContent.selection)
    }

    @Test fun `empty append preserves selection`() {
        val state = ChatInputState()
        state.setMessageText("原文")
        state.textContent.edit { selection = TextRange(0) }
        state.appendText("")
        assertEquals(TextRange(0), state.textContent.selection)
    }
}
