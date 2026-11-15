package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "assistant_memory_file",
    primaryKeys = ["store_id", "path"],
    indices = [Index(value = ["store_id", "file_id"], unique = true)],
)
data class MemoryFileEntity(
    @ColumnInfo(name = "store_id") val storeId: String,
    val path: String,
    val content: String,
    val revision: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "anchor_id") val anchorId: String? = null,
    @ColumnInfo(name = "file_id") val fileId: String? = null,
) {
    // Legacy rows keep their existing opaque revision as identity until the first write materializes it.
    val identity: String get() = fileId ?: revision
}
