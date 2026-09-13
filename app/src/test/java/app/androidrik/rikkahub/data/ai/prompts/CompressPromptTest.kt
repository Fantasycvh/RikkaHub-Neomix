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

    // ===== filterOutCheckpointMessages（自愈历史泄漏数据）=====
    // 注意：判据只用文本前导语——isSynthetic 是 @Transient，持久化后丢失（DB 读回恒 false）

    @Test
    fun `filter removes leaked checkpoint message even without synthetic flag`() {
        // 模拟 DB 读回：isSynthetic 丢失（false），仅剩前导语文本
        val leaked = user(buildCompactionCheckpointText("summary"), synthetic = false)
        val real = user("real question")
        val result = listOf(real, leaked).filterOutCheckpointMessages()

        assertEquals(1, result.size)
        assertEquals("real question", result.first().toText())
    }

    @Test
    fun `filter keeps ordinary messages`() {
        val real = user("q")
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("a")))
        val result = listOf(real, assistant).filterOutCheckpointMessages()

        assertEquals(2, result.size)
    }

    @Test
    fun `filter keeps assistant message that happens to be synthetic`() {
        // 其它 synthetic（如时间提醒）是 USER 且无前导语 -> 保留；ASSISTANT 消息一律保留
        val reminder = user("time reminder", synthetic = true)
        val assistantSynthetic = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("x")),
            isSynthetic = true,
        )
        assertEquals(2, listOf(reminder, assistantSynthetic).filterOutCheckpointMessages().size)
    }

    @Test
    fun `filter is idempotent and order preserving`() {
        val messages = listOf(
            user("a"),
            user(buildCompactionCheckpointText("s")),
            user("b"),
        )
        val once = messages.filterOutCheckpointMessages()
        val twice = once.filterOutCheckpointMessages()
        assertEquals(once, twice)
        assertEquals(listOf("a", "b"), once.map { it.toText() })
    }

    // ===== 前导语识别（文本标记，模型无关；须容忍历史措辞）=====

    @Test
    fun `preamble recognition is client-side and model independent`() {
        // 前导语是客户端常量：摘要正文（模型生成）不同不影响识别
        val fromGpt = buildCompactionCheckpointText("GPT generated summary")
        val fromClaude = buildCompactionCheckpointText("Claude generated summary")
        val fromGemini = buildCompactionCheckpointText("Gemini generated summary")
        assertTrue(fromGpt.isCompactionCheckpointPreamble())
        assertTrue(fromClaude.isCompactionCheckpointPreamble())
        assertTrue(fromGemini.isCompactionCheckpointPreamble())
        // 识别只依赖客户端前导语：三者去除前导语后的剩余部分都应从摘要标签开始
        listOf(fromGpt, fromClaude, fromGemini).forEach { text ->
            val rest = text.removePrefix(COMPACTION_CHECKPOINT_PREAMBLE).trimStart()
            assertTrue(rest.startsWith(COMPACTION_SUMMARY_OPEN))
        }
    }

    @Test
    fun `legacy preamble list is honored by recognition`() {
        // 机制保证：LEGACY_COMPACTION_PREAMBLES 中任一措辞都应被识别（当前为空列表，故恒不匹配）
        LEGACY_COMPACTION_PREAMBLES.forEach { legacy ->
            assertTrue((legacy + " rest").isCompactionCheckpointPreamble())
        }
        // 空列表时不误伤普通文本
        assertFalse("Let's continue the task".isCompactionCheckpointPreamble())
    }

    @Test
    fun `recognition requires prefix not mere containment`() {
        // 前导语出现在中间不算检查点（避免误伤引用该文本的正常消息）
        val quoted = "Here is a quote: " + COMPACTION_CHECKPOINT_PREAMBLE
        assertFalse(quoted.isCompactionCheckpointPreamble())
    }
}
