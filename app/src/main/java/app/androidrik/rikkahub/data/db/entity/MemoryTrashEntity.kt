package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable tombstone: never automatically purged by score, quota, or elapsed time. */
@Entity(
    tableName = "memory_trash",
    indices = [Index(value = ["assistant_id", "deleted_at"]), Index(value = ["public_id"])],
)
data class MemoryTrashEntity(
    @PrimaryKey @ColumnInfo("memory_id") val memoryId: Int,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("public_id") val publicId: String,
    /** Tombstone version: strictly greater than the deleted snapshot's revision. */
    val revision: Long,
    val snapshot: String,
    val actor: String,
    val reason: String,
    @ColumnInfo("deleted_at") val deletedAt: Long,
)
