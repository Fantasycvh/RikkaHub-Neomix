package app.androidrik.rikkahub.data.task

import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.APPROVAL_TIMEOUT_REASON
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.ai.USER_DENIED_REASON
import app.androidrik.rikkahub.data.model.SubagentToolApproval
import kotlinx.coroutines.withTimeoutOrNull

const val BACKGROUND_APPROVAL_UNAVAILABLE = "Background approval notification is unavailable; tool execution denied."

data class BackgroundApprovalDecision(val policy: SubagentToolApproval, val note: String? = null)

fun backgroundApprovalDecision(requested: SubagentToolApproval, routeAvailable: Boolean) =
    if (requested == SubagentToolApproval.ASK && !routeAvailable)
        BackgroundApprovalDecision(SubagentToolApproval.DENY, BACKGROUND_APPROVAL_UNAVAILABLE)
    else BackgroundApprovalDecision(requested)

/** One queue registration and bounded wait per batch; loss of routing never leaves a background waiter. */
suspend fun awaitRunApproval(
    runId: String,
    label: String,
    pending: List<UIMessagePart.Tool>,
    parentConversationId: String?,
    background: Boolean,
    timeoutMillis: Long,
    routeAvailable: () -> Boolean,
    postNotification: () -> Boolean,
    cancelNotification: () -> Unit,
): Map<String, ToolApprovalState> {
    fun denied(reason: String) = pending.associate { it.toolCallId to ToolApprovalState.Denied(reason) }
    if (background && !routeAvailable()) return denied(BACKGROUND_APPROVAL_UNAVAILABLE)
    val waiters = SubagentApprovalQueue.registerBatch(runId, label, pending, parentConversationId)
    try {
        if (!postNotification() && background) return denied(BACKGROUND_APPROVAL_UNAVAILABLE)
        withTimeoutOrNull(timeoutMillis) { waiters.values.forEach { it.await() } }
        return pending.associate { tool ->
            val waiter = waiters.getValue(tool.toolCallId)
            tool.toolCallId to when {
                !waiter.isCompleted -> ToolApprovalState.Denied(APPROVAL_TIMEOUT_REASON)
                waiter.getCompleted() -> ToolApprovalState.Approved
                else -> ToolApprovalState.Denied(USER_DENIED_REASON)
            }
        }
    } finally {
        pending.forEach { SubagentApprovalQueue.forget(it.toolCallId) }
        cancelNotification()
    }
}
