package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.MemoryFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryFileDAO {
    @Query("SELECT * FROM assistant_memory_file WHERE store_id = :storeId ORDER BY path")
    fun observe(storeId: String): Flow<List<MemoryFileEntity>>

    @Query("SELECT * FROM assistant_memory_file WHERE store_id = :storeId ORDER BY path")
    suspend fun list(storeId: String): List<MemoryFileEntity>

    @Query("SELECT * FROM assistant_memory_file WHERE store_id = :storeId AND path = :path")
    suspend fun get(storeId: String, path: String): MemoryFileEntity?

    @Query("SELECT COUNT(*) FROM assistant_memory_file WHERE store_id = :storeId")
    suspend fun count(storeId: String): Int

    @Insert
    suspend fun insert(file: MemoryFileEntity)

    @Query("UPDATE assistant_memory_file SET content = :content, revision = :revision, updated_at = :updatedAt WHERE store_id = :storeId AND path = :path AND revision = :expected")
    suspend fun update(storeId: String, path: String, expected: String, content: String, revision: String, updatedAt: Long): Int

    @Query(
        "UPDATE assistant_memory_file SET path = :destination, content = :content, revision = :revision, updated_at = :updatedAt " +
            "WHERE store_id = :storeId AND path = :source AND revision = :expected"
    )
    suspend fun move(storeId: String, source: String, destination: String, expected: String, content: String, revision: String, updatedAt: Long): Int

    @Query("DELETE FROM assistant_memory_file WHERE store_id = :storeId AND path = :path AND revision = :expected")
    suspend fun delete(storeId: String, path: String, expected: String): Int

    @Query("DELETE FROM assistant_memory_file WHERE store_id = :storeId")
    suspend fun deleteStore(storeId: String)
}
