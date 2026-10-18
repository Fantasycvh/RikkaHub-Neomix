package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Before-image audit events (CREATE/RESTORE_TRASH have an after-image because no live row existed). */
@Entity(tableName = "memory_history", indices = [Index(value = ["assistant_id", "memory_id"])])
data class MemoryHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("memory_id") val memoryId: Int,
    @ColumnInfo("public_id") val publicId: String,
    val revision: Long,
    val snapshot: String,
    val operation: String,
    val actor: String,
    val reason: String,
    @ColumnInfo("recorded_at") val recordedAt: Long,
)
