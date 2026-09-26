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

    @Query("DELETE FROM agent_run WHERE id = :id")
    suspend fun deleteRun(id: String)
}
