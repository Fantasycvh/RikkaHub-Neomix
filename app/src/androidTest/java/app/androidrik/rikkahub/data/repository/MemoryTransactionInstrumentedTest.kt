package app.androidrik.rikkahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryProposalDAO
import app.androidrik.rikkahub.data.db.dao.MemoryReviewCursorDAO
import app.androidrik.rikkahub.data.db.entity.ConversationEntity
import app.androidrik.rikkahub.data.db.entity.MemoryConsolidationEntity
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity
import app.androidrik.rikkahub.data.db.entity.MemoryReviewCursorEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/**
 * Real SQLite rollback tests, not snapshot-restoring fake transactions.
 * Every DAO delegates to this test's Room instance; fault injection happens after real SQL writes.
 * No production database name, DI, FTS callback or existing device data is used.
 * Field-write tests inject stale snapshots deterministically; they do not claim multi-connection concurrency coverage.
 */
@RunWith(AndroidJUnit4::class)
class MemoryTransactionInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var transactions: RoomMemoryTransactionRunner
    private lateinit var memories: MemoryDAO
    private lateinit var proposals: MemoryProposalDAO
    private lateinit var memoryRepository: MemoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build() // Deliberately do not install production FTS callbacks.
        transactions = RoomMemoryTransactionRunner(database)
        memories = database.memoryDao()
        proposals = database.memoryProposalDao()
        memoryRepository = memoryRepository(memories)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun concurrentAdd_sameContentCreatesOnlyOneRecord() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val jobs = (1..12).map {
            async(Dispatchers.IO) {
                start.await()
                memoryRepository.addMemory(STORE, "one shared fact", importance = 0.8f)
            }
        }
        start.complete(Unit)
        val results = withTimeout(10_000) { jobs.awaitAll() }
        assertEquals(1, results.map { it.id }.distinct().size)
        assertEquals(1, memories.getMemoriesOfAssistant(STORE).size)
    }

    @Test
    fun concurrentReadEditAndAnchor_preserveIndependentFields() = runBlocking {
        val before = seedMemory()
        val start = CompletableDeferred<Unit>()
        val jobs = (1..12).flatMap { n ->
            listOf(
                async(Dispatchers.IO) {
                    start.await()
                    memoryRepository.readMemoryInStore(STORE, before.publicId, now = 100L + n)
                },
                async(Dispatchers.IO) {
                    start.await()
                    memoryRepository.updateContentInStore(STORE, before.publicId, "edited $n", now = 200L + n)
                },
                async(Dispatchers.IO) {
                    start.await()
                    memoryRepository.setAnchor(before.publicId, true)
                },
            )
        }
        start.complete(Unit)
        withTimeout(10_000) { jobs.awaitAll() }
        val after = requireNotNull(memories.getMemoryById(before.id))
        assertEquals(before.readCount + 12, after.readCount)
        assertEquals(112L, after.lastReadAt)
        assertTrue(after.content.startsWith("edited "))
        assertTrue(after.isAnchor)
        assertEquals(before.description, after.description)
    }

    @Test
    fun cancellationOfActiveRoomTransaction_rollsBackItsWrites() = runBlocking {
        val before = seedMemory()
        val entered = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            transactions.run {
                memories.updateContentFields(before.id, STORE, "cancelled edit", null, null, 30L)
                memories.insertMemory(MemoryEntity(assistantId = STORE, publicId = "c0000001"))
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(10_000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(listOf(before), memories.getMemoriesOfAssistant(STORE))
    }

    @Test
    fun outerFailure_rollsBackSuccessfulNestedTransaction() = runBlocking {
        val before = seedMemory()
        val failure = InjectedFailure()

        expectFailure(failure) {
            transactions.run {
                memories.recordReadInStore(before.id, STORE, 20L)
                transactions.run {
                    memories.updateContentFields(before.id, STORE, "nested edit", null, null, 30L)
                    memories.insertMemory(MemoryEntity(assistantId = STORE, publicId = "b0000001"))
                }
                assertEquals("nested edit", memories.getMemoryById(before.id)?.content)
                assertEquals(2, memories.getMemoriesOfAssistant(STORE).size)
                throw failure
            }
        }

        assertEquals(listOf(before), memories.getMemoriesOfAssistant(STORE))
    }

    @Test
    fun nestedFailure_rollsBackBothInnerAndOuterWrites() = runBlocking {
        val before = seedMemory()
        val failure = InjectedFailure()

        expectFailure(failure) {
            transactions.run {
                memories.updateAnchor(before.id, STORE, true)
                transactions.run {
                    memories.recordReadInStore(before.id, STORE, 20L)
                    throw failure
                }
            }
        }

        assertEquals(before, memories.getMemoryById(before.id))
    }

    @Test
    fun apply_decisionFailureRollsBackEditDeleteCreateAndEarlierDecisions() = runBlocking {
        val edited = seedMemory()
        val deleted = seedMemory(publicId = "a0000002", content = "retire this")
        val repository = proposalRepository()
        repository.savePending(
            STORE,
            listOf(
                MemoryConsolidationProposal(
                    "edit", edited.publicId, "rewritten", batchId = "apply-batch",
                    expectedRevision = edited.revision, operationId = "apply-edit",
                ),
                MemoryConsolidationProposal(
                    "delete", deleted.publicId, batchId = "apply-batch", expectedRevision = deleted.revision,
                    dependsOn = listOf("apply-edit"),
                ),
                MemoryConsolidationProposal("create", content = "new fact", batchId = "apply-batch"),
            ),
        )
        val oldPending = proposals.getPending(STORE)
        val failure = InjectedFailure()
        var decisionsWritten = 0
        val failingDAO = object : MemoryProposalDAO by proposals {
            override suspend fun markApplied(id: Long, targetId: String?, revision: Long?, now: Long) {
                proposals.markApplied(id, targetId, revision, now)
                decisionsWritten++
                if (decisionsWritten == oldPending.size) {
                    // Establish that all three memory mutations and decisions really executed.
                    assertEquals("rewritten", memories.getMemoryById(edited.id)?.content)
                    assertNull(memories.getMemoryById(deleted.id))
                    assertTrue(memories.getMemoriesOfAssistant(STORE).any { it.content == "new fact" })
                    assertTrue(proposals.getPending(STORE).isEmpty())
                    throw failure
                }
            }
        }

        expectFailure(failure) {
            proposalRepository(failingDAO).apply(STORE, oldPending.map { it.id }.toSet())
        }

        assertEquals(3, decisionsWritten)
        assertEquals(listOf(edited, deleted), memories.getMemoriesOfAssistant(STORE))
        assertEquals(oldPending, proposals.getPending(STORE))
    }

    @Test
    fun savePending_insertFailureRestoresDeletedPendingAndRemovesNewRows() = runBlocking {
        proposalRepository().savePending(STORE, listOf(createProposal("old pending")))
        val before = proposals.getPending(STORE)
        val failure = InjectedFailure()
        var inserted = false
        val failingDAO = object : MemoryProposalDAO by proposals {
            override suspend fun insertAll(rows: List<MemoryProposalEntity>) {
                assertTrue("deletePending must already have run", proposals.getPending(STORE).isEmpty())
                proposals.insertAll(rows)
                inserted = true
                assertEquals(listOf("new pending"), proposals.getPending(STORE).map { it.content })
                throw failure
            }
        }

        expectFailure(failure) {
            proposalRepository(failingDAO).savePending(STORE, listOf(createProposal("new pending")))
        }

        assertTrue(inserted)
        assertEquals(before, proposals.getPending(STORE))
    }

    @Test
    fun outerCommit_cursorFailureRollsBackProposalsWatermarkAndCursor() = runBlocking {
        exerciseOuterCommit(failAfterCursorWrite = true)
    }

    @Test
    fun outerCommit_successPersistsProposalsWatermarkAndCursor() = runBlocking {
        exerciseOuterCommit(failAfterCursorWrite = false)
    }

    @Test
    fun contentEdit_fromStaleLookupPreservesLaterReadCountAndAnchor() = runBlocking {
        val before = seedMemory()
        var injected = false
        val interleavedDAO = object : MemoryDAO by memories {
            override suspend fun getMemoryByPublicId(publicId: String): MemoryEntity? {
                val snapshot = memories.getMemoryByPublicId(publicId)
                if (!injected && snapshot != null) {
                    injected = true
                    memories.recordReadInStore(snapshot.id, STORE, 40L)
                    memories.updateAnchor(snapshot.id, STORE, true)
                }
                return snapshot
            }
        }

        expectRevisionConflict {
            memoryRepository(interleavedDAO).updateContentInStore(
                STORE, before.publicId, "edited content", now = 50L,
            )
        }
        assertTrue(injected)
        assertEquals("A business-state interleave must roll back the whole transaction", before,
            memories.getMemoryById(before.id))
        memoryRepository.readMemoryInStore(STORE, before.publicId, now = 40L)
        memoryRepository.setAnchor(before.publicId, true)
        val updated = memoryRepository.updateContentInStore(STORE, before.publicId, "edited content", now = 50L)
        val expected = before.copy(
            content = "edited content", updatedAt = 50L, readCount = before.readCount + 1,
            lastReadAt = 40L, isAnchor = true, revision = before.revision + 2,
            chatReadCount = before.chatReadCount + 1, lastChatReadAt = 40L,
        )
        assertEquals(expected, memories.getMemoryById(before.id))
        assertEquals(expected.readCount, updated.readCount)
        assertTrue(updated.isAnchor)
    }

    @Test
    fun anchorWrite_fromStaleLookupPreservesLaterContentAndReadTelemetry() = runBlocking {
        val before = seedMemory()
        var injected = false
        val interleavedDAO = object : MemoryDAO by memories {
            override suspend fun getMemoryByPublicId(publicId: String): MemoryEntity? {
                val snapshot = memories.getMemoryByPublicId(publicId)
                if (!injected && snapshot != null) {
                    injected = true
                    memories.updateContentFields(snapshot.id, STORE, "later content", 0.9f, "later topic", 50L)
                    memories.recordReadInStore(snapshot.id, STORE, 60L)
                }
                return snapshot
            }
        }

        expectRevisionConflict { memoryRepository(interleavedDAO).setAnchor(before.publicId, true) }
        assertTrue(injected)
        assertEquals(before, memories.getMemoryById(before.id))
        memoryRepository.updateContentInStore(STORE, before.publicId, "later content", 0.9f, "later topic", 50L)
        memoryRepository.readMemoryInStore(STORE, before.publicId, now = 60L)
        memoryRepository.setAnchor(before.publicId, true)
        assertEquals(
            before.copy(
                content = "later content", importance = 0.9f, description = "later topic", updatedAt = 50L,
                readCount = before.readCount + 1, lastReadAt = 60L, isAnchor = true,
                chatReadCount = before.chatReadCount + 1, lastChatReadAt = 60L,
                revision = before.revision + 2,
            ),
            memories.getMemoryById(before.id),
        )
    }

    @Test
    fun duplicateDescriptionBackfill_preservesReadTelemetryAnchorAndNewerDescription() = runBlocking {
        val before = seedMemory(description = "")
        var injected = false
        val interleavedDAO = object : MemoryDAO by memories {
            override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> {
                val snapshot = memories.getMemoriesOfAssistant(assistantId)
                if (!injected) {
                    injected = true
                    memories.recordReadInStore(before.id, STORE, 70L)
                    memories.updateAnchor(before.id, STORE, true)
                    memories.updateContentFields(before.id, STORE, before.content, null, "newer topic", 80L)
                }
                return snapshot
            }
        }

        expectRevisionConflict {
            memoryRepository(interleavedDAO).addMemory(
                STORE, before.content, description = "stale backfill", now = 90L,
            )
        }
        assertTrue(injected)
        assertEquals(before, memories.getMemoryById(before.id))
        memoryRepository.readMemoryInStore(STORE, before.publicId, now = 70L)
        memoryRepository.setAnchor(before.publicId, true)
        memoryRepository.updateContentInStore(STORE, before.publicId, before.content, description = "newer topic",
            now = 80L)
        val duplicate = memoryRepository.addMemory(STORE, before.content, description = "stale backfill", now = 90L)
        val expected = before.copy(
            description = "newer topic", updatedAt = 80L, readCount = before.readCount + 1,
            lastReadAt = 70L, isAnchor = true, revision = before.revision + 2,
            chatReadCount = before.chatReadCount + 1, lastChatReadAt = 70L,
        )
        assertEquals(listOf(expected), memories.getMemoriesOfAssistant(STORE))
        assertEquals("newer topic", duplicate.description)
    }

    @Test
    fun scopedFieldWrites_cannotModifyAnotherStore() = runBlocking {
        val before = seedMemory()
        assertEquals(0, memories.updateContentFields(before.id, OTHER_STORE, "wrong", 1f, "wrong", 90L))
        assertEquals(0, memories.updateImportanceFields(before.id, OTHER_STORE, 1f, 90L))
        assertEquals(0, memories.updateAnchor(before.id, OTHER_STORE, true))
        assertEquals(0, memories.backfillDescription(before.id, OTHER_STORE, "wrong"))
        assertEquals(0, memories.recordReadInStore(before.id, OTHER_STORE, 90L))
        assertEquals(0, memories.deleteMemoryInStore(before.id, OTHER_STORE))
        assertEquals(before, memories.getMemoryById(before.id))
    }

    @Test
    fun addMemory_lowScoreAtCapacityThrowsAndLeavesOriginalLibraryUnchanged() = runBlocking {
        val before = seedHighScoreLibrary(MemoryRepository.MAX_MEMORY_COUNT)

        expectCapacityFailure {
            memoryRepository.addMemory(STORE, "low score addition", importance = 0f, now = CAPACITY_NOW)
        }

        assertEquals(MemoryRepository.MAX_MEMORY_COUNT, before.size)
        assertEquals(before, memories.getMemoriesOfAssistant(STORE))
    }

    @Test
    fun apply_createReplacementAtCapacityCannotDeleteOriginalOrConsumePending() = runBlocking {
        val before = seedHighScoreLibrary(MemoryRepository.MAX_MEMORY_COUNT)
        val original = before.first()
        val repository = proposalRepository()
        repository.savePending(
            STORE,
            listOf(
                MemoryConsolidationProposal(
                    action = "create", content = "replacement too weak to survive", importance = 0f,
                    batchId = "capacity-replacement", operationId = "capacity-create",
                ),
                MemoryConsolidationProposal(
                    action = "delete", targetId = original.publicId, batchId = "capacity-replacement",
                    expectedRevision = original.revision, dependsOn = listOf("capacity-create"),
                ),
            ),
        )
        val oldPending = proposals.getPending(STORE)

        expectCapacityFailure {
            repository.apply(STORE, oldPending.map { it.id }.toSet())
        }

        assertEquals(before, memories.getMemoriesOfAssistant(STORE))
        assertEquals(oldPending, proposals.getPending(STORE))
    }

    @Test
    fun apply_laterCreateEvictsEarlierCreate_rollsBackLibraryAndAllPendingDecisions() = runBlocking {
        // The first create fits. The second fits only by evicting the first, not an original high-score row.
        val before = seedHighScoreLibrary(MemoryRepository.MAX_MEMORY_COUNT - 1)
        var earlierCreateEvicted = false
        val observingDAO = object : MemoryDAO by memories {
            override suspend fun deleteMemoryInStore(id: Int, assistantId: String, expectedRevision: Long?): Int {
                val victim = memories.getMemoryById(id)
                val affected = memories.deleteMemoryInStore(id, assistantId, expectedRevision)
                if (victim?.content == "earlier create") {
                    earlierCreateEvicted = true
                    assertNull(memories.getMemoryById(id))
                }
                return affected
            }
        }
        val repository = MemoryProposalRepository(
            proposalDAO = proposals,
            memoryRepository = memoryRepository(observingDAO),
            transactions = transactions,
            now = { CAPACITY_NOW },
        )
        repository.savePending(
            STORE,
            listOf(
                MemoryConsolidationProposal(
                    action = "create", content = "earlier create", importance = 0.1f,
                    batchId = "capacity-two-creates",
                ),
                MemoryConsolidationProposal(
                    action = "create", content = "later create", importance = 0.9f,
                    batchId = "capacity-two-creates",
                ),
            ),
        )
        val oldPending = proposals.getPending(STORE)

        expectCapacityFailure {
            repository.apply(STORE, oldPending.map { it.id }.toSet())
        }

        assertTrue("Real SQL must have evicted the earlier successfully created row", earlierCreateEvicted)
        assertEquals(before, memories.getMemoriesOfAssistant(STORE))
        assertEquals(oldPending, proposals.getPending(STORE))
    }

    @Test
    fun apply_deleteAfterRejectedSiblingEdit_staysBlockedAndPreservesOriginal() = runBlocking {
        val before = seedMemory()
        val repository = proposalRepository()
        repository.savePending(
            STORE,
            listOf(
                MemoryConsolidationProposal(
                    action = "edit", targetId = before.publicId, content = "rejected rewrite",
                    batchId = "rejected-edit-delete", expectedRevision = before.revision,
                    operationId = "rejected-edit",
                ),
                MemoryConsolidationProposal(
                    action = "delete", targetId = before.publicId, batchId = "rejected-edit-delete",
                    expectedRevision = before.revision, dependsOn = listOf("rejected-edit"),
                ),
            ),
        )
        val batch = proposals.getPending(STORE)
        val edit = batch.single { it.action == "edit" }
        val delete = batch.single { it.action == "delete" }
        assertEquals(1, repository.reject(STORE, setOf(edit.id)))
        val beforeApply = proposals.getPending(STORE)

        val result = repository.apply(STORE, setOf(delete.id))

        assertEquals(MemoryProposalApplyResult(blocked = 1, blockedByDecision = 1), result)
        assertEquals(before, memories.getMemoryById(before.id))
        assertEquals(beforeApply, proposals.getPending(STORE))
        assertEquals(listOf(edit.id), proposals.getRecentlyRejected(STORE, 10).map { it.id })
    }

    @Test
    fun apply_deleteAfterStaleSiblingEdit_staysBlockedAndPreservesOriginal() = runBlocking {
        val before = seedMemory()
        val repository = proposalRepository()
        repository.savePending(
            STORE,
            listOf(
                MemoryConsolidationProposal(
                    action = "edit", targetId = "ffffffff", content = "missing rewrite target",
                    batchId = "stale-edit-delete", expectedRevision = 1L, operationId = "stale-edit",
                ),
                MemoryConsolidationProposal(
                    action = "delete", targetId = before.publicId, batchId = "stale-edit-delete",
                    expectedRevision = before.revision, dependsOn = listOf("stale-edit"),
                ),
            ),
        )
        val batch = proposals.getPending(STORE)
        val edit = batch.single { it.action == "edit" }
        val delete = batch.single { it.action == "delete" }
        assertEquals(MemoryProposalApplyResult(stale = 1), repository.apply(STORE, setOf(edit.id)))
        val beforeApply = proposals.getPending(STORE)

        assertEquals(
            MemoryProposalApplyResult(blocked = 1, blockedByDecision = 1),
            repository.apply(STORE, setOf(delete.id)),
        )

        assertEquals(before, memories.getMemoryById(before.id))
        assertEquals(beforeApply, proposals.getPending(STORE))
    }

    @Test
    fun apply_deleteAfterAppliedSiblingEdit_isAllowedWithoutRepeatingTheEdit() = runBlocking {
        val retained = seedMemory()
        val retired = seedMemory(publicId = "a0000002", content = "retired duplicate")
        val repository = proposalRepository()
        repository.savePending(
            STORE,
            listOf(
                MemoryConsolidationProposal(
                    action = "edit", targetId = retained.publicId, content = "merged content",
                    batchId = "applied-edit-delete", expectedRevision = retained.revision,
                    operationId = "applied-edit",
                ),
                MemoryConsolidationProposal(
                    action = "delete", targetId = retired.publicId, batchId = "applied-edit-delete",
                    expectedRevision = retired.revision, dependsOn = listOf("applied-edit"),
                ),
            ),
        )
        val batch = proposals.getPending(STORE)
        val edit = batch.single { it.action == "edit" }
        val delete = batch.single { it.action == "delete" }
        assertEquals(MemoryProposalApplyResult(applied = 1), repository.apply(STORE, setOf(edit.id)))
        assertEquals("merged content", memories.getMemoryById(retained.id)?.content)

        assertEquals(MemoryProposalApplyResult(applied = 1), repository.apply(STORE, setOf(delete.id)))

        assertNull(memories.getMemoryById(retired.id))
        assertEquals("merged content", memories.getMemoryById(retained.id)?.content)
        assertTrue(proposals.getPending(STORE).isEmpty())
    }

    @Test
    fun pruneDecided_preservesPendingBatchEvidenceUntilLastPendingSiblingIsDecided() = runBlocking {
        val before = seedMemory()
        val repository = proposalRepository()
        val batchId = "protected-rejected-edit-delete"
        repository.savePending(
            STORE,
            listOf(
                MemoryConsolidationProposal(
                    action = "edit", targetId = before.publicId, content = "rejected rewrite", batchId = batchId,
                    expectedRevision = before.revision, operationId = "protected-edit",
                ),
                MemoryConsolidationProposal(
                    action = "delete", targetId = before.publicId, batchId = batchId,
                    expectedRevision = before.revision, dependsOn = listOf("protected-edit"),
                ),
            ),
        )
        val batch = proposals.getPending(STORE)
        val edit = batch.single { it.action == "edit" }
        val delete = batch.single { it.action == "delete" }
        assertEquals(1, repository.reject(STORE, setOf(edit.id)))
        val keep = MemoryProposalRepository.DECIDED_AUDIT_KEEP
        proposals.insertAll(
            List(keep + 1) { index ->
                MemoryProposalEntity(
                    memoryId = STORE, batchId = "ordinary-audit-$index", action = "create", targetId = null,
                    content = "ordinary decided audit $index", importance = null, description = null,
                    createdAt = 1_000L + index, decision = MemoryProposalEntity.DECISION_REJECTED,
                    decidedAt = 1_000L + index,
                )
            },
        )

        proposals.pruneDecided(STORE, keep)

        val retainedAudit = proposals.getRecentlyRejected(STORE, keep + 10)
        assertEquals("Protected evidence must not consume the ordinary audit budget", keep + 1, retainedAudit.size)
        assertTrue(retainedAudit.any { it.id == edit.id })
        assertEquals(keep, retainedAudit.count { it.batchId != batchId })
        assertEquals(
            MemoryProposalApplyResult(blocked = 1, blockedByDecision = 1),
            repository.apply(STORE, setOf(delete.id)),
        )
        assertEquals(before, memories.getMemoryById(before.id))
        assertEquals(listOf(delete), proposals.getPending(STORE))
        assertEquals(2, proposals.getBatch(STORE, batchId).size)

        // Once the last pending sibling is rejected, neither old row needs protection.
        // reject() itself prunes, so both old decisions now lose to the 100 newer ordinary audit rows.
        assertEquals(1, repository.reject(STORE, setOf(delete.id)))

        assertTrue(proposals.getPending(STORE).isEmpty())
        assertTrue(proposals.getBatch(STORE, batchId).isEmpty())
        val finalAudit = proposals.getRecentlyRejected(STORE, keep + 10)
        assertEquals(keep, finalAudit.size)
        assertTrue(finalAudit.all { it.batchId != batchId })
        assertEquals(before, memories.getMemoryById(before.id))
    }

    @Test
    fun recordReadInStore_outOfOrderTimesCountBothReadsWithoutRegressingLastRead() = runBlocking {
        val before = MemoryEntity(
            assistantId = STORE, publicId = "d0000001", content = "read ordering",
            updatedAt = 5L, isAnchor = true,
        )
        val id = memories.insertMemory(before).toInt()

        assertEquals(1, memories.recordReadInStore(id, STORE, 20L))
        assertEquals(1, memories.recordReadInStore(id, STORE, 10L))

        assertEquals(before.copy(id = id, readCount = 2, lastReadAt = 20L,
            chatReadCount = 2, lastChatReadAt = 20L), memories.getMemoryById(id))
    }

    private suspend fun seedHighScoreLibrary(count: Int): List<MemoryEntity> {
        memories.insertMemories(
            List(count) { index ->
                MemoryEntity(
                    assistantId = STORE, publicId = "c" + index.toString(16).padStart(7, '0'),
                    content = "high-score original $index", importance = 1f, updatedAt = CAPACITY_NOW,
                    description = "original topic $index", readCount = index, lastReadAt = CAPACITY_NOW - 1,
                    isAnchor = index % 2 == 0,
                )
            },
        )
        return memories.getMemoriesOfAssistant(STORE)
    }

    private suspend fun expectCapacityFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected a capacity failure, never a successful result for an evicted write")
        } catch (failure: IllegalStateException) {
            assertTrue("Expected a capacity failure, got: $failure", failure.message.orEmpty().contains("capacity"))
        }
    }

    private suspend fun exerciseOuterCommit(failAfterCursorWrite: Boolean) {
        val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000001")
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId.toString(), assistantId = STORE, title = "isolated test",
                nodes = "[]", createAt = 1L, updateAt = 1L, chatSuggestions = "[]", isPinned = false,
            ),
        )
        val progressDAO = database.memoryConsolidationDao()
        val cursorDAO = database.memoryReviewCursorDao()
        val oldProgress = MemoryConsolidationEntity(conversationId.toString(), 10L)
        val oldCursor = MemoryReviewCursorEntity(STORE, 2L, 10L)
        progressDAO.upsertProgress(oldProgress)
        cursorDAO.upsert(oldCursor)
        val repository = proposalRepository()
        repository.savePending(STORE, listOf(createProposal("old pending")))
        val oldPending = proposals.getPending(STORE)
        val failure = InjectedFailure()
        var cursorWritten = false
        val injectedCursorDAO = object : MemoryReviewCursorDAO by cursorDAO {
            override suspend fun upsert(entry: MemoryReviewCursorEntity) {
                cursorDAO.upsert(entry)
                cursorWritten = true
                assertEquals(20L, progressDAO.getConsolidatedAt(conversationId.toString()))
                assertEquals(listOf("new pending"), proposals.getPending(STORE).map { it.content })
                assertEquals(9L, cursorDAO.getCursor(STORE))
                if (failAfterCursorWrite) throw failure
            }
        }
        val consolidation = MemoryConsolidationRepository(
            consolidationDAO = progressDAO,
            assistants = { emptyList() },
            conversationLoader = { null },
            reviewCursorDAO = injectedCursorDAO,
            now = { 20L },
        )
        val commit: suspend () -> Unit = {
            transactions.run {
                repository.savePending(STORE, listOf(createProposal("new pending")))
                consolidation.markConsolidated(conversationId, 20L)
                consolidation.setReviewCursor(STORE, 9L)
            }
        }

        if (failAfterCursorWrite) {
            expectFailure(failure, commit)
            assertEquals(oldPending, proposals.getPending(STORE))
            assertEquals(oldProgress, progressDAO.getProgress(conversationId.toString()))
            assertEquals(oldCursor.cursor, cursorDAO.getCursor(STORE))
        } else {
            commit()
            assertEquals(listOf("new pending"), proposals.getPending(STORE).map { it.content })
            assertEquals(20L, progressDAO.getConsolidatedAt(conversationId.toString()))
            assertEquals(9L, cursorDAO.getCursor(STORE))
            assertFalse(proposals.getPending(STORE).any { it.id in oldPending.map { old -> old.id } })
        }
        assertTrue(cursorWritten)
    }

    private fun memoryRepository(dao: MemoryDAO) = MemoryRepository(
        dao, transactions, database.memoryHistoryDao(), database.memoryTrashDao(),
    )

    private fun proposalRepository(dao: MemoryProposalDAO = proposals) = MemoryProposalRepository(
        proposalDAO = dao,
        memoryRepository = memoryRepository,
        transactions = transactions,
        now = { 100L },
    )

    private fun createProposal(content: String) = MemoryConsolidationProposal(
        action = "create", content = content, batchId = "test-batch",
    )

    private suspend fun seedMemory(
        publicId: String = "a0000001",
        content: String = "original content",
        description: String = "original topic",
    ): MemoryEntity {
        val row = MemoryEntity(
            assistantId = STORE, publicId = publicId, content = content, description = description,
            importance = 0.6f, updatedAt = 10L, readCount = 3, lastReadAt = 9L,
        )
        return row.copy(id = memories.insertMemory(row).toInt())
    }

    private suspend fun expectRevisionConflict(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected real SQL revision CAS conflict")
        } catch (_: MemoryRevisionConflictException) {
            // The Room transaction, not a fake runner, rolls back the injected business-state interleave.
        }
    }

    private suspend fun expectFailure(expected: InjectedFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected injected failure after real SQL writes")
        } catch (actual: InjectedFailure) {
            assertTrue("The injected failure must propagate unchanged", actual === expected)
        }
    }

    private class InjectedFailure : RuntimeException("injected after real Room write")

    private companion object {
        const val STORE = "room-test-store"
        const val OTHER_STORE = "room-other-store"
        const val CAPACITY_NOW = 1_000_000L
    }
}
