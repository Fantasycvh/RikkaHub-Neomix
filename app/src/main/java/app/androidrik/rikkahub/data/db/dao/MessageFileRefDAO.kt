package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.MessageFileRefEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageFileRefDAO {
    /** 文件管理页展示：全部关联（VM 侧与 managed_files.relative_path JOIN）。 */
    @Query("SELECT * FROM message_file_ref")
    fun observeAll(): Flow<List<MessageFileRefEntity>>

    @Query("SELECT * FROM message_file_ref WHERE relative_path = :relativePath")
    suspend fun getByPath(relativePath: String): List<MessageFileRefEntity>

    @Query("SELECT conversation_id FROM message_file_ref WHERE relative_path = :relativePath")
    suspend fun getConversationIdsOfPath(relativePath: String): List<String>

    /** updateConversation 收口的 diff 重写：先清该会话全部关联再批量插入。 */
    @Query("DELETE FROM message_file_ref WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    /** 稀疏保存里被删节点的关联移除（节点行删除前由调用方读出其引用）。 */
    @Query(
        "DELETE FROM message_file_ref WHERE conversation_id = :conversationId AND relative_path = :relativePath"
    )
    suspend fun deleteRef(conversationId: String, relativePath: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(refs: List<MessageFileRefEntity>)

    /** 回填 Job 用：单事务内全量重写（IGNORE 去重，冲突行保留原 first_seen_at）。 */
    @Query("DELETE FROM message_file_ref")
    suspend fun deleteAll()
}
