package app.androidrik.rikkahub.data.task

import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.data.db.dao.AgentRunDAO
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.utils.JsonInstant
import app.androidrik.workspace.WorkspaceTaskSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

data class ConversationTaskBranch(
    val task: TaskCard,
    val tools: List<String>,
    val approvals: List<SubagentApprovalQueue.Request>,
    val commands: List<TaskCard>,
)

data class ConversationTaskSnapshot(
    val runs: List<ConversationTaskBranch> = emptyList(),
    val commands: List<TaskCard> = emptyList(),
)

class ConversationTaskTree(private val dao: AgentRunDAO, private val workspace: WorkspaceRepository) {
    fun observe(conversationId: String, currentScope: () -> String?) = combine(
        dao.observeRunsByConversation(conversationId), workspace.observeBackgroundTasks(), SubagentApprovalQueue.requests,
    ) { runs, shells, approvals -> conversationTaskSnapshot(runs, shells, approvals, currentScope()) }
        .distinctUntilChanged().flowOn(Dispatchers.Default)
}

/** One bounded join in memory; no recursive DAO queries and no access to another conversation's tasks. */
internal fun conversationTaskSnapshot(
    runs: List<AgentRunEntity>, shells: List<WorkspaceTaskSnapshot>,
    approvals: List<SubagentApprovalQueue.Request>, currentScope: String?,
): ConversationTaskSnapshot {
    val byOwner = shells.filter { !it.owner.isNullOrBlank() }.groupBy { it.owner }
    val branches = runs.map { run ->
        val pending = approvals.filter { it.runId == run.id }
        val tools = runCatching {
            JsonInstant.decodeFromString<List<UIMessage>>(run.transcript)
                .flatMap { it.parts.filterIsInstance<UIMessagePart.Tool>() }.takeLast(3).map { it.toolName }
        }.getOrDefault(emptyList())
        ConversationTaskBranch(agentTaskCard(run, pending.size), tools, pending,
            byOwner["run:${run.id}"].orEmpty().map(::shellTaskCard))
    }
    val scopes = runs.mapNotNull { it.scopeToken }.toSet() + listOfNotNull(currentScope)
    val runOwners = runs.map { "run:${it.id}" }.toSet()
    return ConversationTaskSnapshot(branches,
        shells.filter { it.owner in scopes && it.owner !in runOwners }.map(::shellTaskCard))
}
