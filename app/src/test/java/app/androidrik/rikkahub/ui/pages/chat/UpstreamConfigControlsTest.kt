package app.androidrik.rikkahub.ui.pages.chat

import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.UpstreamConversationConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class UpstreamConfigControlsTest {
    @Test fun changingOneGlobalFieldDoesNotThawOtherSnapshotFields() {
        val global = Assistant(
            chatModelId = Uuid.random(), enableWebSearch = true,
            mcpServers = setOf(Uuid.random()), workspaceId = Uuid.random(), enabledSkills = setOf("global"),
        )
        val frozen = UpstreamConversationConfig(builtInSearch = true)
        assertEquals(
            frozen.copy(reasoningLevel = ReasoningLevel.HIGH),
            frozen.withAssistantChanges(global, global.copy(reasoningLevel = ReasoningLevel.HIGH)),
        )
    }

    @Test fun effectiveBaselineAllowsToggleToValueAlreadyUsedGlobally() {
        val config = UpstreamConversationConfig(enableWebSearch = false)
        val effective = config.applyTo(Assistant(enableWebSearch = true))
        assertEquals(config.copy(enableWebSearch = true), config.withAssistantChanges(effective, effective.copy(enableWebSearch = true)))
    }

    @Test fun explicitNullAndEmptyControlValuesAreApplied() {
        val config = UpstreamConversationConfig(chatModelId = Uuid.random(), mcpServers = setOf(Uuid.random()),
            workspaceId = Uuid.random(), enabledSkills = setOf("a"))
        val before = config.applyTo(Assistant())
        val after = before.copy(chatModelId = null, mcpServers = emptySet(), workspaceId = null, enabledSkills = emptySet())
        assertEquals(UpstreamConversationConfig(), config.withAssistantChanges(before, after))
    }
}
