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
 * 44→45 真实 AutoMigration：只新增 `translation_history` 一张表，不重建任何表、不回填数据。
 *
 * 只在 UUID 命名的测试自有数据库上操作；生产库/备份路径不碰。
 */
@RunWith(AndroidJUnit4::class)
class Migration_44_45_Test {
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
    fun migrate44To45_addsTranslationHistoryTableMatchingSchema45() {
        val name = newName()
        helper.createDatabase(name, 44).use { db ->
            assertEquals(44, db.version)
            assertFalse("v44 不该已经有 translation_history", TRANSLATION_TABLE in tableNames(db))
        }

        // 不显式传 Migration：MigrationTestHelper 会跑 AppDatabase 注册的 AutoMigration(44→45)。
        helper.runMigrationsAndValidate(name, 45, true).use { db ->
            assertEquals(45, db.version)
            assertTrue("迁移后必须有 translation_history", TRANSLATION_TABLE in tableNames(db))

            assertEquals(EXPECTED_COLUMNS, readColumns(db, TRANSLATION_TABLE))
            assertEquals(
                mapOf("index_translation_history_create_at" to listOf("create_at")),
                createdIndices(db, TRANSLATION_TABLE),
            )
            assertEquals("纯新增表升级后应为空", 0, count(db, TRANSLATION_TABLE))
        }
    }

    @Test
    fun migrate44To45_preservesLegacyDataAndOnlyAppendsTranslationHistory() {
        val name = newName()
        val legacyTableSql: Map<String, String?>
        val legacyTableNames: Set<String>

        helper.createDatabase(name, 44).use { db ->
            assertEquals(44, db.version)
            seedLegacyRows(db)
            legacyTableSql = readTableSql(db)
            legacyTableNames = legacyTableSql.keys
            assertTrue(legacyTableNames.containsAll(listOf("ConversationEntity", "usage_record", "message_node")))
            assertFalse(TRANSLATION_TABLE in legacyTableNames)
            assertLegacyRows(db)
        }

        helper.runMigrationsAndValidate(name, 45, true).use { db ->
            assertEquals(45, db.version)

            // 非破坏性：所有旧表的 CREATE 语句逐字不变，只在末尾多一张表。
            val migratedTableSql = readTableSql(db)
            legacyTableSql.forEach { (table, sql) ->
                assertEquals("迁移不得重建旧表 $table", sql, migratedTableSql[table])
            }
            assertEquals(legacyTableNames + TRANSLATION_TABLE, migratedTableSql.keys)

            // 升级前的数据原样保留。
            assertLegacyRows(db)

            // 新表可写，且每一列都按 45 的列名/类型落盘。
            insertTranslationHistory(db)
            assertTranslationHistory(db)
        }
    }

    private fun seedLegacyRows(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO ConversationEntity " +
                "(id,title,nodes,create_at,update_at,is_pinned,folder_id,memory_disabled) " +
                "VALUES ('conv-legacy-44','legacy conversation','[]',1000,2000,1,'legacy-folder',1)",
        )
        db.execSQL(
            "INSERT INTO message_node (id,conversation_id,node_index,messages,select_index) " +
                "VALUES ('node-legacy-44','conv-legacy-44',0,'[]',0)",
        )
        // v44 才有的用量流水：升级不得动它（43→44 回填出来的历史花费不能因为 45 消失）。
        db.execSQL(
            "INSERT INTO usage_record " +
                "(id,created_at,source,provider_id,provider_name,model_id,model_name,assistant_id," +
                "conversation_id,prompt_tokens,completion_tokens,cached_tokens) " +
                "VALUES (9001,1234,'CHAT','provider-1','Provider One','model-1','Model One'," +
                "'assistant-1','conv-legacy-44',100,20,5)",
        )
    }

    private fun assertLegacyRows(db: SupportSQLiteDatabase) {
        assertEquals(1, count(db, "ConversationEntity"))
        assertEquals(1, count(db, "message_node"))
        assertEquals(1, count(db, "usage_record"))

        db.query("SELECT * FROM ConversationEntity WHERE id='conv-legacy-44'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy conversation", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("nodes")))
            assertEquals(1000L, cursor.getLong(cursor.getColumnIndexOrThrow("create_at")))
            assertEquals(2000L, cursor.getLong(cursor.getColumnIndexOrThrow("update_at")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("is_pinned")))
            assertEquals("legacy-folder", cursor.getString(cursor.getColumnIndexOrThrow("folder_id")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("memory_disabled")))
        }
        db.query("SELECT * FROM usage_record WHERE id=9001").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1234L, cursor.getLong(cursor.getColumnIndexOrThrow("created_at")))
            assertEquals("CHAT", cursor.getString(cursor.getColumnIndexOrThrow("source")))
            assertEquals(100, cursor.getInt(cursor.getColumnIndexOrThrow("prompt_tokens")))
            assertEquals(20, cursor.getInt(cursor.getColumnIndexOrThrow("completion_tokens")))
            assertEquals(5, cursor.getInt(cursor.getColumnIndexOrThrow("cached_tokens")))
        }
        db.query("SELECT * FROM message_node WHERE id='node-legacy-44'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("conv-legacy-44", cursor.getString(cursor.getColumnIndexOrThrow("conversation_id")))
            assertEquals("[]", cursor.getString(cursor.getColumnIndexOrThrow("messages")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("select_index")))
        }
    }

    private fun insertTranslationHistory(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO translation_history " +
                "(id,source_text,translated_text,target_language,model_id,create_at) VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(
                "entry-45",
                "Hello world",
                "你好，世界",
                "zh-CN",
                "9f8e7d6c-0000-4000-8000-000000000045",
                1_700_000_000_123L,
            ),
        )
    }

    private fun assertTranslationHistory(db: SupportSQLiteDatabase) {
        assertEquals(1, count(db, TRANSLATION_TABLE))
        db.query("SELECT * FROM translation_history WHERE id='entry-45'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Hello world", cursor.getString(cursor.getColumnIndexOrThrow("source_text")))
            assertEquals("你好，世界", cursor.getString(cursor.getColumnIndexOrThrow("translated_text")))
            assertEquals("zh-CN", cursor.getString(cursor.getColumnIndexOrThrow("target_language")))
            assertEquals(
                "9f8e7d6c-0000-4000-8000-000000000045",
                cursor.getString(cursor.getColumnIndexOrThrow("model_id")),
            )
            assertEquals(1_700_000_000_123L, cursor.getLong(cursor.getColumnIndexOrThrow("create_at")))
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
                            primaryKey = cursor.getInt(5) != 0,
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

    /** 业务表；Room/SQLite 自己的记账表不算迁移契约。 */
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
        val primaryKey: Boolean = false,
    )

    private companion object {
        const val PREFIX = "translation-history-migration-test-"
        const val TRANSLATION_TABLE = "translation_history"

        /** 与 45.json 里 Room 生成的 `translation_history` 表完全一致（列名/类型/可空性/主键）。 */
        val EXPECTED_COLUMNS = listOf(
            ColumnSpec("id", "TEXT", notNull = true, primaryKey = true),
            ColumnSpec("source_text", "TEXT", notNull = true),
            ColumnSpec("translated_text", "TEXT", notNull = true),
            ColumnSpec("target_language", "TEXT", notNull = true),
            ColumnSpec("model_id", "TEXT", notNull = true),
            ColumnSpec("create_at", "INTEGER", notNull = true),
        )
    }
}
