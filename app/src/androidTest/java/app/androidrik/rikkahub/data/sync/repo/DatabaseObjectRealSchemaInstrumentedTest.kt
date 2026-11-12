package app.androidrik.rikkahub.data.sync.repo

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.ConversationEntity
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity
import app.androidrik.rikkahub.data.db.fts.createMessageFtsTables
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import io.requery.android.database.sqlite.SQLiteCustomExtension
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * 用**真实 Room schema**（含 FTS 虚表与 requery SQLite）验证 P2 捕获/合并：
 * 1. 捕获只导出普通表 + 按会话对象，FTS 全家族被排除；
 * 2. 合并产物能通过平台 SQLite 的 `integrity_check` / `foreign_key_check`（恢复暂存校验同款）；
 * 3. 合并后的库能被 Room 正常打开（schema 未破坏）。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseObjectRealSchemaInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "db-object-real-${UUID.randomUUID()}").apply { check(mkdirs()) }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun requeryFactory() = RequerySQLiteOpenHelperFactory(
        listOf(
            RequerySQLiteOpenHelperFactory.ConfigurationOptions { options ->
                options.customExtensions.add(
                    SQLiteCustomExtension(context.applicationInfo.nativeLibraryDir + "/libsimple", null),
                )
                options
            },
        ),
    )

    private fun openRoom(name: String): AppDatabase {
        val database = Room.databaseBuilder(context, AppDatabase::class.java, File(root, name).path)
            .openHelperFactory(requeryFactory())
            .build()
        createMessageFtsTables(database.openHelper.writableDatabase)
        return database
    }

    @Test
    fun realSchemaCaptureAndMergePassRestoreChecks() {
        val conversationId = UUID.randomUUID().toString()
        val nodeId = UUID.randomUUID().toString()
        val source = openRoom("source.db")
        try {
            runBlocking {
                source.conversationDao().insert(
                    ConversationEntity(
                        id = conversationId,
                        assistantId = UUID.randomUUID().toString(),
                        title = "P2 real schema",
                        nodes = "[]",
                        createAt = 1L,
                        updateAt = 2L,
                        chatSuggestions = "[]",
                        isPinned = false,
                    ),
                )
                source.messageNodeDao().insert(
                    MessageNodeEntity(
                        id = nodeId,
                        conversationId = conversationId,
                        nodeIndex = 0,
                        messages = "[{\"role\":\"user\",\"parts\":[{\"type\":\"text\",\"text\":\"hello\"}]}]",
                        selectIndex = 0,
                    ),
                )
            }
            // memory_consolidation 挂会话 CASCADE 外键：必须进快照，否则合并会留下孤儿行让 FK 校验失败。
            val db = source.openHelper.writableDatabase
            db.execSQL("INSERT INTO MemoryEntity(id, assistant_id, content, public_id) VALUES (3, 'private', 'entry backup', '00000003')")
            db.execSQL("INSERT INTO assistant_memory_file(store_id, path, content, revision, updated_at) VALUES ('private', '/profile.md', 'file backup', 'revision', 1)")
            db.execSQL(
                "INSERT INTO memory_consolidation(conversation_id, consolidated_at) VALUES(?, ?)",
                arrayOf<Any?>(conversationId, 42L),
            )
            // FTS 里也放一行：捕获必须整族排除（可重建 + 体积最大）。
            db.execSQL(
                "INSERT INTO message_fts(text, node_id, message_id, conversation_id) VALUES(?, ?, ?, ?)",
                arrayOf("hello", nodeId, UUID.randomUUID().toString(), conversationId),
            )
            db.execSQL("INSERT INTO message_fts_rowid(fts_rowid, conversation_id) VALUES(last_insert_rowid(), ?)", arrayOf(conversationId))
            db.execSQL("VACUUM main INTO ?", arrayOf(File(root, "snapshot.db").absolutePath))
        } finally {
            source.close()
        }

        val staging = File(root, "staging").apply { check(mkdirs()) }
        val objects = DatabaseObjectCapture.capture(File(root, "snapshot.db"), staging)
        assertTrue(objects.any { it.objectKey == "conversation:$conversationId" })
        assertFalse(objects.any { it.objectKey.startsWith("table:message_fts") })
        assertFalse(objects.any { it.objectKey.startsWith("table:sqlite_") })
        assertFalse(objects.any { it.objectKey == "table:room_master_table" })
        assertTrue(objects.any { it.objectKey == "table:assistant_memory_file" })
        val consolidationObject = objects.firstOrNull { it.objectKey == "table:memory_consolidation" }
        assertNotNull("memory_consolidation must be captured (FK to ConversationEntity)", consolidationObject)
        assertEquals(1, readDbObjectPayload(consolidationObject!!.file).tables.getValue("memory_consolidation").rows.size)
        val conversationPayload = readDbObjectPayload(objects.first { it.objectKey == "conversation:$conversationId" }.file)
        // Room 默认表名是实体类名（ConversationEntity），对象内保留实际表名、比较大小写不敏感。
        assertEquals(
            listOf("conversationentity", "message_node", "compression_checkpoint"),
            conversationPayload.tables.keys.map { it.lowercase() },
        )
        assertEquals(1, conversationPayload.tables.getValue("message_node").rows.size)

        // 当前库副本：故意留一条「快照里不存在」的旧会话 + 它的记忆整理进度，模拟快照后被删除的会话。
        val staleConversation = UUID.randomUUID().toString()
        val base = openRoom("base.db")
        try {
            val db = base.openHelper.writableDatabase
            db.execSQL(
                "INSERT INTO conversationentity(id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(staleConversation, UUID.randomUUID().toString(), "stale", "[]", 3L, 4L, "[]", 0),
            )
            db.execSQL(
                "INSERT INTO memory_consolidation(conversation_id, consolidated_at) VALUES(?, ?)",
                arrayOf<Any?>(staleConversation, 7L),
            )
            db.execSQL("VACUUM main INTO ?", arrayOf(File(root, "base-copy.db").absolutePath))
        } finally {
            base.close()
        }
        val merged = DatabaseObjectMerge.merge(File(root, "base-copy.db"), objects.map { it.objectKey to it.file }, AppDatabase.SCHEMA_VERSION)

        SQLiteDatabase.openDatabase(merged.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            database.rawQuery("SELECT content FROM MemoryEntity WHERE assistant_id = 'private'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("entry backup", cursor.getString(0))
            }
            database.rawQuery("SELECT content FROM assistant_memory_file WHERE store_id = 'private' AND path = '/profile.md'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("file backup", cursor.getString(0))
            }
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(AppDatabase.SCHEMA_VERSION, cursor.getInt(0))
            }
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("ok", cursor.getString(0))
            }
            database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                assertFalse("foreign key violations in merged database", cursor.moveToNext())
            }
            database.rawQuery("SELECT COUNT(*) FROM message_node", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.rawQuery("SELECT COUNT(*) FROM conversationentity", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            // 旧会话的孤儿进度必须被整表替换清掉，且快照里的进度行被恢复。
            database.rawQuery("SELECT conversation_id, consolidated_at FROM memory_consolidation", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(conversationId, cursor.getString(0))
                assertEquals(42L, cursor.getLong(1))
                assertFalse(cursor.moveToNext())
            }
        }

        // Room 打开合并产物：schema 校验通过（说明没有漏表/错列）。
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, merged.path)
            .openHelperFactory(requeryFactory())
            .build()
        try {
            reopened.openHelper.writableDatabase.query("SELECT COUNT(*) FROM message_node").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            reopened.openHelper.writableDatabase.query("SELECT COUNT(*) FROM memory_consolidation").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            reopened.close()
        }
    }
}
