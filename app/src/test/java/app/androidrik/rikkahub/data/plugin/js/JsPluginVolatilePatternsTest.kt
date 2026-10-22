package app.androidrik.rikkahub.data.plugin.js

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsPluginVolatilePatternsTest {
    private fun matcher(vararg patterns: String, target: JsTarget = JsTarget.INPUT) =
        JsPluginVolatilePatterns(JsPluginManifest("volatile", "Volatile", target = target,
            volatilePatterns = patterns.toList()))

    private fun message(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.USER, parts = parts.toList())

    @Test
    fun `matches transformed text with UTF16 inclusive offsets and newline joined text parts`() {
        val output = listOf(message(
            UIMessagePart.Text("😀stable"),
            UIMessagePart.Reasoning(reasoning = "ignored timestamp=99"),
            UIMessagePart.Text("timestamp=42"),
        ), message(UIMessagePart.Text("nothing volatile")), message())
        assertEquals(listOf(listOf(9..20), emptyList<IntRange>(), emptyList<IntRange>()),
            matcher("timestamp=\\d+").spans(output))
        assertEquals(listOf(listOf(2..19), emptyList<IntRange>(), emptyList<IntRange>()),
            matcher("stable\ntimestamp=4").spans(output))
    }

    @Test
    fun `all output messages are matched and zero width matches are ignored`() {
        val output = listOf(message(UIMessagePart.Text("v1 v2")), message(UIMessagePart.Text("v3")))
        assertEquals(listOf(listOf(0..1, 3..4), listOf(0..1)), matcher("v\\d", "(?=v)", "v\\d").spans(output))
        assertEquals(listOf(emptyList<IntRange>(), emptyList<IntRange>()), matcher("").spans(output))
    }

    @Test
    fun `absent patterns and non input targets produce no annotation`() {
        val output = listOf(message(UIMessagePart.Text("volatile")))
        assertNull(matcher().spans(output))
        assertNull(matcher("volatile", target = JsTarget.OUTPUT).spans(output))
        assertNull(matcher("volatile", target = JsTarget.NONE).spans(output))
        assertEquals(listOf(listOf(0..7)), matcher("volatile", target = JsTarget.BOTH).spans(output))
    }
}
