package app.androidrik.rikkahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.ai.memoryScore
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.model.MemoryReadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real SQL tests. No production app/DI/database callbacks or user records. */
@RunWith(AndroidJUnit4::class)
class MemoryReadTelemetryInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: MemoryRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java,
        ).build()
        repository = MemoryRepository(
            database.memoryDao(), RoomMemoryTransactionRunner(database),
            database.memoryHistoryDao(), database.memoryTrashDao(),
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun chatReadAddsKnownSourceButNeverReclassifiesLegacyCredit() = runBlocking {
        val before = seed()
        repository.readMemoryInStore(STORE, before.publicId, now = 100, source = MemoryReadSource.CHAT)
        repository.readMemoryInStore(STORE, before.publicId, now = 50, source = MemoryReadSource.CHAT)
        val after = requireNotNull(database.memoryDao().getMemoryById(before.id))
        assertEquals(before.copy(readCount = 9, lastReadAt = 100, chatReadCount = 2, lastChatReadAt = 100), after)
        val model = requireNotNull(repository.getMemoryInStore(STORE, before.publicId))
        assertEquals(7, model.legacyReadCount)
        assertEquals(2, model.chatReadCount)
        assertTrue(repository.listHistory(STORE, before.publicId).isEmpty())
    }

    @Test
    fun consolidationReadsHaveNoCreditRecencyOrBusinessSideEffect() = runBlocking {
        val before = seed()
        val baseline = requireNotNull(repository.getMemoryInStore(STORE, before.publicId))
        val score = memoryScore(baseline, now = 10_000)
        repository.readMemoryInStore(STORE, before.publicId, now = 5_000, source = MemoryReadSource.CONSOLIDATION)
        repository.readMemoryInStore(STORE, before.publicId, now = 4_000, source = MemoryReadSource.CONSOLIDATION)
        val after = requireNotNull(database.memoryDao().getMemoryById(before.id))
        assertEquals(before.copy(consolidationReadCount = 2, lastConsolidationReadAt = 5_000), after)
        val model = requireNotNull(repository.getMemoryInStore(STORE, before.publicId))
        assertEquals(score, memoryScore(model, now = 10_000), 0f)
        assertEquals(7, model.legacyReadCount)
        assertTrue(repository.listHistory(STORE, before.publicId).isEmpty())
        assertTrue(repository.listTrash(STORE).isEmpty())
    }

    @Test
    fun mixedConcurrentSourcesKeepExactScopedCountersAndMonotonicTimes() = runBlocking {
        val before = seed()
        (1..20).flatMap { index ->
            listOf(
                async(Dispatchers.IO) {
                    repository.readMemoryInStore(STORE, before.publicId, 100L + index, MemoryReadSource.CHAT)
                },
                async(Dispatchers.IO) {
                    repository.readMemoryInStore(STORE, before.publicId, 200L + index, MemoryReadSource.CONSOLIDATION)
                },
            )
        }.awaitAll()
        assertEquals(before.copy(
            readCount = 27, lastReadAt = 120, chatReadCount = 20, lastChatReadAt = 120,
            consolidationReadCount = 20, lastConsolidationReadAt = 220,
        ), database.memoryDao().getMemoryById(before.id))
        assertNull(repository.readMemoryInStore("other", before.publicId, source = MemoryReadSource.CHAT))
        assertNull(repository.readMemoryInStore("other", before.id.toString(), source = MemoryReadSource.CONSOLIDATION))
        assertEquals(20, database.memoryDao().getMemoryById(before.id)?.consolidationReadCount)
    }

    @Test
    fun searchAndOrdinaryReadsAreNotUsageEvents() = runBlocking {
        val before = seed()
        assertNotNull(repository.getMemoryInStore(STORE, before.publicId))
        assertTrue(repository.searchMemories(STORE, "source").isNotEmpty())
        assertEquals(before, database.memoryDao().getMemoryById(before.id))
    }

    @Test
    fun historyRestorePreservesCurrentSourcesAndTrashRestorePreservesDeletedSources() = runBlocking {
        val before = seed()
        val edited = repository.updateContentInStore(STORE, before.publicId, "edited", expectedRevision = 1)
        repository.readMemoryInStore(STORE, before.publicId, 100, MemoryReadSource.CHAT)
        repository.readMemoryInStore(STORE, before.publicId, 200, MemoryReadSource.CONSOLIDATION)
        val restored = repository.restoreRevision(STORE, before.publicId, 1, expectedRevision = edited.revision)
        assertEquals(before.content, restored.content)
        assertEquals(8, restored.readCount)
        assertEquals(1, restored.chatReadCount)
        assertEquals(1, restored.consolidationReadCount)
        assertEquals(100L, restored.lastChatReadAt)
        assertEquals(200L, restored.lastConsolidationReadAt)
        assertTrue(repository.deleteMemoryInStore(STORE, before.publicId, expectedRevision = restored.revision))
        val tombstone = repository.listTrash(STORE).single().tombstone
        val live = repository.restoreTrash(STORE, before.id, tombstone.revision)
        assertEquals(restored.chatReadCount, live.chatReadCount)
        assertEquals(restored.consolidationReadCount, live.consolidationReadCount)
        assertEquals(restored.lastChatReadAt, live.lastChatReadAt)
        assertEquals(restored.lastConsolidationReadAt, live.lastConsolidationReadAt)
        assertEquals(restored.revision + 2, live.revision)
    }

    @Test
    fun copyingIntoAnotherStoreDoesNotCloneLegacyOrSourceUsage() = runBlocking {
        val before = seed()
        repository.readMemoryInStore(STORE, before.publicId, 100, MemoryReadSource.CHAT)
        repository.readMemoryInStore(STORE, before.publicId, 200, MemoryReadSource.CONSOLIDATION)
        val source = requireNotNull(database.memoryDao().getMemoryById(before.id))
        assertEquals(1, repository.copyMemories(STORE, "private-copy", now = 300))
        val copy = database.memoryDao().getMemoriesOfAssistant("private-copy").single()
        assertTrue(copy.id != source.id && copy.publicId != source.publicId)
        assertEquals(source.content, copy.content)
        assertEquals(source.isAnchor, copy.isAnchor)
        assertEquals(1L, copy.revision)
        assertEquals(300L, copy.updatedAt)
        assertEquals(0, copy.readCount)
        assertEquals(0L, copy.lastReadAt)
        assertEquals(0, copy.chatReadCount)
        assertEquals(0L, copy.lastChatReadAt)
        assertEquals(0, copy.consolidationReadCount)
        assertEquals(0L, copy.lastConsolidationReadAt)
        assertEquals(source, database.memoryDao().getMemoryById(before.id))
    }

    private suspend fun seed(): MemoryEntity {
        val memory = MemoryEntity(
            id = 43, assistantId = STORE, publicId = "a1000043", content = "source fact",
            importance = 0.4f, updatedAt = 10, readCount = 7, lastReadAt = 19, isAnchor = true,
        )
        database.memoryDao().insertMemory(memory)
        return memory
    }

    companion object {
        private const val STORE = "__global__"
    }
}
