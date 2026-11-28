package app.androidrik.rikkahub.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.GenMediaEntity

@Dao
interface GenMediaDAO {
    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    fun getAll(): PagingSource<Int, GenMediaEntity>

    @Query("SELECT * FROM genmediaentity ORDER BY create_at DESC")
    suspend fun getAllMedia(): List<GenMediaEntity>

    @Insert
    suspend fun insert(media: GenMediaEntity)

    @Query("""
        INSERT INTO genmediaentity (path, model_id, prompt, create_at, type, source_paths)
        SELECT :path, :model, :prompt, :createdAt, :type, :sources
        WHERE NOT EXISTS (SELECT 1 FROM genmediaentity WHERE path = :path)
    """)
    suspend fun insertIfMissing(path: String, model: String, prompt: String, createdAt: Long, type: String, sources: String?)

    @Query("DELETE FROM genmediaentity WHERE id = :id")
    suspend fun delete(id: Int)
}
