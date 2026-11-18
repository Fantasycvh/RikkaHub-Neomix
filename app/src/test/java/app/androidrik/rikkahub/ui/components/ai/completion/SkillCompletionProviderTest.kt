package app.androidrik.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillCompletionProviderTest {
    @Test
    fun `accepts a bare slash and skill names at a token boundary`() {
        assertEquals(TextRange(0, 1), findSkillCommand(ChatCompletionContext("/", TextRange(1))))
        val text = "Ask /skill-2"
        assertEquals(TextRange(4, text.length), findSkillCommand(ChatCompletionContext(text, TextRange(text.length))))
    }

    @Test
    fun `ignores urls paths selections and unproven boundaries`() {
        for (text in listOf("https://site", "relative/path", "/dir/file", "@/file")) {
            assertNull(text, findSkillCommand(ChatCompletionContext(text, TextRange(text.length))))
        }
        assertNull(findSkillCommand(ChatCompletionContext("/selected", TextRange(0, 9))))
        assertNull(findSkillCommand(ChatCompletionContext("/clipped", TextRange(18), textOffset = 10)))
    }
}
