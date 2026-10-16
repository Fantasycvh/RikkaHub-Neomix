package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.androidrik.rikkahub.data.db.entity.MemoryReviewCursorEntity

/**
 * 记忆库「全量复核」的游标（每记忆库一行）。
 *
 * 为什么必须有它：整理侧的记忆库快照被预算截断（真机 49 条 ≈58,489 token vs 快照预算 4,000
 * ⇒ 约 3/4 进不了上下文），而截断后的**选取位置每轮都一样** ⇒ 反复点「整理一下」永远看同一批，
 * 剩下的永不见天日。有了游标，全量复核才能**跨多次推进**并**可断点续跑**。
 */
@Dao
interface MemoryReviewCursorDAO {

    @Query("SELECT cursor FROM memory_review_cursor WHERE memory_id = :memoryId")
    suspend fun getCursor(memoryId: String): Long?

    @Upsert
    suspend fun upsert(entry: MemoryReviewCursorEntity)

    @Query("DELETE FROM memory_review_cursor WHERE memory_id = :memoryId")
    suspend fun clear(memoryId: String)
}
