package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity

@Dao
interface MemoryProposalDAO {

    /** 某记忆库**待决策**的建议，按批 + 产出顺序（界面要按批成组展示，顺序不能乱） */
    @Query(
        "SELECT * FROM memory_proposal WHERE memory_id = :memoryId AND decision = 'pending' " +
            "ORDER BY created_at ASC, id ASC"
    )
    suspend fun getPending(memoryId: String): List<MemoryProposalEntity>

    /** 同库同批的全部成员，含已决策行：删除不能把 rejected/stale 前提当作已完成。 */
    @Query(
        "SELECT * FROM memory_proposal WHERE memory_id = :memoryId AND batch_id = :batchId " +
            "ORDER BY created_at ASC, id ASC"
    )
    suspend fun getBatch(memoryId: String, batchId: String): List<MemoryProposalEntity>

    /**
     * 最近**被用户拒绝**的建议（整理提示要据此避免原地重提）。
     *
     * 只取 rejected：`stale` 是「前提不成立了」，不是用户的意见，喂给模型反而会误导它。
     */
    @Query(
        "SELECT * FROM memory_proposal WHERE memory_id = :memoryId AND decision = 'rejected' " +
            "ORDER BY decided_at DESC, id DESC LIMIT :limit"
    )
    suspend fun getRecentlyRejected(memoryId: String, limit: Int): List<MemoryProposalEntity>

    /** 全部有建议的记忆库（启动时把建议恢复进状态用） */
    @Query("SELECT DISTINCT memory_id FROM memory_proposal WHERE decision = 'pending'")
    suspend fun memoryIdsWithPending(): List<String>

    @Insert
    suspend fun insertAll(proposals: List<MemoryProposalEntity>)

    /**
     * 换掉某记忆库的待决策建议：新一轮整理提出建议时，旧的 pending 让位。
     *
     * 为什么是「替换」而不是「追加」：同一次 pending 集合代表**当前对库的待办清单**，
     * 叠上几轮会出现互相矛盾的重复改动（前一轮说 edit、后一轮说 delete 同一条）。
     * 语义与落库前的内存版一致（那版也是「只有下一次真的又提出建议才替换」）。
     */
    @Query("DELETE FROM memory_proposal WHERE memory_id = :memoryId AND decision = 'pending'")
    suspend fun deletePending(memoryId: String)

    @Query("UPDATE memory_proposal SET decision = :decision, decided_at = :now WHERE id IN (:ids)")
    suspend fun updateDecision(ids: List<Long>, decision: String, now: Long)

    /** 单条改判（应用时逐条判 stale，只把这个失败的挑出来） */
    @Query("UPDATE memory_proposal SET decision = :decision, decided_at = :now WHERE id = :id")
    suspend fun updateOne(id: Long, decision: String, now: Long)

    /**
     * 每库保留最新 [keep] 条可裁剪审计，另保留仍有同库同 batch pending 的已决策成员。
     *
     * 后者是 delete 守卫的必要证据，不占 [keep] 预算；不能让 rejected/stale 因裁剪消失后
     * 被误当作已完成。batch 不再含 pending 时，其已决策成员恢复普通审计裁剪。
     */
    @Query(
        "DELETE FROM memory_proposal WHERE memory_id = :memoryId AND decision != 'pending' " +
            "AND batch_id NOT IN (" +
            "SELECT batch_id FROM memory_proposal WHERE memory_id = :memoryId AND decision = 'pending') " +
            "AND id NOT IN (" +
            "SELECT id FROM memory_proposal WHERE memory_id = :memoryId AND decision != 'pending' " +
            "AND batch_id NOT IN (" +
            "SELECT batch_id FROM memory_proposal WHERE memory_id = :memoryId AND decision = 'pending') " +
            "ORDER BY decided_at DESC, id DESC LIMIT :keep)"
    )
    suspend fun pruneDecided(memoryId: String, keep: Int)
}
