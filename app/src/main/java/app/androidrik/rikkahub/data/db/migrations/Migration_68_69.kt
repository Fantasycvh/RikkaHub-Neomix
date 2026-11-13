package app.androidrik.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_68_69"

/**
 * message_node 的索引从 (conversation_id) 换成 (conversation_id, node_index)。
 *
 * 加载会话按 node_index 排序分页读取（MessageNodeDAO 的全量 ORDER BY 与 LIMIT/OFFSET 分页两条路径），
 * 只有 conversation_id 单列索引时每一页都要把整个会话的行取出来排序，耗时随消息数平方增长，
 * 几千条消息的会话打开要好几秒（上游 c1c3c4d12 同款修复，本仓 DB 68→69）。
 * 复合索引以 conversation_id 开头，外键级联删除和按会话的查询照样能用，旧索引不再需要。
 * 索引不是唯一索引：旧数据里同一会话有重复 node_index（树形分支 P0 时代的历史行）不影响迁移与读取。
 * 只建索引、删索引，不改动表里的数据。
 */
val Migration_68_69 = object : Migration(68, 69) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(68, 69)
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` " +
                    "ON `message_node` (`conversation_id`, `node_index`)"
            )
            db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: message_node composite index swapped (conversation_id, node_index)")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
