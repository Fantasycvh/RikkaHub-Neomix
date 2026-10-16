package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 「全量复核」推进到哪了（每记忆库一行）。
 *
 * ## 为什么需要它（不是优化，是能力缺口）
 *
 * 整理 agent 拿到的记忆库快照走的是聊天注入那套两层披露，预算 `DEFAULT_LIBRARY_TOKENS = 4_000`。
 * 真机库实测 **49 条 / 55,075 字符 / ≈58,489 token** ⇒ **约 3/4 的记录进不了上下文**；
 * 更糟的是 `selectMemoriesForPrompt` 的选取顺序**每轮都一样**（分数 + sortKey，与时间无关）
 * ⇒ 反复「整理一下」永远看同一批，**剩下的一页永远不看**。
 *
 * 于是「存量记忆本身的整理」在修这个之前是**不可能的**：不是模型懒，是它从来看不到。
 *
 * ## 语义
 *
 * [cursor] 是**已复核到的 `sortKey` 水位**（含）：下次复核从**大于**它的记录开始取一页。
 * `0` / 无行 = 还没开始过 ⇒ 从头取。走完最后一页时**重置为 0**，让下一轮全量复核从新开始
 * （库会变，旧的「已复核」不能当永久结论）。
 *
 * **不挂外键**：与 `memory_proposal` 同理——`memoryId` 的语义是助手（住 DataStore，Room 里没有父表），
 * 且 `__global__` 这种共享库根本没有对应的助手行。
 */
@Entity(tableName = "memory_review_cursor")
data class MemoryReviewCursorEntity(
    @PrimaryKey
    @ColumnInfo("memory_id")
    val memoryId: String,
    /** 已复核到的 `sortKey`（含）。0 = 从头开始 */
    @ColumnInfo("cursor")
    val cursor: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
