package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.MemoryTrashEntity

@Dao
interface MemoryTrashDAO {
    @Insert
    suspend fun insert(tombstone: MemoryTrashEntity)

    @Query("SELECT * FROM memory_trash WHERE assistant_id = :storeId ORDER BY deleted_at DESC, memory_id DESC")
    suspend fun list(storeId: String): List<MemoryTrashEntity>

    @Query("SELECT * FROM memory_trash WHERE assistant_id = :storeId AND memory_id = :memoryId")
    suspend fun get(storeId: String, memoryId: Int): MemoryTrashEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM memory_trash WHERE public_id = :publicId)")
    suspend fun publicIdExists(publicId: String): Boolean

    @Query("DELETE FROM memory_trash WHERE assistant_id = :storeId AND memory_id = :memoryId AND revision = :revision")
    suspend fun delete(storeId: String, memoryId: Int, revision: Long): Int
}
