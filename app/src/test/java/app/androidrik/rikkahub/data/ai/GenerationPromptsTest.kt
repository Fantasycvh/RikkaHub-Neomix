package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPromptsTest {

    @Test
    fun `build memory prompt injects newest 60 memories not oldest`() {
        // 零填充避免 "mem-1" 成为 "mem-100" 的子串（断言需精确匹配）
        val memories = (1..100).map { AssistantMemory(id = it, content = "mem-%03d".format(it)) }
        val prompt = buildMemoryPrompt(memories)

        // 60 条上限取「最新」的：id 100..41 入选，最旧的 40 条被排除
        assertTrue(prompt.contains("mem-100"))
        assertTrue(prompt.contains("mem-041"))
        assertTrue("oldest memory leaked into prompt", !prompt.contains("mem-040"))
        assertTrue("oldest memory leaked into prompt", !prompt.contains("mem-001"))
    }

    @Test
    fun `build memory prompt selection is independent of input list order`() {
        val ascending = (1..100).map { AssistantMemory(id = it, content = "mem-%03d".format(it)) }
        val descending = ascending.sortedByDescending { it.id }

        // 回归护栏：修复前行为取决于调用方列表顺序（DAO 无 ORDER BY → rowid 升序 → 最旧入选）；
        // 现在排序收敛在 buildMemoryPrompt 内，两种入参必须得到同一结果。
        assertEquals(buildMemoryPrompt(ascending), buildMemoryPrompt(descending))
    }

    @Test
    fun `build memory prompt caps total injected content at 100K chars`() {
        val memories = (1..60).map { AssistantMemory(id = it, content = "x".repeat(10_000)) }
        val prompt = buildMemoryPrompt(memories)

        // 60 条 × 10K = 600K，但总量被限制在 100K 附近
        assertTrue("prompt too large: ${prompt.length}", prompt.length < 110_000)
    }

    @Test
    fun `build memory prompt with empty memories produces header only`() {
        val prompt = buildMemoryPrompt(emptyList())
        assertTrue(prompt.contains("**Memories**"))
    }
}
