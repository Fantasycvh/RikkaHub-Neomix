package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一个会话的**记忆整理进度**：`consolidated_at` 及之前的内容都已经整理进记忆库（做梦，V3）。
 *
 * **为什么单独成表，而不是在 `ConversationEntity` 上加一列**：会话每次保存都会整行重写
 * （`nodes`/`update_at`/`sort_order` 全被覆盖），进度会跟着被冲掉。上游 #2130 同样这么处理。
 *
 * **为什么挂 CASCADE 外键**：会话删了进度就得跟着走——否则孤儿行会把进度表越撑越大，
 * 而且「同一个 id 后来又出现」时（备份恢复）会拿旧进度当基线、静默漏掉一批轮次。
 */
@Entity(
    tableName = "memory_consolidation",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("conversation_id")],
)
data class MemoryConsolidationEntity(
    @PrimaryKey
    @ColumnInfo("conversation_id")
    val conversationId: String,
    /** epoch millis；「这一刻之前的内容都算整理过」 */
    @ColumnInfo("consolidated_at")
    val consolidatedAt: Long,
)
