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
     * 审计行的保留上限：某记忆库只留最新 [keep] 条已决策记录。
     *
     * 为什么要管：建议表会随每次整理增长，而**已决策的行没有任何查询会读全量**
     * （界面只看 pending）。无上限的话它是又一个只增不减的表。
     */
    @Query(
        "DELETE FROM memory_proposal WHERE memory_id = :memoryId AND decision != 'pending' " +
            "AND id NOT IN (" +
            "SELECT id FROM memory_proposal WHERE memory_id = :memoryId AND decision != 'pending' " +
            "ORDER BY decided_at DESC, id DESC LIMIT :keep)"
    )
    suspend fun pruneDecided(memoryId: String, keep: Int)
}
