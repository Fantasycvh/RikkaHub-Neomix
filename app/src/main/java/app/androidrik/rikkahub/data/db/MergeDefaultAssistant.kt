package app.androidrik.rikkahub.data.db

import androidx.room.withTransaction
import app.androidrik.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import app.androidrik.rikkahub.data.datastore.LEGACY_EXAMPLE_ASSISTANT_ID
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** No row deletion: preserve conversation contents, folder membership, memory and task history. */
internal suspend fun AppDatabase.mergeDefaultAssistantReferences() = withTransaction {
    val from = LEGACY_EXAMPLE_ASSISTANT_ID.toString()
    val to = DEFAULT_ASSISTANT_ID.toString()
    val db = openHelper.writableDatabase
    // History/trash validate the identity embedded inside their JSON snapshot when restoring.
    for ((table, key) in listOf("memory_history" to "id", "memory_trash" to "memory_id")) {
        val snapshots = db.query("SELECT `$key`, snapshot FROM `$table` WHERE assistant_id = ?", arrayOf(from)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1))
            }
        }
        for ((id, snapshot) in snapshots) {
            val obj = JsonInstant.parseToJsonElement(snapshot) as JsonObject
            check(obj["assistantId"] == JsonPrimitive(from)) { "Memory snapshot identity mismatch during assistant merge" }
            val migrated = JsonObject(obj + ("assistantId" to JsonPrimitive(to))).toString()
            db.execSQL("UPDATE `$table` SET snapshot = ? WHERE `$key` = ?", arrayOf<Any>(migrated, id))
        }
    }
    // Only re-rank tiers receiving conversations; keep each original list's relative order.
    for (pinned in listOf(0, 1)) {
        val sourceIds = conversationDao().getTierIds(from, pinned == 1)
        if (sourceIds.isEmpty()) continue
        if (conversationDao().isSortOrderMaterialized(from, pinned == 1) ||
            conversationDao().isSortOrderMaterialized(to, pinned == 1)
        ) {
            val ids = conversationDao().getTierIds(to, pinned == 1) + sourceIds
            ids.forEachIndexed { index, id ->
                db.execSQL("UPDATE ConversationEntity SET sort_order = ? WHERE id = ?", arrayOf<Any>((index + 1L) * 1000, id))
            }
        }
    }
    var moved = 0
    for ((table, column) in listOf(
        "ConversationEntity" to "assistant_id",
        "conversation_folder" to "assistant_id",
        "MemoryEntity" to "assistant_id",
        "memory_history" to "assistant_id",
        "memory_trash" to "assistant_id",
        "memory_proposal" to "memory_id",
        "scheduled_tasks" to "assistant_id",
        "usage_record" to "assistant_id",
        "agent_run" to "profile_assistant_id",
    )) {
        db.compileStatement("UPDATE `$table` SET `$column` = ? WHERE `$column` = ?").use { statement ->
            statement.bindString(1, to)
            statement.bindString(2, from)
            moved += statement.executeUpdateDelete()
        }
    }
    // A merged library needs a fresh review; cursor keys may already exist in both libraries.
    if (moved > 0) db.execSQL("DELETE FROM memory_review_cursor WHERE memory_id IN (?, ?)", arrayOf(from, to))
}
