package app.androidrik.ai.ui

import kotlinx.serialization.Serializable

/** Binds a stored Vertex response to the local request prefix and unedited assistant reply. */
@Serializable
data class VertexInteractionCheckpoint(
    val id: String,
    val contextHash: String,
    val inputHash: String,
    val answerHash: String,
    val expiresAtMillis: Long,
)
