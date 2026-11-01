package app.androidrik.rikkahub.ui.components.ai

import app.androidrik.ai.provider.UsageWindow
import app.androidrik.rikkahub.data.datastore.UsageDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageWindowDisplayTest {
    private fun window(usedPercent: Double) = UsageWindow(
        usedPercent = usedPercent,
        windowMinutes = 300,
        resetsAt = null,
    )

    @Test
    fun `used mode shows backend used percent`() {
        assertEquals(0, window(0.0).displayPercent(UsageDisplayMode.USED))
        assertEquals(2, window(2.0).displayPercent(UsageDisplayMode.USED))
        assertEquals(37, window(36.6).displayPercent(UsageDisplayMode.USED))
        assertEquals(100, window(100.0).displayPercent(UsageDisplayMode.USED))
    }

    @Test
    fun `remaining mode complements used percent`() {
        assertEquals(100, window(0.0).displayPercent(UsageDisplayMode.REMAINING))
        assertEquals(98, window(2.0).displayPercent(UsageDisplayMode.REMAINING))
        assertEquals(63, window(36.6).displayPercent(UsageDisplayMode.REMAINING))
        assertEquals(0, window(100.0).displayPercent(UsageDisplayMode.REMAINING))
    }

    @Test
    fun `display percent is clamped to 0-100`() {
        assertEquals(100, window(120.0).displayPercent(UsageDisplayMode.USED))
        assertEquals(0, window(-5.0).displayPercent(UsageDisplayMode.USED))
        assertEquals(0, window(120.0).displayPercent(UsageDisplayMode.REMAINING))
        assertEquals(100, window(-5.0).displayPercent(UsageDisplayMode.REMAINING))
    }
}
