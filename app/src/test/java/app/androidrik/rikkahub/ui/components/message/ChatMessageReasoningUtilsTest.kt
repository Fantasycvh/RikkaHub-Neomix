package app.androidrik.rikkahub.ui.components.message

import app.androidrik.rikkahub.utils.extractThinkingTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageReasoningUtilsTest {

    @Test
    fun `extractThinkingTitle returns last non-blank bold-only line`() {
        val text = """
            **First Step**
            Analyzing the problem...
            **Final Step**
            Some trailing reasoning
            
        """.trimIndent()

        assertEquals("Final Step", text.extractThinkingTitle())
    }

    @Test
    fun `extractThinkingTitle ignores empty bold lines and empty text`() {
        assertNull("".extractThinkingTitle())
        assertNull("   \n  ".extractThinkingTitle())
        assertEquals("Valid", "**Valid**\n**   **".extractThinkingTitle())
    }

    @Test
    fun `reasoningPreviewTail returns same instance when within limit`() {
        val short = "Thinking briefly."
        assertSame(short, reasoningPreviewTail(short, maxChars = 100))
    }

    @Test
    fun `reasoningPreviewTail cuts at paragraph boundary without splitting surrogate pairs`() {
        val prefix = "A".repeat(200)
        val paragraph1 = "B".repeat(50)
        val paragraph2 = "C".repeat(220)
        val full = "$prefix\n\n$paragraph1\n\n$paragraph2"

        val tail = reasoningPreviewTail(full, maxChars = 300)
        assertEquals("$paragraph1\n\n$paragraph2", tail)

        // 边界落在 emoji 代理对中间时不应产生孤立代理字符
        val emojiTail = "\uD83D\uDE00".repeat(300)
        val sliced = reasoningPreviewTail(emojiTail, maxChars = 101)
        assertTrue(sliced.length <= 101)
        assertTrue(!sliced.first().isLowSurrogate())
    }
}
