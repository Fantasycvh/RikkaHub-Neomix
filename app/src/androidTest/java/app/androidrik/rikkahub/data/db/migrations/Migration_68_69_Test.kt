package app.androidrik.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 68→69：message_node 索引 (conversation_id) → (conversation_id, node_index)。
 * 对照上游 c1c3c4d12 的 Migration_32_33_Test，按本仓 v68 schema 适配（message_node 多一列可空 parent_id）。
 */
@RunWith(AndroidJUnit4::class)
class Migration_68_69_Test {
    private val testDb = "migration-68-69-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    private fun createConvAndNodes(db: androidx.sqlite.db.SupportSQLiteDatabase, indices: List<Any?>) {
        db.execSQL(
            "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                "VALUES ('conv', 'assistant', 'Test', '[]', 1000, 1000)"
        )
        indices.forEachIndexed { i, index ->
            db.execSQL(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index, parent_id) " +
                    "VALUES (?, 'conv', ?, '[]', 0, NULL)",
                arrayOf<Any?>("node-$i", index),
            )
        }
    }

    @Test
    fun loadsNodesInOrderWithoutSorting() {
        helper.createDatabase(testDb, 68).apply {
            // 故意不按 node_index 的顺序插入
            createConvAndNodes(this, listOf(2, 0, 3, 1))
            close()
        }

        // 同时校验迁移后的表结构和索引与 69 的 schema 一致
        val db = helper.runMigrationsAndValidate(testDb, 69, true, Migration_68_69)

        val query = "SELECT * FROM message_node WHERE conversation_id = 'conv' ORDER BY node_index ASC LIMIT 2 OFFSET 1"
        val ids = db.query(query).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("id"))) }
        }
        assertEquals(listOf("node-1", "node-2"), ids)

        val plan = db.query("EXPLAIN QUERY PLAN $query").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }
        assertTrue(plan.toString(), plan.any { "index_message_node_conversation_id_node_index" in it })
        assertFalse(plan.toString(), plan.any { "TEMP B-TREE" in it })
        db.close()
    }

    // 索引不是唯一索引：旧数据里同一会话有重复 node_index 时迁移不能失败，分页读取不能漏行或重复
    @Test
    fun keepsNodesWithDuplicateIndex() {
        helper.createDatabase(testDb, 68).apply {
            createConvAndNodes(this, listOf(0, 1, 1, 1, 2))
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 69, true, Migration_68_69)

        val ids = (0 until 6 step 2).flatMap { offset ->
            db.query(
                "SELECT id FROM message_node WHERE conversation_id = 'conv' " +
                    "ORDER BY node_index ASC LIMIT 2 OFFSET $offset"
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        }
        assertEquals(listOf("node-0", "node-1", "node-2", "node-3", "node-4"), ids)
        db.close()
    }
}
