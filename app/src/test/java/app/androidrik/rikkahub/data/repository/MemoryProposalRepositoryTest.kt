package app.androidrik.rikkahub.data.repository

import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryHistoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryProposalDAO
import app.androidrik.rikkahub.data.db.dao.MemoryTrashDAO
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryHistoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity
import app.androidrik.rikkahub.data.db.entity.MemoryTrashEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Contract fakes exercise policy/rollback propagation, not SQLite isolation or actual process death. */
class MemoryProposalRepositoryTest {
    private class FakeProposalDAO : MemoryProposalDAO {
        val rows = mutableListOf<MemoryProposalEntity>()
        var nextId = 1L
        var afterInsert: (() -> Unit)? = null
        var afterDecision: (() -> Unit)? = null
        var afterPrune: (() -> Unit)? = null
        var afterGetPending: (suspend () -> Unit)? = null
        override suspend fun getPending(memoryId: String): List<MemoryProposalEntity> {
            val result = rows.filter { it.memoryId == memoryId && it.decision == "pending" }
            afterGetPending?.invoke()
            return result
        }
        override suspend fun getBatch(memoryId: String, batchId: String) =
            rows.filter { it.memoryId == memoryId && it.batchId == batchId }
        override suspend fun getRecentlyRejected(memoryId: String, limit: Int) =
            rows.filter { it.memoryId == memoryId && it.decision == "rejected" }
                .sortedByDescending { it.decidedAt }.take(limit)
        override suspend fun memoryIdsWithPending() =
            rows.filter { it.decision == "pending" }.map { it.memoryId }.distinct()
        override suspend fun insertAll(proposals: List<MemoryProposalEntity>) {
            proposals.forEach { rows += it.copy(id = nextId++) }
            afterInsert?.invoke()
        }
        override suspend fun deletePending(memoryId: String) {
            rows.removeAll { it.memoryId == memoryId && it.decision == "pending" }
        }
        override suspend fun updateDecision(ids: List<Long>, decision: String, now: Long) {
            rows.replaceAll { if (it.id in ids) it.copy(decision = decision, decidedAt = now) else it }
            afterDecision?.invoke()
        }
        override suspend fun updateOne(id: Long, decision: String, now: Long) {
            updateDecision(listOf(id), decision, now)
        }
        override suspend fun markApplied(id: Long, targetId: String?, revision: Long?, now: Long) {
            rows.replaceAll {
                if (it.id == id) it.copy(
                    decision = "applied", decidedAt = now, appliedTargetId = targetId, appliedRevision = revision,
                ) else it
            }
            afterDecision?.invoke()
        }
        override suspend fun pruneDecided(memoryId: String, keep: Int) {
            val protected = rows.filter { it.memoryId == memoryId && it.decision == "pending" }
                .map { it.batchId }.toSet()
            val drop = rows.filter {
                it.memoryId == memoryId && it.decision != "pending" && it.batchId !in protected
            }.sortedWith(compareByDescending<MemoryProposalEntity> { it.decidedAt }.thenByDescending { it.id })
                .drop(keep).map { it.id }.toSet()
            rows.removeAll { it.id in drop }
            afterPrune?.invoke()
        }
    }

