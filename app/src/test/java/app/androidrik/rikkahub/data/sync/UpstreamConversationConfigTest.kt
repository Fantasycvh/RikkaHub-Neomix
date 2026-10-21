package app.androidrik.rikkahub.data.sync

import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.UpstreamConversationConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** Synthetic upstream 3.1 snapshots only: never relies on a user's backup. */
class UpstreamConversationConfigTest {
    private val modelId = Uuid.parse("11111111-1111-4111-8111-111111111111")
    private val workspaceId = Uuid.parse("22222222-2222-4222-8222-222222222222")
    private val serverId = Uuid.parse("33333333-3333-4333-8333-333333333333")

    @Test
    fun missingSnapshotIsDifferentFromEmptySnapshot() {
        listOf(null, "", "  ", "null").forEach { raw ->
            assertNull(UpstreamConversationConfig.fromLegacyJson(raw))
            assertNull(UpstreamConversationConfig.normalizeLegacyJson(raw))
        }
        assertEquals(UpstreamConversationConfig(), UpstreamConversationConfig.fromLegacyJson("{}"))
    }

    @Test
    fun emptySnapshotUsesUpstreamDefaultsNotAssistantDefaults() {
        val stored = Assistant(
            chatModelId = modelId,
            workspaceId = workspaceId,
            reasoningLevel = ReasoningLevel.HIGH,
            enableWebSearch = true,
            mcpServers = setOf(serverId),
            enabledSkills = setOf("stored-skill"),
            temperature = 0.7f,
            enableMemory = true,
        )
        val effective = requireNotNull(UpstreamConversationConfig.fromLegacyJson("{}"))
            .applyTo(stored)
        assertNull(effective.chatModelId)
        assertNull(effective.workspaceId)
        assertEquals(ReasoningLevel.AUTO, effective.reasoningLevel)
        assertFalse(effective.enableWebSearch)
        assertTrue(effective.mcpServers.isEmpty())
        assertTrue(effective.enabledSkills.isEmpty())
        assertEquals(stored.temperature, effective.temperature)
        assertTrue(effective.enableMemory)
    }

    @Test
    fun explicitFalseEmptyAndNullAreNotInheritance() {
        val raw = """{
            "chatModelId":null,"reasoningLevel":"off","enableWebSearch":false,
            "builtInSearch":false,"mcpServers":[],"workspaceId":null,"enabledSkills":[]
        }""".trimIndent()
        val config = requireNotNull(UpstreamConversationConfig.fromLegacyJson(raw))
        assertEquals(ReasoningLevel.OFF, config.reasoningLevel)
        assertEquals(Json.parseToJsonElement(raw), Json.parseToJsonElement(
            requireNotNull(UpstreamConversationConfig.normalizeLegacyJson(raw)),
        ))
        val effective = config.applyTo(Assistant(
            chatModelId = modelId, workspaceId = workspaceId, enableWebSearch = true,
            mcpServers = setOf(serverId), enabledSkills = setOf("skill"),
        ))
        assertNull(effective.chatModelId)
        assertNull(effective.workspaceId)
        assertFalse(effective.enableWebSearch)
        assertTrue(effective.mcpServers.isEmpty())
        assertTrue(effective.enabledSkills.isEmpty())
    }

    @Test
    fun importsAllSupportedFieldsWithoutChangingIdentifiers() {
        val raw = """{
            "chatModelId":"$modelId","reasoningLevel":"high","enableWebSearch":true,
            "builtInSearch":true,"mcpServers":["$serverId"],"workspaceId":"$workspaceId",
            "enabledSkills":["me.rerere.rikkahub.skill","/data/user/0/me.rerere.rikkahub/opaque"]
        }""".trimIndent()
        val config = requireNotNull(UpstreamConversationConfig.fromLegacyJson(raw))
        val effective = config.applyTo(Assistant())
        assertEquals(modelId, effective.chatModelId)
        assertEquals(ReasoningLevel.HIGH, effective.reasoningLevel)
        assertTrue(effective.enableWebSearch)
        assertEquals(setOf(serverId), effective.mcpServers)
        assertEquals(workspaceId, effective.workspaceId)
        assertEquals(setOf("me.rerere.rikkahub.skill", "/data/user/0/me.rerere.rikkahub/opaque"),
            effective.enabledSkills)
        assertEquals(Json.parseToJsonElement(raw), Json.parseToJsonElement(
            requireNotNull(UpstreamConversationConfig.normalizeLegacyJson(raw)),
        ))
    }

    @Test
    fun builtInSearchIsIndependentFromAssistantWebSearchAndPreservesOtherTools() {
        val model = Model(tools = setOf(BuiltInTools.UrlContext, BuiltInTools.ImageGeneration))
        val config = UpstreamConversationConfig(builtInSearch = true, enableWebSearch = false)
        val withSearch = config.applyToModel(model)
        assertEquals(model.tools + BuiltInTools.Search, withSearch.tools)
        assertFalse(config.applyTo(Assistant(enableWebSearch = true)).enableWebSearch)
        assertEquals(model, config.copy(builtInSearch = false).applyToModel(withSearch))
        assertFalse(BuiltInTools.Search in model.tools) // Original model was not mutated.
    }

    @Test
    fun unknownFieldsArePreservedWithoutInventingMappings() {
        val raw = """{"futureField":{"enabled":false},"modelId":"not-chatModelId"}"""
        val config = requireNotNull(UpstreamConversationConfig.fromLegacyJson(raw))
        assertNull(config.chatModelId)
        assertEquals(Json.parseToJsonElement(raw), Json.parseToJsonElement(
            requireNotNull(UpstreamConversationConfig.normalizeLegacyJson(raw)),
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedKnownFieldsFailInsteadOfSilentlyFollowingAssistant() {
        UpstreamConversationConfig.normalizeLegacyJson("""{"mcpServers":false}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonObjectSnapshotFails() {
        UpstreamConversationConfig.normalizeLegacyJson("[]")
    }
}
