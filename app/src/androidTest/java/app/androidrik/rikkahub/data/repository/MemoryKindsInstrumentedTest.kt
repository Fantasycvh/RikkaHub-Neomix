package app.androidrik.rikkahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Isolated real Room transactions, never the production database. */
@RunWith(AndroidJUnit4::class)
class MemoryKindsInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: MemoryRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        repo = MemoryRepository(db.memoryDao(), RoomMemoryTransactionRunner(db), db.memoryHistoryDao(), db.memoryTrashDao())
    }

    @After fun tearDown() { db.close() }

    @Test fun completionAndReopenPreserveRecencyAndEnforceCas() = runBlocking {
        val created = repo.addMemory("a", "user commitment", now = 10L, kind = MemoryKind.TASK, dueAt = 100L)
        val done = repo.setTaskStatusInStore("a", created.id, MemoryTaskStatus.DONE, created.revision, now = 20L)
        assertEquals(10L, done.updatedAt)
        assertEquals(created.revision + 1, done.revision)
        assertEquals(done, repo.setTaskStatusInStore("a", done.id, MemoryTaskStatus.DONE, done.revision))
        assertTrue(runCatching { repo.setTaskStatusInStore("a", done.id, MemoryTaskStatus.DONE, created.revision) }.exceptionOrNull() is MemoryRevisionConflictException)
        val reopened = repo.setTaskStatusInStore("a", done.id, MemoryTaskStatus.OPEN, done.revision, now = 30L)
        assertEquals(10L, reopened.updatedAt)
        assertEquals(MemoryTaskStatus.OPEN, reopened.taskStatus)
        assertEquals(2, db.memoryHistoryDao().list("a", 1).count { it.operation == "TASK_STATUS" })
    }

    @Test fun closedTasksEvictedBeforeEvenLowScoreTopicsWhileProtectedSurvive() = runBlocking {
        val closed = repo.addMemory("a", "completed", importance = 1f, kind = MemoryKind.TASK)
        repo.setTaskStatusInStore("a", closed.id, MemoryTaskStatus.DONE, closed.revision, now = 20L)
        val profile = repo.addMemory("a", "identity", kind = MemoryKind.PROFILE)
        val task = repo.addMemory("a", "open commitment", kind = MemoryKind.TASK)
        val anchor = repo.createMemoryInStore("a", "anchor", isAnchor = true)
        repeat(196) { index -> repo.addMemory("a", "topic $index", importance = 0f) }
        val newest = repo.addMemory("a", "new identity", kind = MemoryKind.PROFILE)
        assertNull(repo.getMemoryInStore("a", closed.id))
        listOf(profile, task, anchor, newest).forEach { assertNotNull(repo.getMemoryInStore("a", it.id)) }
        assertEquals(200, repo.getMemoriesOfAssistant("a").size)
        assertEquals(1, repo.listTrash("a").size)
    }

    @Test fun allProtectedCapacityFailureRollsBackCreateAndAudit() = runBlocking {
        repeat(200) { index -> db.memoryDao().insertMemory(MemoryEntity(assistantId = "a", publicId = "p$index", content = "profile $index", kind = "profile")) }
        val failure = runCatching { repo.addMemory("a", "one too many", kind = MemoryKind.TASK) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.startsWith("Memory capacity exceeded"))
        assertEquals(200, repo.getMemoriesOfAssistant("a").size)
        assertTrue(repo.listTrash("a").isEmpty())
        assertTrue(db.memoryHistoryDao().list("a", 201).isEmpty())
    }

    @Test fun newLowScoreTopicCannotDisappearSilently() = runBlocking {
        repeat(200) { index -> db.memoryDao().insertMemory(MemoryEntity(assistantId = "a", publicId = "p$index", content = "profile $index", kind = "profile")) }
        assertTrue(runCatching { repo.addMemory("a", "new topic", importance = 0f) }.isFailure)
        assertEquals(200, repo.getMemoriesOfAssistant("a").size)
        assertTrue(repo.listTrash("a").isEmpty())
        assertTrue(db.memoryHistoryDao().list("a", 201).isEmpty())
    }
}
