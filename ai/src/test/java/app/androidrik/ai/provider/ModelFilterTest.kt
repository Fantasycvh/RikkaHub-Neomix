package app.androidrik.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilterTest {
    private val sonnet = Model(modelId = "relay-a/claude-sonnet", displayName = "relay-a/claude-sonnet")
    private val renamed = Model(modelId = "relay-b/claude-opus", displayName = "Custom Opus")
    private val gpt = Model(modelId = "gpt-mini", displayName = "GPT Mini")
    private val models = listOf(sonnet, renamed, gpt)

    @Test
    fun `keywords match in any order`() {
        assertEquals(listOf(sonnet), models.filterByKeywords("claude sonnet"))
        assertEquals(listOf(sonnet), models.filterByKeywords("sonnet claude"))
    }

    @Test
    fun `all keywords must match`() {
        assertTrue(models.filterByKeywords("claude mini").isEmpty())
    }

    @Test
    fun `case and extra spaces are ignored`() {
        assertEquals(listOf(sonnet), models.filterByKeywords("  CLAUDE   sonnet  "))
    }

    @Test
    fun `renamed model is still found by model id`() {
        assertEquals(listOf(renamed), models.filterByKeywords("relay-b claude"))
    }

    @Test
    fun `keywords can match across id and display name`() {
        assertEquals(listOf(renamed), models.filterByKeywords("relay-b custom"))
    }

    @Test
    fun `blank query preserves existing order`() {
        assertEquals(models, models.filterByKeywords(""))
        assertEquals(models, models.filterByKeywords("   "))
    }
}
