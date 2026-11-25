package app.androidrik.rikkahub.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "lan_conversation_tombstone")
data class LanConversationTombstoneEntity(
    @PrimaryKey @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
)

@Dao
interface LanConversationTombstoneDAO {
    @Query("SELECT * FROM lan_conversation_tombstone WHERE deleted_at >= :cutoff")
    suspend fun listSince(cutoff: Long): List<LanConversationTombstoneEntity>

    @Query("SELECT deleted_at FROM lan_conversation_tombstone WHERE conversation_id = :id")
    suspend fun time(id: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(mark: LanConversationTombstoneEntity)

    @Query("UPDATE lan_conversation_tombstone SET deleted_at = :deletedAt WHERE conversation_id = :id AND deleted_at < :deletedAt")
    suspend fun advance(id: String, deletedAt: Long)

    @Query("DELETE FROM lan_conversation_tombstone WHERE conversation_id = :id")
    suspend fun clear(id: String)

    @Query("DELETE FROM lan_conversation_tombstone WHERE deleted_at < :cutoff")
    suspend fun prune(cutoff: Long)
}
