package app.androidrik.rikkahub.data.db.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class Migration_11_12_Test {
    private val TEST_DB = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate11To12_createsMessageNodeTableWithCorrectSchema() {
        // 创建版本 11 的数据库
        helper.createDatabase(TEST_DB, 11).apply {
            close()
        }

        // 运行迁移到版本 12
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        // 验证表结构
        val cursor = db.query("SELECT * FROM message_node LIMIT 0")
        val columnNames = cursor.columnNames.toList()
        cursor.close()

        assertTrue("message_node table should exist", columnNames.isNotEmpty())
        assertTrue("Should have 'id' column", columnNames.contains("id"))
        assertTrue("Should have 'conversation_id' column", columnNames.contains("conversation_id"))
        assertTrue("Should have 'node_index' column", columnNames.contains("node_index"))
        assertTrue("Should have 'messages' column", columnNames.contains("messages"))
        assertTrue("Should have 'select_index' column", columnNames.contains("select_index"))

        db.close()
    }

    @Test
    fun migrate11To12_migratesSimpleConversationCorrectly() {
        // 准备测试数据
        val conversationId = Uuid.random().toString()
        val messageNodes = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("Hello"))
                    )
                ),
                selectIndex = 0
            ),
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Hi there!")),
                        modelId = Uuid.random(),
                        usage = TokenUsage(promptTokens = 10, completionTokens = 5)
                    )
                ),
                selectIndex = 0
            )
        )
        val nodesJson = JsonInstant.encodeToString(messageNodes)

        // 创建版本 11 的数据库并插入数据
        helper.createDatabase(TEST_DB, 11).apply {
            val values = ContentValues().apply {
                put("id", conversationId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Test Conversation")
                put("nodes", nodesJson)
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values)
            close()
        }

        // 运行迁移
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        // 验证迁移结果
        val cursor = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC",
            arrayOf(conversationId)
        )

        assertEquals("Should have migrated 2 message nodes", 2, cursor.count)

        // 验证第一个节点
        assertTrue(cursor.moveToFirst())
        val firstNodeId = cursor.getString(cursor.getColumnIndex("id"))
        val firstConversationId = cursor.getString(cursor.getColumnIndex("conversation_id"))
        val firstNodeIndex = cursor.getInt(cursor.getColumnIndex("node_index"))
        val firstMessagesJson = cursor.getString(cursor.getColumnIndex("messages"))
        val firstSelectIndex = cursor.getInt(cursor.getColumnIndex("select_index"))

        assertNotNull("First node should have ID", firstNodeId)
        assertEquals("Conversation ID should match", conversationId, firstConversationId)
        assertEquals("First node index should be 0", 0, firstNodeIndex)
        assertEquals("First node selectIndex should be 0", 0, firstSelectIndex)

        val firstMessages = JsonInstant.decodeFromString<List<UIMessage>>(firstMessagesJson)
        assertEquals("First node should have 1 message", 1, firstMessages.size)
        assertEquals("First message should be from USER", MessageRole.USER, firstMessages[0].role)
        assertEquals(
            "First message content should match",
            "Hello",
            (firstMessages[0].parts[0] as UIMessagePart.Text).text
        )

        // 验证第二个节点
        assertTrue(cursor.moveToNext())
        val secondNodeIndex = cursor.getInt(cursor.getColumnIndex("node_index"))
        val secondMessagesJson = cursor.getString(cursor.getColumnIndex("messages"))

        assertEquals("Second node index should be 1", 1, secondNodeIndex)

        val secondMessages = JsonInstant.decodeFromString<List<UIMessage>>(secondMessagesJson)
        assertEquals("Second node should have 1 message", 1, secondMessages.size)
        assertEquals(
            "Second message should be from ASSISTANT",
            MessageRole.ASSISTANT,
            secondMessages[0].role
        )
        assertEquals(
            "Second message content should match",
            "Hi there!",
            (secondMessages[0].parts[0] as UIMessagePart.Text).text
        )

        cursor.close()

        // 验证原 conversationentity 表中的 nodes 字段已被清空
        val conversationCursor = db.query(
            "SELECT nodes FROM conversationentity WHERE id = ?",
            arrayOf(conversationId)
        )
        assertTrue(conversationCursor.moveToFirst())
        val updatedNodes = conversationCursor.getString(0)
        assertEquals("Original nodes should be cleared to empty array", "[]", updatedNodes)
        conversationCursor.close()

        db.close()
    }

    @Test
    fun migrate11To12_handlesBranchedMessages() {
        // 准备有分支的测试数据（一个节点有多个备选消息）
        val conversationId = Uuid.random().toString()
        val messageNodes = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Response 1")),
                        modelId = Uuid.random()
                    ),
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Response 2")),
                        modelId = Uuid.random()
                    ),
                    UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(UIMessagePart.Text("Response 3")),
                        modelId = Uuid.random()
                    )
                ),
                selectIndex = 1 // 选择第二个消息
            )
        )
        val nodesJson = JsonInstant.encodeToString(messageNodes)

        // 创建版本 11 的数据库并插入数据
        helper.createDatabase(TEST_DB, 11).apply {
            val values = ContentValues().apply {
                put("id", conversationId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Branched Conversation")
                put("nodes", nodesJson)
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values)
            close()
        }

        // 运行迁移
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        // 验证结果
        val cursor = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId)
        )

        assertEquals("Should have migrated 1 message node", 1, cursor.count)
        assertTrue(cursor.moveToFirst())

        val messagesJson = cursor.getString(cursor.getColumnIndex("messages"))
        val selectIndex = cursor.getInt(cursor.getColumnIndex("select_index"))

        val messages = JsonInstant.decodeFromString<List<UIMessage>>(messagesJson)
        assertEquals("Node should have 3 messages", 3, messages.size)
        assertEquals("selectIndex should be preserved", 1, selectIndex)
        assertEquals(
            "Should preserve all message variants",
            "Response 2",
            (messages[1].parts[0] as UIMessagePart.Text).text
        )

        cursor.close()
        db.close()
    }

    @Test
    fun migrate11To12_handlesEmptyConversations() {
        // 准备空节点列表的测试数据
        val conversationId = Uuid.random().toString()
        val nodesJson = "[]"

        // 创建版本 11 的数据库并插入数据
        helper.createDatabase(TEST_DB, 11).apply {
            val values = ContentValues().apply {
                put("id", conversationId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Empty Conversation")
                put("nodes", nodesJson)
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values)
            close()
        }

        // 运行迁移
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        // 验证结果
        val cursor = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId)
        )

        assertEquals("Empty conversation should have no message nodes", 0, cursor.count)
        cursor.close()

        // 验证 conversation 仍然存在
        val conversationCursor = db.query(
            "SELECT id FROM conversationentity WHERE id = ?",
            arrayOf(conversationId)
        )
        assertEquals("Conversation should still exist", 1, conversationCursor.count)
        conversationCursor.close()

        db.close()
    }

    @Test
    fun migrate11To12_handlesMultipleConversations() {
        // 准备多个对话的测试数据
        val conversationId1 = Uuid.random().toString()
        val conversationId2 = Uuid.random().toString()

        val nodes1 = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("Conversation 1"))
                    )
                ),
                selectIndex = 0
            )
        )

        val nodes2 = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("Conversation 2 - Message 1"))
                    )
                ),
                selectIndex = 0
            ),
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("Conversation 2 - Message 2"))
                    )
                ),
                selectIndex = 0
            )
        )

        // 创建版本 11 的数据库并插入数据
        helper.createDatabase(TEST_DB, 11).apply {
            val values1 = ContentValues().apply {
                put("id", conversationId1)
                put("assistant_id", Uuid.random().toString())
                put("title", "Conversation 1")
                put("nodes", JsonInstant.encodeToString(nodes1))
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values1)

            val values2 = ContentValues().apply {
                put("id", conversationId2)
                put("assistant_id", Uuid.random().toString())
                put("title", "Conversation 2")
                put("nodes", JsonInstant.encodeToString(nodes2))
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, values2)
            close()
        }

        // 运行迁移
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        // 验证第一个对话的节点数
        val cursor1 = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId1)
        )
        assertEquals("Conversation 1 should have 1 message node", 1, cursor1.count)
        cursor1.close()

        // 验证第二个对话的节点数
        val cursor2 = db.query(
            "SELECT * FROM message_node WHERE conversation_id = ?",
            arrayOf(conversationId2)
        )
        assertEquals("Conversation 2 should have 2 message nodes", 2, cursor2.count)
        cursor2.close()

        // 验证总节点数
        val cursorAll = db.query("SELECT * FROM message_node")
        assertEquals("Total should have 3 message nodes", 3, cursorAll.count)
        cursorAll.close()

        db.close()
    }

    @Test
    fun migrate11To12_createsIndexOnConversationId() {
        // 创建版本 11 的数据库
        helper.createDatabase(TEST_DB, 11).apply {
            close()
        }

        // 运行迁移
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        // 验证索引是否创建
        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='message_node' AND name='index_message_node_conversation_id'"
        )

        assertTrue("Index on conversation_id should exist", cursor.count > 0)
        cursor.close()
        db.close()
    }

    @Test
    fun migrate11To12_handlesVeryLargeConversations() {
        // 准备一个超大对话和一个普通对话
        val largeConversationId = Uuid.random().toString()
        val normalConversationId = Uuid.random().toString()

        // 创建一个包含大量消息节点的超大对话（模拟 SQLiteBlobTooBigException 场景）
        val largeNodes = buildList {
            repeat(5000) { i ->
                add(
                    MessageNode(
                        id = Uuid.random(),
                        messages = listOf(
                            UIMessage(
                                role = MessageRole.USER,
                                parts = listOf(
                                    UIMessagePart.Text(
                                        "Message $i with some content to increase size " + "x".repeat(
                                            100
                                        )
                                    )
                                )
                            ),
                            UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = listOf(
                                    UIMessagePart.Text(
                                        "Response $i with some content to increase size " + "y".repeat(
                                            100
                                        )
                                    )
                                ),
                                modelId = Uuid.random()
                            )
                        ),
                        selectIndex = 0
                    )
                )
            }
        }

        val normalNodes = listOf(
            MessageNode(
                id = Uuid.random(),
                messages = listOf(
                    UIMessage(
                        role = MessageRole.USER,
                        parts = listOf(UIMessagePart.Text("Normal conversation message"))
                    )
                ),
                selectIndex = 0
            )
        )

        // 创建版本 11 的数据库并插入数据
        helper.createDatabase(TEST_DB, 11).apply {
            // 插入超大对话
            val largeValues = ContentValues().apply {
                put("id", largeConversationId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Very Large Conversation")
                put("nodes", JsonInstant.encodeToString(largeNodes))
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, largeValues)

            // 插入普通对话
            val normalValues = ContentValues().apply {
                put("id", normalConversationId)
                put("assistant_id", Uuid.random().toString())
                put("title", "Normal Conversation")
                put("nodes", JsonInstant.encodeToString(normalNodes))
                put("truncate_index", -1)
                put("suggestions", "[]")
                put("is_pinned", 0)
                put("create_at", Instant.now().toEpochMilli())
                put("update_at", Instant.now().toEpochMilli())
            }
            insert("conversationentity", SQLiteDatabase.CONFLICT_NONE, normalValues)
            close()
        }

        // 运行迁移 - 应该不会失败，即使超大对话无法处理
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, Migration_11_12)

        fun countOf(sql: String, args: Array<Any?> = emptyArray()): Int =
            db.query(sql, args).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }

        // 只取计数：`SELECT *` 会把每行的 messages 读进 CursorWindow，5000 行本身也可能超窗。
        val largeNodesMigrated = countOf(
            "SELECT COUNT(*) FROM message_node WHERE conversation_id = ?",
            arrayOf<Any?>(largeConversationId),
        )
        val normalNodesMigrated = countOf(
            "SELECT COUNT(*) FROM message_node WHERE conversation_id = ?",
            arrayOf<Any?>(normalConversationId),
        )
        assertEquals("Normal conversation should be migrated successfully", 1, normalNodesMigrated)
        assertEquals("Both conversations should still exist", 2, countOf("SELECT COUNT(*) FROM conversationentity"))

        // **不要 `getString(nodes)`**：超大对话的 nodes 是几 MB 的 TEXT，超过 CursorWindow
        // （约 2 MB）会抛 SQLiteBlobTooBigException，测试读不回来。改由 SQLite 自己比较/计数，
        // 只把标量结果放进 cursor——语义仍是「被跳过的保留原文、迁移成功的清空」。
        val (largeNodesLength, largeNodesCleared) = db.query(
            "SELECT length(nodes), CASE WHEN nodes = '[]' THEN 1 ELSE 0 END " +
                "FROM conversationentity WHERE id = ?",
            arrayOf(largeConversationId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0) to (cursor.getInt(1) == 1)
        }
        val normalNodesCleared = db.query(
            "SELECT CASE WHEN nodes = '[]' THEN 1 ELSE 0 END FROM conversationentity WHERE id = ?",
            arrayOf(normalConversationId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0) == 1
        }
        assertTrue("Normal conversation nodes should be cleared", normalNodesCleared)

        // blob 超过窗口 ⇒ 迁移跳过：nodes 原文保留、message_node 为空；窗口更大的设备若迁移成功，
        // 则 5000 个节点都应存在且 nodes 被清空。两种结果都自洽，但不能出现「一半」。
        if (largeNodesMigrated == 0) {
            assertFalse("Skipped large conversation must keep its nodes", largeNodesCleared)
            assertTrue("Skipped large conversation must retain its JSON", largeNodesLength > 0)
        } else {
            assertEquals("All 5000 nodes should migrate when the blob is readable", 5000, largeNodesMigrated)
            assertTrue("Migrated large conversation nodes should be cleared", largeNodesCleared)
        }

        Log.i(
            "Migration_11_12_Test",
            "Large conversation migration result: $largeNodesMigrated nodes migrated, " +
                "nodesLength=$largeNodesLength, cleared=$largeNodesCleared",
        )

        db.close()
    }
}
