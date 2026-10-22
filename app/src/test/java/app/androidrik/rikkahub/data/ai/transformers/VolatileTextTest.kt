package app.androidrik.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Test

class VolatileTextTest {
    @Test fun `scattered placeholders on separate lines stay separate ranges`() {
        val before = "## Info\n- Time: {{cur_date}}\n- Locale: {{locale}}\n- Nick: {{user}}"
        val after = "## Info\n- Time: 2026-10-22\n- Locale: 中文\n- Nick: me"
        val ranges = changedOutputRange(before, after)
        assertEquals(3, ranges.size)
        assertEquals(listOf("2026-10-22", "中文", "me"), ranges.map { after.substring(it) })
    }

    @Test fun `line count changes fall back to one conservative range`() {
        val ranges = changedOutputRange("a\n{{user}}", "a\nx\ny")
        assertEquals(1, ranges.size)
    }

    @Test fun `changed values are annotated in output coordinates`() {
        assertEquals(listOf(6..8), changedOutputRange("date: {{date}}!", "date: now!"))
        assertEquals(emptyList<IntRange>(), changedOutputRange("same", "same"))
        assertEquals(listOf(0..0), changedOutputRange("abcd", "a"))
    }

    @Test fun `template wrappers preserve a unique body and shift its annotations`() {
        val body = "body with time 12345"
        val output = "prefix\n$body\nsuffix"
        assertEquals(listOf(0..6, (7 + body.length)..output.lastIndex), wrapperOutputRanges(body, output))
        assertEquals(listOf(22..26), rebaseVolatileRanges(body, output, listOf(15..19)))
    }

    @Test fun `overwritten ranges are dropped while surviving suffix is shifted`() {
        assertEquals(listOf(3..5), rebaseVolatileRanges("abcDEFGxyz", "abQxyz", listOf(3..6, 7..9)))
        assertEquals(emptyList<IntRange>(), rebaseVolatileRanges("abc", "xyz", listOf(1..2)))
    }

    @Test fun `bad ranges are rejected and adjacent ranges merge`() {
        assertEquals(listOf(1..5), mergeVolatileRanges(listOf(-1..2, 1..3, 4..5, 8..10, 7..6), 10))
    }
}
