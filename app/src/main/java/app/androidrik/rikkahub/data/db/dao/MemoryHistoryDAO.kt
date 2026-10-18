package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.MemoryHistoryEntity

@Dao
interface MemoryHistoryDAO {
    @Insert
    suspend fun insert(event: MemoryHistoryEntity): Long

    @Query("""
        SELECT * FROM memory_history WHERE assistant_id = :storeId AND memory_id = :memoryId
        ORDER BY id DESC
    """)
    suspend fun list(storeId: String, memoryId: Int): List<MemoryHistoryEntity>

    @Query("""
        SELECT * FROM memory_history
        WHERE assistant_id = :storeId AND memory_id = :memoryId AND revision = :revision
        ORDER BY id DESC LIMIT 1
    """)
    suspend fun getRevision(storeId: String, memoryId: Int, revision: Long): MemoryHistoryEntity?

    @Query("""
        DELETE FROM memory_history WHERE assistant_id = :storeId AND memory_id = :memoryId
        AND id NOT IN (
            SELECT id FROM memory_history WHERE assistant_id = :storeId AND memory_id = :memoryId
            ORDER BY id DESC LIMIT :keep
        )
    """)
    suspend fun prune(storeId: String, memoryId: Int, keep: Int)
}
