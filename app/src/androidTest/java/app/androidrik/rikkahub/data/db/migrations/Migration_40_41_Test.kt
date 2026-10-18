package app.androidrik.rikkahub.data.db.migrations

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryHistoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryTrashEntity
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.data.repository.RoomMemoryTransactionRunner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Only UUID-named, test-owned SQLite files are opened/copied. Never uses the production database or backup path. */
@RunWith(AndroidJUnit4::class)
class Migration_40_41_Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testNames = mutableListOf<String>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        testNames.forEach { name ->
            check(name.startsWith("memory-40-41-test-"))
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrate40To41_validatesFullSchemaAndPreservesIdentityDataAndLegacyAudit() {
        val name = newName()
        helper.createDatabase(name, 40).apply {
            seedVersion40(this)
            close()
        }

        helper.runMigrationsAndValidate(name, 41, true, Migration_40_41).use { db ->
            assertMigratedData(db)
            db.query("SELECT * FROM memory_history").use { assertEquals(0, it.count) }
            db.query("SELECT * FROM memory_trash").use { assertEquals(0, it.count) }
            // The numeric key/cursor and the empty public token must not be regenerated during migration.
            db.query("SELECT cursor, updated_at FROM memory_review_cursor WHERE memory_id = '__global__'").use {
                assertTrue(it.moveToFirst())
                assertEquals(43L, it.getLong(0))
                assertEquals(31L, it.getLong(1))
            }
        }
    }

    @Test
    fun restoredVersion40File_isMigratedByRealRoomOpenWithoutTouchingSourceFile() = runBlocking {
        val source = newName()
        val restored = newName()
        helper.createDatabase(source, 40).apply {
            seedVersion40(this)
            checkpointAndVacuum(this)
            close()
        }
        context.getDatabasePath(source).copyTo(context.getDatabasePath(restored), overwrite = false)

        withRoom(restored, migrate = true) { room ->
                assertMigratedData(room.openHelper.writableDatabase)
                val global = requireNotNull(room.memoryDao().getMemoryById(43))
                assertEquals("__global__", global.assistantId)
                assertEquals("", global.publicId)
                assertEquals(1L, global.revision)
                assertTrue(room.memoryProposalDao().getPending("__global__").isEmpty())
            }

        // Opening the copy cannot have upgraded the closed source file.
        android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(source).absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { assertEquals(40, it.version) }
    }

