package app.androidrik.rikkahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.dao.MemoryProposalDAO
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room SQL and nested transaction rollback; never touches a production database. */
@RunWith(AndroidJUnit4::class)
class MemoryKindsProposalInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var transactions: RoomMemoryTransactionRunner
    private lateinit var memories: MemoryRepository
    private lateinit var proposals: MemoryProposalRepository
    private var clock = 100L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        transactions = RoomMemoryTransactionRunner(db)
        memories = MemoryRepository(db.memoryDao(), transactions, db.memoryHistoryDao(), db.memoryTrashDao())
        proposals = proposalRepository()
    }

    @After fun tearDown() { db.close() }

    private fun proposalRepository(dao: MemoryProposalDAO = db.memoryProposalDao()) =
        MemoryProposalRepository(dao, memories, transactions) { clock }

    private fun operation(action: String, target: String, revision: Long, batch: String = "batch", op: String = action) =
        MemoryConsolidationProposal(action = action, targetId = target, expectedRevision = revision, batchId = batch, operationId = op)

    private suspend fun applyPending() = proposals.apply("a", proposals.pending("a").map { it.id }.toSet())

    @Test fun completeAndDropPersistAcrossRepositoryRecreationWithExactReceipts() = runBlocking {
        for ((action, status) in listOf("complete" to MemoryTaskStatus.DONE, "drop" to MemoryTaskStatus.DROPPED)) {
            val task = memories.addMemory("a", "commitment $action", now = 10L, kind = MemoryKind.TASK, dueAt = 500L)
            proposals.savePending("a", listOf(operation(action, task.id, task.revision, batch = action)))
            proposals = proposalRepository() // Read persisted rows, not the original in-memory proposal.
            val pending = proposals.pending("a").single()
            assertEquals(action, pending.action)
            assertEquals(task.revision, pending.expectedRevision)
            assertEquals(1, applyPending().applied)
            val after = requireNotNull(memories.getMemoryInStore("a", task.id))
            assertEquals(status, after.taskStatus)
            assertEquals(10L, after.updatedAt)
            assertEquals(500L, after.dueAt)
            assertEquals(task.content, after.content)
            assertEquals(task.revision + 1, after.revision)
            val receipt = db.memoryProposalDao().getBatch("a", action).single()
            assertEquals(MemoryProposalEntity.DECISION_APPLIED, receipt.decision)
            assertEquals(task.id, receipt.appliedTargetId)
            assertEquals(after.revision, receipt.appliedRevision)
            assertTrue(proposals.pending("a").isEmpty())
        }
    }

    @Test fun repeatedStatusProposalIsIdempotentButStaleSnapshotDoesNotBypassCas() = runBlocking {
        val task = memories.addMemory("a", "commitment", now = 10L, kind = MemoryKind.TASK)
        val done = memories.setTaskStatus(task.id, MemoryTaskStatus.DONE, task.revision, now = 20L)
        val events = db.memoryHistoryDao().list("a", task.sortKey)
        proposals.savePending("a", listOf(operation("complete", done.id, done.revision, batch = "idempotent")))
        assertEquals(1, applyPending().applied)
        assertEquals(done, memories.getMemoryInStore("a", task.id))
        assertEquals(events, db.memoryHistoryDao().list("a", task.sortKey))
        assertEquals(done.revision, db.memoryProposalDao().getBatch("a", "idempotent").single().appliedRevision)
        proposals.savePending("a", listOf(operation("complete", done.id, task.revision, batch = "old")))
        assertEquals(1, applyPending().stale)
        assertEquals(done, memories.getMemoryInStore("a", task.id))
    }

    @Test fun nonTaskAndForeignStoreStatusTargetsAreStaleWithoutMutation() = runBlocking {
        val topic = memories.addMemory("a", "ordinary topic")
        val foreign = memories.addMemory("b", "private commitment", kind = MemoryKind.TASK)
        proposals.savePending("a", listOf(operation("complete", topic.id, topic.revision), operation("drop", foreign.id, foreign.revision)))
        assertEquals(2, applyPending().stale)
        assertEquals(topic, memories.getMemoryInStore("a", topic.id))
        assertEquals(foreign, memories.getMemoryInStore("b", foreign.id))
        assertTrue(memories.listTrash("a").isEmpty())
    }

    @Test fun deleteCannotConsumeCrossKindReplacementEvenWithValidReceipt() = runBlocking {
        val original = memories.addMemory("a", "task to preserve", kind = MemoryKind.TASK)
        proposals.savePending("a", listOf(
            MemoryConsolidationProposal(action = "create", content = "topic replacement", kind = "topic", batchId = "cross", operationId = "replacement"),
            operation("delete", original.id, original.revision, batch = "cross").copy(dependsOn = listOf("replacement")),
        ))
        val result = applyPending()
        assertEquals(1, result.applied)
        assertEquals(1, result.stale)
        assertEquals(original, memories.getMemoryInStore("a", original.id))
        assertEquals(2, memories.getMemoriesOfAssistant("a").size)
        assertTrue(memories.listTrash("a").isEmpty())
        assertEquals(MemoryProposalEntity.DECISION_STALE, db.memoryProposalDao().getBatch("a", "cross").single { it.action == "delete" }.decision)
    }

    @Test fun statusActionsCannotServeAsMergeReplacementDependencies() = runBlocking {
        val task = memories.addMemory("a", "commitment", kind = MemoryKind.TASK)
        val duplicate = memories.addMemory("a", "duplicate", kind = MemoryKind.TASK)
        for (action in listOf("complete", "drop")) {
            assertTrue(runCatching {
                proposals.savePending("a", listOf(
                    operation(action, task.id, task.revision, op = "status"),
                    operation("delete", duplicate.id, duplicate.revision).copy(dependsOn = listOf("status")),
                ))
            }.isFailure)
        }
        assertTrue(proposals.pending("a").isEmpty())
        assertEquals(task, memories.getMemoryInStore("a", task.id))
        assertEquals(duplicate, memories.getMemoryInStore("a", duplicate.id))
    }

    @Test fun failedReceiptWriteRollsBackRealStatusHistoryAndReceiptSql() = runBlocking {
        val task = memories.addMemory("a", "commitment", kind = MemoryKind.TASK, now = 10L)
        proposals.savePending("a", listOf(operation("drop", task.id, task.revision)))
        val before = db.memoryHistoryDao().list("a", task.sortKey)
        val fault = object : MemoryProposalDAO by db.memoryProposalDao() {
            override suspend fun markApplied(id: Long, targetId: String?, revision: Long?, now: Long) {
                db.memoryProposalDao().markApplied(id, targetId, revision, now)
                error("injected receipt failure after real SQL")
            }
        }
        val result = runCatching { proposalRepository(fault).apply("a", proposals.pending("a").map { it.id }.toSet()) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("injected receipt failure"))
        assertEquals(task, memories.getMemoryInStore("a", task.id))
        assertEquals(before, db.memoryHistoryDao().list("a", task.sortKey))
        val row = db.memoryProposalDao().getBatch("a", "batch").single()
        assertEquals(MemoryProposalEntity.DECISION_PENDING, row.decision)
        assertNull(row.appliedTargetId)
        assertNull(row.appliedRevision)
        assertTrue(memories.listTrash("a").isEmpty())
    }

    @Test fun statusWriteEvictedByLaterCreateRollsBackWholeBatchAtCapacity() = runBlocking {
        for (action in listOf("complete", "drop")) {
            // Separate stores let the same isolated Room instance exercise both status actions.
            val store = "capacity-$action"
            repeat(199) { index ->
                db.memoryDao().insertMemory(app.androidrik.rikkahub.data.db.entity.MemoryEntity(
                    assistantId = store, publicId = "$action-profile-$index", content = "profile $index", kind = "profile",
                ))
            }
            val task = memories.addMemory(store, "protected open commitment", kind = MemoryKind.TASK, now = 10L)
            val before = memories.getMemoriesOfAssistant(store)
            val history = db.memoryHistoryDao().list(store, task.sortKey)
            val batch = "quota-$action"
            proposals.savePending(store, listOf(
                operation(action, task.id, task.revision, batch = batch),
                MemoryConsolidationProposal(action = "create", content = "new protected identity", kind = "profile", batchId = batch, operationId = "create"),
            ))
            val failure = runCatching {
                proposals.apply(store, proposals.pending(store).map { it.id }.toSet())
            }.exceptionOrNull()
            assertTrue(failure?.message.orEmpty().contains("capacity conflict"))
            assertEquals(before, memories.getMemoriesOfAssistant(store))
            assertEquals(history, db.memoryHistoryDao().list(store, task.sortKey))
            assertTrue(memories.listTrash(store).isEmpty())
            assertEquals(2, proposals.pending(store).size)
            assertTrue(db.memoryProposalDao().getBatch(store, batch).all {
                it.decision == MemoryProposalEntity.DECISION_PENDING && it.appliedTargetId == null && it.appliedRevision == null
            })
        }
    }

    @Test fun laterCompletionCannotInvalidateReplacementAlreadyConsumedByDelete() = runBlocking {
        val canonical = memories.addMemory("a", "canonical commitment", kind = MemoryKind.TASK, now = 10L)
        val duplicate = memories.addMemory("a", "duplicate commitment", kind = MemoryKind.TASK, now = 11L)
        val before = memories.getMemoriesOfAssistant("a")
        val histories = before.associate { it.sortKey to db.memoryHistoryDao().list("a", it.sortKey) }
        proposals.savePending("a", listOf(
            // Deduplication intentionally resolves create to the existing canonical version.
            MemoryConsolidationProposal(action = "create", content = canonical.content, kind = "task", batchId = "rollback", operationId = "replacement"),
            operation("delete", duplicate.id, duplicate.revision, batch = "rollback").copy(dependsOn = listOf("replacement")),
            operation("complete", canonical.id, canonical.revision, batch = "rollback"),
        ))
        val failure = runCatching { applyPending() }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("dependency conflict"))
        assertEquals(before, memories.getMemoriesOfAssistant("a"))
        histories.forEach { (id, history) -> assertEquals(history, db.memoryHistoryDao().list("a", id)) }
        assertTrue(memories.listTrash("a").isEmpty())
        assertEquals(3, proposals.pending("a").size)
        assertTrue(db.memoryProposalDao().getBatch("a", "rollback").all { it.appliedTargetId == null && it.appliedRevision == null })
    }
}
