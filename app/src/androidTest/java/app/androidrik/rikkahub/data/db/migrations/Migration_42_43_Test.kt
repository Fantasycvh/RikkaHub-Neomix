package app.androidrik.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Real 42→43 AutoMigration: only `media_creation_session/node/record` are added, nothing is rebuilt.
 * Only UUID-named, test-owned databases are opened; the production database/backup path is never touched.
 */
@RunWith(AndroidJUnit4::class)
class Migration_42_43_Test {
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
            check(name.startsWith(PREFIX))
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrate42To43_addsMediaCreationTablesMatchingSchema43() {
        val name = newName()
        helper.createDatabase(name, 42).use { db ->
            assertEquals(42, db.version)
            assertTrue("v42 must not contain media creation tables", MEDIA_TABLES.none { it in tableNames(db) })
        }

        // No explicit Migration is passed on purpose: MigrationTestHelper runs AppDatabase's AutoMigration(42→43).
        helper.runMigrationsAndValidate(name, 43, true).use { db ->
            assertEquals(43, db.version)

            assertEquals(
                listOf(
                    ColumnSpec("id", "TEXT", notNull = true, primaryKey = true),
                    ColumnSpec("title", "TEXT", notNull = true),
                    ColumnSpec("draft", "TEXT", notNull = true),
                    ColumnSpec("create_at", "INTEGER", notNull = true),
                    ColumnSpec("update_at", "INTEGER", notNull = true),
                ),
                readColumns(db, "media_creation_session"),
            )
            assertEquals(
                listOf(
                    ColumnSpec("id", "TEXT", notNull = true, primaryKey = true),
                    ColumnSpec("session_id", "TEXT", notNull = true),
                    ColumnSpec("selected_record_id", "TEXT", notNull = true),
                    ColumnSpec("create_at", "INTEGER", notNull = true),
                ),
                readColumns(db, "media_creation_node"),
            )
            assertEquals(
                listOf(
                    ColumnSpec("id", "TEXT", notNull = true, primaryKey = true),
                    ColumnSpec("session_id", "TEXT", notNull = true),
                    ColumnSpec("node_id", "TEXT", notNull = true),
                    ColumnSpec("provider_id", "TEXT", notNull = true),
                    ColumnSpec("provider_name", "TEXT", notNull = true),
                    ColumnSpec("model_id", "TEXT", notNull = true),
                    ColumnSpec("kind", "TEXT", notNull = true),
                    ColumnSpec("prompt", "TEXT", notNull = true),
                    ColumnSpec("params", "TEXT", notNull = true),
                    ColumnSpec("inputs", "TEXT", notNull = true),
                    ColumnSpec("status", "TEXT", notNull = true),
                    ColumnSpec("task_id", "TEXT", notNull = false),
                    ColumnSpec("error", "TEXT", notNull = false),
                    ColumnSpec("outputs", "TEXT", notNull = true),
                    ColumnSpec("create_at", "INTEGER", notNull = true),
                    ColumnSpec("update_at", "INTEGER", notNull = true),
                ),
                readColumns(db, "media_creation_record"),
            )

            assertEquals(
                listOf(ForeignKeySpec("media_creation_session", "session_id", "id", "NO ACTION", "CASCADE")),
                foreignKeys(db, "media_creation_node"),
            )
            assertEquals(
                listOf(
                    ForeignKeySpec("media_creation_session", "session_id", "id", "NO ACTION", "CASCADE"),
                    ForeignKeySpec("media_creation_node", "node_id", "id", "NO ACTION", "CASCADE"),
                ).sortedBy { it.from },
                foreignKeys(db, "media_creation_record").sortedBy { it.from },
            )

            assertEquals(
                mapOf("index_media_creation_node_session_id" to listOf("session_id")),
                createdIndices(db, "media_creation_node"),
            )
            assertEquals(
                mapOf(
                    "index_media_creation_record_node_id" to listOf("node_id"),
                    "index_media_creation_record_session_id" to listOf("session_id"),
                ),
                createdIndices(db, "media_creation_record"),
            )

            MEDIA_TABLES.forEach { table ->
                assertEquals("$table must start empty", 0, count(db, table))
            }
        }
    }

