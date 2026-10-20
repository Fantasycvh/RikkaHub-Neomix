package app.androidrik.rikkahub.data.db.migrations

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.androidrik.rikkahub.data.db.DatabaseMigrationTracker

private const val TAG = "Migration_43_44"

/**
 * 新增 `usage_record` 用量流水表，并用现有消息上的 usage **回填**，升级后统计不断档。
 *
 * 回填口径：
 * - 每条 `role = 'assistant'` 且带 `usage` 对象、且 token 非零的消息记一行，`source = 'CHAT'`；
 * - `messages` 数组里**全部分支**都算（含重新生成/编辑后被丢弃的版本）——这些请求都已经发生了，
 *   是真实花费；
 * - 时间取消息自己的 `createdAt`（本地 ISO 字符串，按设备时区换算），解析不出来时退回会话创建时间；
 * - 模型用消息上的 `modelId` + 名称快照（与 `UIMessage.modelNameSnapshot` 同一语义）。
 *
 * 字符串长度用 `substr(..., 1, 19)` 截到 `yyyy-MM-ddTHH:mm:ss`：SQLite 的日期函数接受这个
 * ISO-8601 形式，毫秒/纳秒尾巴会让它解析失败。
 */
val Migration_43_44 = object : Migration(43, 44) {
    override fun migrate(db: SupportSQLiteDatabase) {
        Log.i(TAG, "migrate: start migrate from 43 to 44 (usage records)")
        DatabaseMigrationTracker.onMigrationStart(43, 44)
        db.beginTransaction()
        try {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `usage_record` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, " +
                    "`source` TEXT NOT NULL, " +
                    "`provider_id` TEXT, " +
                    "`provider_name` TEXT, " +
                    "`model_id` TEXT, " +
                    "`model_name` TEXT, " +
                    "`assistant_id` TEXT, " +
                    "`conversation_id` TEXT, " +
                    "`prompt_tokens` INTEGER NOT NULL, " +
                    "`completion_tokens` INTEGER NOT NULL, " +
                    "`cached_tokens` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_usage_record_created_at` " +
                    "ON `usage_record` (`created_at`)"
            )

            // 损坏的 messages JSON 按空数组处理，避免一行坏数据让整个升级失败
            db.execSQL(
                """
                INSERT INTO usage_record (
                    created_at, source, provider_id, provider_name, model_id, model_name,
                    assistant_id, conversation_id,
                    prompt_tokens, completion_tokens, cached_tokens
                )
                SELECT
                    COALESCE(
                        CAST(strftime('%s', substr(json_extract(j.value, '$.createdAt'), 1, 19), 'utc') AS INTEGER) * 1000,
                        c.create_at,
                        0
                    ),
                    'CHAT',
                    NULL,
                    json_extract(j.value, '$.providerNameSnapshot'),
                    json_extract(j.value, '$.modelId'),
                    json_extract(j.value, '$.modelNameSnapshot'),
                    c.assistant_id,
                    mn.conversation_id,
                    COALESCE(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER), 0),
                    COALESCE(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER), 0),
                    COALESCE(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER), 0)
                FROM message_node mn
                LEFT JOIN ConversationEntity c ON c.id = mn.conversation_id,
                    json_each(CASE WHEN json_valid(mn.messages) THEN mn.messages ELSE '[]' END) j
                WHERE json_extract(j.value, '$.role') = 'assistant'
                    AND json_type(j.value, '$.usage') = 'object'
                    AND (
                        COALESCE(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER), 0) > 0
                        OR COALESCE(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER), 0) > 0
                    )
                """.trimIndent()
            )
            db.setTransactionSuccessful()
            Log.i(TAG, "migrate: migrate from 43 to 44 success")
        } finally {
            db.endTransaction()
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}
