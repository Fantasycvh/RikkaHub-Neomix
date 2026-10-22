package app.androidrik.rikkahub.utils

import app.androidrik.common.tokenizer.TokenizerRegistry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class VolatileTokenCounterTest {
    @Before fun install() { check(TokenizerRegistry.installFromClasspath()) }

    @Test fun `changed dates share stable chunks without changing exact counts`() {
        listOf("2026-10-22 19:00", "2026-11-23 20:45").forEach { date ->
            val text = "Stable history repeated. ".repeat(100) + "\nDate $date\n" + "稳定正文与 emoji 😀 ".repeat(100)
            val start = text.indexOf(date)
            val counter = VolatileTokenCounter(mapOf(text to listOf(start until start + date.length)))
            assertEquals(TokenizerRegistry.current!!.count(text), counter.count(text))
            assertEquals(counter.count(text), counter.count(text))
        }
    }

    @Test fun `per-part offset projection excludes joining newline`() {
        assertEquals(listOf(0..2), partVolatileRanges(listOf(3..7), 5, 3))
        assertEquals(emptyList<IntRange>(), partVolatileRanges(listOf(3..4), 5, 3))
        assertEquals(listOf(1..2), partVolatileRanges(listOf(6..9), 5, 3))
    }
}
