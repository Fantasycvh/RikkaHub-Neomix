package app.androidrik.rikkahub.data.repository

import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.db.dao.MemoryProposalDAO
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/** Narrow persistent proposal interface; no LLM work belongs inside its short transactions. */
interface MemoryProposalStore {
    suspend fun savePending(memoryId: String, proposals: List<MemoryConsolidationProposal>)
    suspend fun pending(memoryId: String): List<MemoryConsolidationProposal>
    suspend fun memoryIdsWithPending(): List<String>
    suspend fun recentlyRejected(
        memoryId: String,
        limit: Int = MemoryProposalRepository.REFUSED_FEEDBACK_LIMIT,
    ): List<MemoryConsolidationProposal>
    suspend fun apply(memoryId: String, ids: Set<Long>): MemoryProposalApplyResult
    suspend fun reject(memoryId: String, ids: Set<Long>): Int
}

/**
 * Versioned proposal application. The supplied expectedRevision is evidence from the model-visible
 * snapshot, never a version acquired while saving. Each batch is an explicit operation DAG, not an
 * implicit all-or-nothing dependency group. Only named dependencies gate an operation.
 *
 * Save/reject/apply and their memory/history/trash writes share the same transaction runner. Dependency
 * receipts retain the precise canonical output ID and revision; an applied label alone is not evidence
 * that the replacement content still exists. Legacy rows and malformed graphs fail closed.
 */
