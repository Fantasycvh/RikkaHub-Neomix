package app.androidrik.rikkahub.data.repository

import org.junit.Assert.*
import org.junit.Test

class MemoryDisplayOrderTest {
    @Test fun `insert between neighbors changes only moved row`() {
        assertEquals(mapOf(3 to 1_500_000L), planMemoryDisplayOrder(listOf(1 to 1_000_000L, 3 to 3_000_000L, 2 to 2_000_000L), 1))
    }

    @Test fun `first and last insertion use distinct sparse keys`() {
        assertEquals(mapOf(3 to 0L), planMemoryDisplayOrder(listOf(3 to 3_000_000L, 1 to 1_000_000L), 0))
        assertEquals(mapOf(1 to 4_000_000L), planMemoryDisplayOrder(listOf(3 to 3_000_000L, 1 to 1_000_000L), 1))
    }

    @Test fun `exhausted gap expands suffix without touching prefix`() {
        assertEquals(mapOf(3 to 1_000_010L, 2 to 2_000_010L), planMemoryDisplayOrder(listOf(1 to 10L, 3 to 99L, 2 to 11L), 1))
    }
}
