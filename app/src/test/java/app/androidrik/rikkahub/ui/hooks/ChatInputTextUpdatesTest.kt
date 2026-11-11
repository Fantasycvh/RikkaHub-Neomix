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

    @Test fun `append keeps existing content, adds newline and moves caret to end`() {
        val state = ChatInputState()
        val original = "文".repeat(100_000)
        state.setMessageText(original)
        state.textContent.edit { selection = TextRange(25) }
        state.appendText("追加😀")
        assertEquals(original + "\n追加😀", state.textContent.text.toString())
        assertEquals(TextRange(original.length + "\n追加😀".length), state.textContent.selection)
    }

    @Test fun `append to blank input adds no leading newline`() {
        val state = ChatInputState()
        state.appendText("第一句")
        assertEquals("第一句", state.textContent.text.toString())
    }

    @Test fun `append to text ending with newline adds no extra newline`() {
        val state = ChatInputState()
        state.setMessageText("第一句\n")
        state.appendText("第二句")
        assertEquals("第一句\n第二句", state.textContent.text.toString())
    }

    @Test fun `empty append preserves selection`() {
        val state = ChatInputState()
        state.setMessageText("原文")
        state.textContent.edit { selection = TextRange(0) }
        state.appendText("")
        assertEquals(TextRange(0), state.textContent.selection)
    }
}
