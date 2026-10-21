package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.TranslationHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 翻译历史表。
 *
 * 读取只回传**最近 N 条**（`LIMIT` 由调用方给，仓储层固定为保留上限），不把整张表读进内存；
 * 写入后的裁剪也在 SQLite 侧完成，避免每加一条就把全表拉回 Kotlin。
 */
@Dao
interface TranslationHistoryDAO {
    /** 按时间倒序的最多 [limit] 条，供翻译页列表观察 */
    @Query("SELECT * FROM translation_history ORDER BY create_at DESC LIMIT :limit")
    fun flowLatest(limit: Int): Flow<List<TranslationHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranslationHistoryEntity)

    @Query("DELETE FROM translation_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM translation_history")
    suspend fun deleteAll()

    /** 只留最新的 [keep] 条（保留上限的兜底裁剪） */
    @Query(
        "DELETE FROM translation_history WHERE id NOT IN " +
            "(SELECT id FROM translation_history ORDER BY create_at DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)
}
