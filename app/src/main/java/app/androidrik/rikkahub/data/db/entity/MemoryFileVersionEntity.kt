package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_file_version",
    indices = [Index(value = ["store_id", "file_id", "id"]), Index(value = ["store_id", "in_trash", "id"])],
)
data class MemoryFileVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo("store_id") val storeId: String,
    @ColumnInfo("file_id") val fileId: String,
    val path: String,
    val revision: String,
    val content: String,
    @ColumnInfo("anchor_id") val anchorId: String?,
    @ColumnInfo("recorded_at") val recordedAt: Long,
    @ColumnInfo("in_trash") val inTrash: Boolean,
)

data class MemoryFileVersionSummary(
    val id: Long,
    @ColumnInfo("file_id") val fileId: String,
    val path: String,
    @ColumnInfo("recorded_at") val recordedAt: Long,
)
