package app.androidrik.rikkahub.data.repository

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.ai.tools.buildFileMemoryTool
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

    private inline fun <T> AppDatabase.withClose(block: (AppDatabase) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }
}
