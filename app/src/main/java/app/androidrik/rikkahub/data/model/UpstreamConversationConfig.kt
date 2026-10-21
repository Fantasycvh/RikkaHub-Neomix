package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * Frozen conversation configuration from upstream 3.1 (eeef60b22).
 *
 * A null configuration means follow the assistant. Inside a configuration, false/empty/null are
 * actual snapshot values, not requests to inherit. Defaults match upstream's serialized defaults.
 * builtInSearch belongs to Model.tools, not Assistant.enableWebSearch; callers apply it separately.
 */
@Serializable
data class UpstreamConversationConfig(
    val chatModelId: Uuid? = null,
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val enableWebSearch: Boolean = false,
    val builtInSearch: Boolean = false,
    val mcpServers: Set<Uuid> = emptySet(),
    val workspaceId: Uuid? = null,
    val enabledSkills: Set<String> = emptySet(),
) {
    fun applyTo(assistant: Assistant): Assistant = assistant.copy(
        chatModelId = chatModelId,
        reasoningLevel = reasoningLevel,
        enableWebSearch = enableWebSearch,
        mcpServers = mcpServers,
        workspaceId = workspaceId,
        enabledSkills = enabledSkills,
    )

    fun applyToModel(model: Model): Model = model.copy(
        tools = if (builtInSearch) model.tools + BuiltInTools.Search else model.tools - BuiltInTools.Search,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** SQL NULL, upstream's empty-string sentinel and JSON null all mean no frozen config. */
        fun fromLegacyJson(value: String?): UpstreamConversationConfig? {
            if (value.isNullOrBlank()) return null
            val element = json.parseToJsonElement(value)
            if (element == JsonNull) return null
            require(element is JsonObject) { "Upstream conversation config must be an object" }
            return json.decodeFromJsonElement(serializer(), element)
        }

        /**
         * Validate before import, but retain unknown fields and explicit null/false/empty values.
         * There are no polymorphic class names in this upstream schema: UUIDs and skills are opaque
         * identifiers, so package-name replacement here would corrupt user data.
         */
        fun normalizeLegacyJson(value: String?): String? {
            fromLegacyJson(value) ?: return null
            return json.parseToJsonElement(requireNotNull(value)).toString()
        }
    }
}