    @Test
    fun checkpointVacuumCurrentFile_roundTripsLiveHistoryTrashAndProposalReceipt() = runBlocking {
        val source = newName()
        val restored = newName()
        val live = MemoryEntity(
            id = 43, assistantId = "__global__", publicId = "", content = "restored live fact",
            description = "original topic", importance = 0.8f, updatedAt = 99L,
            readCount = 7, lastReadAt = 101L, isAnchor = true, revision = 9L,
        )
        val snapshots = Json { encodeDefaults = true }
        val prior = live.copy(content = "previous fact", revision = 8L, readCount = 1)
        val deleted = live.copy(id = 72, publicId = "dead0072", content = "deleted fact", revision = 11L)
        val history = MemoryHistoryEntity(
            assistantId = "__global__", memoryId = 43, publicId = "", revision = 8L,
            snapshot = snapshots.encodeToString(prior), operation = "UPDATE", actor = "USER",
            reason = "roundtrip test", recordedAt = 100L,
        )
        val trash = MemoryTrashEntity(
            memoryId = 72, assistantId = "__global__", publicId = "dead0072", revision = 12L,
            snapshot = snapshots.encodeToString(deleted), actor = "USER", reason = "roundtrip test", deletedAt = 110L,
        )
        val historyRows = withRoom(source) { room ->
            room.memoryDao().insertMemory(live)
            // Raw inserts deliberately verify the persisted schema independently of repository object caches.
            val db = room.openHelper.writableDatabase
            db.execSQL(
                "INSERT INTO memory_history " +
                    "(assistant_id,memory_id,public_id,revision,snapshot,operation,actor,reason,recorded_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    history.assistantId, history.memoryId, history.publicId, history.revision, history.snapshot,
                    history.operation, history.actor, history.reason, history.recordedAt,
                ),
            )
            db.execSQL(
                "INSERT INTO memory_trash " +
                    "(memory_id,assistant_id,public_id,revision,snapshot,actor,reason,deleted_at) " +
                    "VALUES (?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(
                    trash.memoryId, trash.assistantId, trash.publicId, trash.revision, trash.snapshot,
                    trash.actor, trash.reason, trash.deletedAt,
                ),
            )
            db.execSQL(
                "INSERT INTO memory_proposal " +
                    "(memory_id,batch_id,action,target_id,content,importance,description,created_at,decision," +
                    "decided_at,expected_revision,operation_id,depends_on,applied_target_id,applied_revision," +
                    "contract_version) VALUES ('__global__','roundtrip','edit','43','new',NULL,NULL,1,'applied'," +
                    "2,8,'edit-43','[]','43',9,1)",
            )
            checkpointAndVacuum(db)
            queryCount(db, "memory_history")
        }
        assertEquals(1, historyRows)
        context.getDatabasePath(source).copyTo(context.getDatabasePath(restored), overwrite = false)

        withRoom(restored) { room ->
            assertEquals(live, room.memoryDao().getMemoryById(43))
            val db = room.openHelper.writableDatabase
            assertEquals(41, db.version)
            db.query("SELECT snapshot,revision FROM memory_history WHERE memory_id=43").use {
                assertTrue(it.moveToFirst())
                assertEquals(history.snapshot, it.getString(0))
                assertEquals(8L, it.getLong(1))
            }
            db.query("SELECT snapshot,revision,public_id FROM memory_trash WHERE memory_id=72").use {
                assertTrue(it.moveToFirst())
                assertEquals(trash.snapshot, it.getString(0))
                assertEquals(12L, it.getLong(1))
                assertEquals("dead0072", it.getString(2))
            }
            val receipt = room.memoryProposalDao().getBatch("__global__", "roundtrip").single()
            assertEquals("applied", receipt.decision)
            assertEquals(8L, receipt.expectedRevision)
            assertEquals("43", receipt.appliedTargetId)
            assertEquals(9L, receipt.appliedRevision)
            assertEquals(1, receipt.contractVersion)
            assertTrue(room.memoryProposalDao().getPending("__global__").isEmpty())
            val repository = MemoryRepository(
                room.memoryDao(), RoomMemoryTransactionRunner(room), room.memoryHistoryDao(), room.memoryTrashDao(),
            )
            assertEquals(prior, repository.listHistory("__global__", "43").single().snapshot)
            val historicalRestore = repository.restoreRevision("__global__", "43", 8L, expectedRevision = 9L)
            assertEquals("previous fact", historicalRestore.content)
            assertEquals(10L, historicalRestore.revision)
            assertEquals(7, historicalRestore.readCount)
            val trashRestore = repository.restoreTrash("__global__", 72, expectedRevision = 12L)
            assertEquals("dead0072", trashRestore.id)
            assertEquals(72, trashRestore.sortKey)
            assertEquals(13L, trashRestore.revision)
            assertEquals("deleted fact", trashRestore.content)
            assertTrue(repository.listTrash("__global__").isEmpty())
        }
    }

    private fun seedVersion40(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO memoryentity " +
                "(id,assistant_id,content,public_id,importance,updated_at,description," +
                "read_count,last_read_at,is_anchor) " +
                "VALUES (43,'__global__','global original','',0.8,20,'global topic',7,19,1)",
        )
        db.execSQL(
            "INSERT INTO memoryentity " +
                "(id,assistant_id,content,public_id,importance,updated_at,description," +
                "read_count,last_read_at,is_anchor) " +
                "VALUES (44,'private-store','private original','abcd0044',0.6,30,'private topic',3,29,0)",
        )
        db.execSQL("INSERT INTO memory_review_cursor (memory_id,cursor,updated_at) VALUES ('__global__',43,31)")
        listOf("create", "edit", "delete").forEachIndexed { index, action ->
            insertOldProposal(db, index + 1, action, "pending")
        }
        insertOldProposal(db, 4, "edit", "rejected")
        insertOldProposal(db, 5, "delete", "applied")
    }

    private fun insertOldProposal(db: SupportSQLiteDatabase, id: Int, action: String, decision: String) {
        db.execSQL(
            "INSERT INTO memory_proposal " +
                "(id,memory_id,batch_id,action,target_id,content,importance,description," +
                "created_at,decision,decided_at) " +
                "VALUES (?, '__global__', 'legacy-run', ?, ?, ?, 0.5, 'legacy topic', 10, ?, ?)",
            arrayOf<Any?>(id, action, if (action == "create") null else "43", "legacy $action", decision,
                if (decision == "pending") 0L else 11L),
        )
    }

    private fun assertMigratedData(db: SupportSQLiteDatabase) {
        db.query("SELECT * FROM memoryentity ORDER BY id").use { cursor ->
            assertEquals(2, cursor.count)
            assertTrue(cursor.moveToFirst())
            assertEquals(43, cursor.getInt(cursor.getColumnIndexOrThrow("id")))
            assertEquals("__global__", cursor.getString(cursor.getColumnIndexOrThrow("assistant_id")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("public_id")))
            assertEquals("global original", cursor.getString(cursor.getColumnIndexOrThrow("content")))
            assertEquals("global topic", cursor.getString(cursor.getColumnIndexOrThrow("description")))
            assertEquals(7, cursor.getInt(cursor.getColumnIndexOrThrow("read_count")))
            assertEquals(19L, cursor.getLong(cursor.getColumnIndexOrThrow("last_read_at")))
            assertEquals(20L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("is_anchor")))
            assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("revision")))
            assertTrue(cursor.moveToNext())
            assertEquals(44, cursor.getInt(cursor.getColumnIndexOrThrow("id")))
            assertEquals("private-store", cursor.getString(cursor.getColumnIndexOrThrow("assistant_id")))
            assertEquals("abcd0044", cursor.getString(cursor.getColumnIndexOrThrow("public_id")))
            assertEquals("private original", cursor.getString(cursor.getColumnIndexOrThrow("content")))
            assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("revision")))
        }
        db.query("SELECT * FROM memory_proposal ORDER BY id").use { cursor ->
            assertEquals(5, cursor.count)
            while (cursor.moveToNext()) {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow("id"))
                val decision = cursor.getString(cursor.getColumnIndexOrThrow("decision"))
                assertEquals(if (id <= 3) "stale" else if (id == 4) "rejected" else "applied", decision)
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("expected_revision")))
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("applied_revision")))
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("applied_target_id")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("operation_id")))
                assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("depends_on")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("contract_version")))
                assertEquals("legacy-run", cursor.getString(cursor.getColumnIndexOrThrow("batch_id")))
                assertNotNull(cursor.getString(cursor.getColumnIndexOrThrow("content")))
                if (id <= 3) assertTrue(cursor.getLong(cursor.getColumnIndexOrThrow("decided_at")) > 0L)
                else assertEquals(11L, cursor.getLong(cursor.getColumnIndexOrThrow("decided_at")))
            }
        }
        assertEquals(0, db.query("SELECT * FROM memory_proposal WHERE decision='pending'").use { it.count })
    }

    private suspend fun <T> withRoom(
        name: String,
        migrate: Boolean = false,
        block: suspend (AppDatabase) -> T,
    ): T {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, name)
        if (migrate) builder.addMigrations(Migration_40_41)
        val room = builder.build()
        return try {
            block(room)
        } finally {
            room.close()
        }
    }

    private fun checkpointAndVacuum(db: SupportSQLiteDatabase) {
        db.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Checkpoint must not be busy", 0, cursor.getInt(0))
        }
        db.execSQL("VACUUM")
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
    }

    private fun queryCount(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun newName(): String = "memory-40-41-test-${UUID.randomUUID()}.db".also { name ->
        assertFalse(context.getDatabasePath(name).exists())
        testNames += name
    }
}
