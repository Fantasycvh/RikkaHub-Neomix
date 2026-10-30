package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
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
}
