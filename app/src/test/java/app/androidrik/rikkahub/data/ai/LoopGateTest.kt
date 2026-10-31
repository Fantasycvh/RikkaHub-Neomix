package app.androidrik.rikkahub.data.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收尾闸门核心的单测（design-deliverable-gate.md §六）：
 * [DeliverableChecker] 纯校验、[evaluateLoopGate] 的三档裁决（通过 / 打回 / 强制放行）。
 */
class LoopGateTest {

    // ---- DeliverableChecker.normalizeRelative ----

    @Test
    fun `normalize keeps clean relative paths`() {
        assertEquals("report.md", DeliverableChecker.normalizeRelative("report.md"))
        assertNull("leading slash = absolute", DeliverableChecker.normalizeRelative("/out/a/b.md"))
        assertEquals("out/a.md", DeliverableChecker.normalizeRelative("./out//a.md"))
        assertEquals("out/a.md", DeliverableChecker.normalizeRelative(" out/a.md "))
    }

    @Test
    fun `normalize rejects illegal declarations`() {
        assertNull("absolute path", DeliverableChecker.normalizeRelative("/abs/x.md"))
        assertNull("parent escape", DeliverableChecker.normalizeRelative("../x.md"))
        assertNull("nested parent escape", DeliverableChecker.normalizeRelative("a/../../x.md"))
        assertNull("blank", DeliverableChecker.normalizeRelative("   "))
        assertNull("dot only", DeliverableChecker.normalizeRelative("."))
    }

    @Test
    fun `normalizeAll dedupes and drops illegal`() {
        assertEquals(
            listOf("a.md"),
            DeliverableChecker.normalizeAll(listOf("a.md", "../evil", "/abs", "a.md", "./a.md")),
        )
    }

    // ---- evaluateLoopGate：确定性档 ----

    @Test
    fun `deterministic gate passes when nothing missing`() = runTest {
        val gate = LoopGateConfig(deliverables = listOf("a.md")) { emptyList() }
        assertTrue(evaluateLoopGate(gate) is LoopGateVerdict.Pass)
        assertEquals(0, gate.retries)
        assertFalse(gate.forcePassed)
    }

    @Test
    fun `deterministic gate retries with missing prompt before cap`() = runTest {
        val missing = listOf("a.md", "b.md")
        val gate = LoopGateConfig(deliverables = listOf("a.md")) { missing }
        val verdict = evaluateLoopGate(gate)
        assertTrue(verdict is LoopGateVerdict.Retry)
        assertTrue((verdict as LoopGateVerdict.Retry).prompt.contains("a.md"))
        assertEquals(1, gate.retries)
        assertFalse(gate.forcePassed)
    }

    @Test
    fun `deterministic gate force passes after cap`() = runTest {
        val missing = listOf("a.md")
        val gate = LoopGateConfig(deliverables = listOf("a.md")) { missing }
        repeat(LOOP_GATE_MAX_RETRIES) {
            assertTrue(evaluateLoopGate(gate) is LoopGateVerdict.Retry)
        }
        assertEquals(LOOP_GATE_MAX_RETRIES, gate.retries)
        // 达上限：强制放行（Pass），但缺失清单已记录
        assertTrue(evaluateLoopGate(gate) is LoopGateVerdict.Pass)
        assertTrue(gate.forcePassed)
        assertEquals(missing, gate.missingAtForcePass)
    }

    // ---- evaluateLoopGate：自检档 ----

    @Test
    fun `self check injects once then passes`() = runTest {
        val gate = LoopGateConfig(deliverables = emptyList())
        val first = evaluateLoopGate(gate)
        assertTrue(first is LoopGateVerdict.Retry)
        assertTrue((first as LoopGateVerdict.Retry).prompt == SELF_CHECK_PROMPT)
        // 注入一次后，模型仍自然收束 = 放行
        assertTrue(evaluateLoopGate(gate) is LoopGateVerdict.Pass)
        assertEquals(1, gate.retries)
        assertFalse(gate.forcePassed)
    }

    // ---- 措辞 ----

    @Test
    fun `missing prompt lists all paths`() {
        val prompt = deliverableMissingPrompt(listOf("a.md", "b.md"))
        assertTrue(prompt.contains("a.md"))
        assertTrue(prompt.contains("b.md"))
    }
}
