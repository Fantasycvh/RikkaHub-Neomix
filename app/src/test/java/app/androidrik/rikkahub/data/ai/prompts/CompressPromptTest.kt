package app.androidrik.rikkahub.data.ai.prompts

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩提示词与检查点文本构造。
 *
 * 对齐 deepseek-harness compaction-basic：结构化 8 段 + 检查点前导语（当背景/不复述/不承认）。
 */
class CompressPromptTest {

    @Test
    fun `default prompt keeps all eight sections in order`() {
        val sections = listOf(
            "# Primary Request and Intent",
            "# Key Technical Concepts",
            "# Files and Code",
            "# Errors and Fixes",
            "# Pending Jobs",
            "# Current Work",
            "# Next Step",
            "# Critical Context",
        )
        var lastIndex = -1
        sections.forEach { section ->
            val index = DEFAULT_COMPRESS_PROMPT.indexOf(section)
            assertTrue("缺少分节: $section", index >= 0)
            assertTrue("分节顺序错误: $section", index > lastIndex)
            lastIndex = index
        }
    }

    @Test
    fun `default prompt keeps template placeholders`() {
        listOf("{target_tokens}", "{locale}", "{additional_context}", "{content}").forEach { key ->
            assertTrue("缺少占位符: $key", DEFAULT_COMPRESS_PROMPT.contains(key))
        }
    }

    @Test
    fun `default prompt instructs preserving exact facts and prior merge`() {
        // 精确信息保留（路径/命令/错误串/标识符/数值）
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("Preserve exact file paths"))
        // prior checkpoint 合并重写（避免摘要层层累积）
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("PRIOR CHECKPOINT"))
        // 不提及压缩行为
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("Do NOT mention this summarization"))
    }

    @Test
    fun `checkpoint text wraps preamble and summary body`() {
        val text = buildCompactionCheckpointText("summary body")
        assertTrue(text.startsWith(COMPACTION_CHECKPOINT_PREAMBLE))
        assertTrue(text.contains(COMPACTION_SUMMARY_OPEN))
        assertTrue(text.contains("summary body"))
        assertTrue(text.contains(COMPACTION_SUMMARY_CLOSE))
    }

    @Test
    fun `preamble detection matches checkpoint text`() {
        assertTrue(buildCompactionCheckpointText("x").isCompactionCheckpointPreamble())
        assertFalse("ordinary message".isCompactionCheckpointPreamble())
    }

    @Test
    fun `body extraction returns inner summary`() {
        val text = buildCompactionCheckpointText("inner content")
        assertEquals("inner content", compactionCheckpointBody(text))
    }

    @Test
    fun `body extraction tolerates missing tags`() {
        assertEquals("plain", compactionCheckpointBody("plain"))
    }

    // ===== partitionCheckpointMessages（发送前分离，避免 limitContext 截掉检查点）=====

    private fun user(text: String, synthetic: Boolean = false) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
        isSynthetic = synthetic,
    )

    @Test
    fun `partition separates synthetic checkpoint from ordinary messages`() {
        val checkpoint = user(buildCompactionCheckpointText("summary"), synthetic = true)
        val messages = listOf(checkpoint, user("hello"), user("world"))
        val (checkpoints, rest) = messages.partitionCheckpointMessages()

        assertEquals(1, checkpoints.size)
        assertEquals(2, rest.size)
        assertTrue(checkpoints.first() === checkpoint)
    }

    @Test
    fun `partition keeps ordinary synthetic message in window`() {
        // isSynthetic 但非检查点前导语（如 workspaceReminder/时间提醒）应留在窗口内
        val syntheticNonCheckpoint = user("workspace reminder", synthetic = true)
        val (checkpoints, rest) = listOf(syntheticNonCheckpoint, user("hi")).partitionCheckpointMessages()

        assertTrue(checkpoints.isEmpty())
        assertEquals(2, rest.size)
    }

    @Test
    fun `partition treats non-synthetic checkpoint-like text as ordinary`() {
        // 用户手打同样文字（非 synthetic）不应被当作检查点
        val (checkpoints, rest) = listOf(user(buildCompactionCheckpointText("x"))).partitionCheckpointMessages()
        assertTrue(checkpoints.isEmpty())
        assertEquals(1, rest.size)
    }
}
