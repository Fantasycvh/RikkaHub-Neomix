package app.androidrik.rikkahub.data.datastore

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.ConversationEntity
import app.androidrik.rikkahub.data.db.entity.FolderEntity
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryHistoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryTrashEntity
import app.androidrik.rikkahub.data.db.mergeDefaultAssistantReferences
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultAssistantMergeInstrumentedTest {
    private val from = LEGACY_EXAMPLE_ASSISTANT_ID.toString()
    private val to = DEFAULT_ASSISTANT_ID.toString()

    private suspend fun withDatabase(test: suspend (AppDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.folderDao().insert(FolderEntity("folder", from, "Existing folder", createAt = 1))
            for ((id, assistant) in listOf("example" to from, "default" to to, "custom" to "custom")) {
                db.conversationDao().insert(ConversationEntity(
                    id = id, assistantId = assistant, title = id, nodes = "[]", createAt = 1,
                    updateAt = 2, chatSuggestions = "[]", isPinned = false,
                    folderId = if (id == "example") "folder" else "",
                    sortOrder = if (id == "default") 1000 else null,
                ))
            }
            db.memoryDao().insertMemory(MemoryEntity(assistantId = from, content = "Remember this", publicId = "12345678"))
            val snapshot = JsonInstant.encodeToString(MemoryEntity(id = 123, assistantId = from, publicId = "abcdef12", content = "Deleted memory"))
            db.memoryHistoryDao().insert(MemoryHistoryEntity(
                assistantId = from, memoryId = 123, publicId = "abcdef12", revision = 1, snapshot = snapshot,
                operation = "DELETE", actor = "USER", reason = "", recordedAt = 1,
            ))
            db.memoryTrashDao().insert(MemoryTrashEntity(
                memoryId = 123, assistantId = from, publicId = "abcdef12", revision = 2, snapshot = snapshot,
                actor = "USER", reason = "", deletedAt = 1,
            ))
            test(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun mergePreservesRowsAndFolderMembershipAndCanReplayAfterBackup() = runBlocking {
        withDatabase { db ->
            db.mergeDefaultAssistantReferences()
            val sql = db.openHelper.writableDatabase
            assertEquals(to, db.conversationDao().getAssistantIdOf("example"))
            assertEquals("custom", db.conversationDao().getAssistantIdOf("custom"))
            assertEquals(to, db.folderDao().getFolderById("folder")!!.assistantId)
            sql.query("SELECT folder_id FROM ConversationEntity WHERE id = 'example'").use {
                assertTrue(it.moveToFirst())
                assertEquals("folder", it.getString(0))
            }
            sql.query("SELECT assistant_id, content FROM MemoryEntity").use {
                assertTrue(it.moveToFirst())
                assertEquals(to, it.getString(0))
                assertEquals("Remember this", it.getString(1))
            }
            assertEquals(3, db.conversationDao().countAll())
            val history = db.memoryHistoryDao().list(to, 123).single()
            val trash = db.memoryTrashDao().list(to).single()
            for (snapshot in listOf(history.snapshot, trash.snapshot)) {
                val memory = JsonInstant.decodeFromString<MemoryEntity>(snapshot)
                assertEquals(to, memory.assistantId)
                assertEquals("abcdef12", memory.publicId)
                assertEquals("Deleted memory", memory.content)
            }
            assertEquals(1000L, db.conversationDao().getSortOrder("default"))
            assertEquals(2000L, db.conversationDao().getSortOrder("example"))
            sql.execSQL("INSERT INTO memory_review_cursor(memory_id, cursor, updated_at) VALUES (?, 12, 34)", arrayOf(to))
            db.mergeDefaultAssistantReferences()
            sql.query("SELECT cursor FROM memory_review_cursor WHERE memory_id = ?", arrayOf(to)).use {
                assertTrue("replaying a completed migration must not reset review progress", it.moveToFirst())
                assertEquals(12, it.getInt(0))
            }
            // A staged old database may restore the old IDs after preferences have already migrated.
            sql.execSQL("UPDATE ConversationEntity SET assistant_id = ? WHERE id = 'example'", arrayOf(from))
            db.mergeDefaultAssistantReferences()
            assertEquals(to, db.conversationDao().getAssistantIdOf("example"))
        }
    }

    @Test
    fun failureRollsBackConversationAndFolderChanges() = runBlocking {
        withDatabase { db ->
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_memory_merge BEFORE UPDATE ON MemoryEntity BEGIN SELECT RAISE(ABORT, 'test failure'); END",
            )
            assertTrue(runCatching { db.mergeDefaultAssistantReferences() }.isFailure)
            assertEquals(from, db.conversationDao().getAssistantIdOf("example"))
            assertEquals(from, db.folderDao().getFolderById("folder")!!.assistantId)
            assertNull(db.conversationDao().getSortOrder("example"))
            val snapshot = db.memoryTrashDao().list(from).single().snapshot
            assertEquals(from, JsonInstant.decodeFromString<MemoryEntity>(snapshot).assistantId)
        }
    }
}
