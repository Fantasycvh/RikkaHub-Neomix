package app.androidrik.rikkahub.data.task

import kotlinx.serialization.Serializable

/** Immutable destination captured before dispatch, independently of the current session owner. */
@Serializable
data class AgentNoticeTarget(
    val conversationId: String,
    val assistantId: String,
    val workspaceId: String,
    val anchorMessageId: String,
    val rootScope: String,
    val allowWake: Boolean,
)
