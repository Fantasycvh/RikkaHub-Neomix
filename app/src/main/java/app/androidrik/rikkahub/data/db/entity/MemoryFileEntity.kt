package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "assistant_memory_file", primaryKeys = ["store_id", "path"])
data class MemoryFileEntity(
    @ColumnInfo(name = "store_id") val storeId: String,
    val path: String,
    val content: String,
    val revision: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