    @Test
    fun migrate42To43_preservesLegacyDataAndOnlyAppendsMediaCreationTables() {
        val name = newName()
        val legacyTableSql: Map<String, String?>
        val legacyTableNames: Set<String>
        helper.createDatabase(name, 42).use { db ->
            assertEquals(42, db.version)
            seedLegacyRows(db)
            legacyTableSql = readTableSql(db)
            legacyTableNames = legacyTableSql.keys
            assertTrue(legacyTableNames.containsAll(listOf("ConversationEntity", "MemoryEntity", "message_node")))
            assertTrue(MEDIA_TABLES.none(legacyTableNames::contains))
            assertLegacyRows(db)
        }

        helper.runMigrationsAndValidate(name, 43, true).use { db ->
            assertEquals(43, db.version)

            // Non-destructive: every pre-existing table keeps its exact CREATE statement and only three are appended.
            val migratedTableSql = readTableSql(db)
            legacyTableSql.forEach { (table, sql) ->
                assertEquals("Migration must not recreate table $table", sql, migratedTableSql[table])
            }
            assertEquals(legacyTableNames + MEDIA_TABLES, migratedTableSql.keys)

            // Existing rows survive the migration unchanged.
            assertLegacyRows(db)

            // The new tables are writable with the 43 columns, including the nullable columns.
            insertMediaCreationGraph(db)
            assertMediaCreationGraph(db)
        }
    }

