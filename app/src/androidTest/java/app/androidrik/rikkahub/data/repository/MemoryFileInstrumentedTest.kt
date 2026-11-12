package app.androidrik.rikkahub.data.repository

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.ai.tools.buildFileMemoryTool
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.MemoryMode
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class MemoryFileInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val migrations = MigrationTestHelper(
        instrumentation, AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun upgradeKeepsEntriesAndBothLibrariesSurviveReopen() = runBlocking {
        val name = "memory-modes-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 66).use { db ->
                db.execSQL("INSERT INTO MemoryEntity(id, assistant_id, content, public_id) VALUES (3, 'private', 'old entry', '00000003')")
            }
            Room.databaseBuilder(context, AppDatabase::class.java, name).build().withClose { db ->
                val files = MemoryFileRepository(db)
                assertEquals("old entry", db.memoryDao().getMemoriesOfAssistant("private").single().content)
                assertTrue(files.list("private").isEmpty())
                files.write("private", "/profile.md", null) { "private file" }
                files.write(MemoryRepository.GLOBAL_MEMORY_ID, "/profile.md", null) { "shared file" }
                assertNull(files.read("other", "/profile.md"))
            }
            Room.databaseBuilder(context, AppDatabase::class.java, name).build().withClose { db ->
                val files = MemoryFileRepository(db)
                assertEquals("old entry", db.memoryDao().getMemoriesOfAssistant("private").single().content)
                assertEquals("private file", files.read("private", "/profile.md")?.content)
                assertEquals("shared file", files.read(MemoryRepository.GLOBAL_MEMORY_ID, "/profile.md")?.content)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test
    fun toolApprovalAndRevisionProtectTheTarget() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().withClose { db ->
            val files = MemoryFileRepository(db)
            val assistant = Assistant(memoryMode = MemoryMode.FILES, memoryCreateApproval = false, memoryModifyApproval = true)
            val tool = buildFileMemoryTool(files, "private", assistant)
            val create = JsonInstant.parseToJsonElement("""{"action":"create","path":"profile.md","content":"one"}""")
            assertFalse(tool.needsApproval(create))
            tool.execute(create)
            val original = checkNotNull(files.read("private", "/profile.md"))
            val append = JsonInstant.parseToJsonElement(
                """{"action":"append","path":"/profile.md","revision":"${original.revision}","content":" two"}""",
            )
            assertTrue(tool.needsApproval(append))
            tool.execute(append)
            assertEquals("one two", files.read("private", "/profile.md")?.content)
            assertTrue(runCatching { tool.execute(append) }.isFailure)
            assertTrue(runCatching { tool.execute(create) }.isFailure)
            files.delete("private", "/profile.md", checkNotNull(files.read("private", "/profile.md")).revision)
            tool.execute(create)
            assertNotEquals(original.revision, files.read("private", "/profile.md")?.revision)
            assertTrue(runCatching { files.read("private", "/../profile.md") }.isFailure)
        }
    }

    @Test
    fun organizerReadsTheWholeRevisionBeforeStagingAnEdit() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().withClose { db ->
            val files = MemoryFileRepository(db)
            val source = files.write("private", "/topics/notes.md", null) { "a".repeat(4_010) }
            val proposals = mutableListOf<MemoryConsolidationProposal>()
            val tool = buildFileMemoryTool(files, "private", Assistant()) { proposals += it }
            val edit = JsonInstant.parseToJsonElement(
                """{"action":"edit","path":"/topics/notes.md","revision":"${source.revision}","content":"revised"}""",
            )
            assertTrue(runCatching { tool.execute(edit) }.isFailure)
            tool.execute(JsonInstant.parseToJsonElement("""{"action":"read","path":"/topics/notes.md"}"""))
            assertTrue(runCatching { tool.execute(edit) }.isFailure)
            tool.execute(JsonInstant.parseToJsonElement("""{"action":"read","path":"/topics/notes.md","offset":4000}"""))
            tool.execute(edit)
            assertEquals(source.revision, proposals.single().expectedFileRevision)
            assertEquals("revised", proposals.single().content)
            assertEquals(source, files.read("private", source.path))
        }
    }

    @Test
    fun moveProposalIsScopedVersionedAndPreservesContent() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().withClose { db ->
            val files = MemoryFileRepository(db)
            val transactions = RoomMemoryTransactionRunner(db)
            val entries = MemoryRepository(db.memoryDao(), transactions, db.memoryHistoryDao(), db.memoryTrashDao())
            val proposals = MemoryProposalRepository(db.memoryProposalDao(), entries, transactions, memoryFiles = files)
            val initial = files.write("private", "/topics/release.md", null) { "release decisions" }
            val change = MemoryConsolidationProposal(
                action = "move", targetId = initial.path, expectedFileRevision = initial.revision,
                fileDestination = "/areas/release.md",
            )
            proposals.savePending("files:private", listOf(change))
            val first = proposals.pending("files:private").single()
            assertTrue(proposals.apply("private", setOf(first.id)).isEmpty)
            files.write("private", initial.path, initial.revision) { "updated release decisions" }
            assertEquals(1, proposals.apply("files:private", setOf(first.id)).stale)
            assertNull(files.read("private", "/areas/release.md"))
            val current = checkNotNull(files.read("private", initial.path))
            proposals.savePending("files:private", listOf(change.copy(expectedFileRevision = current.revision)))
            val second = proposals.pending("files:private").single()
            assertEquals(1, proposals.apply("files:private", setOf(second.id)).applied)
            assertNull(files.read("private", initial.path))
            assertEquals(current.content, files.read("private", "/areas/release.md")?.content)
        }
    }

    @Test
    fun upgradeFrom67RetainsFileRevisions() = runBlocking {
        val name = "file-memory-progress-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 67).use { db ->
                db.execSQL(
                    "INSERT INTO assistant_memory_file(store_id, path, content, revision, updated_at) " +
                        "VALUES ('private', '/profile.md', 'original', 'stable-revision', 1)",
                )
            }
            Room.databaseBuilder(context, AppDatabase::class.java, name).build().withClose { db ->
                val file = MemoryFileRepository(db).read("private", "/profile.md")
                assertEquals("original", file?.content)
                assertEquals("stable-revision", file?.revision)
                assertNull(db.memoryFileProgressDao().get("unknown", "private"))
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    private inline fun <T> AppDatabase.withClose(block: (AppDatabase) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }
}
