package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskRunEntity
import kotlinx.coroutines.flow.Flow

/**
 * 定时任务表（docs/design/design-scheduled-ai-tasks.md）。
 *
 * [claim] 是幂等核心：`WHERE next_run_at = :seen` 的乐观锁——认领即推进，两个 sweep 窗口
 * 同时醒来也只有一个能改到行（rowCount == 1）；认领后 Worker 被杀不回滚，任务不卡中间态。
 */
@Dao
interface ScheduledTaskDAO {
    @Query("SELECT * FROM scheduled_tasks ORDER BY created_at ASC")
    fun flowAll(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks")
    suspend fun all(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun byId(id: String): ScheduledTaskEntity?

    /** sweeper 的到期查询；索引 next_run_at */
    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND next_run_at <= :now")
    suspend fun due(now: Long): List<ScheduledTaskEntity>

    @Query("SELECT COUNT(*) FROM scheduled_tasks WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledTaskEntity)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE scheduled_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    /** 目标会话已删：任务自失效并写明原因（管理页可见，不是静默消失） */
    @Query("UPDATE scheduled_tasks SET enabled = 0, last_status = 'TARGET_GONE' WHERE id = :id")
    suspend fun markTargetGone(id: String)

    /** 目标助手已删（NEW_EACH_RUN / DEDICATED）：与「会话没了」分开记，便于排查 */
    @Query("UPDATE scheduled_tasks SET enabled = 0, last_status = 'ASSISTANT_GONE' WHERE id = :id")
    suspend fun markAssistantGone(id: String)

    /** NEW_EACH_RUN 每轮新建的会话：记下来供列表/详情跳转看结果 */
    @Query("UPDATE scheduled_tasks SET last_conversation_id = :conversationId WHERE id = :id")
    suspend fun recordLastConversation(id: String, conversationId: String)

    /**
     * 认领：推进 next_run_at + 记 last_run_at，仅当行仍处于「看到时的状态」。
     * ONCE 任务的推进值传 null → 调用方应改用 [claimOnce]（enabled 直接落 false）。
     */
    @Query(
        "UPDATE scheduled_tasks SET next_run_at = :advanced, last_run_at = :claimedAt " +
            "WHERE id = :id AND enabled = 1 AND next_run_at = :seen"
    )
    suspend fun claim(id: String, seen: Long, advanced: Long, claimedAt: Long): Int

    /** ONCE 任务的认领变体：推进不存在，直接禁用（设计稿 §四：认领即置 enabled=false） */
    @Query(
        "UPDATE scheduled_tasks SET enabled = 0, last_run_at = :claimedAt " +
            "WHERE id = :id AND enabled = 1 AND next_run_at = :seen"
    )
    suspend fun claimOnce(id: String, seen: Long, claimedAt: Long): Int

    @Query("UPDATE scheduled_tasks SET last_status = :status WHERE id = :id")
    suspend fun recordStatus(id: String, status: String)

    // ---- 运行账本（P2）：每次执行一行，终态必达；诊断「昨晚为什么没跑成」用 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: ScheduledTaskRunEntity)

    @Query(
        "UPDATE scheduled_task_runs SET finished_at = :finishedAt, status = :status WHERE id = :id"
    )
    suspend fun finishRun(id: String, finishedAt: Long, status: String)

    /** NEW_EACH_RUN：会话是执行中途才建出来的，账本行补记本轮实际会话 */
    @Query("UPDATE scheduled_task_runs SET conversation_id = :conversationId WHERE id = :id")
    suspend fun updateRunConversation(id: String, conversationId: String)

    /** 详情页运行历史：最近 [limit] 条（新→旧） */
    @Query(
        "SELECT * FROM scheduled_task_runs WHERE task_id = :taskId " +
            "ORDER BY started_at DESC LIMIT :limit"
    )
    fun flowRuns(taskId: String, limit: Int): Flow<List<ScheduledTaskRunEntity>>

    /** 任务删除时清账本（无外键，手动清；防孤儿行无限累积） */
    @Query("DELETE FROM scheduled_task_runs WHERE task_id = :taskId")
    suspend fun deleteRunsOfTask(taskId: String)

    /** per-task 保底裁剪：只留最近 [keep] 条（insert 后调用） */
    @Query(
        "DELETE FROM scheduled_task_runs WHERE task_id = :taskId AND id NOT IN (" +
            "SELECT id FROM scheduled_task_runs WHERE task_id = :taskId " +
            "ORDER BY started_at DESC LIMIT :keep)"
    )
    suspend fun pruneRuns(taskId: String, keep: Int)
}