    private fun seedLegacyRows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO ConversationEntity " +
                "(id,title,nodes,create_at,update_at,is_pinned,folder_id,memory_disabled) " +
                "VALUES ('conv-legacy-42','legacy conversation','[]',1000,2000,1,'legacy-folder',1)",
        )
        db.execSQL(
            "INSERT INTO MemoryEntity " +
                "(id,assistant_id,content,public_id,importance,updated_at,description,read_count,last_read_at) " +
                "VALUES (42,'__global__','legacy memory','a1000042',0.8,30,'legacy topic',7,19)",
        )
        db.execSQL(
            "INSERT INTO message_node (id,conversation_id,node_index,messages,select_index) " +
                "VALUES ('node-legacy-42','conv-legacy-42',0,'[]',0)",
        )
    }

    private fun assertLegacyRows(db: SupportSQLiteDatabase) {
        assertEquals(1, count(db, "ConversationEntity"))
        assertEquals(1, count(db, "MemoryEntity"))
        assertEquals(1, count(db, "message_node"))

        db.query("SELECT * FROM ConversationEntity WHERE id='conv-legacy-42'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy conversation", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("nodes")))
            assertEquals(1000L, cursor.getLong(cursor.getColumnIndexOrThrow("create_at")))
            assertEquals(2000L, cursor.getLong(cursor.getColumnIndexOrThrow("update_at")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("is_pinned")))
            assertEquals("legacy-folder", cursor.getString(cursor.getColumnIndexOrThrow("folder_id")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("memory_disabled")))
        }
        db.query("SELECT * FROM MemoryEntity WHERE id=42").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("__global__", cursor.getString(cursor.getColumnIndexOrThrow("assistant_id")))
            assertEquals("legacy memory", cursor.getString(cursor.getColumnIndexOrThrow("content")))
            assertEquals("a1000042", cursor.getString(cursor.getColumnIndexOrThrow("public_id")))
            assertEquals(0.8, cursor.getDouble(cursor.getColumnIndexOrThrow("importance")), 1e-9)
            assertEquals(30L, cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")))
            assertEquals("legacy topic", cursor.getString(cursor.getColumnIndexOrThrow("description")))
            assertEquals(7, cursor.getInt(cursor.getColumnIndexOrThrow("read_count")))
            assertEquals(19L, cursor.getLong(cursor.getColumnIndexOrThrow("last_read_at")))
            // Untouched defaults must not be rewritten by the migration.
            assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("revision")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("is_anchor")))
        }
        db.query("SELECT * FROM message_node WHERE id='node-legacy-42'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("conv-legacy-42", cursor.getString(cursor.getColumnIndexOrThrow("conversation_id")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("node_index")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("messages")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("select_index")))
        }
    }

    private fun insertMediaCreationGraph(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO media_creation_session (id,title,draft,create_at,update_at) VALUES (?,?,?,?,?)",
            arrayOf<Any?>("session-43", "session 43", """{"prompt":"cat"}""", 100L, 200L),
        )
        db.execSQL(
            "INSERT INTO media_creation_node (id,session_id,selected_record_id,create_at) VALUES (?,?,?,?)",
            arrayOf<Any?>("node-43", "session-43", "record-43", 110L),
        )
        db.execSQL(
            "INSERT INTO media_creation_record " +
                "(id,session_id,node_id,provider_id,provider_name,model_id,kind,prompt,params,inputs,status," +
                "outputs,create_at,update_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                "record-43", "session-43", "node-43", "provider-1", "Provider One", "model-1", "image",
                "a cat", "{}", "[]", "succeeded", "[]", 120L, 130L,
            ),
        )
    }

    private fun assertMediaCreationGraph(db: SupportSQLiteDatabase) {
        assertEquals(1, count(db, "media_creation_session"))
        assertEquals(1, count(db, "media_creation_node"))
        assertEquals(1, count(db, "media_creation_record"))

        db.query("SELECT * FROM media_creation_session WHERE id='session-43'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session 43", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("""{"prompt":"cat"}""", cursor.getString(cursor.getColumnIndexOrThrow("draft")))
            assertEquals(100L, cursor.getLong(cursor.getColumnIndexOrThrow("create_at")))
            assertEquals(200L, cursor.getLong(cursor.getColumnIndexOrThrow("update_at")))
        }
        db.query("SELECT * FROM media_creation_node WHERE id='node-43'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session-43", cursor.getString(cursor.getColumnIndexOrThrow("session_id")))
            assertEquals("record-43", cursor.getString(cursor.getColumnIndexOrThrow("selected_record_id")))
            assertEquals(110L, cursor.getLong(cursor.getColumnIndexOrThrow("create_at")))
        }
        db.query("SELECT * FROM media_creation_record WHERE id='record-43'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session-43", cursor.getString(cursor.getColumnIndexOrThrow("session_id")))
            assertEquals("node-43", cursor.getString(cursor.getColumnIndexOrThrow("node_id")))
            assertEquals("provider-1", cursor.getString(cursor.getColumnIndexOrThrow("provider_id")))
            assertEquals("Provider One", cursor.getString(cursor.getColumnIndexOrThrow("provider_name")))
            assertEquals("model-1", cursor.getString(cursor.getColumnIndexOrThrow("model_id")))
            assertEquals("image", cursor.getString(cursor.getColumnIndexOrThrow("kind")))
            assertEquals("a cat", cursor.getString(cursor.getColumnIndexOrThrow("prompt")))
            assertEquals("{}", cursor.getString(cursor.getColumnIndexOrThrow("params")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("inputs")))
            assertEquals("succeeded", cursor.getString(cursor.getColumnIndexOrThrow("status")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("outputs")))
            assertEquals(120L, cursor.getLong(cursor.getColumnIndexOrThrow("create_at")))
            assertEquals(130L, cursor.getLong(cursor.getColumnIndexOrThrow("update_at")))
            assertTrue("task_id must stay nullable", cursor.isNull(cursor.getColumnIndexOrThrow("task_id")))
            assertTrue("error must stay nullable", cursor.isNull(cursor.getColumnIndexOrThrow("error")))
        }
    }

    private fun readColumns(db: SupportSQLiteDatabase, table: String): List<ColumnSpec> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ColumnSpec(
                            name = cursor.getString(1),
                            type = cursor.getString(2),
                            notNull = cursor.getInt(3) != 0,
                            defaultValue = cursor.getString(4),
                            primaryKey = cursor.getInt(5) != 0,
                        ),
                    )
                }
            }
        }

    private fun foreignKeys(db: SupportSQLiteDatabase, table: String): List<ForeignKeySpec> =
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ForeignKeySpec(
                            table = cursor.getString(2),
                            from = cursor.getString(3),
                            to = cursor.getString(4),
                            onUpdate = cursor.getString(5),
                            onDelete = cursor.getString(6),
                        ),
                    )
                }
            }
        }

    private fun createdIndices(db: SupportSQLiteDatabase, table: String): Map<String, List<String>> =
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL",
            arrayOf(table),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val indexName = cursor.getString(0)
                    put(indexName, readIndexColumns(db, indexName))
                }
            }
        }

    private fun readIndexColumns(db: SupportSQLiteDatabase, indexName: String): List<String> =
        db.query("PRAGMA index_info(`$indexName`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(2))
            }
        }

    /** Application tables only: Room/SQLite bookkeeping tables are not part of the migration contract. */
    private fun readTableSql(db: SupportSQLiteDatabase): Map<String, String?> =
        db.query(
            "SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata','room_master_table')",
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
            }
        }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> = readTableSql(db).keys

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun newName(): String = "$PREFIX${UUID.randomUUID()}.db".also { name ->
        assertFalse(context.getDatabasePath(name).exists())
        names += name
    }

    private data class ColumnSpec(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val defaultValue: String? = null,
        val primaryKey: Boolean = false,
    )

    private data class ForeignKeySpec(
        val table: String,
        val from: String,
        val to: String,
        val onUpdate: String,
        val onDelete: String,
    )

    private companion object {
        const val PREFIX = "media-migration-test-"
        val MEDIA_TABLES = listOf("media_creation_session", "media_creation_node", "media_creation_record")
    }
}
