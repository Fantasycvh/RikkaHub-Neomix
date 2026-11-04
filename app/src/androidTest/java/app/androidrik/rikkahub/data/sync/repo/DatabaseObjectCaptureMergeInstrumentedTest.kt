package app.androidrik.rikkahub.data.sync.repo

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 合成数据；验证 P2 捕获/合并的 SQLite 行为（JVM 无 android.database，必须真机/模拟器跑）。 */
@RunWith(AndroidJUnit4::class)
class DatabaseObjectCaptureMergeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "db-object-${UUID.randomUUID()}").apply { check(mkdirs()) }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun schema(file: File, withFutureColumn: Boolean): SQLiteDatabase {
        file.delete()
        val database = SQLiteDatabase.openOrCreateDatabase(file, null)
        database.execSQL(
            "CREATE TABLE conversationentity(id TEXT PRIMARY KEY NOT NULL, assistant_id TEXT NOT NULL, " +
                "title TEXT NOT NULL, nodes TEXT NOT NULL, create_at INTEGER NOT NULL, update_at INTEGER NOT NULL, " +
                "suggestions TEXT NOT NULL DEFAULT '[]', is_pinned INTEGER NOT NULL DEFAULT 0, sort_order INTEGER" +
                (if (withFutureColumn) ", future_column TEXT DEFAULT 'base-default'" else "") + ")"
        )
        database.execSQL(
            "CREATE TABLE message_node(id TEXT PRIMARY KEY NOT NULL, conversation_id TEXT NOT NULL, " +
                "node_index INTEGER NOT NULL, messages TEXT NOT NULL, select_index INTEGER NOT NULL, " +
                "FOREIGN KEY(conversation_id) REFERENCES conversationentity(id) ON DELETE CASCADE)"
        )
        database.execSQL(
            "CREATE TABLE compression_checkpoint(id INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id TEXT NOT NULL, " +
                "content TEXT NOT NULL, message_count INTEGER NOT NULL, boundary_node_id TEXT, created_at INTEGER NOT NULL, " +
                "area_fingerprint TEXT, FOREIGN KEY(conversation_id) REFERENCES conversationentity(id) ON DELETE CASCADE)"
        )
        database.execSQL("CREATE TABLE folderentity(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, payload BLOB)")
        database.execSQL("PRAGMA user_version = 58")
        return database
    }

    private fun insertConversation(database: SQLiteDatabase, id: String, title: String) {
        database.execSQL(
            "INSERT INTO conversationentity(id, assistant_id, title, nodes, create_at, update_at) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(id, UUID.randomUUID().toString(), title, "[]", 1L, 2L),
        )
    }

    @Test
    fun captureAndMergeReplaceCoveredTablesAndKeepLocalTables() {
        val conversationA = UUID.randomUUID().toString()
        val conversationB = UUID.randomUUID().toString()
        val oldConversation = UUID.randomUUID().toString()
        val nodeA1 = UUID.randomUUID().toString()
        val nodeA2 = UUID.randomUUID().toString()
        val nodeB1 = UUID.randomUUID().toString()
        val folderId = UUID.randomUUID().toString()
        val payloadBlob = byteArrayOf(0, 5, 9, -3)
        val sourceFile = File(root, "source.db")
        val snapshot = File(root, "snapshot.db")

        schema(sourceFile, withFutureColumn = false).use { database ->
            insertConversation(database, conversationA, "A")
            insertConversation(database, conversationB, "B")
            database.execSQL(
                "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(nodeA1, conversationA, 0, "[\"a1\"]", 0),
            )
            database.execSQL(
                "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(nodeA2, conversationA, 1, "[\"a2\"]", 0),
            )
            database.execSQL(
                "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(nodeB1, conversationB, 0, "[\"b1\"]", 0),
            )
            database.execSQL(
                "INSERT INTO compression_checkpoint(conversation_id, content, message_count, boundary_node_id, created_at) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(conversationA, "summary", 2, nodeA1, 123L),
            )
            database.execSQL(
                "INSERT INTO folderentity(id, name, payload) VALUES(?,?,?)",
                arrayOf<Any?>(folderId, "folder", payloadBlob),
            )
            database.execSQL("VACUUM main INTO ?", arrayOf(snapshot.absolutePath))
        }
        assertTrue(snapshot.isFile)

        val staging = File(root, "staging").apply { check(mkdirs()) }
        val objects = DatabaseObjectCapture.capture(snapshot, staging)
        assertEquals(
            setOf("conversation:$conversationA", "conversation:$conversationB", "table:folderentity"),
            objects.map { it.objectKey }.toSet(),
        )
        val conversationObject = objects.first { it.objectKey == "conversation:$conversationA" }
        assertEquals(
            listOf("conversationentity", "message_node", "compression_checkpoint"),
            readDbObjectPayload(conversationObject.file).tables.keys.toList(),
        )

        // 当前库：多一列 future_column、多一张本机表 app_new_table、且有一条将被快照删除的旧会话。
        val base = File(root, "base.db")
        schema(base, withFutureColumn = true).use { database ->
            insertConversation(database, oldConversation, "old")
            database.execSQL("INSERT INTO folderentity(id, name, payload) VALUES(?,?,?)", arrayOf(UUID.randomUUID().toString(), "old", null))
            database.execSQL("CREATE TABLE app_new_table(id INTEGER PRIMARY KEY, value TEXT)")
            database.execSQL("INSERT INTO app_new_table(value) VALUES('keep')")
        }
        DatabaseObjectMerge.merge(base, objects.map { it.objectKey to it.file }, 58)

        SQLiteDatabase.openDatabase(base.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val conversations = mutableSetOf<String>()
            database.rawQuery("SELECT id, future_column FROM conversationentity", null).use { cursor ->
                while (cursor.moveToNext()) {
                    conversations += cursor.getString(0)
                    assertEquals("base-default", cursor.getString(1))
                }
            }
            assertEquals(setOf(conversationA, conversationB), conversations)
            assertEquals(3, count(database, "message_node"))
            assertEquals(1, count(database, "compression_checkpoint"))
            database.rawQuery("SELECT content FROM compression_checkpoint", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("summary", cursor.getString(0))
            }
            database.rawQuery("SELECT id, payload FROM folderentity", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(folderId, cursor.getString(0))
                assertArrayEquals(payloadBlob, cursor.getBlob(1))
                assertFalse(cursor.moveToNext())
            }
            assertEquals(1, count(database, "app_new_table"))
            assertTrue(foreignKeyViolations(database).isEmpty())
        }
    }

    @Test
    fun mergeRejectsForeignKeyViolation() {
        val base = File(root, "fk-base.db")
        schema(base, withFutureColumn = false).close()
        val payload = File(root, "fk-payload.jsonl")
        val missingConversation = UUID.randomUUID().toString()
        DbObjectPayloadWriter(payload, conversationObjectKey(UUID.randomUUID().toString())).use { writer ->
            writer.start(listOf(
                DbTableSchema("conversationentity", listOf("id", "assistant_id", "title", "nodes", "create_at", "update_at")),
                DbTableSchema("message_node", listOf("id", "conversation_id", "node_index", "messages", "select_index")),
            ))
            // 只有 message_node，没有父 conversation ⇒ foreign_key_check 必须失败。
            writer.row("message_node", listOf(dbText(UUID.randomUUID().toString()), dbText(missingConversation), dbLong(0), dbText("[]"), dbLong(0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseObjectMerge.merge(base, listOf("conversation:${UUID.randomUUID()}" to payload), 58)
        }
    }

    @Test
    fun mergeRejectsVersionMismatch() {
        val base = File(root, "version-base.db")
        schema(base, withFutureColumn = false).use { it.execSQL("PRAGMA user_version = 57") }
        val payload = File(root, "version-payload.jsonl")
        DbObjectPayloadWriter(payload, tableObjectKey("folderentity")).use { writer ->
            writer.start(listOf(DbTableSchema("folderentity", listOf("id", "name"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DatabaseObjectMerge.merge(base, listOf(tableObjectKey("folderentity") to payload), 58)
        }
    }

    private fun count(database: SQLiteDatabase, table: String): Int =
        database.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    private fun foreignKeyViolations(database: SQLiteDatabase): List<String> {
        val violations = ArrayList<String>()
        database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            while (cursor.moveToNext()) violations += cursor.getString(0)
        }
        return violations
    }
}
