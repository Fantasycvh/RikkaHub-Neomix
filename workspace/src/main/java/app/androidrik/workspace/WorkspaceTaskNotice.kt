package app.androidrik.workspace

/** Captured before launch. Execution scopes identify cancellation ownership, never a destination. */
data class WorkspaceTaskNoticeTarget(
    val conversationId: String,
    val assistantId: String,
    val workspaceId: String,
    val anchorMessageId: String,
    val rootScope: String,
    val allowWake: Boolean,
    /** Provenance can be captured while automatic notices are disabled, then activated by handoff. */
    val deliveryEnabled: Boolean = true,
)

/** Retained until the application acknowledges durable receipt, independently of task-list eviction. */
data class WorkspaceTaskTerminal(
    val id: String,
    val target: WorkspaceTaskNoticeTarget,
    val task: WorkspaceTaskSnapshot,
)