class MemoryProposalRepository(
    private val proposalDAO: MemoryProposalDAO,
    private val memoryRepository: MemoryRepository,
    private val transactions: MemoryTransactionRunner,
    private val now: () -> Long = System::currentTimeMillis,
) : MemoryProposalStore {
    companion object {
        const val DECIDED_AUDIT_KEEP = 100
        const val REFUSED_FEEDBACK_LIMIT = 20
    }

    override suspend fun savePending(
        memoryId: String,
        proposals: List<MemoryConsolidationProposal>,
    ) = transactions.run {
        if (proposals.isEmpty()) return@run
        val at = now()
        val defaultBatch = proposals.firstOrNull { it.batchId.isNotBlank() }?.batchId
            ?: Uuid.random().toString()
        val rows = proposals.map { proposal ->
            require(proposal.dueAt == null || proposal.dueAt >= 0L) { "Invalid proposal deadline" }
            MemoryProposalEntity(
                memoryId = memoryId,
                batchId = proposal.batchId.ifBlank { defaultBatch },
                action = proposal.action,
                targetId = proposal.targetId,
                content = proposal.content,
                importance = proposal.importance,
                description = proposal.description,
                createdAt = proposal.createdAt.takeIf { it > 0L } ?: at,
                expectedRevision = proposal.expectedRevision,
                operationId = proposal.operationId,
                dependsOn = Json.encodeToString(proposal.dependsOn),
                contractVersion = proposal.contractVersion,
                kind = proposal.kind.orEmpty(),
                dueAt = proposal.dueAt?.let { if (it == 0L) -1L else it } ?: 0L,
            )
        }
        // Validate before replacing pending. Never read the target here to manufacture version evidence.
        rows.groupBy { it.batchId }.values.forEach { batch ->
            require(orderedBatch(batch) != null) { "Invalid versioned memory proposal dependency graph" }
        }
        proposalDAO.deletePending(memoryId)
        proposalDAO.insertAll(rows)
        proposalDAO.pruneDecided(memoryId, DECIDED_AUDIT_KEEP)
    }

    override suspend fun pending(memoryId: String): List<MemoryConsolidationProposal> =
        proposalDAO.getPending(memoryId).map { it.toProposal() }

    override suspend fun memoryIdsWithPending(): List<String> = proposalDAO.memoryIdsWithPending()

    override suspend fun recentlyRejected(memoryId: String, limit: Int): List<MemoryConsolidationProposal> =
        proposalDAO.getRecentlyRejected(memoryId, limit).map { it.toProposal() }

    override suspend fun apply(memoryId: String, ids: Set<Long>): MemoryProposalApplyResult = transactions.run {
        if (ids.isEmpty()) return@run MemoryProposalApplyResult()
        val rows = proposalDAO.getPending(memoryId).filter { it.id in ids }
        if (rows.isEmpty()) return@run MemoryProposalApplyResult()
        var applied = 0
        var stale = 0
        var blocked = 0
        var blockedByDecision = 0
        val writtenIds = mutableSetOf<String>()
        val explicitlyDeletedIds = mutableSetOf<String>()
        val requiredReplacementOutputs = mutableSetOf<Pair<String, Long>>()
        suspend fun markStale(row: MemoryProposalEntity) {
            proposalDAO.updateOne(row.id, MemoryProposalEntity.DECISION_STALE, now())
            stale++
        }

        for ((batchId, selected) in rows.groupBy { it.batchId }) {
            val batch = proposalDAO.getBatch(memoryId, batchId)
            val ordered = orderedBatch(batch)
            if (ordered == null) {
                selected.forEach { markStale(it) }
                continue
            }
            val selectedIds = selected.map { it.id }.toSet()
            val states = batch.associateBy { it.operationId }.toMutableMap()
            // Two selected writes using one snapshot cannot both claim successful CAS. Detect aliases
            // before mutating anything; even an explicit edge cannot invent the second observed version.
            val targetWrites = selected.filter { it.action != "create" }.mapNotNull { row ->
                row.targetId?.let { memoryRepository.getMemoryInStore(memoryId, it)?.id }?.let { it to row }
            }
            val conflictingIds = targetWrites.groupBy { it.first }.values.filter { it.size > 1 }
                .flatten().map { it.second.id }.toSet()
            // A conflicting pair invalidates its own dependencies, not an unrelated merge in this run.
            selected.filter { it.id in conflictingIds }.forEach { row ->
                markStale(row)
                states[row.operationId] = row.copy(decision = MemoryProposalEntity.DECISION_STALE)
            }
            for (row in ordered.filter { it.id in selectedIds && it.id !in conflictingIds }) {
                val target = row.targetId?.let { memoryRepository.getMemoryInStore(memoryId, it) }
                if (row.action != "create" && (target == null || target.revision != row.expectedRevision ||
                        (row.action in setOf("complete", "drop") && target.kind != MemoryKind.TASK))) {
                    markStale(row)
                    states[row.operationId] = row.copy(decision = MemoryProposalEntity.DECISION_STALE)
                    continue
                }
                val dependencies = requireNotNull(row.dependencies()).map { requireNotNull(states[it]) }
                if (dependencies.any { it.decision != MemoryProposalEntity.DECISION_APPLIED }) {
                    blocked++
                    if (dependencies.any {
                        it.decision == MemoryProposalEntity.DECISION_REJECTED ||
                            it.decision == MemoryProposalEntity.DECISION_STALE
                    }) blockedByDecision++
                    continue
                }
                // Revalidate every named replacement immediately before consumption. A user edit,
                // deletion, or quota eviction after an earlier apply invalidates its old receipt.
                val invalidOutput = dependencies.any { dependency ->
                    val output = dependency.appliedTargetId?.let {
                        memoryRepository.getMemoryInStore(memoryId, it)
                    }
                    output == null || dependency.appliedRevision == null ||
                        output.revision != dependency.appliedRevision ||
                        (row.action == "delete" && (output.id == target?.id || output.kind != target?.kind))
                }
                if (invalidOutput) {
                    markStale(row)
                    states[row.operationId] = row.copy(decision = MemoryProposalEntity.DECISION_STALE)
                    continue
                }
                val receipt = applyRow(memoryId, row)
                when (row.action) {
                    "create", "edit", "complete", "drop" -> writtenIds += requireNotNull(receipt.appliedTargetId)
                    "delete" -> {
                        explicitlyDeletedIds += requireNotNull(receipt.appliedTargetId)
                        dependencies.forEach { dependency ->
                            requiredReplacementOutputs += requireNotNull(dependency.appliedTargetId) to
                                requireNotNull(dependency.appliedRevision)
                        }
                    }
                }
                states[row.operationId] = receipt
                applied++
            }
        }
        // A later create may evict an earlier write. Explicit user-approved deletes are exempt.
        check((writtenIds - explicitlyDeletedIds).all { memoryRepository.getMemoryInStore(memoryId, it) != null }) {
            "Memory capacity conflict: proposal write was evicted during apply"
        }
        // A selected delete is not permission to destroy content another delete relied on preserving.
        // Recheck versions too: later writes must not silently replace the exact consumed output.
        check(requiredReplacementOutputs.all { (id, revision) ->
            memoryRepository.getMemoryInStore(memoryId, id)?.revision == revision
        }) { "Memory dependency conflict: required replacement output changed during apply" }
        proposalDAO.pruneDecided(memoryId, DECIDED_AUDIT_KEEP)
        MemoryProposalApplyResult(applied, stale, blocked, blockedByDecision)
    }

    private suspend fun applyRow(memoryId: String, row: MemoryProposalEntity): MemoryProposalEntity {
        val reason = "memory proposal ${row.operationId}"
        val output = when (row.action) {
            "create" -> memoryRepository.addMemory(
                memoryId, row.content, row.importance, row.description,
                actor = "CONSOLIDATOR", reason = reason,
                kind = MemoryKind.fromStorage(row.kind), dueAt = row.proposalDueAt() ?: 0L,
            )
            "edit" -> memoryRepository.updateContentInStore(
                memoryId, requireNotNull(row.targetId), row.content, row.importance, row.description,
                expectedRevision = requireNotNull(row.expectedRevision), actor = "CONSOLIDATOR", reason = reason,
                kind = row.kind.takeIf { it.isNotBlank() }?.let(MemoryKind::fromStorage),
                dueAt = row.proposalDueAt(),
            )
            "complete", "drop" -> memoryRepository.setTaskStatusInStore(
                memoryId, requireNotNull(row.targetId),
                if (row.action == "complete") MemoryTaskStatus.DONE else MemoryTaskStatus.DROPPED,
                expectedRevision = requireNotNull(row.expectedRevision), actor = "CONSOLIDATOR", reason = reason,
            )
            "delete" -> null
            else -> error("Unsupported proposal action")
        }
        val targetId = output?.id ?: requireNotNull(
            memoryRepository.getMemoryInStore(memoryId, requireNotNull(row.targetId)),
        ).id
        if (row.action == "delete") {
            check(memoryRepository.deleteMemoryInStore(
                memoryId, requireNotNull(row.targetId), expectedRevision = requireNotNull(row.expectedRevision),
                actor = "CONSOLIDATOR", reason = reason,
            )) { "Memory proposal target disappeared during apply" }
        }
        proposalDAO.markApplied(row.id, targetId, output?.revision, now())
        return row.copy(
            decision = MemoryProposalEntity.DECISION_APPLIED,
            appliedTargetId = targetId,
            appliedRevision = output?.revision,
        )
    }

    override suspend fun reject(memoryId: String, ids: Set<Long>): Int = transactions.run {
        if (ids.isEmpty()) return@run 0
        val rows = proposalDAO.getPending(memoryId).filter { it.id in ids }
        if (rows.isEmpty()) return@run 0
        proposalDAO.updateDecision(rows.map { it.id }, MemoryProposalEntity.DECISION_REJECTED, now())
        proposalDAO.pruneDecided(memoryId, DECIDED_AUDIT_KEEP)
        rows.size
    }
}

