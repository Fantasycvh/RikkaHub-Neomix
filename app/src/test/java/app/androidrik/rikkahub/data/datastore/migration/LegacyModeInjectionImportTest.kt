package app.androidrik.rikkahub.data.datastore.migration

import app.androidrik.ai.core.MessageRole
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.model.InjectionPosition
import app.androidrik.rikkahub.data.model.PromptInjection
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LegacyModeInjectionImportTest {
    private val firstId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val secondId = Uuid.parse("22222222-2222-4222-8222-222222222222")

    // Synthetic legacy settings only; never load a user's backup as a test fixture.
    private val legacyJson = """{
        "dynamicColor": false,
        "assistants": [{
            "id": "33333333-3333-4333-8333-333333333333",
            "name": "Bound assistant",
            "modeInjectionIds": ["$secondId", "$firstId"]
        }],
        "modeInjections": [{
            "id": "$firstId",
            "name": "Depth entry",
            "enabled": false,
            "priority": -7,
            "position": "at_depth",
            "content": "First line\nSecond line {{user}}",
            "injectDepth": 9,
            "role": "assistant",
            "futureField": {"keep": true}
        }, {
            "id": "$secondId",
            "name": "System entry",
            "enabled": true,
            "priority": 42,
            "position": "before_system_prompt",
            "content": "Second content",
            "injectDepth": 2,
            "role": "system"
        }]
    }""".trimIndent()

    private fun migrate(input: String) = SettingsJsonMigrator.migrate(input, "app.androidrik.rikkahub")

    private fun decode(input: String) = JsonInstant.decodeFromString(Settings.serializer(), input)

    @Test
    fun `legacy entries decode as complete Settings preserving all fields and assistant bindings`() {
        val migrated = migrate(legacyJson)
        val root = JsonInstant.parseToJsonElement(migrated).jsonObject
        val settings = decode(migrated)
        val expectedEntries = listOf(
            PromptInjection.ModeInjection(
                id = firstId,
                name = "Depth entry",
                enabled = false,
                priority = -7,
                position = InjectionPosition.AT_DEPTH,
                content = "First line\nSecond line {{user}}",
                injectDepth = 9,
                role = MessageRole.ASSISTANT,
            ),
            PromptInjection.ModeInjection(
                id = secondId,
                name = "System entry",
                enabled = true,
                priority = 42,
                position = InjectionPosition.BEFORE_SYSTEM_PROMPT,
                content = "Second content",
                injectDepth = 2,
                role = MessageRole.SYSTEM,
            ),
        )

        assertEquals(expectedEntries, settings.promptPresets.single().entries)
        assertEquals(expectedEntries, settings.modeInjections)
        assertTrue(settings.promptPresets.single().enabled)
        assertFalse(settings.dynamicColor)
        assertEquals("Bound assistant", settings.assistants.single().name)
        assertEquals(setOf(firstId, secondId), settings.assistants.single().modeInjectionIds)
        assertTrue(settings.assistants.single().promptPresetIds.isEmpty())
        // Verify JSON fidelity too: even an unknown entry field must survive the wrapping.
        val original = JsonInstant.parseToJsonElement(legacyJson).jsonObject
        assertEquals(original["modeInjections"], root["promptPresets"]!!.jsonArray.single().jsonObject["entries"])
        assertEquals(original["assistants"], root["assistants"])
        assertFalse(root.containsKey("modeInjections"))
    }

    @Test
    fun `current promptPresets take precedence without merging or changing their fields`() {
        val currentPresets = JsonInstant.parseToJsonElement("""[{
            "id": "44444444-4444-4444-8444-444444444444",
            "name": "Current preset",
            "description": "Keep this metadata",
            "enabled": false,
            "entries": [{"id": "$secondId", "name": "Current entry", "content": "Current content"}],
            "futureContainerField": "preserved"
        }]""")
        val input = JsonInstant.parseToJsonElement(legacyJson).jsonObject.toMutableMap()
        input["promptPresets"] = currentPresets
        val migrated = migrate(JsonInstant.encodeToString(JsonObject(input)))

        assertEquals(currentPresets, JsonInstant.parseToJsonElement(migrated).jsonObject["promptPresets"])
        val preset = decode(migrated).promptPresets.single()
        assertEquals("Current preset", preset.name)
        assertEquals("Keep this metadata", preset.description)
        assertFalse(preset.enabled)
        assertEquals(listOf(secondId), preset.entries.map { it.id })
        assertEquals("Current content", preset.entries.single().content)
    }

    @Test
    fun `current-only format stays unchanged including preset and entry bindings`() {
        val input = """{
            "promptPresets": [{
                "id": "44444444-4444-4444-8444-444444444444",
                "name": "Current preset",
                "description": "Existing metadata",
                "enabled": true,
                "entries": [{"id": "$firstId", "name": "Current entry", "content": "Keep content"}]
            }],
            "assistants": [{
                "id": "33333333-3333-4333-8333-333333333333",
                "modeInjectionIds": ["$firstId"],
                "promptPresetIds": ["44444444-4444-4444-8444-444444444444"]
            }]
        }"""
        val migrated = migrate(input)
        assertEquals(JsonInstant.parseToJsonElement(input), JsonInstant.parseToJsonElement(migrated))
        val settings = decode(migrated)
        assertEquals("Keep content", settings.promptPresets.single().entries.single().content)
        assertEquals(setOf(firstId), settings.assistants.single().modeInjectionIds)
        assertEquals(
            setOf(settings.promptPresets.single().id),
            settings.assistants.single().promptPresetIds,
        )
    }

    @Test
    fun `explicitly empty current promptPresets are not replaced by legacy entries`() {
        val input = JsonInstant.parseToJsonElement(legacyJson).jsonObject.toMutableMap()
        input["promptPresets"] = JsonArray(emptyList())
        val migrated = migrate(JsonInstant.encodeToString(JsonObject(input)))

        assertEquals(JsonArray(emptyList()), JsonInstant.parseToJsonElement(migrated).jsonObject["promptPresets"])
        assertTrue(decode(migrated).promptPresets.isEmpty())
        assertTrue(decode(migrated).modeInjections.isEmpty())
    }

    @Test
    fun `empty legacy list stays empty instead of restoring default injections`() {
        val migrated = migrate("""{"modeInjections":[]}""")
        val settings = decode(migrated)

        assertTrue(settings.promptPresets.single().entries.isEmpty())
        assertTrue(settings.modeInjections.isEmpty())
        assertFalse(JsonInstant.parseToJsonElement(migrated).jsonObject.containsKey("modeInjections"))
    }

    @Test
    fun `migration is idempotent including generated container ID`() {
        for (input in listOf(legacyJson, """{"modeInjections":[]}""")) {
            val once = migrate(input)
            val twice = migrate(once)

            assertEquals(once, twice)
            assertEquals(decode(once).promptPresets, decode(twice).promptPresets)
        }
    }

    @Test
    fun `missing legacy and current fields keep Settings defaults without adding a preset`() {
        val migrated = migrate("{}")

        assertFalse(JsonInstant.parseToJsonElement(migrated).jsonObject.containsKey("promptPresets"))
        assertEquals(Settings().modeInjections, decode(migrated).modeInjections)
    }
}
