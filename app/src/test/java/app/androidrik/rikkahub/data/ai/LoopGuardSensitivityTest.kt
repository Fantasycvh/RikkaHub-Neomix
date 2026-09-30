package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopGuardSensitivityTest {
    @Test
    fun `standard level matches original hardcoded constants`() {
        val (window, success, failure) = LoopGuardSensitivity.STANDARD.guardThresholds()
        assertEquals(ToolCallLoopGuard.DEFAULT_WINDOW_SIZE, window)
        assertEquals(ToolCallLoopGuard.DEFAULT_SUCCESS_THRESHOLD, success)
        assertEquals(ToolCallLoopGuard.DEFAULT_FAILURE_THRESHOLD, failure)
    }

    @Test
    fun `strict is tighter than standard and loose is looser`() {
        val strict = LoopGuardSensitivity.STRICT.guardThresholds()
        val standard = LoopGuardSensitivity.STANDARD.guardThresholds()
        val loose = LoopGuardSensitivity.LOOSE.guardThresholds()

        assertTrue(strict.second < standard.second && strict.third < standard.third)
        assertTrue(loose.second > standard.second && loose.third > standard.third)
        // 窗口三档一致：不同档位间只差阈值，不差记忆长度
        assertEquals(strict.first, standard.first)
        assertEquals(standard.first, loose.first)
    }

    @Test
    fun `unknown stored name falls back to standard`() {
        assertEquals(LoopGuardSensitivity.STANDARD, parseLoopGuardSensitivity(null))
        assertEquals(LoopGuardSensitivity.STANDARD, parseLoopGuardSensitivity(""))
        assertEquals(LoopGuardSensitivity.STANDARD, parseLoopGuardSensitivity("bogus"))
        assertEquals(LoopGuardSensitivity.LOOSE, parseLoopGuardSensitivity("LOOSE"))
    }
}
