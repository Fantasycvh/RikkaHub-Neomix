package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryInjectionFloorTest {

    private fun memory(id: String, importance: Float, ageDays: Float, now: Long) = AssistantMemory(
        id = id,
        content = "memory $id",
        importance = importance,
        // updatedAt 显式设为 now - ageDays（0 会被视为「不衰减」，见 memoryScore 注释）
        updatedAt = now - (ageDays * 24 * 60 * 60 * 1000).toLong(),
        sortKey = 0,
    )

    @Test
    fun `default importance memories never fall below the floor`() {
        // importance 0.6 全老化也有 0.33 > 0.2 下限 ⇒ 永不沉底
        val now = 730L * 24 * 60 * 60 * 1000
        val memories = listOf(memory("a", 0.6f, ageDays = 365f, now = now))
        val selected = selectMemoriesForPrompt(memories, budgetTokens = 10_000, now = now)
        assertEquals(listOf("a"), selected.map { it.id })
    }

    @Test
    fun `low importance aged memories are filtered by the floor`() {
        // importance 0.15 老化一年：0.15 × 0.55 ≈ 0.083 < 0.2 ⇒ 不注入（即使预算充裕）
        val now = 730L * 24 * 60 * 60 * 1000
        val memories = listOf(
            memory("low", 0.15f, ageDays = 365f, now = now),
            memory("keep", 0.6f, ageDays = 365f, now = now),
        )
        val selected = selectMemoriesForPrompt(memories, budgetTokens = 10_000, now = now)
        assertEquals(listOf("keep"), selected.map { it.id })
    }
}
