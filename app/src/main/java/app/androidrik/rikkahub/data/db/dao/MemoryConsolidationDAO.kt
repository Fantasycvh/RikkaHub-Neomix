package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.MemoryConsolidationEntity

/**
 * 做梦（V3）的进度表与「哪些会话有新内容」的查询。
 *
 * 候选查询的判据全部落在 SQL 里（而不是把整张会话表拉进内存过滤）：
 * 会话的 `last_message_at` 由保存路径从**末尾消息的 createdAt** 维护（改标题/置顶不会刷新它），
 * 所以它是"可能聊了新东西"的廉价粗筛；**精确到轮次要数消息时间**，那一步在仓库层做
 * （`message_node` 表没有时间戳，时间在消息 JSON 里）。
 */
@Dao
interface MemoryConsolidationDAO {

    @Query("SELECT consolidated_at FROM memory_consolidation WHERE conversation_id = :conversationId")
    suspend fun getConsolidatedAt(conversationId: String): Long?

    @Query("SELECT * FROM memory_consolidation WHERE conversation_id = :conversationId")
    suspend fun getProgress(conversationId: String): MemoryConsolidationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(progress: MemoryConsolidationEntity)

    @Query("DELETE FROM memory_consolidation WHERE conversation_id = :conversationId")
    suspend fun deleteProgress(conversationId: String)

    /**
     * 该助手最近一次整理**完成**的时间（名下所有会话里最新的 `consolidated_at`）。
     *
     * 用作「自动整理频次上限」的**近似**：整理按库跑，一次 run 会给它处理的每个会话写进度，
     * 所以库级的 max ≈ 上次 run 的时间。近似而不是专门记库级状态，省一张表（见设计稿 §7.3）；
     * 代价是「一次什么都没改的 run」不会推进它——那本来也不需要限流（没花钱）。
     */
    @Query(
        """
        SELECT MAX(p.consolidated_at) FROM ConversationEntity c
        INNER JOIN memory_consolidation p ON p.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
        """
    )
    suspend fun getLastConsolidatedAt(assistantId: String): Long?

    /**
     * 该助手下**可能有新内容**的会话 id（按最近活跃倒序，最多 [limit] 条）。
     *
     * 三个条件缺一不可：
     * - `memory_disabled = 0`：隐身对话（V2 §4.4）不参与整理；
     * - 末尾消息时间**晚于基线**：基线 = 该会话的整理进度 与 调用方给的 [baseline]（= 做梦开关
     *   开启时刻 / 全局库取最晚开启）取较大者；
     * - 倒序 + LIMIT：**新内容优先**——历史存量不该插到新对话前面去。
     *
     * 粗筛会漏吗：只漏「末尾消息时间没变但内容变了」的情况（导入旧库、恢复备份），
     * 那种场景走手动整理；不这么做就得把每个会话的消息 JSON 解一遍才能判，代价不成比例。
     */
    @Query(
        """
        SELECT c.id FROM conversationentity c
        LEFT JOIN memory_consolidation p ON p.conversation_id = c.id
        WHERE c.assistant_id = :assistantId
          AND c.memory_disabled = 0
          AND COALESCE(c.last_message_at, c.update_at) > MAX(COALESCE(p.consolidated_at, 0), :baseline)
        ORDER BY COALESCE(c.last_message_at, c.update_at) DESC
        LIMIT :limit
        """
    )
    suspend fun getCandidateConversationIds(assistantId: String, baseline: Long, limit: Int): List<String>
}
