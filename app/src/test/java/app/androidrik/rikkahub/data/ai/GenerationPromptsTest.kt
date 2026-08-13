package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPromptsTest {

    @Test
    fun `build memory prompt limits injected memories to 60 entries`() {
        val memories = (1..100).map { AssistantMemory(id = it, content = "memory-$it") }
        val prompt = buildMemoryPrompt(memories)

        // 60 条上限：只注入前 60 条
        assertTrue(prompt.contains("memory-1"))
        assertTrue(prompt.contains("memory-60"))
        assertTrue(!prompt.contains("memory-61"))
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
