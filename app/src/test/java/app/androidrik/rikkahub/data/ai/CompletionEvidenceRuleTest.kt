package app.androidrik.rikkahub.data.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 出口审计 R1（bigeye `task/action_chain.py:373 _r_unverified_done`）：
 * 声称完成、但最后一次写之后没有「跑一次 / 读回」证据 ⇒ 提醒一次。
 */
class CompletionEvidenceRuleTest {

    private fun engine(enabled: Boolean = true) = RuleEngine(enabled = enabled)

    @Test
    fun `write then completion claim without verification reminds`() {
        val engine = engine()
        engine.record("workspace_write_file", null)

        assertNotNull(engine.unverifiedCompletionHint("Done — fixed the parser and updated the tests."))
    }

    @Test
    fun `write then shell run is treated as verification`() {
        val engine = engine()
        engine.record("workspace_write_file", null)
        engine.record("workspace_shell", null)

        assertNull(engine.unverifiedCompletionHint("Done — fixed the parser."))
    }

    @Test
    fun `write then read back is treated as verification`() {
        val engine = engine()
        engine.record("workspace_edit_file", null)
        engine.record("workspace_read_file", null)

        assertNull(engine.unverifiedCompletionHint("已完成修改。"))
    }

    @Test
    fun `read before write does not count as verification`() {
        val engine = engine()
        engine.record("workspace_read_file", null)
        engine.record("workspace_write_file", null)

        assertNotNull(engine.unverifiedCompletionHint("Fixed it, all set."))
    }

    @Test
    fun `no write means no reminder`() {
        val engine = engine()
        engine.record("workspace_read_file", null)

        assertNull(engine.unverifiedCompletionHint("Done — the answer is 42."))
    }

    @Test
    fun `explicitly unverified answer is honest and not reminded`() {
        val engine = engine()
        engine.record("workspace_write_file", null)

        assertNull(engine.unverifiedCompletionHint("Changed the file but could not verify it — unverified."))
    }

    @Test
    fun `only fires once per turn`() {
        val engine = engine()
        engine.record("workspace_write_file", null)

        assertNotNull(engine.unverifiedCompletionHint("Done."))
        assertNull("每轮最多注入一次，避免自指循环", engine.unverifiedCompletionHint("Done."))
    }

    @Test
    fun `reset re-arms the audit`() {
        val engine = engine()
        engine.record("workspace_write_file", null)
        engine.unverifiedCompletionHint("Done.")
        engine.reset()

        engine.record("workspace_write_file", null)
        assertNotNull(engine.unverifiedCompletionHint("Done."))
    }

    @Test
    fun `disabled engine never reminds`() {
        val engine = engine(enabled = false)
        engine.record("workspace_write_file", null)

        assertNull(engine.unverifiedCompletionHint("Done."))
    }
}
