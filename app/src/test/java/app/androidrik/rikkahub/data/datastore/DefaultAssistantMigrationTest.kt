package app.androidrik.rikkahub.data.datastore

import app.androidrik.rikkahub.data.model.Assistant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class DefaultAssistantMigrationTest {
    private val example = Assistant(id = LEGACY_EXAMPLE_ASSISTANT_ID, systemPrompt = LEGACY_EXAMPLE_PROMPTS.first())

    @Test
    fun `fresh installs have exactly one empty-prompt default`() {
        assertEquals(listOf(DEFAULT_ASSISTANT_ID), Settings().assistants.map { it.id })
        assertEquals("", Settings().assistants.single().systemPrompt)
    }

    @Test
    fun `all published seeds merge and selected assistant follows the destination`() = runBlocking {
        for (prompt in LEGACY_EXAMPLE_PROMPTS) {
            for (temperature in listOf(null, 0.6f)) {
                var moved = 0
                val originalDefault = DEFAULT_ASSISTANTS.single().copy(name = "My default", systemPrompt = "custom")
                val before = Settings(assistants = listOf(originalDefault, example.copy(systemPrompt = prompt, temperature = temperature)), assistantId = example.id)
                val after = before.mergeLegacyDefaultAssistant { moved++ }
                assertEquals(listOf(originalDefault), after.assistants)
                assertEquals(DEFAULT_ASSISTANT_ID, after.assistantId)
                assertTrue(after.legacyDefaultAssistantMerged)
                assertTrue(example.id in after.removedAssistantIds)
                assertEquals(1, moved)
                assertEquals(after, after.mergeLegacyDefaultAssistant { error("must be idempotent") })
            }
        }
    }

    @Test
    fun `customized examples survive with their id and configuration`() = runBlocking {
        val changed = listOf(
            example.copy(systemPrompt = "user prompt"), example.copy(chatModelId = Uuid.random()),
            example.copy(name = "My assistant"), example.copy(temperature = 0.9f),
            example.copy(enableMemory = true), example.copy(enabled = false),
            example.copy(enableWebSearch = true), example.copy(tags = listOf(Uuid.random())),
        )
        for (assistant in changed) {
            val before = Settings(assistants = DEFAULT_ASSISTANTS + assistant, assistantId = assistant.id)
            val after = before.mergeLegacyDefaultAssistant { error("customized assistant cannot be merged") }
            val retained = after.assistants.last()
            assertEquals(assistant, retained.copy(name = assistant.name))
            assertEquals(assistant.id, after.assistantId)
            assertFalse(after.legacyDefaultAssistantMerged)
            if (assistant.name.isEmpty()) assertEquals("Example Assistant", retained.name)
        }
    }

    @Test
    fun `deleted destination is respected and deleted example is never refilled`() = runBlocking {
        val before = Settings(assistants = listOf(example), removedAssistantIds = setOf(DEFAULT_ASSISTANT_ID))
        val after = before.mergeLegacyDefaultAssistant { error("destination was deleted") }
        assertEquals(listOf(example.id), after.assistants.map { it.id })
        val existing = mutableListOf<Assistant>()
        refillDefaultAssistants(existing, setOf(LEGACY_EXAMPLE_ASSISTANT_ID))
        assertEquals(DEFAULT_ASSISTANTS, existing)
    }

    @Test
    fun `missing default is supplied without affecting unrelated assistants`() = runBlocking {
        val custom = Assistant(name = "custom")
        val after = Settings(assistants = listOf(example, custom)).mergeLegacyDefaultAssistant {}
        assertEquals(listOf(custom, DEFAULT_ASSISTANTS.single()), after.assistants)
    }

    @Test
    fun `same name or prompt under another id is not a seed`() = runBlocking {
        val custom = example.copy(id = Uuid.random())
        val before = Settings(assistants = DEFAULT_ASSISTANTS + custom)
        assertEquals(before, before.mergeLegacyDefaultAssistant { error("wrong id") })
    }
}
