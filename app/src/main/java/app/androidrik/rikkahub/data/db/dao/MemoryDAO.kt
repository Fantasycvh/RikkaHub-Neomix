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
    // 「注入哪些记忆」的新旧取舍由 buildMemoryPrompt 自行按 id 降序选取，不依赖本处顺序。
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY id ASC")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY id ASC")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity ORDER BY id ASC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity ORDER BY id ASC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memoryentity WHERE id = :id")
    suspend fun deleteMemory(id: Int)

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
