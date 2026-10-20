package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    /** 节点总数（统计页「总消息数」）。只数行，不碰 messages JSON。 */
    @Query("SELECT COUNT(*) FROM message_node")
    suspend fun countAll(): Int

    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    /**
     * 按 node_index 升序返回该会话全部节点的 id + 内容摘要（增量保存时求公共前缀）。
     *
     * 同时返回 `messages`：**只比 id 不够**——编辑消息时节点 id 不变（仅追加候选），
     * 只比 id 会误判为「未变化」而跳过写入。
     */
    @Query(
        "SELECT id || CHAR(1) || select_index || CHAR(1) || messages FROM message_node " +
            "WHERE conversation_id = :conversationId ORDER BY node_index ASC"
    )
    suspend fun getNodeSignaturesOfConversation(conversationId: String): List<String>

    /** 删除 node_index >= :fromIndex 的节点（增量保存：清理已不存在的尾部节点） */
    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId AND node_index >= :fromIndex")
    suspend fun deleteFromIndex(conversationId: String, fromIndex: Int)

    // 队列附件清理：判断某 file:// URL 是否仍被任何消息 JSON 引用（保守保留所有分支出现的 URL）。
    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean

}
