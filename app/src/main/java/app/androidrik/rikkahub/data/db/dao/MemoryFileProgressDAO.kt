package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.androidrik.rikkahub.data.db.entity.MemoryFileProgressEntity

@Dao
interface MemoryFileProgressDAO {
    @Query("SELECT file_path FROM memory_review_cursor WHERE memory_id = :memoryId")
    suspend fun reviewPath(memoryId: String): String?

    @Query("SELECT * FROM memory_file_progress WHERE conversation_id = :conversationId AND store_id = :storeId")
    suspend fun get(conversationId: String, storeId: String): MemoryFileProgressEntity?

    @Upsert
    suspend fun upsert(progress: MemoryFileProgressEntity)

    @Query(
        """
        SELECT MAX(p.consolidated_at) FROM ConversationEntity c
        JOIN memory_file_progress p ON p.conversation_id = c.id
        WHERE c.assistant_id = :assistantId AND p.store_id = :storeId
        """
    )
    suspend fun latest(assistantId: String, storeId: String): Long?

    @Query(
        """
        SELECT c.id FROM ConversationEntity c
        LEFT JOIN memory_file_progress p ON p.conversation_id = c.id AND p.store_id = :storeId
        WHERE c.assistant_id = :assistantId
          AND c.memory_disabled = 0
          AND COALESCE(c.last_message_at, c.update_at) > MAX(COALESCE(p.consolidated_at, 0), :baseline)
        ORDER BY COALESCE(c.last_message_at, c.update_at) DESC, c.id
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun candidates(assistantId: String, storeId: String, baseline: Long, limit: Int, offset: Int): List<String>
}
