package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.CompressionCheckpointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CompressionCheckpointDAO {
    /** 插入一条新检查点（轨迹只追加，不删改旧行；取末行为 active） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(checkpoint: CompressionCheckpointEntity): Long

    /** 取会话的全部检查点（按 id 升序，末行 = 最新边界 = active） */
    @Query("SELECT * FROM compression_checkpoint WHERE conversation_id = :conversationId ORDER BY id ASC")
    suspend fun getOfConversation(conversationId: String): List<CompressionCheckpointEntity>

    /** 会话级 Flow：检查点变化时自动刷新（UI 显示压缩行用） */
    @Query("SELECT * FROM compression_checkpoint WHERE conversation_id = :conversationId ORDER BY id ASC")
    fun observeOfConversation(conversationId: String): Flow<List<CompressionCheckpointEntity>>

    /** 删除会话的全部检查点（取消压缩 / 转移会话时） */
    @Query("DELETE FROM compression_checkpoint WHERE conversation_id = :conversationId")
    suspend fun deleteOfConversation(conversationId: String)

    /** 仅保留末行（最新检查点），删除更早的检查点行——合并旧摘要后清理冗余 */
    @Query(
        "DELETE FROM compression_checkpoint WHERE conversation_id = :conversationId " +
            "AND id < (SELECT MAX(id) FROM compression_checkpoint WHERE conversation_id = :conversationId)"
    )
    suspend fun trimToLatest(conversationId: String)
}
