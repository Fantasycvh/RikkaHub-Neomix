package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class PromptInspectionTimingTest {
    @Test fun `stages use monotonic elapsed time and nested timings do not move checkpoint`() {
        var now = 0L
        val timing = PromptInspectionTiming { now }
        now = 2_000_000
        timing.mark("memories")
        timing.record("transformer_0_Test", 1_000_000)
        now = 5_000_000
        timing.mark("transforms")
        assertEquals("totalMs=5 memoriesMs=2 transformer_0_TestMs=1 transformsMs=3", timing.summary())
    }
}
