package app.androidrik.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_71_72"

/**
 * message_node 加 `selected_message_id`（长会话懒加载阶段 11，本仓 DB 71→72）。
 *
 * 用途：稀疏会话的区域指纹要覆盖「原地替换选中候选、index 不变」，摘要层必须能直接拿到
 * 选中消息 id（只有 select_index 会漏检）。手写迁移而非 AutoMigration：需要 JSON1 **回填**
 * 存量行（`$[select_index].id`），AutoMigration 只能加列不能回填。
 *
 * 回填失败（messages 非法 / 下标越界 / 空候选）一律留 NULL——指纹侧会退回 select_index，
 * 与升级前的覆盖能力一致，不会因迁移把检查点判失效。
 */
val Migration_71_72 = object : Migration(71, 72) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(71, 72)
        db.beginTransaction()
        try {
            db.execSQL("ALTER TABLE `message_node` ADD COLUMN `selected_message_id` TEXT")
            db.execSQL(
                "UPDATE `message_node` SET `selected_message_id` = CASE " +
                    "WHEN json_valid(`messages`) THEN json_extract(" +
                    "`messages`, '\$[' || `select_index` || '].id') END"
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: message_node.selected_message_id added and backfilled")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
