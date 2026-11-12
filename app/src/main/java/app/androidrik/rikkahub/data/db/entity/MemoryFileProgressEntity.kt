package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "memory_file_progress",
    primaryKeys = ["conversation_id", "store_id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MemoryFileProgressEntity(
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("store_id") val storeId: String,
    @ColumnInfo("consolidated_at") val consolidatedAt: Long,
    @ColumnInfo("trigger_turn_fired") val triggerTurnFired: Int,
)
