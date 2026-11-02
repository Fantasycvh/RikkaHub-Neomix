package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import app.androidrik.rikkahub.data.db.entity.AgentRunEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentRunDAO {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: AgentRunEntity)

    /** 运行中轨迹更新；终态必须走 finishRun，投递确认只更新投递列。 */
    @Update
    suspend fun updateRun(run: AgentRunEntity)

    @Query("SELECT * FROM agent_run WHERE id = :id")
    fun observeRun(id: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_run WHERE id = :id")
    suspend fun getRun(id: String): AgentRunEntity?

    /** 账本列表：按创建时间倒序（新任务在上）。 */
    @Query("SELECT * FROM agent_run ORDER BY created_at DESC")
    fun observeRuns(): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_run WHERE scope_token = :scopeToken ORDER BY created_at, id")
    fun observeRunsByScope(scopeToken: String): Flow<List<AgentRunEntity>>

    @Query("SELECT * FROM agent_run WHERE parent_conversation_id = :conversationId ORDER BY created_at DESC, id")
    fun observeRunsByConversation(conversationId: String): Flow<List<AgentRunEntity>>

    /** 只暴露已落终态的 notice；不能把半截生成投给父会话。 */
    @Query("""
        SELECT * FROM agent_run
        WHERE delivery_mode = 'notice' AND delivery_state = 'pending'
          AND status IN ('completed', 'failed', 'cancelled', 'interrupted')
          AND finished_at IS NOT NULL
        ORDER BY finished_at, id
    """)
    fun observePendingDeliveries(): Flow<List<AgentRunEntity>>

    /** 终态与待投递标记在同一个 UPDATE 提交，进程死亡不会留下漏投的终态。 */
    @Query("""
        UPDATE agent_run
        SET status = :status, result_summary = :resultSummary, error = :error,
            finished_at = :finishedAt, cancel_reason = :cancelReason,
            delivery_state = CASE
                WHEN delivery_mode = 'notice' AND delivery_state = 'none' THEN 'pending'
                ELSE delivery_state END
        WHERE id = :id AND finished_at IS NULL
          AND status IN ('pending', 'running', 'awaiting_approval')
    """)
    suspend fun finishRun(
        id: String,
        status: String,
        resultSummary: String?,
        error: String?,
        finishedAt: Long,
        cancelReason: String?,
    ): Int

    /** 幂等确认；消息落库与此更新须由投递方在同一 Room 事务内完成。 */
    @Query("""
        UPDATE agent_run SET delivery_state = 'delivered', delivered_at = :deliveredAt
        WHERE id IN (:ids) AND delivery_mode = 'notice' AND delivery_state = 'pending'
          AND status IN ('completed', 'failed', 'cancelled', 'interrupted')
          AND finished_at IS NOT NULL
    """)
    suspend fun markDelivered(ids: List<String>, deliveredAt: Long): Int

    /** 父会话消失时保留结果账本，停止重试；不能覆盖已成功投递的记录。 */
    @Query("""
        UPDATE agent_run SET delivery_state = 'orphaned'
        WHERE id IN (:ids) AND delivery_mode = 'notice' AND delivery_state = 'pending'
          AND status IN ('completed', 'failed', 'cancelled', 'interrupted')
          AND finished_at IS NOT NULL
    """)
    suspend fun markOrphaned(ids: List<String>): Int

    /**
     * 启动对账（P0.5）：把**上一个进程遗留**的未完成 run 标成
     * [AgentRunEntity.STATUS_INTERRUPTED]。
     *
     * 判据是「创建/开始于本次进程启动之前」：本次进程创建的 run 其
     * `created_at >= processStartedAt`，不会被误伤；`finished_at IS NULL` 则排除
     * 已经收尾、只是状态列没来得及改的边角。
     *
     * 返回受影响行数（0 = 没有遗留，正常冷启动）。
     */
    @Query(
        """
        UPDATE agent_run
        SET status = :interruptedStatus, error = :reason, finished_at = :finishedAt,
            cancel_reason = :cancelReason,
            delivery_state = CASE
                WHEN delivery_mode = 'notice' AND delivery_state = 'none' THEN 'pending'
                ELSE delivery_state END
        WHERE status IN (:unfinishedStatuses)
          AND finished_at IS NULL
          AND COALESCE(started_at, created_at) < :processStartedAt
        """
    )
    suspend fun markInterruptedBefore(
        processStartedAt: Long,
        finishedAt: Long,
        interruptedStatus: String,
        reason: String,
        unfinishedStatuses: List<String>,
        cancelReason: String,
    ): Int

    @Query("DELETE FROM agent_run WHERE id = :id")
    suspend fun deleteRun(id: String)
}
