package app.androidrik.rikkahub.data.repository

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.ai.tools.buildFileMemoryTool
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.migrations.Migration_68_69
import app.androidrik.rikkahub.data.db.migrations.Migration_69_70
import app.androidrik.rikkahub.data.db.migrations.Migration_70_71
import app.androidrik.rikkahub.data.db.migrations.Migration_71_72
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.MemoryMode
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
            openDatabase(name).withClose { db ->
                val files = MemoryFileRepository(db)
                assertEquals("old entry", db.memoryDao().getMemoriesOfAssistant("private").single().content)
                assertTrue(files.list("private").isEmpty())
                files.write("private", "/profile.md", null) { "private file" }
                files.write(MemoryRepository.GLOBAL_MEMORY_ID, "/profile.md", null) { "shared file" }
                assertNull(files.read("other", "/profile.md"))
            }
            openDatabase(name).withClose { db ->
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
            openDatabase(name).withClose { db ->
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

    @Test
    fun anchorIdentitySurvivesEditsAndMovesAndIsClearedByUnpinning() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().withClose { db ->
            val files = MemoryFileRepository(db)
            val created = files.writeFile("private", "/profile.md", null, pin = true) { "profile" }
            val anchor = checkNotNull(created.anchorId)
            val edited = files.write("private", created.path, created.revision) { "edited profile" }
            val moved = files.move("private", edited.path, "/areas/profile.md", edited.revision)
            assertEquals(anchor, moved.anchorId)
            assertEquals(anchor, files.read("private", moved.path)?.anchorId)
            assertTrue(runCatching { files.move("private", moved.path, moved.path, edited.revision, pin = false) }.isFailure)
            val unpinned = files.move("private", moved.path, moved.path, moved.revision, pin = false)
            assertNull(unpinned.anchorId)
            assertNull(files.read("private", moved.path)?.anchorId)
        }
    }

    @Test
    fun upgradeFrom72KeepsExistingFilesUnpinned() = runBlocking {
        val name = "file-anchor-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 72).use { old ->
                old.execSQL(
                    "INSERT INTO assistant_memory_file(store_id, path, content, revision, updated_at) " +
                        "VALUES ('private', '/profile.md', 'original', 'stable-revision', 1)",
                )
            }
            openDatabase(name).withClose { db ->
                val file = checkNotNull(MemoryFileRepository(db).read("private", "/profile.md"))
                assertEquals("original", file.content)
                assertEquals("stable-revision", file.revision)
                assertNull(file.anchorId)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test
    fun historyFollowsRenameAndRestoresOriginalPathAsNewRevision() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().withClose { db ->
            val files = MemoryFileRepository(db)
            val original = files.writeFile("private", "/topics/project.md", null, pin = true) { "original" }
            val moved = files.move("private", original.path, "/areas/project.md", original.revision, "edited", pin = false)
            val old = files.observeHistory("private", moved.identity).first().single()
            assertEquals(original.identity, moved.identity)
            assertEquals(original.path, old.path)
            assertNull(files.readVersion("other", old.id))
            val occupant = files.write("private", original.path, null) { "different file" }
            val conflict = runCatching { files.restoreVersion("private", old.id, moved.revision) }.exceptionOrNull()
            assertEquals(MemoryFileError.PATH_OCCUPIED, (conflict as? MemoryFileException)?.reason)
            files.delete("private", occupant.path, occupant.revision)
            assertTrue(runCatching { files.restoreVersion("private", old.id, original.revision) }.isFailure)
            val restored = files.restoreVersion("private", old.id, moved.revision)
            assertEquals(original.identity, restored.identity)
            assertEquals(original.content, restored.content)
            assertEquals(original.anchorId, restored.anchorId)
            assertNotEquals(original.revision, restored.revision)
            assertNull(files.read("private", moved.path))
            assertEquals(2, files.observeHistory("private", restored.identity).first().size)
        }
    }

    @Test
    fun trashCanRestoreOrPermanentlyDeleteAnIdentityWithoutTouchingOtherFiles() = runBlocking {
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build().withClose { db ->
            val files = MemoryFileRepository(db)
            val original = files.write("private", "/profile.md", null) { "original" }
            val changed = files.write("private", original.path, original.revision) { "updated" }
            files.delete("private", changed.path, changed.revision)
            val trash = files.observeTrash("private").first().single()
            assertTrue(files.list("private").isEmpty())
            assertTrue(files.observeTrash("other").first().isEmpty())
            assertTrue(runCatching { files.restoreTrash("other", trash.id) }.isFailure)
            val restored = files.restoreTrash("private", trash.id)
            assertEquals(changed.identity, restored.identity)
            assertEquals(changed.content, restored.content)
            assertNotEquals(changed.revision, restored.revision)
            assertTrue(files.observeTrash("private").first().isEmpty())
            assertTrue(runCatching { files.permanentlyDelete("private", trash.id) }.isFailure)
            files.delete("private", restored.path, restored.revision)
            val deletedAgain = files.observeTrash("private").first().single()
            val replacement = files.write("private", original.path, null) { "replacement" }
            assertTrue(runCatching { files.restoreTrash("private", deletedAgain.id) }.isFailure)
            files.permanentlyDelete("private", deletedAgain.id)
            assertTrue(files.observeTrash("private").first().isEmpty())
            assertTrue(files.observeHistory("private", original.identity).first().isEmpty())
            assertEquals(replacement, files.read("private", replacement.path))
        }
    }

    @Test
    fun upgradeFrom73RetainsFilesAndTheirFirstEditKeepsTheLegacyIdentity() = runBlocking {
        val name = "file-recovery-${UUID.randomUUID()}.db"
        try {
            migrations.createDatabase(name, 73).use { db ->
                db.execSQL(
                    "INSERT INTO assistant_memory_file(store_id, path, content, revision, updated_at, anchor_id) " +
                        "VALUES ('private', '/profile.md', 'original', 'legacy-id', 1, 'pinned')",
                )
            }
            openDatabase(name).withClose { db ->
                val files = MemoryFileRepository(db)
                val before = checkNotNull(files.read("private", "/profile.md"))
                assertEquals("legacy-id", before.identity)
                assertTrue(files.observeHistory("private", before.identity).first().isEmpty())
                val updated = files.write("private", before.path, before.revision) { "edited" }
                assertEquals(before.identity, updated.identity)
                assertEquals("pinned", updated.anchorId)
                val history = files.observeHistory("private", updated.identity).first().single()
                assertEquals("original", files.readVersion("private", history.id)?.content)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    private fun openDatabase(name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(Migration_68_69, Migration_69_70, Migration_70_71, Migration_71_72)
            .build()

    private inline fun <T> AppDatabase.withClose(block: (AppDatabase) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }
}
