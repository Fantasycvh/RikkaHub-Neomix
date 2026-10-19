package app.androidrik.rikkahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.model.MemoryReadSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic in-memory data only. No production Application, FTS callbacks, files or model requests. */
@RunWith(AndroidJUnit4::class)
class MemoryRetrievalInstrumentedTest {
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
    fun searchNeverReturnsOtherStoresOrTrashedRecords() = runBlocking {
        val live = repository.addMemory(STORE, "espresso current preference", now = 100)
        val deleted = repository.addMemory(STORE, "espresso retired preference", now = 100)
        repository.addMemory("another-store", "espresso private preference", now = 100)
        assertTrue(repository.deleteMemoryInStore(STORE, deleted.id, expectedRevision = deleted.revision))
        val before = database.memoryDao().getMemoriesOfAssistant(STORE)
        val trash = repository.listTrash(STORE)
        val history = repository.listHistory(STORE, live.id)

        assertEquals(listOf(live.id), repository.searchMemories(STORE, "espresso").map { it.id })
        assertEquals(before, database.memoryDao().getMemoriesOfAssistant(STORE))
        assertEquals(trash, repository.listTrash(STORE))
        assertEquals(history, repository.listHistory(STORE, live.id))
        assertEquals(listOf(deleted.id), trash.map { it.snapshot.publicId })
    }

    @Test
    fun correctedFactDoesNotLeakHistoricalTextOrRevisionThroughSearch() = runBlocking {
        val original = repository.addMemory(STORE, "obsoleteespresso preference", description = "coffee", now = 100)
        val corrected = repository.updateContentInStore(
            STORE, original.id, "currentespresso preference", expectedRevision = original.revision, now = 200,
        )
        assertTrue(repository.listHistory(STORE, original.id).any { it.snapshot.content == original.content })
        val before = database.memoryDao().getMemoriesOfAssistant(STORE)

        assertTrue(repository.searchMemories(STORE, "obsoleteespresso").isEmpty())
        val found = repository.searchMemories(STORE, "coffee").single()
        assertEquals(original.id, found.id)
        assertEquals(corrected.revision, found.revision)
        assertEquals("currentespresso preference", found.content)
        assertEquals(before, database.memoryDao().getMemoriesOfAssistant(STORE))
    }

    @Test
    fun unicodeAndMixedQueriesPreserveIdentityAndExistingSourceTelemetry() = runBlocking {
        val diagram = repository.addMemory(STORE, "Café project uses TikZ", now = 100)
        val cat = repository.addMemory(STORE, "用户养了一只猫", now = 100)
        repository.readMemoryInStore(STORE, diagram.id, 200, MemoryReadSource.CHAT)
        repository.readMemoryInStore(STORE, diagram.id, 300, MemoryReadSource.CONSOLIDATION)
        val before = database.memoryDao().getMemoriesOfAssistant(STORE)

        assertEquals(listOf(diagram.id), repository.searchMemories(STORE, "ＴｉｋＺ").map { it.id })
        assertEquals(listOf(diagram.id), repository.searchMemories(STORE, "Cafe\u0301").map { it.id })
        assertEquals(setOf(diagram.id, cat.id), repository.searchMemories(STORE, "猫 TikZ").map { it.id }.toSet())
        assertEquals(before, database.memoryDao().getMemoriesOfAssistant(STORE))
    }

    @Test
    fun searchCoverageOutranksInsertionOrderAndLimitsRemainReadOnly() = runBlocking {
        val partial = repository.addMemory(STORE, "alpha", now = 100)
        val complete = repository.addMemory(STORE, "alpha beta", now = 100)
        val before = database.memoryDao().getMemoriesOfAssistant(STORE)

        assertEquals(listOf(complete.id, partial.id), repository.searchMemories(STORE, "alpha beta").map { it.id })
        assertEquals(listOf(partial.id, complete.id), repository.searchMemories(STORE, "alpha").map { it.id })
        assertEquals(listOf(complete.id), repository.searchMemories(STORE, "alpha beta", 1).map { it.id })
        assertTrue(repository.searchMemories(STORE, "alpha", 0).isEmpty())
        assertTrue(repository.searchMemories(STORE, "alpha", -1).isEmpty())
        assertEquals(before, database.memoryDao().getMemoriesOfAssistant(STORE))
    }

    companion object {
        private const val STORE = "__global__"
    }
}
