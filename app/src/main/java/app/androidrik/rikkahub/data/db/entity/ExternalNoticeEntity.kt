package app.androidrik.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/** Durable completion inbox shared by shell commands and resumable agent runs. */
@Entity(tableName = "external_notice", indices = [Index("conversationId"), Index("rootScope"), Index("createdAt")])
data class ExternalNoticeEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val assistantId: String,
    val workspaceId: String,
    val anchorMessageId: String,
    val rootScope: String,
    val sourceId: String,
    val status: String,
    val body: String,
    val createdAt: Long,
    val allowWake: Boolean,
    val deliveryState: String = "pending",
    val wakeState: String = "pending",
    @ColumnInfo(defaultValue = "'workspace'") val sourceType: String = "workspace",
)
