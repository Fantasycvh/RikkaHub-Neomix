package app.androidrik.rikkahub.ui.components.richtext

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AsyncCodeHighlightTest {

    @Test
    fun `mergeHighlightedPrefix preserves spans on prefix and appends plain suffix`() {
        val previous = buildAnnotatedString {
            withStyle(SpanStyle(color = Color.Red)) {
                append("val x")
            }
        }

        val merged = mergeHighlightedPrefix("val x = 1", previous)

        assertEquals("val x = 1", merged.text)
        assertEquals(1, merged.spanStyles.size)
        assertEquals(0, merged.spanStyles.first().start)
        assertEquals(5, merged.spanStyles.first().end)
        assertEquals(Color.Red, merged.spanStyles.first().item.color)
    }

    @Test
    fun `mergeHighlightedPrefix returns previous when unchanged and falls back on non-prefix`() {
        val previous = AnnotatedString("abc")
        assertSame(previous, mergeHighlightedPrefix("abc", previous))

        val replaced = mergeHighlightedPrefix("xyz", previous)
        assertEquals("xyz", replaced.text)
        assertEquals(0, replaced.spanStyles.size)
    }

    @Test
    fun `splitAnnotatedStringByLines preserves line count and spans`() {
        val source = buildAnnotatedString {
            withStyle(SpanStyle(color = Color.Blue)) {
                append("line1\nli")
            }
            append("ne2\n")
        }

        val lines = splitAnnotatedStringByLines(source)

        assertEquals(listOf("line1", "line2", ""), lines.map { it.text })
        assertEquals(1, lines[0].spanStyles.size)
        assertEquals(0..5, lines[0].spanStyles.first().start..lines[0].spanStyles.first().end)
        assertEquals(1, lines[1].spanStyles.size)
        assertEquals(0..2, lines[1].spanStyles.first().start..lines[1].spanStyles.first().end)
        assertEquals(0, lines[2].spanStyles.size)
    }

    @Test
    fun `splitAnnotatedStringByLines handles empty and single line`() {
        assertEquals(listOf(""), splitAnnotatedStringByLines(AnnotatedString("")).map { it.text })
        assertEquals(listOf("single"), splitAnnotatedStringByLines(AnnotatedString("single")).map { it.text })
    }
}
