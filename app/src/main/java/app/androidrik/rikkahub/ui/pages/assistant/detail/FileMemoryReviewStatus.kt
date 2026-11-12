package app.androidrik.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.ai.MemoryConsolidationStatus
import app.androidrik.rikkahub.data.repository.MemoryProposalApplyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun FileMemoryReviewStatus(
    status: MemoryConsolidationStatus,
    enabled: Boolean,
    libraryId: String,
    vm: AssistantDetailVM,
) {
    val scope = rememberCoroutineScope()
    var detail by remember(libraryId) { mutableStateOf<MemoryConsolidationProposal?>(null) }
    var result by remember(libraryId) { mutableStateOf<MemoryProposalApplyResult?>(null) }
    var failed by remember(libraryId) { mutableStateOf(false) }
    var deciding by remember(libraryId) { mutableStateOf(false) }
    fun decide(proposal: MemoryConsolidationProposal, apply: Boolean) {
        if (deciding) return
        deciding = true
        failed = false
        result = null
        scope.launch {
            try {
                if (apply) result = vm.applyProposals(setOf(proposal.id), libraryId)
                else vm.rejectProposals(setOf(proposal.id), libraryId)
                detail = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                deciding = false
            }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.assistant_page_memory_file_review_desc),
            style = MaterialTheme.typography.bodySmall,
        )
        if (status.pendingTurns > 0) {
            Text(stringResource(
                R.string.assistant_page_memory_consolidation_pending,
                status.pendingConversations,
                status.pendingTurns,
            ))
        }
        TextButton(
            enabled = enabled && !status.running && status.suggestions.isEmpty(),
            onClick = { vm.organizeMemory(libraryId); result = null; failed = false },
        ) {
            Text(stringResource(
                if (status.running) R.string.assistant_page_memory_consolidation_running
                else R.string.assistant_page_memory_consolidate_now,
            ))
        }
        status.lastRun?.let { last ->
            when {
                last.error != null -> Text(stringResource(R.string.assistant_page_memory_file_review_failed))
                last.hasChanges -> Text(stringResource(R.string.assistant_page_memory_consolidation_applied, last.changedMemories))
                last.proposals.isEmpty() -> Text(stringResource(R.string.assistant_page_memory_file_review_no_changes))
            }
            if (last.error == null && last.libraryTotal > 0) {
                Text(stringResource(
                    R.string.assistant_page_memory_file_review_progress,
                    last.libraryReviewed,
                    last.libraryTotal,
                ))
            }
        }
        if (status.suggestions.isNotEmpty()) {
            Text(stringResource(R.string.assistant_page_memory_consolidation_waiting_review))
            status.suggestions.forEach { proposal ->
                TextButton(onClick = { detail = proposal; failed = false }) {
                    Text(proposal.fileTitle())
                }
            }
        }
        result?.let {
            Text(stringResource(R.string.assistant_page_memory_file_review_result, it.applied, it.stale, it.blocked))
        }
    }
    detail?.takeIf { selected -> status.suggestions.any { it.id == selected.id } }?.let { proposal ->
        AlertDialog(
            onDismissRequest = { if (!deciding) detail = null },
            title = { Text(proposal.fileTitle()) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (proposal.content.isNotBlank()) Text(proposal.content)
                    if (failed) Text(stringResource(R.string.assistant_page_memory_file_update_failed))
                }
            },
            confirmButton = {
                TextButton(enabled = !deciding, onClick = { decide(proposal, true) }) {
                    Text(stringResource(R.string.assistant_page_memory_consolidation_confirm_apply))
                }
            },
            dismissButton = {
                TextButton(enabled = !deciding, onClick = { decide(proposal, false) }) {
                    Text(stringResource(R.string.assistant_page_memory_consolidation_reject))
                }
            },
        )
    }
}

@Composable
private fun MemoryConsolidationProposal.fileTitle(): String = when (action) {
    "create" -> stringResource(R.string.assistant_page_memory_file_proposal_create, targetId.orEmpty())
    "edit" -> stringResource(R.string.assistant_page_memory_file_proposal_edit, targetId.orEmpty())
    "move" -> stringResource(R.string.assistant_page_memory_file_proposal_move, targetId.orEmpty(), fileDestination.orEmpty())
    else -> targetId.orEmpty()
}
