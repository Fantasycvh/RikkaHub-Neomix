package app.androidrik.rikkahub.data.ai

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class MemoryExtractionConfig(
    val enabled: Boolean = false,
    val modelId: Uuid? = null,
    val reusePromptCache: Boolean = false,
)
