package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPromptsTest {

    /** 测试辅助：token 是随机 opaque 串，排序由内部 sortKey（= 自增主键）决定。 */
    private fun memory(seq: Int, content: String = "mem-%03d".format(seq)) =
        AssistantMemory(id = "tok$seq", content = content, sortKey = seq)

    @Test
    fun `build memory prompt injects newest 60 memories not oldest`() {
        val memories = (1..100).map { memory(it) }
        val prompt = buildMemoryPrompt(memories)

        // 60 条上限取「最新」的：sortKey 100..41 入选，最旧的 40 条被排除
        assertTrue(prompt.contains("mem-100"))
        assertTrue(prompt.contains("mem-041"))
        assertTrue("oldest memory leaked into prompt", !prompt.contains("mem-040"))
        assertTrue("oldest memory leaked into prompt", !prompt.contains("mem-001"))
    }

    @Test
    fun `build memory prompt selection is independent of input list order`() {
        val ascending = (1..100).map { memory(it) }
        val descending = ascending.sortedByDescending { it.sortKey }

        // 排序收敛在 buildMemoryPrompt 内（按 sortKey），两种入参必须得到同一结果。
        assertEquals(buildMemoryPrompt(ascending), buildMemoryPrompt(descending))
    }

    @Test
    fun `build memory prompt emits opaque token as id not sequential number`() {
        val prompt = buildMemoryPrompt(listOf(memory(seq = 7, content = "hello")))

        // 注入给模型的是 opaque token，而非自增序号——否则模型会把编号当记忆条数
        assertTrue(prompt.contains("\"id\": \"tok7\""))
    }

    @Test
    fun `build memory prompt caps total injected content at 100K chars`() {
        val memories = (1..60).map { memory(it, content = "x".repeat(10_000)) }
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
