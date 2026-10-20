package app.androidrik.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.datetime.LocalDateTime as KdxLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime as JavaLocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 43→44 手写迁移：建 `usage_record` 流水表 + 用 `message_node.messages` 里的 usage 回填。
 *
 * 回填口径是「**真实发生过的花费**」，因此除了表结构，还必须验证数值：
 * - 只回填 `role = 'assistant'` 且 prompt/completion 至少一个非 0 的消息；
 * - `messages` 数组里的**全部分支**都回填（重新生成/编辑丢弃的版本也是真花掉的钱）；
 * - `created_at` 取消息自己的本地 `createdAt`，解析不出来时退回会话 `create_at`；
 * - 损坏的 messages JSON 不能让升级失败。
 *
 * 只用 UUID 命名的测试自有数据库；生产库/备份路径不碰。
 */
@OptIn(ExperimentalUuidApi::class)
@RunWith(AndroidJUnit4::class)
class Migration_43_44_Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableListOf<String>()

    private val conversationId = "conv-usage-43"
    private val assistantId = Uuid.parse("a1b2c3d4-0000-4000-8000-000000000043")
    private val modelUuid = Uuid.parse("9f8e7d6c-0000-4000-8000-000000000044")

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
    fun migrate43To44_createsUsageRecordTableMatchingSchema44() {
        val name = newName()
        helper.createDatabase(name, 43).use { db ->
            assertEquals(43, db.version)
            assertFalse("v43 不该已经有 usage_record", tableNames(db).contains(USAGE_TABLE))
        }

        // 显式传入手写迁移：43→44 不在 AppDatabase.autoMigrations 里（需要回填数据）。
        helper.runMigrationsAndValidate(name, 44, true, Migration_43_44).use { db ->
            assertEquals(44, db.version)
            assertTrue("迁移后必须有 usage_record", tableNames(db).contains(USAGE_TABLE))
            assertEquals(EXPECTED_COLUMNS, readColumns(db, USAGE_TABLE))
            assertEquals(
                mapOf("index_usage_record_created_at" to listOf("created_at")),
                createdIndices(db, USAGE_TABLE),
            )
            assertEquals("空库升级不产生流水行", 0, count(db, USAGE_TABLE))
        }
    }

    @Test
    fun migrate43To44_backfillsEveryAssistantBranchWithRealTokenValues() {
        val name = newName()
        val conversationCreatedAt = 1_700_000_000_000L
        val firstCreatedAt = KdxLocalDateTime(2026, 8, 12, 10, 30, 15)
        val secondCreatedAt = KdxLocalDateTime(2026, 8, 12, 11, 0, 45)

        val messages = listOf(
            // 用户消息即使带 usage 也不是「模型请求的产出」，不回填
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text("question")),
                createdAt = KdxLocalDateTime(2026, 8, 12, 10, 30, 0),
                usage = TokenUsage(promptTokens = 999, completionTokens = 999),
            ),
            assistantMessage(
                createdAt = firstCreatedAt,
                usage = TokenUsage(promptTokens = 100, completionTokens = 20, cachedTokens = 10),
            ),
            // 第二个分支：重新生成后被丢弃的版本，但请求已经计费，必须回填
            assistantMessage(
                createdAt = secondCreatedAt,
                usage = TokenUsage(promptTokens = 50, completionTokens = 5, cachedTokens = 0),
            ),
            // 全 0 usage 不产生流水行
            assistantMessage(
                createdAt = KdxLocalDateTime(2026, 8, 12, 11, 5, 0),
                usage = TokenUsage(promptTokens = 0, completionTokens = 0, cachedTokens = 3),
            ),
            // 没有 usage 对象（上游没回）也不回填
            assistantMessage(
                createdAt = KdxLocalDateTime(2026, 8, 12, 11, 10, 0),
                usage = null,
            ),
        )

        helper.createDatabase(name, 43).use { db ->
            insertConversation(db, conversationCreatedAt)
            insertMessageNode(db, nodeId = "node-usage-1", messagesJson = messagesJson(messages))
        }

        helper.runMigrationsAndValidate(name, 44, true, Migration_43_44).use { db ->
            assertEquals("user 消息 + 两条有效 assistant 分支 + 两条无效 = 只回填 2 行", 2, count(db, USAGE_TABLE))

            db.query("SELECT * FROM $USAGE_TABLE ORDER BY created_at").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertUsageRow(
                    cursor = cursor,
                    expectedCreatedAt = epochMillis(firstCreatedAt),
                    expectedPrompt = 100,
                    expectedCompletion = 20,
                    expectedCached = 10,
                )
                assertTrue(cursor.moveToNext())
                assertUsageRow(
                    cursor = cursor,
                    expectedCreatedAt = epochMillis(secondCreatedAt),
                    expectedPrompt = 50,
                    expectedCompletion = 5,
                    expectedCached = 0,
                )
                assertFalse("不应有多余的回填行", cursor.moveToNext())
            }

            // 回填不得改写原消息表
            assertEquals(1, count(db, "message_node"))
            db.query("SELECT messages FROM message_node WHERE id='node-usage-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(messagesJson(messages), cursor.getString(0))
            }
        }
    }

    @Test
    fun migrate43To44_fallsBackToConversationTimeAndToleratesBrokenMessageJson() {
        val name = newName()
        val conversationCreatedAt = 1_700_000_000_000L

        // 消息上没有 createdAt（回填应退回会话创建时间）
        val strippedAssistant = (JsonInstant.encodeToJsonElement(
            assistantMessage(usage = TokenUsage(promptTokens = 7, completionTokens = 3)),
        ) as JsonObject).filterKeys { it != "createdAt" }
        val missingCreatedAtJson = JsonInstant.encodeToString(JsonArray(listOf(JsonObject(strippedAssistant))))

        helper.createDatabase(name, 43).use { db ->
            insertConversation(db, conversationCreatedAt)
            insertMessageNode(db, nodeId = "node-usage-missing-time", messagesJson = missingCreatedAtJson)
            insertMessageNode(db, nodeId = "node-usage-broken-json", messagesJson = "this is not json at all")
        }

        helper.runMigrationsAndValidate(name, 44, true, Migration_43_44).use { db ->
            assertEquals("坏 JSON 按空数组处理，只有缺时间那条回填", 1, count(db, USAGE_TABLE))
            db.query("SELECT * FROM $USAGE_TABLE").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertUsageRow(
                    cursor = cursor,
                    expectedCreatedAt = conversationCreatedAt,
                    expectedPrompt = 7,
                    expectedCompletion = 3,
                    expectedCached = 0,
                )
                assertFalse(cursor.moveToNext())
            }
        }
    }

    private fun assertUsageRow(
        cursor: android.database.Cursor,
        expectedCreatedAt: Long,
        expectedPrompt: Int,
        expectedCompletion: Int,
        expectedCached: Int,
    ) {
        assertEquals(expectedCreatedAt, cursor.getLong(cursor.getColumnIndexOrThrow("created_at")))
        assertEquals("CHAT", cursor.getString(cursor.getColumnIndexOrThrow("source")))
        // 回填没有 provider id（历史上没存），名称快照来自消息
        assertNull(cursor.getString(cursor.getColumnIndexOrThrow("provider_id")))
        assertEquals("OpenAI", cursor.getString(cursor.getColumnIndexOrThrow("provider_name")))
        assertEquals(modelUuid.toString(), cursor.getString(cursor.getColumnIndexOrThrow("model_id")))
        assertEquals("GPT Test", cursor.getString(cursor.getColumnIndexOrThrow("model_name")))
        assertEquals(assistantId.toString(), cursor.getString(cursor.getColumnIndexOrThrow("assistant_id")))
        assertEquals(conversationId, cursor.getString(cursor.getColumnIndexOrThrow("conversation_id")))
        assertEquals(expectedPrompt, cursor.getInt(cursor.getColumnIndexOrThrow("prompt_tokens")))
        assertEquals(expectedCompletion, cursor.getInt(cursor.getColumnIndexOrThrow("completion_tokens")))
        assertEquals(expectedCached, cursor.getInt(cursor.getColumnIndexOrThrow("cached_tokens")))
    }

    private fun assistantMessage(
        createdAt: KdxLocalDateTime = KdxLocalDateTime(2026, 8, 12, 10, 30, 15),
        usage: TokenUsage?,
    ): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("answer")),
        createdAt = createdAt,
        modelId = modelUuid,
        modelNameSnapshot = "GPT Test",
        providerNameSnapshot = "OpenAI",
        usage = usage,
    )

    private fun messagesJson(messages: List<UIMessage>): String = JsonInstant.encodeToString(messages)

    private fun insertConversation(db: SupportSQLiteDatabase, createAt: Long) {
        db.execSQL(
            "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                "VALUES (?,?,?,?,?,?)",
            arrayOf<Any?>(conversationId, assistantId.toString(), "usage migration", "[]", createAt, createAt),
        )
    }

    private fun insertMessageNode(db: SupportSQLiteDatabase, nodeId: String, messagesJson: String) {
        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES (?,?,?,?,?)",
            arrayOf<Any?>(nodeId, conversationId, 0, messagesJson, 0),
        )
    }

    /** 独立于迁移 SQL 的期望值：消息 `createdAt` 是本地墙钟时间，按设备时区换算回 epoch millis。 */
    private fun epochMillis(localDateTime: KdxLocalDateTime): Long =
        JavaLocalDateTime.parse(localDateTime.toString())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

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
    private fun tableNames(db: SupportSQLiteDatabase): Set<String> =
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('android_metadata','room_master_table')",
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

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
        const val PREFIX = "usage-migration-43-44-test-"
        const val USAGE_TABLE = "usage_record"

        /** 与 44.json 里 Room 生成的使用记录表完全一致（列名/类型/可空性/主键）。 */
        val EXPECTED_COLUMNS = listOf(
            ColumnSpec("id", "INTEGER", notNull = true, primaryKey = true),
            ColumnSpec("created_at", "INTEGER", notNull = true),
            ColumnSpec("source", "TEXT", notNull = true),
            ColumnSpec("provider_id", "TEXT", notNull = false),
            ColumnSpec("provider_name", "TEXT", notNull = false),
            ColumnSpec("model_id", "TEXT", notNull = false),
            ColumnSpec("model_name", "TEXT", notNull = false),
            ColumnSpec("assistant_id", "TEXT", notNull = false),
            ColumnSpec("conversation_id", "TEXT", notNull = false),
            ColumnSpec("prompt_tokens", "INTEGER", notNull = true),
            ColumnSpec("completion_tokens", "INTEGER", notNull = true),
            ColumnSpec("cached_tokens", "INTEGER", notNull = true),
        )
    }
}
