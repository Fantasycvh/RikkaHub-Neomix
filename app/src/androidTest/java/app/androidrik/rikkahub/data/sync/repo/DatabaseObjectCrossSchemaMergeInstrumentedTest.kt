package app.androidrik.rikkahub.data.sync.repo

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 跨 schema 合并：旧版本逻辑对象（列子集、已删除的表）合并进当前版本库副本。
 *
 * 断言：旧数据插入、新增列取默认值、当前版本新增的表被清空、已删除的表跳过并上报 warning、
 * FK/完整性通过；更新版本的快照被拒绝。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseObjectCrossSchemaMergeInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun baseDb(name: String): File {
        val file = File(context.cacheDir, "cross-$name-${UUID.randomUUID()}.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE conversationentity (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, " +
                    "update_at INTEGER NOT NULL, new_column TEXT NOT NULL DEFAULT 'def')",
            )
            db.execSQL(
                "CREATE TABLE message_node (id TEXT PRIMARY KEY NOT NULL, conversation_id TEXT NOT NULL, " +
                    "messages TEXT NOT NULL)",
            )
            db.execSQL("CREATE TABLE table_new (id TEXT PRIMARY KEY NOT NULL, content TEXT NOT NULL)")
            db.execSQL("INSERT INTO table_new (id, content) VALUES ('stale', 'must-be-cleared')")
            db.execSQL("PRAGMA user_version = 59")
        }
        return file
    }

    private fun conversationPayload(id: String, nodeId: String): Pair<String, File> {
        val file = File(context.cacheDir, "cross-conv-${UUID.randomUUID()}.jsonl")
        DbObjectPayloadWriter(file, conversationObjectKey(id)).use { writer ->
            writer.start(
                listOf(
                    DbTableSchema("conversationentity", listOf("id", "title", "update_at")),
                    DbTableSchema("message_node", listOf("id", "conversation_id", "messages")),
                ),
            )
            writer.row("conversationentity", listOf(dbText(id), dbText("restored title"), dbLong(1L)))
            writer.row("message_node", listOf(dbText(nodeId), dbText(id), dbText("[\"hi\"]")))
        }
        return conversationObjectKey(id) to file
    }

    private fun removedTablePayload(): Pair<String, File> {
        val file = File(context.cacheDir, "cross-legacy-${UUID.randomUUID()}.jsonl")
        DbObjectPayloadWriter(file, tableObjectKey("legacy_removed")).use { writer ->
            writer.start(listOf(DbTableSchema("legacy_removed", listOf("id", "content"))))
            writer.row("legacy_removed", listOf(dbText("x"), dbText("y")))
        }
        return tableObjectKey("legacy_removed") to file
    }

    @Test
    fun oldSnapshotMergesIntoCurrentSchema() {
        val base = baseDb("current")
        val conversationId = UUID.randomUUID().toString()
        val warnings = ArrayList<String>()
        val objects = listOf(conversationPayload(conversationId, UUID.randomUUID().toString()), removedTablePayload())

        val merged = DatabaseObjectMerge.merge(
            baseDb = base,
            objects = objects,
            currentDbVersion = 59,
            snapshotDbVersion = 58,
            onWarning = { warnings += it },
        )

        SQLiteDatabase.openDatabase(merged.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { c -> c.moveToFirst(); assertEquals(59, c.getInt(0)) }
            db.rawQuery("SELECT title, new_column FROM conversationentity WHERE id = ?", arrayOf(conversationId)).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("restored title", c.getString(0))
                assertEquals("def", c.getString(1))
            }
            db.rawQuery("SELECT COUNT(*) FROM message_node", null).use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.rawQuery("SELECT COUNT(*) FROM table_new", null).use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.rawQuery("PRAGMA foreign_key_check", null).use { c -> assertTrue(!c.moveToNext()) }
            db.rawQuery("PRAGMA integrity_check", null).use { c -> c.moveToFirst(); assertEquals("ok", c.getString(0)) }
        }
        assertTrue("warnings=$warnings", warnings.any { it.contains("legacy_removed") })
        assertTrue("warnings=$warnings", warnings.any { it.contains("new_column") })
        assertTrue("warnings=$warnings", warnings.any { it.contains("v58") && it.contains("v59") })
    }

    @Test
    fun newerSnapshotIsRejected() {
        val base = baseDb("newer")
        val objects = listOf(conversationPayload(UUID.randomUUID().toString(), UUID.randomUUID().toString()))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            DatabaseObjectMerge.merge(base, objects, currentDbVersion = 59, snapshotDbVersion = 60)
        }
        assertTrue(failure.message.orEmpty().contains("newer"))
    }

    @Test
    fun baseVersionMustMatchCurrentApp() {
        val base = baseDb("mismatch")
        val objects = listOf(conversationPayload(UUID.randomUUID().toString(), UUID.randomUUID().toString()))
        val failure = assertThrows(IllegalArgumentException::class.java) {
            DatabaseObjectMerge.merge(base, objects, currentDbVersion = 58, snapshotDbVersion = 58)
        }
        assertTrue(failure.message.orEmpty().contains("does not match"))
    }
}
