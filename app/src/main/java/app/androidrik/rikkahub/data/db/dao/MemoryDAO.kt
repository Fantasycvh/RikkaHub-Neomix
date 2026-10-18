package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import app.androidrik.rikkahub.data.db.entity.MemoryEntity

@Dao
interface MemoryDAO {
    // 显式 ORDER BY：此前依赖 SQLite 未定义的行扫描顺序（实际等于 rowid 升序），语义脆弱。
    // 这里保持升序（= 原 de-facto 行为，记忆页展示顺序不变）；
    // 「注入哪些记忆」的新旧取舍由 buildMemoryDataPrompt 按 sortKey 自行选取，不依赖本处顺序。
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY id ASC")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY id ASC")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity ORDER BY id ASC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity ORDER BY id ASC")
    suspend fun getAllMemories(): List<MemoryEntity>

    /** 按自增 id 取（内部用：裁剪、按 id 兜底查找）。 */
    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    /** 按对外 opaque id 取（模型 edit/delete 目标、UI 审批预览）。 */
    @Query("SELECT * FROM memoryentity WHERE public_id = :publicId LIMIT 1")
    suspend fun getMemoryByPublicId(publicId: String): MemoryEntity?

    /** public_id 是否已被占用（新建时保证唯一）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM memoryentity WHERE public_id = :publicId)")
    suspend fun publicIdExists(publicId: String): Boolean

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    /** 批量插入（复制助手时整批搬运记忆，上游 `282b85f2`）。 */
    @Insert
    suspend fun insertMemories(memories: List<MemoryEntity>)

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    /** Field-only writes must never overwrite concurrent telemetry or the user-owned anchor. */
    @Query("""
        UPDATE memoryentity SET content = :content,
            importance = COALESCE(:importance, importance),
            description = COALESCE(:description, description), updated_at = :now
        WHERE id = :id AND assistant_id = :assistantId
    """)
    suspend fun updateContentFields(
        id: Int,
        assistantId: String,
        content: String,
        importance: Float?,
        description: String?,
        now: Long,
    ): Int

    @Query("""
        UPDATE memoryentity SET importance = :importance, updated_at = :now
        WHERE id = :id AND assistant_id = :assistantId
    """)
    suspend fun updateImportanceFields(id: Int, assistantId: String, importance: Float, now: Long): Int

    @Query("""
        UPDATE memoryentity SET is_anchor = :isAnchor
        WHERE id = :id AND assistant_id = :assistantId AND is_anchor != :isAnchor
    """)
    suspend fun updateAnchor(id: Int, assistantId: String, isAnchor: Boolean): Int

    @Query("""
        UPDATE memoryentity SET description = :description
        WHERE id = :id AND assistant_id = :assistantId AND description = ''
    """)
    suspend fun backfillDescription(id: Int, assistantId: String, description: String): Int

    @Query("""
        UPDATE memoryentity SET read_count = read_count + 1, last_read_at = MAX(last_read_at, :now)
        WHERE id = :id AND assistant_id = :assistantId
    """)
    suspend fun recordReadInStore(id: Int, assistantId: String, now: Long): Int

    @Query("DELETE FROM memoryentity WHERE id = :id AND assistant_id = :assistantId")
    suspend fun deleteMemoryInStore(id: Int, assistantId: String): Int

    /**
     * 遥测：记一次 `memory_read`（读计数 +1、刷新最近读取时间）。
     *
     * 用 SQL 自增而非「读出实体→改→写回」：后者在并发读（模型一步里连读多条）时会丢计数。
     * 最近读取时间只增不减，避免较早的读取请求晚排队时把时间写回过去。
     * **`updated_at` 不动**——读取不是修改，动它会平白刷新时效衰减（等于「读一次就变新」）。
     */
    @Query("UPDATE memoryentity SET read_count = read_count + 1, last_read_at = MAX(last_read_at, :now) WHERE id = :id")
    suspend fun recordRead(id: Int, now: Long)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE public_id = :publicId")
    suspend fun deleteMemoryByPublicId(publicId: String)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
