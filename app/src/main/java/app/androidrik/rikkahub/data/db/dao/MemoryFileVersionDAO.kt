package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.MemoryFileVersionEntity
import app.androidrik.rikkahub.data.db.entity.MemoryFileVersionSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryFileVersionDAO {
    @Insert
    suspend fun insert(version: MemoryFileVersionEntity)

    @Query("SELECT id, file_id, path, recorded_at FROM memory_file_version WHERE store_id = :storeId AND in_trash = 1 ORDER BY id DESC")
    fun observeTrash(storeId: String): Flow<List<MemoryFileVersionSummary>>

    @Query("SELECT id, file_id, path, recorded_at FROM memory_file_version WHERE store_id = :storeId AND file_id = :fileId AND in_trash = 0 ORDER BY id DESC")
    fun observeHistory(storeId: String, fileId: String): Flow<List<MemoryFileVersionSummary>>

    @Query("SELECT * FROM memory_file_version WHERE store_id = :storeId AND id = :id")
    suspend fun get(storeId: String, id: Long): MemoryFileVersionEntity?

    @Query("UPDATE memory_file_version SET in_trash = 0 WHERE store_id = :storeId AND id = :id AND in_trash = 1")
    suspend fun markRestored(storeId: String, id: Long): Int

    @Query(
        """DELETE FROM memory_file_version WHERE store_id = :storeId AND file_id = :fileId AND in_trash = 0
           AND id NOT IN (
               SELECT id FROM memory_file_version WHERE store_id = :storeId AND file_id = :fileId AND in_trash = 0
               ORDER BY id DESC LIMIT :keep
           )""",
    )
    suspend fun pruneHistory(storeId: String, fileId: String, keep: Int)

    @Query("DELETE FROM memory_file_version WHERE store_id = :storeId AND file_id = :fileId")
    suspend fun deleteFileVersions(storeId: String, fileId: String)

    @Query("DELETE FROM memory_file_version WHERE store_id = :storeId")
    suspend fun deleteStore(storeId: String)
}
