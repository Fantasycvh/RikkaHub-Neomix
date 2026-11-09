package app.androidrik.rikkahub.ui.components.ai

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import org.junit.Assert.*
import org.junit.Test

class SegmentedEditSessionTest {
    @Test fun `all source and CRLF emoji boundaries survive paging and local edits`() {
        val original = "a".repeat(4095) + "😀\r\n" + "正文".repeat(50_000)
        val source = TextFieldState(original, TextRange(50_000))
        val session = SegmentedEditSession(source)
        assertEquals(original, session.pages.joinToString("") { it.text.toString() })
        assertTrue(session.pages.all { it.text.length <= 4096 })
        assertTrue(session.pages.none { it.text.firstOrNull()?.isLowSurrogate() == true })
        session.pages[session.index].edit { replace(0, 0, "变更") }
        val expected = session.pages.joinToString("") { it.text.toString() }
        assertEquals(original, source.text.toString())
        assertTrue(session.applyTo(source))
        assertEquals(expected, source.text.toString())
    }
    @Test fun `external updates are rejected and inserted pages do not lose source`() {
        val source = TextFieldState("文".repeat(10_000))
        val session = SegmentedEditSession(source)
        session.insertPage()
        session.pages[session.index].edit { append("新增") }
        source.edit { append("外部") }
        assertFalse(session.applyTo(source))
        assertEquals("文".repeat(10_000) + "外部", source.text.toString())
    }
}