    private class FakeMemoryDAO : MemoryDAO {
        val rows = mutableListOf<MemoryEntity>()
        var nextId = 1
        override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> =
            MutableStateFlow(rows.filter { it.assistantId == assistantId })
        override suspend fun getMemoriesOfAssistant(assistantId: String) = rows.filter { it.assistantId == assistantId }
        override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = MutableStateFlow(rows.toList())
        override suspend fun getAllMemories() = rows.toList()
        override suspend fun getMemoryById(id: Int) = rows.find { it.id == id }
        override suspend fun getMemoryByPublicId(publicId: String) = rows.find { it.publicId == publicId }
        override suspend fun publicIdExists(publicId: String) = rows.any { it.publicId == publicId }
        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val id = memory.id.takeIf { it != 0 } ?: nextId++
            nextId = maxOf(nextId, id + 1)
            rows += memory.copy(id = id)
            return id.toLong()
        }
        override suspend fun insertMemories(memories: List<MemoryEntity>) { memories.forEach { insertMemory(it) } }
        override suspend fun updateMemory(memory: MemoryEntity) = error("Do not replace whole rows")
        private fun update(
            id: Int, store: String, revision: Long?, transform: (MemoryEntity) -> MemoryEntity,
        ): Int {
            val index = rows.indexOfFirst {
                it.id == id && it.assistantId == store && (revision == null || revision == it.revision)
            }
            if (index < 0) return 0
            rows[index] = transform(rows[index])
            return 1
        }
        override suspend fun updateContentFields(
            id: Int, assistantId: String, content: String, importance: Float?, description: String?, now: Long,
            expectedRevision: Long?,
        ) = update(id, assistantId, expectedRevision) {
            it.copy(content = content, importance = importance ?: it.importance,
                description = description ?: it.description, updatedAt = now, revision = it.revision + 1)
        }
        override suspend fun updateImportanceFields(
            id: Int, assistantId: String, importance: Float, now: Long, expectedRevision: Long?,
        ) = update(id, assistantId, expectedRevision) {
            it.copy(importance = importance, updatedAt = now, revision = it.revision + 1)
        }
        override suspend fun updateAnchor(
            id: Int, assistantId: String, isAnchor: Boolean, expectedRevision: Long?,
        ): Int {
            if (rows.none { it.id == id && it.isAnchor != isAnchor }) return 0
            return update(id, assistantId, expectedRevision) {
                it.copy(isAnchor = isAnchor, revision = it.revision + 1)
            }
        }
        override suspend fun backfillDescription(
            id: Int, assistantId: String, description: String, expectedRevision: Long?,
        ): Int {
            if (rows.none { it.id == id && it.description.isEmpty() }) return 0
            return update(id, assistantId, expectedRevision) {
                it.copy(description = description, revision = it.revision + 1)
            }
        }
        override suspend fun replaceBusinessFields(
            id: Int, assistantId: String, content: String, importance: Float, description: String,
            isAnchor: Boolean, now: Long, expectedRevision: Long,
        ) = update(id, assistantId, expectedRevision) {
            it.copy(content = content, importance = importance, description = description, isAnchor = isAnchor,
                updatedAt = now, revision = it.revision + 1)
        }
        override suspend fun recordReadInStore(id: Int, assistantId: String, now: Long) =
            update(id, assistantId, null) {
                it.copy(readCount = it.readCount + 1, lastReadAt = maxOf(it.lastReadAt, now))
            }
        override suspend fun deleteMemoryInStore(id: Int, assistantId: String, expectedRevision: Long?): Int {
            val removed = rows.removeAll {
                it.id == id && it.assistantId == assistantId &&
                    (expectedRevision == null || expectedRevision == it.revision)
            }
            return if (removed) 1 else 0
        }
        override suspend fun recordRead(id: Int, now: Long) {
            rows.firstOrNull { it.id == id }?.let { recordReadInStore(id, it.assistantId, now) }
        }
        override suspend fun deleteMemory(id: Int) { rows.removeAll { it.id == id } }
        override suspend fun deleteMemoryByPublicId(publicId: String) { rows.removeAll { it.publicId == publicId } }
        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            rows.removeAll { it.assistantId == assistantId }
        }
    }

    private class FakeHistoryDAO : MemoryHistoryDAO {
        val rows = mutableListOf<MemoryHistoryEntity>()
        var afterInsert: (() -> Unit)? = null
        override suspend fun insert(event: MemoryHistoryEntity): Long {
            val id = (rows.maxOfOrNull { it.id } ?: 0L) + 1
            rows += event.copy(id = id)
            afterInsert?.invoke()
            return id
        }
        override suspend fun list(storeId: String, memoryId: Int) =
            rows.filter { it.assistantId == storeId && it.memoryId == memoryId }.sortedByDescending { it.id }
        override suspend fun getRevision(storeId: String, memoryId: Int, revision: Long) =
            list(storeId, memoryId).firstOrNull { it.revision == revision }
        override suspend fun prune(storeId: String, memoryId: Int, keep: Int) {
            val drop = list(storeId, memoryId).drop(keep).map { it.id }.toSet()
            rows.removeAll { it.id in drop }
        }
    }

    private class FakeTrashDAO : MemoryTrashDAO {
        val rows = mutableListOf<MemoryTrashEntity>()
        var afterInsert: (() -> Unit)? = null
        override suspend fun insert(tombstone: MemoryTrashEntity) {
            rows += tombstone
            afterInsert?.invoke()
        }
        override suspend fun list(storeId: String) = rows.filter { it.assistantId == storeId }
        override suspend fun get(storeId: String, memoryId: Int) =
            rows.find { it.assistantId == storeId && it.memoryId == memoryId }
        override suspend fun publicIdExists(publicId: String) = rows.any { it.publicId == publicId }
        override suspend fun delete(storeId: String, memoryId: Int, revision: Long): Int =
            if (rows.removeAll {
                it.assistantId == storeId && it.memoryId == memoryId && it.revision == revision
            }) 1 else 0
    }

    private class Fixture {
        val proposals = FakeProposalDAO()
        val memories = FakeMemoryDAO()
        val history = FakeHistoryDAO()
        val trash = FakeTrashDAO()
        val transactions = SnapshotTransactions(this)
        val memory = MemoryRepository(memories, transactions, historyDAO = history, trashDAO = trash)
        val repository = MemoryProposalRepository(proposals, memory, transactions, now = { 1_000L })
        suspend fun seed(content: String, store: String = "store") = memory.addMemory(store, content)
        suspend fun save(vararg proposals: MemoryConsolidationProposal) =
            repository.savePending("store", proposals.toList())
        suspend fun applyAll() = repository.apply("store", repository.pending("store").map { it.id }.toSet())
        fun row(operation: String) = proposals.rows.single { it.operationId == operation }
        suspend fun apply(vararg operations: String) = repository.apply(
            "store", operations.map { row(it).id }.toSet(),
        )
    }

    private class SnapshotTransactions(private val fixture: Fixture) : MemoryTransactionRunner {
        private class Active(val runner: SnapshotTransactions) : AbstractCoroutineContextElement(Key) {
            companion object Key : CoroutineContext.Key<Active>
        }
        private val mutex = Mutex()
        var rollbacks = 0
        override suspend fun <T> run(block: suspend () -> T): T {
            if (currentCoroutineContext()[Active]?.runner === this) return block()
            return mutex.withLock {
                val proposals = fixture.proposals.rows.toList()
                val memories = fixture.memories.rows.toList()
                val history = fixture.history.rows.toList()
                val trash = fixture.trash.rows.toList()
                val nextProposal = fixture.proposals.nextId
                val nextMemory = fixture.memories.nextId
                try {
                    withContext(Active(this)) { block() }
                } catch (failure: Throwable) {
                    fixture.proposals.rows.apply { clear(); addAll(proposals) }
                    fixture.memories.rows.apply { clear(); addAll(memories) }
                    fixture.history.rows.apply { clear(); addAll(history) }
                    fixture.trash.rows.apply { clear(); addAll(trash) }
                    fixture.proposals.nextId = nextProposal
                    fixture.memories.nextId = nextMemory
                    rollbacks++
                    throw failure
                }
            }
        }
    }

    private fun create(operation: String = "create", content: String = "replacement", batch: String = "batch") =
        MemoryConsolidationProposal(action = "create", content = content, operationId = operation, batchId = batch)
    private fun edit(id: String, revision: Long = 1, operation: String = "edit", content: String = "merged") =
        MemoryConsolidationProposal(action = "edit", targetId = id, expectedRevision = revision,
            operationId = operation, content = content, batchId = "batch")
    private fun delete(id: String, dependency: String = "edit", revision: Long = 1, operation: String = "delete") =
        MemoryConsolidationProposal(action = "delete", targetId = id, expectedRevision = revision,
            operationId = operation, dependsOn = listOf(dependency), batchId = "batch")
    private suspend fun expectInvalid(block: suspend () -> Unit) {
        try { block(); fail("Invalid contract must fail closed") } catch (_: IllegalArgumentException) { }
    }
    private class InjectedFailure : IllegalStateException("injected persistence failure")
    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Persistence failure must propagate") } catch (_: InjectedFailure) { }
    }

    @Test fun `save roundtrips visible revision operation and explicit dependencies`() = runBlocking {
        val f = Fixture()
        val source = f.seed("source")
        val output = f.seed("output")
        f.save(edit(output.id, revision = 7), delete(source.id))
        val restored = f.repository.pending("store")
        assertEquals(7L, restored.first().expectedRevision)
        assertEquals(listOf("edit"), restored.last().dependsOn)
        assertEquals("delete", restored.last().operationId)
        assertEquals(1, restored.last().contractVersion)
        assertTrue(restored.all { it.id > 0L })
        assertEquals(listOf("store"), f.repository.memoryIdsWithPending())
    }

    @Test fun `save never replaces supplied snapshot revision with live revision`() = runBlocking {
        val f = Fixture()
        val target = f.seed("original")
        f.memory.updateContentInStore("store", target.id, "newer")
        f.save(edit(target.id, revision = target.revision))
        assertEquals(1L, f.proposals.rows.single().expectedRevision)
        assertEquals(MemoryProposalApplyResult(stale = 1), f.applyAll())
        assertEquals("newer", f.memory.getMemoryInStore("store", target.id)?.content)
    }

    @Test fun `missing and zero edit revision are rejected before replacing pending`() = runBlocking {
        val f = Fixture()
        f.save(create())
        val before = f.proposals.rows.toList()
        for (revision in listOf(null, 0L, -1L)) {
            expectInvalid { f.save(edit("target").copy(expectedRevision = revision)) }
            assertEquals(before, f.proposals.rows)
        }
    }

    @Test fun `legacy pending create and edit both become stale without being applied`() = runBlocking {
        val f = Fixture()
        val target = f.seed("old")
        f.proposals.insertAll(listOf(
            MemoryProposalEntity(memoryId = "store", batchId = "legacy", action = "create", targetId = null,
                content = "untrusted", importance = null, description = null, createdAt = 1),
            MemoryProposalEntity(memoryId = "store", batchId = "legacy", action = "edit", targetId = target.id,
                content = "untrusted", importance = null, description = null, createdAt = 1),
        ))
        assertEquals(MemoryProposalApplyResult(stale = 2), f.applyAll())
        assertEquals("old", f.memories.rows.single().content)
        assertTrue(f.proposals.rows.all { it.decision == "stale" })
    }

    @Test fun `new save rejects legacy contract even for create`() = runBlocking {
        expectInvalid { Fixture().save(create().copy(contractVersion = 0)) }
    }

    @Test fun `invalid graphs cannot replace pending proposals`() = runBlocking {
        val f = Fixture()
        f.save(create("previous"))
        val previous = f.proposals.rows.toList()
        val invalid = listOf(
            listOf(create("same"), create("same")),
            listOf(delete("source", "missing")),
            listOf(create(), delete("source", "create").copy(dependsOn = emptyList())),
            listOf(create("a").copy(dependsOn = listOf("a"))),
            listOf(create("a").copy(dependsOn = listOf("b")), create("b").copy(dependsOn = listOf("a"))),
            listOf(create("a"), delete("source", "a"), create("b").copy(dependsOn = listOf("delete"))),
            listOf(create("a"), delete("source", "a").copy(dependsOn = listOf("a", "a"))),
            listOf(create("a", batch = "other"), delete("source", "a")),
            listOf(create().copy(action = "unsupported")),
        )
        for (graph in invalid) {
            expectInvalid { f.repository.savePending("store", graph) }
            assertEquals(previous, f.proposals.rows)
        }
    }

    @Test fun `corrupt persisted JSON cannot turn delete into independent operation`() = runBlocking {
        val f = Fixture()
        val target = f.seed("keep")
        f.save(create(), delete(target.id, "create"))
        f.proposals.rows.replaceAll { if (it.action == "delete") it.copy(dependsOn = "not json") else it }
        assertEquals(MemoryProposalApplyResult(stale = 2), f.applyAll())
        assertEquals("keep", f.memories.rows.single().content)
    }

    @Test fun `reverse order graph is executed topologically and receipts persist`() = runBlocking {
        val f = Fixture()
        val keep = f.seed("keep")
        val source = f.seed("source")
        f.save(delete(source.id), edit(keep.id))
        assertEquals(MemoryProposalApplyResult(applied = 2), f.applyAll())
        assertEquals("merged", f.memories.rows.single().content)
        assertEquals(keep.id, f.row("edit").appliedTargetId)
        assertEquals(2L, f.row("edit").appliedRevision)
        assertEquals(source.id, f.row("delete").appliedTargetId)
        assertNotNull(f.trash.rows.singleOrNull())
        assertTrue(f.history.rows.any { it.actor == "CONSOLIDATOR" && it.reason.contains("edit") })
    }

    @Test fun `delete alone does not silently execute its unselected dependency`() = runBlocking {
        val f = Fixture()
        val keep = f.seed("keep")
        val source = f.seed("source")
        f.save(edit(keep.id), delete(source.id))
        assertEquals(MemoryProposalApplyResult(blocked = 1), f.apply("delete"))
        assertEquals(2, f.memories.rows.size)
        assertEquals("keep", f.memory.getMemoryInStore("store", keep.id)?.content)
        assertEquals("pending", f.row("delete").decision)
    }

    @Test fun `edit and create may each apply before dependent delete`() = runBlocking {
        for (useCreate in listOf(true, false)) {
            val f = Fixture()
            val keep = f.seed("keep")
            val source = f.seed("source")
            val write = if (useCreate) create("write") else edit(keep.id, operation = "write")
            f.save(write, delete(source.id, "write"))
            assertEquals(MemoryProposalApplyResult(applied = 1), f.apply("write"))
            assertEquals(MemoryProposalApplyResult(applied = 1), f.apply("delete"))
            assertEquals(null, f.memory.getMemoryInStore("store", source.id))
        }
    }

    @Test fun `unrelated rejected and unselected operations do not block named merge`() = runBlocking {
        val f = Fixture()
        val keep = f.seed("keep")
        val source = f.seed("source")
        f.save(edit(keep.id), delete(source.id), create("unrelated"), create("unselected", "later"))
        f.repository.reject("store", setOf(f.row("unrelated").id))
        assertEquals(MemoryProposalApplyResult(applied = 2), f.apply("delete", "edit"))
        assertEquals(null, f.memory.getMemoryInStore("store", source.id))
        assertEquals("pending", f.row("unselected").decision)
    }

    @Test fun `independent merges in one batch do not acquire implicit cross dependencies`() = runBlocking {
        val f = Fixture()
        val keepA = f.seed("a")
        val sourceA = f.seed("source a")
        val keepB = f.seed("b")
        val sourceB = f.seed("source b")
        f.save(edit(keepA.id, operation = "a"), delete(sourceA.id, "a", operation = "delete-a"),
            edit(keepB.id, operation = "b"), delete(sourceB.id, "b", operation = "delete-b"))
        f.repository.reject("store", setOf(f.row("b").id))
        assertEquals(MemoryProposalApplyResult(applied = 2), f.apply("delete-a", "a"))
        assertEquals(MemoryProposalApplyResult(blocked = 1, blockedByDecision = 1), f.apply("delete-b"))
        assertNotNull(f.memory.getMemoryInStore("store", sourceB.id))
    }

    @Test fun `conflicting aliases do not poison an independent merge in the same batch`() = runBlocking {
        val f = Fixture()
        val conflict = f.seed("conflicting source")
        val dependentSource = f.seed("dependent on conflict")
        val keep = f.seed("independent keep")
        val source = f.seed("independent source")
        f.save(
            edit(conflict.id, operation = "conflict-a"),
            edit(conflict.sortKey.toString(), operation = "conflict-b"),
            delete(dependentSource.id, "conflict-a", operation = "blocked-delete"),
            edit(keep.id, operation = "independent"),
            delete(source.id, "independent", operation = "independent-delete"),
        )
        assertEquals(MemoryProposalApplyResult(applied = 2, stale = 2, blocked = 1, blockedByDecision = 1), f.applyAll())
        assertEquals("stale", f.row("conflict-a").decision)
        assertEquals("stale", f.row("conflict-b").decision)
        assertEquals("pending", f.row("blocked-delete").decision)
        assertEquals("applied", f.row("independent-delete").decision)
        assertEquals("conflicting source", f.memory.getMemoryInStore("store", conflict.id)?.content)
        assertNotNull(f.memory.getMemoryInStore("store", dependentSource.id))
        assertNull(f.memory.getMemoryInStore("store", source.id))
    }

    @Test fun `rejected or stale named prerequisite blocks delete and keeps source`() = runBlocking {
        for (reject in listOf(true, false)) {
            val f = Fixture()
            val keep = f.seed("keep")
            val source = f.seed("source")
            f.save(edit(keep.id), delete(source.id))
            if (reject) f.repository.reject("store", setOf(f.row("edit").id)) else {
                f.memory.updateContentInStore("store", keep.id, "new user content")
                assertEquals(MemoryProposalApplyResult(stale = 1), f.apply("edit"))
            }
            assertEquals(MemoryProposalApplyResult(blocked = 1, blockedByDecision = 1), f.apply("delete"))
            assertNotNull(f.memory.getMemoryInStore("store", source.id))
        }
    }

    @Test fun `changed dependency output invalidates later delete despite applied label`() = runBlocking {
        val f = Fixture()
        val keep = f.seed("keep")
        val source = f.seed("source")
        f.save(edit(keep.id), delete(source.id))
        f.apply("edit")
        f.memory.updateContentInStore("store", keep.id, "user changed replacement")
        assertEquals(MemoryProposalApplyResult(stale = 1), f.apply("delete"))
        assertNotNull(f.memory.getMemoryInStore("store", source.id))
        assertEquals(2L, f.row("edit").appliedRevision)
    }

    @Test fun `missing dependency output invalidates later delete`() = runBlocking {
        val f = Fixture()
        val source = f.seed("source")
        f.save(create(), delete(source.id, "create"))
        f.apply("create")
        f.memory.deleteMemoryInStore("store", requireNotNull(f.row("create").appliedTargetId))
        assertEquals(MemoryProposalApplyResult(stale = 1), f.apply("delete"))
        assertNotNull(f.memory.getMemoryInStore("store", source.id))
    }

    @Test fun `receipt without output revision is not valid dependency evidence`() = runBlocking {
        val f = Fixture()
        val source = f.seed("source")
        f.save(create(), delete(source.id, "create"))
        f.apply("create")
        f.proposals.rows.replaceAll { if (it.operationId == "create") it.copy(appliedRevision = null) else it }
        assertEquals(MemoryProposalApplyResult(stale = 1), f.apply("delete"))
        assertNotNull(f.memory.getMemoryInStore("store", source.id))
    }

    @Test fun `read telemetry leaves dependency version valid`() = runBlocking {
        val f = Fixture()
        val keep = f.seed("keep")
        val source = f.seed("source")
        f.save(edit(keep.id), delete(source.id))
        f.apply("edit")
        f.memory.readMemoryInStore("store", keep.id)
        assertEquals(MemoryProposalApplyResult(applied = 1), f.apply("delete"))
    }

    @Test fun `two writes to same canonical target snapshot fail closed before first edit`() = runBlocking {
        val f = Fixture()
        val keep = f.seed("original")
        f.save(edit(keep.id, operation = "first"),
            edit(keep.sortKey.toString(), operation = "second").copy(dependsOn = listOf("first")))
        val history = f.history.rows.toList()
        assertEquals(MemoryProposalApplyResult(stale = 2), f.applyAll())
        assertEquals("original", f.memory.getMemoryInStore("store", keep.id)?.content)
        assertEquals(history, f.history.rows)
    }

    @Test fun `foreign public and legacy numeric targets are stale without mutation`() = runBlocking {
        val f = Fixture()
        val foreign = f.seed("foreign", "other")
        for (id in listOf(foreign.id, foreign.sortKey.toString())) {
            f.save(edit(id))
            assertEquals(MemoryProposalApplyResult(stale = 1), f.applyAll())
        }
        assertEquals("foreign", f.memory.getMemoryInStore("other", foreign.id)?.content)
    }

    @Test fun `versioned same-store numeric alias returns canonical receipt`() = runBlocking {
        val f = Fixture()
        val target = f.seed("old")
        f.save(edit(target.sortKey.toString()))
        assertEquals(MemoryProposalApplyResult(applied = 1), f.applyAll())
        assertEquals(target.id, f.row("edit").appliedTargetId)
        assertEquals(2L, f.row("edit").appliedRevision)
    }

    @Test fun `same batch id in another store cannot satisfy dependency`() = runBlocking {
        val f = Fixture()
        f.repository.savePending("other", listOf(create("write")))
        f.repository.apply("other", f.repository.pending("other").map { it.id }.toSet())
        expectInvalid { f.save(delete("source", "write")) }
        assertTrue(f.repository.pending("store").isEmpty())
    }

    @Test fun `snapshot stale delete preserves source even after valid replacement apply`() = runBlocking {
        val f = Fixture()
        val source = f.seed("source")
        f.save(create(), delete(source.id, "create"))
        f.apply("create")
        f.memory.updateContentInStore("store", source.id, "user changed source")
        assertEquals(MemoryProposalApplyResult(stale = 1), f.apply("delete"))
        assertEquals("user changed source", f.memory.getMemoryInStore("store", source.id)?.content)
    }

    @Test fun `reject and unknown selection never mutate memory`() = runBlocking {
        val f = Fixture()
        val target = f.seed("keep")
        f.save(edit(target.id))
        assertTrue(f.repository.apply("store", setOf(9999L)).isEmpty)
        assertEquals(1, f.repository.reject("store", setOf(f.row("edit").id)))
        assertTrue(f.apply("edit").isEmpty)
        assertEquals("keep", f.memories.rows.single().content)
        assertEquals(1, f.repository.recentlyRejected("store").size)
    }

    @Test fun `empty save preserves pending and new valid save replaces it`() = runBlocking {
        val f = Fixture()
        f.save(create("first"))
        f.repository.savePending("store", emptyList())
        assertEquals("first", f.proposals.rows.single().operationId)
        f.save(create("second"))
        assertEquals("second", f.proposals.rows.single().operationId)
    }

    @Test fun `failed replacement restores previous pending and operation identity`() = runBlocking {
        val f = Fixture()
        f.save(create("previous"))
        val previous = f.proposals.rows.toList()
        f.proposals.afterInsert = { throw InjectedFailure() }
        expectFailure { f.save(create("replacement")) }
        assertEquals(previous, f.proposals.rows)
        assertEquals(1, f.transactions.rollbacks)
    }

    @Test fun `failure after receipt rolls back content history trash and decisions`() = runBlocking {
        val f = Fixture()
        val keep = f.seed("keep")
        val source = f.seed("source")
        f.save(edit(keep.id), delete(source.id))
        val memories = f.memories.rows.toList()
        val history = f.history.rows.toList()
        val proposals = f.proposals.rows.toList()
        var decisions = 0
        f.proposals.afterDecision = { if (++decisions == 2) throw InjectedFailure() }
        expectFailure { f.applyAll() }
        assertEquals(2, decisions)
        assertEquals(memories, f.memories.rows)
        assertEquals(history, f.history.rows)
        assertTrue(f.trash.rows.isEmpty())
        assertEquals(proposals, f.proposals.rows)
    }

    @Test fun `history and trash failures cannot leave proposal falsely applied`() = runBlocking {
        for (failHistory in listOf(true, false)) {
            val f = Fixture()
            val keep = f.seed("keep")
            val source = f.seed("source")
            f.save(edit(keep.id), delete(source.id))
            val before = f.memories.rows.toList()
            val proposals = f.proposals.rows.toList()
            if (failHistory) f.history.afterInsert = { throw InjectedFailure() }
            else f.trash.afterInsert = { throw InjectedFailure() }
            expectFailure { f.applyAll() }
            assertEquals(before, f.memories.rows)
            assertEquals(proposals, f.proposals.rows)
            assertTrue(f.trash.rows.isEmpty())
        }
    }

    @Test fun `outer transaction failure rolls back nested save apply and history`() = runBlocking {
        val f = Fixture()
        expectFailure {
            f.transactions.run {
                f.save(create())
                assertEquals(1, f.applyAll().applied)
                throw InjectedFailure()
            }
        }
        assertTrue(f.proposals.rows.isEmpty())
        assertTrue(f.memories.rows.isEmpty())
        assertTrue(f.history.rows.isEmpty())
    }

    @Test fun `prune failure rolls back rejection and apply`() = runBlocking {
        for (apply in listOf(true, false)) {
            val f = Fixture()
            f.save(create())
            val before = f.proposals.rows.toList()
            f.proposals.afterPrune = { throw InjectedFailure() }
            expectFailure {
                if (apply) f.applyAll() else f.repository.reject("store", setOf(f.row("create").id))
            }
            assertEquals(before, f.proposals.rows)
            assertTrue(f.memories.rows.isEmpty())
            assertTrue(f.history.rows.isEmpty())
        }
    }

    @Test fun `apply excludes concurrent reject and save until receipt commits`() = runBlocking {
        val f = Fixture()
        f.save(create("accepted"))
        val id = f.row("accepted").id
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        f.proposals.afterGetPending = {
            f.proposals.afterGetPending = null
            entered.complete(Unit)
            resume.await()
        }
        val apply = async(start = CoroutineStart.UNDISPATCHED) { f.repository.apply("store", setOf(id)) }
        entered.await()
        val reject = async(start = CoroutineStart.UNDISPATCHED) { f.repository.reject("store", setOf(id)) }
        val save = async(start = CoroutineStart.UNDISPATCHED) { f.save(create("next", "next content")) }
        assertFalse(reject.isCompleted)
        assertFalse(save.isCompleted)
        resume.complete(Unit)
        assertEquals(1, apply.await().applied)
        assertEquals(0, reject.await())
        save.await()
        assertEquals("pending", f.row("next").decision)
        assertEquals("applied", f.row("accepted").decision)
    }

    @Test fun `pruning retains named dependency evidence until pending batch finishes`() = runBlocking {
        val f = Fixture()
        val source = f.seed("source")
        f.save(create(), delete(source.id, "create"))
        f.repository.reject("store", setOf(f.row("create").id))
        val evidence = f.row("create")
        f.proposals.insertAll(List(105) { i ->
            evidence.copy(id = 0, batchId = "audit-$i", operationId = "audit-$i", decidedAt = 10_000L + i)
        })
        assertEquals(MemoryProposalApplyResult(blocked = 1, blockedByDecision = 1), f.apply("delete"))
        assertEquals(evidence, f.row("create"))
        assertEquals(101, f.proposals.rows.count { it.decision != "pending" })
        f.repository.reject("store", setOf(f.row("delete").id))
        assertEquals(100, f.proposals.rows.size)
        assertTrue(f.proposals.rows.none { it.batchId == "batch" })
    }

    @Test fun `later capacity eviction of earlier write aborts entire apply`() = runBlocking {
        val f = Fixture()
        val at = System.currentTimeMillis()
        repeat(MemoryRepository.MAX_MEMORY_COUNT - 1) { i ->
            f.memories.insertMemory(MemoryEntity(assistantId = "store", publicId = "a$i", content = "old-$i",
                importance = 1f, isAnchor = true, updatedAt = at))
        }
        f.save(create("first", "lower importance").copy(importance = 0.1f),
            create("second", "higher importance").copy(importance = 0.9f))
        val before = f.memories.rows.toList()
        val proposals = f.proposals.rows.toList()
        try {
            f.applyAll()
            fail("An earlier successful write cannot silently disappear")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("capacity"))
        }
        assertEquals(before, f.memories.rows)
        assertEquals(proposals, f.proposals.rows)
        assertTrue(f.history.rows.isEmpty())
        assertTrue(f.trash.rows.isEmpty())
    }
}
