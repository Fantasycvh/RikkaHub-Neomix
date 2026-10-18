package app.androidrik.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Test-owned files only; validates source defaults without inventing historical attribution. */
@RunWith(AndroidJUnit4::class)
class Migration_41_42_Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun tearDown() {
        names.forEach { name ->
            check(name.startsWith("memory-source-test-"))
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrate41To42PreservesLegacyCreditAndAllBusinessEvidenceWithoutInventingSources() {
        val name = newName()
        // Literal B-era snapshot: the four source fields did not exist yet.
        val oldSnapshot = """
            {"id":43,"assistantId":"__global__","content":"prior",
            "publicId":"a1000043","revision":8,"readCount":7,"lastReadAt":19}
        """.trimIndent()
        helper.createDatabase(name, 41).apply {
            insertLive(this, revision = 9)
            execSQL(
                "INSERT INTO memory_history " +
                    "(assistant_id,memory_id,public_id,revision,snapshot,operation,actor,reason,recorded_at) " +
                    "VALUES ('__global__',43,'a1000043',8,?,'UPDATE','USER','migration',20)",
                arrayOf<Any?>(oldSnapshot),
            )
            execSQL(
                "INSERT INTO memory_trash " +
                    "(memory_id,assistant_id,public_id,revision,snapshot,actor,reason,deleted_at) " +
                    "VALUES (44,'other','a1000044',10,?,'USER','migration',20)",
                arrayOf<Any?>("untouched B snapshot"),
            )
            execSQL(
                "INSERT INTO memory_proposal " +
                    "(memory_id,batch_id,action,target_id,content,importance,description,created_at,decision," +
                    "decided_at,expected_revision,operation_id,depends_on,applied_target_id,applied_revision," +
                    "contract_version) VALUES ('__global__','b','edit','a1000043','next',NULL,NULL,1,'applied'," +
                    "2,8,'edit-43','[]','a1000043',9,1)",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 42, true, Migration_41_42).use { db ->
            assertUnattributedLegacy(db)
            db.query("SELECT snapshot,revision FROM memory_history WHERE memory_id=43").use {
                assertTrue(it.moveToFirst())
                assertEquals(oldSnapshot, it.getString(0))
                assertEquals(8L, it.getLong(1))
                val decoded = Json.decodeFromString<MemoryEntity>(it.getString(0))
                assertEquals(7, decoded.readCount)
                assertEquals(0, decoded.chatReadCount)
                assertEquals(0, decoded.consolidationReadCount)
            }
            db.query("SELECT snapshot,revision FROM memory_trash WHERE memory_id=44").use {
                assertTrue(it.moveToFirst())
                assertEquals("untouched B snapshot", it.getString(0))
                assertEquals(10L, it.getLong(1))
            }
            db.query("SELECT expected_revision,applied_revision,decision FROM memory_proposal").use {
                assertTrue(it.moveToFirst())
                assertEquals(8L, it.getLong(0))
                assertEquals(9L, it.getLong(1))
                assertEquals("applied", it.getString(2))
            }
        }
    }

    @Test
    fun migrate40ThroughBothStepsRetainsBFailClosedBehaviorAndUnknownLegacySource() {
        val name = newName()
        helper.createDatabase(name, 40).apply {
            insertLive(this, revision = null)
            execSQL(
                "INSERT INTO memory_proposal " +
                    "(memory_id,batch_id,action,target_id,content,importance,description," +
                    "created_at,decision,decided_at) " +
                    "VALUES ('__global__','legacy','create',NULL,'draft',NULL,NULL,1,'pending',0)",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, 42, true, Migration_40_41, Migration_41_42).use { db ->
            assertUnattributedLegacy(db)
            db.query("SELECT decision,expected_revision,contract_version FROM memory_proposal").use {
                assertTrue(it.moveToFirst())
                assertEquals("stale", it.getString(0))
                assertTrue(it.isNull(1))
                assertEquals(0, it.getInt(2))
                assertFalse(it.moveToNext())
            }
        }
    }

    private fun insertLive(db: SupportSQLiteDatabase, revision: Long?) {
        val column = if (revision == null) "" else ",revision"
        val value = if (revision == null) "" else ",$revision"
        db.execSQL(
            "INSERT INTO memoryentity " +
                "(id,assistant_id,content,public_id,importance,updated_at,description," +
                "read_count,last_read_at,is_anchor$column) " +
                "VALUES (43,'__global__','source fact','a1000043',0.4,10,'topic',7,19,1$value)",
        )
    }

    private fun assertUnattributedLegacy(db: SupportSQLiteDatabase) {
        db.query("SELECT * FROM memoryentity WHERE id=43").use {
            assertTrue(it.moveToFirst())
            assertEquals("__global__", it.getString(it.getColumnIndexOrThrow("assistant_id")))
            assertEquals("a1000043", it.getString(it.getColumnIndexOrThrow("public_id")))
            assertEquals(7, it.getInt(it.getColumnIndexOrThrow("read_count")))
            assertEquals(19L, it.getLong(it.getColumnIndexOrThrow("last_read_at")))
            for (column in listOf(
                "chat_read_count", "last_chat_read_at", "consolidation_read_count", "last_consolidation_read_at",
            )) {
                assertEquals("New attribution must start unknown/zero: $column", 0L,
                    it.getLong(it.getColumnIndexOrThrow(column)))
            }
        }
    }

    private fun newName(): String = "memory-source-test-${UUID.randomUUID()}.db".also { names += it }
}
