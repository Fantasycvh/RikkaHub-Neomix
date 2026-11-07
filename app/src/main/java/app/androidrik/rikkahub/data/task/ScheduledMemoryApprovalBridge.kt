package app.androidrik.rikkahub.data.task

import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart

/** The same scheduled generation job owns the wait and continuation, so cancellation reaches both. */
internal suspend fun bridgeScheduledMemoryApprovals(
    pending: () -> List<UIMessagePart.Tool>,
    decide: suspend (List<UIMessagePart.Tool>) -> Map<String, ToolApprovalState>,
    apply: suspend (Map<String, ToolApprovalState>) -> Unit,
    resume: suspend () -> Unit,
    onLimit: suspend () -> Unit,
    maxRounds: Int = 3,
) {
    repeat(maxRounds) {
        val tools = pending()
        if (tools.isEmpty()) return
        apply(decide(tools))
        resume()
    }
    val remaining = pending()
    if (remaining.isNotEmpty()) {
        apply(remaining.associate { it.toolCallId to ToolApprovalState.Denied("Scheduled memory approval limit reached; wait for user review.") })
        onLimit()
    }
}

internal fun pendingScheduledMemoryTools(messages: List<UIMessage>): List<UIMessagePart.Tool> = messages
    .flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>()
    .filter { it.isPending && it.toolName == "memory_tool" }
