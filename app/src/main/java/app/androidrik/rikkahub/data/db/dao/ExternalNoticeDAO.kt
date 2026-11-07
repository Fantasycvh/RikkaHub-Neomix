package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.ExternalNoticeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExternalNoticeDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(notice: ExternalNoticeEntity)

    @Query("SELECT * FROM external_notice WHERE id = :id")
    suspend fun get(id: String): ExternalNoticeEntity?

    @Query("SELECT COUNT(*) FROM external_notice WHERE deliveryState = 'pending' OR (deliveryState = 'delivered' AND wakeState = 'pending')")
    suspend fun pendingCount(): Int

    @Query("DELETE FROM external_notice WHERE createdAt < :before AND deliveryState != 'pending' AND wakeState IN ('completed','failed','suppressed')")
    suspend fun pruneResolved(before: Long)

    @Query("SELECT * FROM external_notice WHERE deliveryState = 'pending' OR (deliveryState = 'delivered' AND wakeState = 'pending') ORDER BY createdAt, id")
    fun observePending(): Flow<List<ExternalNoticeEntity>>

    @Query("SELECT * FROM external_notice WHERE conversationId = :conversationId AND deliveryState = 'pending' ORDER BY createdAt, id LIMIT 8")
    suspend fun pendingForConversation(conversationId: String): List<ExternalNoticeEntity>

    @Query("UPDATE external_notice SET deliveryState = :state WHERE id IN (:ids)")
    suspend fun delivered(ids: List<String>, state: String = "delivered")

    @Query("UPDATE external_notice SET wakeState = :state WHERE id IN (:ids)")
    suspend fun wake(ids: List<String>, state: String)

    @Query("SELECT EXISTS(SELECT 1 FROM external_notice WHERE rootScope = :scope AND wakeState IN ('claimed','started','waiting_approval','completed','failed'))")
    suspend fun wasWoken(scope: String): Boolean

    @Query("UPDATE external_notice SET wakeState = 'suppressed' WHERE rootScope = :scope AND wakeState = 'pending'")
    suspend fun suppressScope(scope: String)

    @Query("UPDATE external_notice SET wakeState = 'failed' WHERE wakeState IN ('claimed','started','waiting_approval')")
    suspend fun recoverInterrupted()
}
