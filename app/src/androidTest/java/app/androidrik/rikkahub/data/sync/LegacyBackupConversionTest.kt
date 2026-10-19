package app.androidrik.rikkahub.data.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 原版 3.x 备份（文件式记忆）→ 当前 schema 的转换接线。
 *
 * 全部为**测试自造**的最小库：不依赖任何真实备份或生产数据，只验证转换契约
 * （家族识别、兼容表复制、Markdown 拆条、旧路径改写、未知库拒绝）。
 */
@RunWith(AndroidJUnit4::class)
class LegacyBackupConversionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val legacyFile = File(context.cacheDir, "legacy-upstream-test.db")

    @After
    fun tearDown() {
        legacyFile.delete()
        context.deleteDatabase(LegacyBackupConverter.CONVERTED_DATABASE_NAME)
    }

    @Test
    fun convertsFileMemoryAndCompatibleTables() = kotlinx.coroutines.runBlocking {
        createLegacyDatabase()
        assertEquals(LegacyDatabaseFamily.LEGACY_UPSTREAM, LegacyBackupConverter.classify(legacyFile))

        val converted = LegacyBackupConverter(context).convert(legacyFile)
        val db = openConverted(converted)
        try {
            val sqlite = db.openHelper.readableDatabase
            assertEquals(1, count(sqlite, "ConversationEntity"))
            assertEquals(1, count(sqlite, "message_node"))
            assertEquals(1, count(sqlite, "memory_consolidation"))
            sqlite.query("select title from ConversationEntity").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("旧会话", cursor.getString(0))
            }

            val memories = db.memoryDao().getMemoriesOfAssistant(ASSISTANT)
            assertEquals(listOf("用户喜欢手冲咖啡", "用户常在晚上工作"), memories.map { it.content }.sorted())
            assertTrue(memories.all { it.revision == 1L && it.publicId.isNotBlank() && !it.isAnchor })
            assertTrue(memories.all { it.updatedAt == 1_700_000_000_000L })

            val messages = sqlite.query("select messages from message_node").use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }
            assertTrue("attachment path must be rewritten", messages.contains("/app.androidrik.rikkahub/files/") ||
                messages.contains("/app.androidrik.rikkahub.debug/files/"))
            assertFalse("legacy path must be gone", messages.contains("/data/user/0/me.rerere.rikkahub/"))
            assertTrue("plain text mentioning the old package stays untouched", messages.contains("at me.rerere.rikkahub."))
        } finally {
            db.close()
        }
    }

    @Test
    fun rejectsUnknownDatabaseFamily() {
        val unknown = File(context.cacheDir, "unknown-family-test.db")
        SQLiteDatabase.openOrCreateDatabase(unknown, null).use { db ->
            db.execSQL("create table random_table (id integer primary key)")
        }
        try {
            assertEquals(LegacyDatabaseFamily.UNKNOWN, LegacyBackupConverter.classify(unknown))
        } finally {
            unknown.delete()
        }
    }

    private fun openConverted(file: File): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, file.name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                app.androidrik.rikkahub.data.db.migrations.Migration_6_7,
                app.androidrik.rikkahub.data.db.migrations.Migration_11_12,
                app.androidrik.rikkahub.data.db.migrations.Migration_13_14,
                app.androidrik.rikkahub.data.db.migrations.Migration_14_15,
                app.androidrik.rikkahub.data.db.migrations.Migration_15_16,
                app.androidrik.rikkahub.data.db.migrations.Migration_27_28,
                app.androidrik.rikkahub.data.db.migrations.Migration_28_29,
                app.androidrik.rikkahub.data.db.migrations.Migration_40_41,
                app.androidrik.rikkahub.data.db.migrations.Migration_41_42,
            )
            .build()

    private fun createLegacyDatabase() {
        legacyFile.delete()
        SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { db ->
            db.execSQL(
                """
                create table ConversationEntity (
                    id TEXT NOT NULL PRIMARY KEY, assistant_id TEXT NOT NULL, title TEXT NOT NULL,
                    nodes TEXT NOT NULL, create_at INTEGER NOT NULL, update_at INTEGER NOT NULL,
                    suggestions TEXT NOT NULL DEFAULT '[]', is_pinned INTEGER NOT NULL DEFAULT 0,
                    custom_system_prompt TEXT NOT NULL DEFAULT '',
                    mode_injection_ids TEXT NOT NULL DEFAULT '[]', lorebook_ids TEXT NOT NULL DEFAULT '[]',
                    workspace_cwd TEXT NOT NULL DEFAULT '', folder_id TEXT NOT NULL DEFAULT '',
                    config TEXT NOT NULL DEFAULT '{}'
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                create table message_node (
                    id TEXT NOT NULL PRIMARY KEY, conversation_id TEXT NOT NULL,
                    node_index INTEGER NOT NULL, messages TEXT NOT NULL, select_index INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("create table memory_consolidation (conversation_id TEXT NOT NULL, consolidated_at INTEGER NOT NULL)")
            db.execSQL(
                """
                create table memory_file (
                    assistant_id TEXT NOT NULL, path TEXT NOT NULL, content TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("create table media_creation_session (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)")

            db.execSQL(
                "insert into ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "values ('c1', ?, '旧会话', '[]', 100, 200)",
                arrayOf<Any?>(ASSISTANT),
            )
            val messages = """[{"id":"m1","role":"user","parts":[{"type":"text","text":"看这个附件"},""" +
                """{"type":"text","text":"file:///data/user/0/me.rerere.rikkahub/files/upload/a.md"},""" +
                """{"type":"text","text":"at me.rerere.rikkahub.data.ai.Tool.invoke(Tool.kt:1)"}]}]"""
            db.execSQL(
                "insert into message_node (id, conversation_id, node_index, messages, select_index) " +
                    "values ('n1', 'c1', 0, ?, 0)",
                arrayOf(messages),
            )
            db.execSQL("insert into memory_consolidation values ('c1', 300)")
            val memory = """
                ---
                name: profile
                description: Who the user is
                ---

                - [stated] 用户喜欢手冲咖啡
                - [inferred] 用户常在晚上工作
            """.trimIndent()
            db.execSQL(
                "insert into memory_file (assistant_id, path, content, updated_at) values (?, '/profile.md', ?, ?)",
                arrayOf<Any?>(ASSISTANT, memory, 1_700_000_000_000L),
            )
        }
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Int =
        db.query("select count(*) from \"$table\"").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val ASSISTANT = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"
    }
}