/** Malformed persisted JSON must not degrade to an empty dependency list. */
private fun MemoryProposalEntity.dependencies(): List<String>? =
    runCatching { Json.decodeFromString<List<String>>(dependsOn) }.getOrNull()

/** Null means an untrusted contract, malformed graph, missing edge, or cycle; never silently repair it. */
private fun orderedBatch(rows: List<MemoryProposalEntity>): List<MemoryProposalEntity>? {
    if (rows.isEmpty()) return null
    val byOperation = rows.associateBy { it.operationId }
    if (byOperation.size != rows.size || rows.any {
        it.contractVersion != 1 || it.operationId.isBlank() || it.action !in setOf("create", "edit", "delete", "complete", "drop") ||
            (it.action != "create" && (it.targetId.isNullOrBlank() || (it.expectedRevision ?: 0L) <= 0L))
    }) return null
    val edges = mutableMapOf<String, List<String>>()
    for (row in rows) {
        val dependencies = row.dependencies() ?: return null
        if (dependencies.size != dependencies.toSet().size ||
            (row.action == "delete" && dependencies.isEmpty()) || dependencies.any {
                val dependency = byOperation[it]
                dependency == null || dependency.batchId != row.batchId ||
                    dependency.action !in setOf("create", "edit")
            }
        ) return null
        edges[row.operationId] = dependencies
    }
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    val result = mutableListOf<MemoryProposalEntity>()
    fun visit(operationId: String): Boolean {
        if (operationId in visited) return true
        if (!visiting.add(operationId)) return false
        for (dependency in edges.getValue(operationId)) if (!visit(dependency)) return false
        visiting.remove(operationId)
        visited += operationId
        result += byOperation.getValue(operationId)
        return true
    }
    for (row in rows) if (!visit(row.operationId)) return null
    return result
}

data class MemoryProposalApplyResult(
    val applied: Int = 0,
    val stale: Int = 0,
    /** Named prerequisites have not successfully applied; row remains pending. */
    val blocked: Int = 0,
    /** Subset of blocked whose named prerequisite was rejected/stale. Unrelated decisions do not count. */
    val blockedByDecision: Int = 0,
) {
    val isEmpty: Boolean get() = applied == 0 && stale == 0 && blocked == 0
}

private fun MemoryProposalEntity.toProposal() = MemoryConsolidationProposal(
    action = action,
    targetId = targetId,
    content = content,
    importance = importance,
    description = description,
    id = id,
    batchId = batchId,
    createdAt = createdAt,
    expectedRevision = expectedRevision,
    operationId = operationId,
    // Display has no authority to apply. Invalid JSON remains invalid in the stored row used by apply.
    dependsOn = dependencies().orEmpty(),
    contractVersion = contractVersion,
    appliedTargetId = appliedTargetId,
    appliedRevision = appliedRevision,
    kind = kind.takeIf { it.isNotBlank() },
    dueAt = proposalDueAt(),
)

/** Keep legacy unspecified deadlines distinct from an explicit removal. */
private fun MemoryProposalEntity.proposalDueAt(): Long? = when {
    dueAt == 0L -> null
    dueAt == -1L -> 0L
    dueAt > 0L -> dueAt
    else -> error("Invalid proposal deadline")
}
