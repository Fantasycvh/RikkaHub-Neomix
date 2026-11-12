package app.androidrik.rikkahub.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity

/**
 * 增量保存 diff 的投影行（树形分支 P0：id 键 map diff 取代公共前缀 diff，
 * 见 design-conversation-tree-branching.md §五.2）。
 *
 * signature = select_index + messages（盖住「编辑后候选变但 id 不变」）；
 * id / node_index 独立成列——node_index 语义为**全局创建序**（append-only、不复用），
 * P0 兼作线性路径的排序键（创建序 == 路径序）。
 */
data class MessageNodeDiffRow(
    val id: String,
    @ColumnInfo("node_index") val nodeIndex: Int,
    val signature: String,
)

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
     * 增量保存 diff 用：该会话全部节点的 id + 创建序 + 内容签名。
     * 不排序（map diff 与顺序无关）；签名不含 id（id 已是独立列）。
     */
    @Query(
        "SELECT id, node_index, select_index || CHAR(1) || messages AS signature FROM message_node " +
            "WHERE conversation_id = :conversationId"
    )
    suspend fun getNodeDiffRows(conversationId: String): List<MessageNodeDiffRow>

    /** 删除内存态已不存在的节点（id 键，替代旧的 deleteFromIndex 尾部删除）。 */
    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId AND id IN (:nodeIds)")
    suspend fun deleteByIds(conversationId: String, nodeIds: List<String>)

    /** 单节点的创建序（流式检查点 upsert 时保持原值用）。 */
    @Query("SELECT node_index FROM message_node WHERE id = :nodeId")
    suspend fun getNodeIndexOf(nodeId: String): Int?

    /** 该会话当前最大创建序（新节点分配 max+1，永不复用旧下标）。 */
    @Query("SELECT COALESCE(MAX(node_index), -1) FROM message_node WHERE conversation_id = :conversationId")
    suspend fun getMaxNodeIndex(conversationId: String): Int

    // 队列附件清理：判断某 file:// URL 是否仍被任何消息 JSON 引用（保守保留所有分支出现的 URL）。
    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean

}