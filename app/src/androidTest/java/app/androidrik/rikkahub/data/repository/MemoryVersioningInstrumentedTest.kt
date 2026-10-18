package app.androidrik.rikkahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryHistoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryProposalDAO
import app.androidrik.rikkahub.data.db.dao.MemoryTrashDAO
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryHistoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity
import app.androidrik.rikkahub.data.db.entity.MemoryTrashEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real, isolated Room tests for revision CAS, reversible mutations and explicit proposal dependencies.
 * Delegating fault DAOs execute real SQL before throwing; no fake/snapshot-restoring transaction runner is used.
 * Concurrent CAS uses independent coroutines sharing this Room instance, not multiple connections/processes.
 */
@RunWith(AndroidJUnit4::class)
class MemoryVersioningInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var transactions: RoomMemoryTransactionRunner
    private lateinit var memories: MemoryDAO
    private lateinit var history: MemoryHistoryDAO
    private lateinit var trash: MemoryTrashDAO
    private lateinit var proposals: MemoryProposalDAO
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).build() // No production callbacks, database name, DI or user data.
        transactions = RoomMemoryTransactionRunner(database)
        memories = database.memoryDao()
        history = database.memoryHistoryDao()
        trash = database.memoryTrashDao()
        proposals = database.memoryProposalDao()
        repository = repository()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun concurrentCompareAndSwap_sameExpectedRevisionAllowsExactlyOneWriter() = runBlocking {
        val before = seed()
        val start = CompletableDeferred<Unit>()
        val jobs = (1..2).map { index ->
            async(Dispatchers.IO) {
                start.await()
                try {
                    repository.updateContentInStore(
                        STORE, before.publicId, "writer $index", now = 20L + index,
                        expectedRevision = before.revision, actor = "CHAT",
                    )
                    true
                } catch (_: MemoryRevisionConflictException) {
                    false
                }
            }
        }
        start.complete(Unit)
        val results = withTimeout(10_000) { jobs.awaitAll() }
        assertEquals(1, results.count { it })
        assertEquals(1, results.count { !it })
        val after = requireNotNull(memories.getMemoryById(before.id))
        assertEquals(before.revision + 1, after.revision)
        assertTrue(after.content in setOf("writer 1", "writer 2"))
        assertEquals(before.readCount, after.readCount)
        val events = history.list(STORE, before.id)
        assertEquals(1, events.size)
        assertEquals("UPDATE", events.single().operation)
        assertEquals(before, snapshot(events.single()))
    }

    @Test
    fun readsDoNotAdvanceRevisionWhileAnchorAndDescriptionBackfillDo() = runBlocking {
        val before = seed(description = "")
        repository.readMemoryInStore(STORE, before.publicId, now = 40L)
        repository.readMemoryInStore(STORE, before.publicId, now = 30L)
        val read = requireNotNull(memories.getMemoryById(before.id))
        assertEquals(before.copy(readCount = before.readCount + 2, lastReadAt = 40L,
            chatReadCount = before.chatReadCount + 2, lastChatReadAt = 40L), read)
        assertTrue(history.list(STORE, before.id).isEmpty())

        repository.setAnchor(before.publicId, true, expectedRevision = read.revision)
        val anchored = requireNotNull(memories.getMemoryById(before.id))
        assertEquals(read.revision + 1, anchored.revision)
        assertEquals(read.updatedAt, anchored.updatedAt)
        repository.setAnchor(before.publicId, true, expectedRevision = anchored.revision)
        assertEquals(anchored, memories.getMemoryById(before.id))
        assertEquals(1, history.list(STORE, before.id).size)

        val backfilled = repository.addMemory(STORE, before.content, description = "new topic", now = 50L)
        assertEquals(anchored.revision + 1, backfilled.revision)
        assertEquals("new topic", backfilled.description)
        assertEquals(anchored.readCount, backfilled.readCount)
        assertEquals(anchored.lastReadAt, backfilled.lastReadAt)
        assertTrue(backfilled.isAnchor)
        assertEquals(listOf("BACKFILL", "ANCHOR"), history.list(STORE, before.id).map { it.operation })
    }

    @Test
    fun restoreOldRevision_restoresBusinessStateButKeepsCurrentTelemetryAndMonotonicVersion() = runBlocking {
        val before = seed()
        val edit = repository.updateContentInStore(
            STORE, before.publicId, "new fact", 0.9f, "new topic", now = 20L,
            expectedRevision = before.revision,
        )
        repository.setAnchor(before.publicId, true, expectedRevision = edit.revision)
        repository.readMemoryInStore(STORE, before.publicId, now = 100L)
        val current = requireNotNull(memories.getMemoryById(before.id))

        val restored = repository.restoreRevision(
            STORE, before.publicId, targetRevision = before.revision,
            expectedRevision = current.revision, now = 200L, reason = "undo business edit",
        )

        assertEquals(before.content, restored.content)
        assertEquals(before.importance, restored.importance, 0f)
        assertEquals(before.description, restored.description)
        assertEquals(before.isAnchor, restored.isAnchor)
        assertEquals(current.readCount, restored.readCount)
        assertEquals(current.lastReadAt, restored.lastReadAt)
        assertEquals(current.revision + 1, restored.revision)
        assertEquals(200L, restored.updatedAt)
        assertEquals(before.id, restored.sortKey)
        val undo = history.list(STORE, before.id).first()
        assertEquals("RESTORE_REVISION", undo.operation)
        assertEquals(current, snapshot(undo))
        val rowsBeforeStaleUndo = history.list(STORE, before.id)
        expectConflict {
            repository.restoreRevision(STORE, before.publicId, before.revision, current.revision, now = 201L)
        }
        assertEquals(rowsBeforeStaleUndo, history.list(STORE, before.id))
        assertEquals(restored.revision, memories.getMemoryById(before.id)?.revision)
    }

    @Test
    fun blankLegacyTokensAcrossStoresDoNotConflictDuringRestore() = runBlocking {
        val before = seed(id = 43, publicId = "")
        val sameStore = before.copy(id = 44, content = "another local legacy memory")
        val otherStore = before.copy(id = 45, assistantId = "other-store", content = "other library legacy memory")
        memories.insertMemory(sameStore)
        memories.insertMemory(otherStore)
        assertTrue(repository.deleteMemoryInStore(STORE, before.id.toString(), before.revision))
        val tombstone = requireNotNull(trash.get(STORE, before.id))
        val restored = repository.restoreTrash(STORE, before.id, tombstone.revision)
        assertEquals(before.id.toString(), restored.id)
        assertEquals(before.id, restored.sortKey)
        assertEquals(before.revision + 2, restored.revision)
        assertEquals(sameStore, memories.getMemoryById(44))
        assertEquals(otherStore, memories.getMemoryById(45))
    }

    @Test
    fun trashRestore_preservesBothIdsAndStrictlyAdvancesVersionAcrossRepeatedDeletion() = runBlocking {
        val before = seed(id = 43)
        assertTrue(repository.deleteMemoryInStore(STORE, before.publicId, before.revision, reason = "test delete"))
        val deleted = requireNotNull(trash.get(STORE, before.id))
        assertEquals(before.revision + 1, deleted.revision)
        assertEquals(before, Json.decodeFromString<MemoryEntity>(deleted.snapshot))
        assertNull(memories.getMemoryById(before.id))
        assertEquals(before, repository.listTrash(STORE).single().snapshot)
        assertTrue(repository.listHistory(STORE, before.publicId).isNotEmpty())

        val restored = repository.restoreTrash(STORE, before.id, deleted.revision, now = 30L)
        assertEquals(before.id, restored.sortKey)
        assertEquals(before.publicId, restored.id)
        assertEquals(deleted.revision + 1, restored.revision)
        assertEquals(before.readCount, restored.readCount)
        assertNull(trash.get(STORE, before.id))
        expectConflict {
            repository.updateContentInStore(
                STORE, before.publicId, "ABA overwrite", expectedRevision = before.revision, actor = "CHAT",
            )
        }
        assertEquals(before.content, memories.getMemoryById(before.id)?.content)

        repository.deleteMemoryInStore(STORE, before.publicId, restored.revision)
        val secondDelete = requireNotNull(trash.get(STORE, before.id))
        assertTrue(secondDelete.revision > restored.revision)
        val secondRestore = repository.restoreTrash(STORE, before.id, secondDelete.revision, now = 40L)
        assertEquals(5L, secondRestore.revision)
        assertEquals(before.id, secondRestore.sortKey)
        assertEquals(before.publicId, secondRestore.id)
    }

    @Test
    fun emptyPublicTokenRestoresAsOriginalNumericReferenceAndStoreIsEnforced() = runBlocking {
        val before = seed(id = 43, publicId = "", store = MemoryRepository.GLOBAL_MEMORY_ID)
        val global = MemoryRepository.GLOBAL_MEMORY_ID
        assertTrue(repository.deleteMemoryInStore(global, "43", before.revision))
        val deleted = requireNotNull(trash.get(global, 43))
        assertTrue(repository.listTrash(STORE).isEmpty())
        assertTrue(repository.listHistory(STORE, "43").isEmpty())
        expectIllegalArgument { repository.restoreTrash(STORE, 43, deleted.revision) }
        assertEquals(deleted, trash.get(global, 43))

        val restored = repository.restoreTrash(global, 43, deleted.revision, now = 50L)
        assertEquals("43", restored.id)
        assertEquals(43, restored.sortKey)
        assertEquals("", memories.getMemoryById(43)?.publicId)
        assertEquals(3L, restored.revision)
    }

    @Test
    fun quotaEvictionIsRecoverableAndFullLibraryRestoreRefusesWithoutEvictingAnotherRow() = runBlocking {
        val victim = seed(id = 43, importance = 0.1f)
        memories.insertMemories(List(MemoryRepository.MAX_MEMORY_COUNT - 1) { index ->
            MemoryEntity(
                assistantId = STORE, publicId = "b" + index.toString(16).padStart(7, '0'),
                content = "high value $index", importance = 1f, updatedAt = NOW,
            )
        })
        val newcomer = repository.addMemory(STORE, "new stronger fact", importance = 0.9f, now = NOW)
        assertNotNull(memories.getMemoryByPublicId(newcomer.id))
        assertNull(memories.getMemoryById(victim.id))
        val tombstone = requireNotNull(trash.get(STORE, victim.id))
        assertEquals(victim, Json.decodeFromString<MemoryEntity>(tombstone.snapshot))
        assertEquals("EVICT", history.list(STORE, victim.id).single().operation)
        val fullLibrary = memories.getMemoriesOfAssistant(STORE)
        val oldTrash = trash.list(STORE)
        expectCapacity { repository.restoreTrash(STORE, victim.id, tombstone.revision, now = NOW + 1) }
        assertEquals(fullLibrary, memories.getMemoriesOfAssistant(STORE))
        assertEquals(oldTrash, trash.list(STORE))

        val freeSlot = fullLibrary.first()
        repository.deleteMemoryInStore(STORE, freeSlot.publicId, freeSlot.revision)
        val restored = repository.restoreTrash(STORE, victim.id, tombstone.revision, now = NOW + 2)
        assertEquals(victim.id, restored.sortKey)
        assertEquals(victim.publicId, restored.id)
        assertEquals(tombstone.revision + 1, restored.revision)
        assertEquals(MemoryRepository.MAX_MEMORY_COUNT, memories.getMemoriesOfAssistant(STORE).size)
        assertNull(trash.get(STORE, victim.id))
        assertNotNull(trash.get(STORE, freeSlot.id))
    }

    @Test
    fun historyInsertFailure_rollsBackRealContentRevisionAndAuditWrite() = runBlocking {
        val before = seed()
        val failure = InjectedFailure()
        var wroteHistory = false
        val failingHistory = object : MemoryHistoryDAO by history {
            override suspend fun insert(event: MemoryHistoryEntity): Long {
                val id = history.insert(event)
                wroteHistory = true
                assertEquals("changed", memories.getMemoryById(before.id)?.content)
                assertEquals(before.revision + 1, memories.getMemoryById(before.id)?.revision)
                assertEquals(1, history.list(STORE, before.id).size)
                throw failure
            }
        }
        expectFailure(failure) {
            repository(historyDAO = failingHistory).updateContentInStore(
                STORE, before.publicId, "changed", expectedRevision = before.revision,
            )
        }
        assertTrue(wroteHistory)
        assertEquals(before, memories.getMemoryById(before.id))
        assertTrue(history.list(STORE, before.id).isEmpty())
        assertTrue(trash.list(STORE).isEmpty())
    }

    @Test
    fun trashInsertFailure_rollsBackRealTombstoneAndPreservesLiveRecord() = runBlocking {
        val before = seed()
        val failure = InjectedFailure()
        var wroteTrash = false
        val failingTrash = object : MemoryTrashDAO by trash {
            override suspend fun insert(tombstone: MemoryTrashEntity) {
                trash.insert(tombstone)
                wroteTrash = true
                assertEquals(tombstone, trash.get(STORE, before.id))
                throw failure
            }
        }
        expectFailure(failure) {
            repository(trashDAO = failingTrash).deleteMemoryInStore(STORE, before.publicId, before.revision)
        }
        assertTrue(wroteTrash)
        assertEquals(before, memories.getMemoryById(before.id))
        assertTrue(trash.list(STORE).isEmpty())
        assertTrue(history.list(STORE, before.id).isEmpty())
    }

    @Test
    fun decisionFailure_rollsBackMutationsHistoryTrashAndAllProposalReceipts() = runBlocking {
        val retained = seed()
        val retired = seed(publicId = "a0000002", content = "retired fact")
        val fixture = merge(retained, retired, "merge-write") + MemoryConsolidationProposal(
            action = "create", content = "new fact", batchId = BATCH, operationId = "create-write",
        )
        val store = proposalRepository()
        store.savePending(STORE, fixture)
        val pending = proposals.getPending(STORE)
        val failure = InjectedFailure()
        var receipts = 0
        val failing = object : MemoryProposalDAO by proposals {
            override suspend fun markApplied(id: Long, targetId: String?, revision: Long?, now: Long) {
                proposals.markApplied(id, targetId, revision, now)
                receipts++
                if (receipts == pending.size) {
                    assertNull(memories.getMemoryById(retired.id))
                    assertEquals("merged merge-write", memories.getMemoryById(retained.id)?.content)
                    assertNotNull(trash.get(STORE, retired.id))
                    assertTrue(history.list(STORE, retained.id).isNotEmpty())
                    assertTrue(history.list(STORE, retired.id).isNotEmpty())
                    assertTrue(proposals.getPending(STORE).isEmpty())
                    throw failure
                }
            }
        }
        expectFailure(failure) { proposalRepository(failing).apply(STORE, pending.map { it.id }.toSet()) }
        assertEquals(pending.size, receipts)
        assertEquals(listOf(retained, retired), memories.getMemoriesOfAssistant(STORE))
        assertEquals(pending, proposals.getPending(STORE))
        assertTrue(trash.list(STORE).isEmpty())
        assertTrue(history.list(STORE, retained.id).isEmpty())
        assertTrue(history.list(STORE, retired.id).isEmpty())
        assertEquals(0, historyCount())
    }

    @Test
    fun restoreTrashHistoryFailure_rollsBackRestoredRowAndTombstoneConsumption() = runBlocking {
        val before = seed()
        repository.deleteMemoryInStore(STORE, before.publicId, before.revision)
        val deleted = requireNotNull(trash.get(STORE, before.id))
        val oldHistory = history.list(STORE, before.id)
        val failure = InjectedFailure()
        var reachedRestore = false
        val failing = object : MemoryHistoryDAO by history {
            override suspend fun insert(event: MemoryHistoryEntity): Long {
                val id = history.insert(event)
                if (event.operation == "RESTORE_TRASH") {
                    reachedRestore = true
                    assertNotNull(memories.getMemoryById(before.id))
                    assertNull(trash.get(STORE, before.id))
                    throw failure
                }
                return id
            }
        }
        expectFailure(failure) {
            repository(historyDAO = failing).restoreTrash(STORE, before.id, deleted.revision, now = 50L)
        }
        assertTrue(reachedRestore)
        assertNull(memories.getMemoryById(before.id))
        assertEquals(deleted, trash.get(STORE, before.id))
        assertEquals(oldHistory, history.list(STORE, before.id))
    }

    @Test
    fun restoreRevisionHistoryFailure_rollsBackUndoButKeepsEarlierEditAndReadCredit() = runBlocking {
        val before = seed()
        repository.updateContentInStore(STORE, before.publicId, "newer", expectedRevision = before.revision)
        repository.readMemoryInStore(STORE, before.publicId, now = NOW)
        val current = requireNotNull(memories.getMemoryById(before.id))
        val oldHistory = history.list(STORE, before.id)
        val failure = InjectedFailure()
        var reachedUndo = false
        val failing = object : MemoryHistoryDAO by history {
            override suspend fun insert(event: MemoryHistoryEntity): Long {
                val id = history.insert(event)
                if (event.operation == "RESTORE_REVISION") {
                    reachedUndo = true
                    assertEquals(before.content, memories.getMemoryById(before.id)?.content)
                    throw failure
                }
                return id
            }
        }
        expectFailure(failure) {
            repository(historyDAO = failing).restoreRevision(
                STORE, before.publicId, before.revision, current.revision, now = NOW + 1,
            )
        }
        assertTrue(reachedUndo)
        assertEquals(current, memories.getMemoryById(before.id))
        assertEquals(oldHistory, history.list(STORE, before.id))
    }

    @Test
    fun independentMergesInSameRun_doNotBlockOneAnotherOrInheritUnrelatedRejection() = runBlocking {
        val keepA = seed(publicId = "a0000001", content = "keep A")
        val dropA = seed(publicId = "a0000002", content = "drop A")
        val keepB = seed(publicId = "b0000001", content = "keep B")
        val dropB = seed(publicId = "b0000002", content = "drop B")
        val store = proposalRepository()
        store.savePending(STORE, merge(keepA, dropA, "merge-a") + merge(keepB, dropB, "merge-b"))
        val rows = proposals.getPending(STORE)
        val rejectedB = rows.single { it.operationId == "merge-b" }
        assertEquals(1, store.reject(STORE, setOf(rejectedB.id)))

        val selectedA = rows.filter { it.operationId == "merge-a" || it.dependsOn == "[\"merge-a\"]" }
        assertEquals(2, selectedA.size)
        assertEquals(MemoryProposalApplyResult(applied = 2), store.apply(STORE, selectedA.map { it.id }.toSet()))
        assertNull(memories.getMemoryById(dropA.id))
        assertEquals("merged merge-a", memories.getMemoryById(keepA.id)?.content)
        assertEquals(keepB, memories.getMemoryById(keepB.id))
        assertEquals(dropB, memories.getMemoryById(dropB.id))
        val remaining = proposals.getPending(STORE).single()
        assertEquals("delete", remaining.action)
        assertEquals(
            MemoryProposalApplyResult(blocked = 1, blockedByDecision = 1),
            store.apply(STORE, setOf(remaining.id)),
        )
        assertEquals(dropB, memories.getMemoryById(dropB.id))
    }

    @Test
    fun twoIndependentMergesSelectedTogether_areTopologicallyAppliedEvenWhenDeletesWereListedFirst() = runBlocking {
        val keepA = seed(publicId = "a0000001", content = "keep A")
        val dropA = seed(publicId = "a0000002", content = "drop A")
        val keepB = seed(publicId = "b0000001", content = "keep B")
        val dropB = seed(publicId = "b0000002", content = "drop B")
        val a = merge(keepA, dropA, "merge-a")
        val b = merge(keepB, dropB, "merge-b")
        val store = proposalRepository()
        store.savePending(STORE, listOf(a[1], b[1], b[0], a[0]))
        val rows = proposals.getPending(STORE)
        assertEquals(MemoryProposalApplyResult(applied = 4), store.apply(STORE, rows.map { it.id }.toSet()))
        assertEquals(listOf(keepA.id, keepB.id), memories.getMemoriesOfAssistant(STORE).map { it.id })
        assertEquals(setOf(dropA.id, dropB.id), trash.list(STORE).map { it.memoryId }.toSet())
        val receipts = proposals.getBatch(STORE, BATCH)
        assertTrue(receipts.all { it.decision == MemoryProposalEntity.DECISION_APPLIED })
        assertEquals(4, receipts.size)
        receipts.filter { it.action == "edit" }.forEach { receipt ->
            val current = requireNotNull(repository.getMemoryInStore(STORE, requireNotNull(receipt.appliedTargetId)))
            assertEquals(current.revision, receipt.appliedRevision)
        }
    }

    @Test
    fun appliedDependencyChangedAfterApproval_blocksDeleteInsteadOfTrustingOldAppliedFlag() = runBlocking {
        val keep = seed()
        val drop = seed(publicId = "a0000002", content = "source must survive")
        val store = proposalRepository()
        store.savePending(STORE, merge(keep, drop, "merge-a"))
        val rows = proposals.getPending(STORE)
        val edit = rows.single { it.action == "edit" }
        val delete = rows.single { it.action == "delete" }
        assertEquals(MemoryProposalApplyResult(applied = 1), store.apply(STORE, setOf(edit.id)))
        val receipt = proposals.getBatch(STORE, BATCH).single { it.id == edit.id }
        repository.updateContentInStore(
            STORE, keep.publicId, "user replaced the merged result", expectedRevision = receipt.appliedRevision,
        )
        val beforeDelete = proposals.getPending(STORE)

        val result = store.apply(STORE, setOf(delete.id))

        assertEquals(MemoryProposalApplyResult(stale = 1), result)
        assertEquals(drop, memories.getMemoryById(drop.id))
        assertEquals(listOf(delete.id), beforeDelete.map { it.id })
        assertTrue(proposals.getPending(STORE).isEmpty())
        assertEquals("stale", proposals.getBatch(STORE, BATCH).single { it.id == delete.id }.decision)
        assertNull(trash.get(STORE, drop.id))
    }

    @Test
    fun expectedRevisionConflictAfterProposalWasSaved_marksEditStaleWithoutOverwritingUserChange() = runBlocking {
        val before = seed()
        val store = proposalRepository()
        store.savePending(STORE, listOf(MemoryConsolidationProposal(
            action = "edit", targetId = before.publicId, content = "obsolete proposal", batchId = BATCH,
            operationId = "old-edit", expectedRevision = before.revision,
        )))
        repository.updateContentInStore(STORE, before.publicId, "new user fact", expectedRevision = before.revision)
        val current = memories.getMemoryById(before.id)
        val oldHistory = history.list(STORE, before.id)
        val row = proposals.getPending(STORE).single()

        assertEquals(MemoryProposalApplyResult(stale = 1), store.apply(STORE, setOf(row.id)))

        assertEquals(current, memories.getMemoryById(before.id))
        assertEquals(oldHistory, history.list(STORE, before.id))
        assertEquals("stale", proposals.getBatch(STORE, BATCH).single().decision)
        assertTrue(trash.list(STORE).isEmpty())
    }

    @Test
    fun legacyPendingCreateEditAndDelete_failClosedRatherThanInventingCurrentRevisions() = runBlocking {
        val before = seed()
        proposals.insertAll(listOf("create", "edit", "delete").map { action ->
            MemoryProposalEntity(
                memoryId = STORE, batchId = BATCH, action = action,
                targetId = if (action == "create") null else before.publicId,
                content = "legacy $action", importance = null, description = null, createdAt = 10L,
            ) // Entity defaults intentionally model an old/unknown contract, including the create.
        })
        val rows = proposals.getPending(STORE)
        assertTrue(rows.all { it.expectedRevision == null && it.contractVersion == 0 })

        assertEquals(MemoryProposalApplyResult(stale = 3),
            proposalRepository().apply(STORE, rows.map { it.id }.toSet()))

        assertEquals(listOf(before), memories.getMemoriesOfAssistant(STORE))
        assertEquals(0, historyCount())
        assertTrue(trash.list(STORE).isEmpty())
        val decisions = proposals.getBatch(STORE, BATCH)
        assertTrue(decisions.all { it.decision == "stale" && it.expectedRevision == null })
        assertTrue(decisions.all { it.appliedTargetId == null && it.appliedRevision == null })
    }

    @Test
    fun sameCanonicalCreateOutputCannotAuthorizeDeletingItsOnlyExistingContent() = runBlocking {
        val before = seed(id = 43)
        val store = proposalRepository()
        store.savePending(STORE, listOf(
            MemoryConsolidationProposal(
                action = "create", content = before.content, batchId = BATCH, operationId = "duplicate-create",
            ),
            MemoryConsolidationProposal(
                action = "delete", targetId = before.id.toString(), batchId = BATCH,
                expectedRevision = before.revision, dependsOn = listOf("duplicate-create"),
            ),
        ))
        val rows = proposals.getPending(STORE)

        assertEquals(MemoryProposalApplyResult(applied = 1, stale = 1),
            store.apply(STORE, rows.map { it.id }.toSet()))

        assertEquals(before, memories.getMemoryById(before.id))
        assertTrue(trash.list(STORE).isEmpty())
        assertEquals(0, historyCount())
        val receipt = proposals.getBatch(STORE, BATCH).single { it.action == "create" }
        assertEquals(before.publicId, receipt.appliedTargetId)
        assertEquals(before.revision, receipt.appliedRevision)
        assertEquals("stale", proposals.getBatch(STORE, BATCH).single { it.action == "delete" }.decision)
    }

    @Test
    fun laterExplicitDeleteOfConsumedReplacement_rollsBackWholeApplyIncludingEarlierSourceDelete() = runBlocking {
        val replacement = seed(publicId = "a0000001", content = "replacement before merge")
        val source = seed(publicId = "a0000002", content = "must survive failed plan")
        val store = proposalRepository()
        val firstBatch = "earlier-merge"
        val secondBatch = "later-replacement-delete"
        store.savePending(STORE, listOf(
            MemoryConsolidationProposal(
                action = "edit", targetId = replacement.publicId, content = "merged source and replacement",
                batchId = firstBatch, expectedRevision = replacement.revision, operationId = "first-edit",
            ),
            MemoryConsolidationProposal(
                action = "delete", targetId = source.publicId, batchId = firstBatch,
                expectedRevision = source.revision, operationId = "source-delete", dependsOn = listOf("first-edit"),
            ),
            MemoryConsolidationProposal(
                action = "create", content = "unrelated next replacement", batchId = secondBatch,
                operationId = "second-create",
            ),
            MemoryConsolidationProposal(
                action = "delete", targetId = replacement.publicId, batchId = secondBatch,
                expectedRevision = replacement.revision + 1, operationId = "replacement-delete",
                dependsOn = listOf("second-create"),
            ),
        ))
        val firstEdit = proposals.getPending(STORE).single { it.operationId == "first-edit" }
        assertEquals(MemoryProposalApplyResult(applied = 1), store.apply(STORE, setOf(firstEdit.id)))
        val beforeApply = memories.getMemoriesOfAssistant(STORE)
        val oldHistory = history.list(STORE, replacement.id)
        val oldPending = proposals.getPending(STORE)
        var sawSourceDelete = false
        var sawReplacementDelete = false
        val observing = object : MemoryProposalDAO by proposals {
            override suspend fun markApplied(id: Long, targetId: String?, revision: Long?, now: Long) {
                proposals.markApplied(id, targetId, revision, now)
                if (targetId == source.publicId) {
                    sawSourceDelete = true
                    assertNull(memories.getMemoryById(source.id))
                }
                if (targetId == replacement.publicId) {
                    sawReplacementDelete = true
                    assertNull(memories.getMemoryById(replacement.id))
                    assertNotNull(trash.get(STORE, source.id))
                    assertNotNull(trash.get(STORE, replacement.id))
                }
            }
        }
        try {
            proposalRepository(observing).apply(STORE, oldPending.map { it.id }.toSet())
            fail("Required replacement consumed by an earlier delete must still exist at commit")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("Memory dependency conflict"))
        }
        assertTrue(sawSourceDelete)
        assertTrue(sawReplacementDelete)
        assertEquals(beforeApply, memories.getMemoriesOfAssistant(STORE))
        assertEquals(oldPending, proposals.getPending(STORE))
        assertEquals(oldHistory, history.list(STORE, replacement.id))
        assertTrue(history.list(STORE, source.id).isEmpty())
        assertTrue(trash.list(STORE).isEmpty())
        assertEquals(1, historyCount())
    }

    private fun repository(
        memoryDAO: MemoryDAO = memories,
        historyDAO: MemoryHistoryDAO = history,
        trashDAO: MemoryTrashDAO = trash,
    ) = MemoryRepository(memoryDAO, transactions, historyDAO, trashDAO)

    private fun proposalRepository(dao: MemoryProposalDAO = proposals) = MemoryProposalRepository(
        proposalDAO = dao, memoryRepository = repository, transactions = transactions, now = { NOW },
    )

    private fun merge(keep: MemoryEntity, drop: MemoryEntity, operation: String) = listOf(
        MemoryConsolidationProposal(
            action = "edit", targetId = keep.publicId, content = "merged $operation", batchId = BATCH,
            operationId = operation, expectedRevision = keep.revision,
        ),
        MemoryConsolidationProposal(
            action = "delete", targetId = drop.publicId, batchId = BATCH,
            operationId = "$operation-delete", expectedRevision = drop.revision, dependsOn = listOf(operation),
        ),
    )

    private suspend fun seed(
        id: Int = 0,
        publicId: String = "a0000001",
        content: String = "original fact",
        description: String = "original topic",
        importance: Float = 0.6f,
        store: String = STORE,
    ): MemoryEntity {
        val row = MemoryEntity(
            id = id, assistantId = store, publicId = publicId, content = content,
            importance = importance, description = description, updatedAt = 10L,
            readCount = 3, lastReadAt = 9L,
        )
        return row.copy(id = memories.insertMemory(row).toInt())
    }

    private fun snapshot(event: MemoryHistoryEntity): MemoryEntity = Json.decodeFromString(event.snapshot)

    private fun historyCount(): Int = database.openHelper.writableDatabase.query(
        "SELECT COUNT(*) FROM memory_history",
    ).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private suspend fun expectConflict(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected revision conflict")
        } catch (_: MemoryRevisionConflictException) {
            // A real CAS/check rejection, not a fake transaction rollback.
        }
    }

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected a missing/foreign-store record to be rejected")
        } catch (_: IllegalArgumentException) {
            // Store lookup fails before any mutation.
        }
    }

    private suspend fun expectCapacity(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected capacity rejection")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("capacity"))
        }
    }

    private suspend fun expectFailure(expected: InjectedFailure, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected injected failure after actual SQL writes")
        } catch (actual: InjectedFailure) {
            assertTrue(actual === expected)
        }
    }

    private class InjectedFailure : RuntimeException("injected after real versioned write")

    private companion object {
        const val STORE = "versioning-room-test"
        const val BATCH = "one-run-two-independent-merges"
        const val NOW = 1_000_000L
    }
}
