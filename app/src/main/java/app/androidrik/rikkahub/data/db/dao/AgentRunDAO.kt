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

    /** 整行更新（run 是单写者——SubagentRunner 的协程，无并发列编辑场景）。 */
    @Update
    suspend fun updateRun(run: AgentRunEntity)

    @Query("SELECT * FROM agent_run WHERE id = :id")
    fun observeRun(id: String): Flow<AgentRunEntity?>

    @Query("SELECT * FROM agent_run WHERE id = :id")
    suspend fun getRun(id: String): AgentRunEntity?

    /** 账本列表：按创建时间倒序（新任务在上）。 */
    @Query("SELECT * FROM agent_run ORDER BY created_at DESC")
    fun observeRuns(): Flow<List<AgentRunEntity>>

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
        SET status = :interruptedStatus, error = :reason, finished_at = :finishedAt
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
    ): Int

    @Query("DELETE FROM agent_run WHERE id = :id")
    suspend fun deleteRun(id: String)
}
