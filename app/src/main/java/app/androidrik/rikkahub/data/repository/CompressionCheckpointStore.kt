package app.androidrik.rikkahub.data.repository

import app.androidrik.rikkahub.data.db.dao.CompressionCheckpointDAO
import app.androidrik.rikkahub.data.db.entity.CompressionCheckpointEntity
import app.androidrik.rikkahub.data.model.CompressionSummary
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 压缩检查点的独立表读写（批次 9 从 [ConversationRepository] 拆出；设计见
 * docs/design/design-auto-compaction.md §4.4 独立表方案）。
 *
 * 轨迹只追加：[addCompressionCheckpoint] 落新行，调用方合并旧摘要后用
 * [trimCompressionCheckpoints] 清理冗余行。仓库公开方法委托到这里，调用面不变。
 */
internal class CompressionCheckpointStore(
    private val checkpointDAO: CompressionCheckpointDAO,
) {
    /** 加载会话的全部检查点（末行 = 最新边界 = active） */
    suspend fun getCompressionCheckpoints(conversationId: Uuid): List<CompressionSummary> =
        checkpointDAO.getOfConversation(conversationId.toString()).map { it.toSummary() }

    /**
     * 追加一条检查点（轨迹只追加）。返回落库后的 Summary（含自增 id）。
     * 调用方负责在合并旧摘要后调 [trimCompressionCheckpoints] 清理冗余行。
     */
    suspend fun addCompressionCheckpoint(summary: CompressionSummary): CompressionSummary {
        val id = checkpointDAO.insert(
            CompressionCheckpointEntity(
                conversationId = summary.conversationId.toString(),
                content = summary.content,
                messageCount = summary.messageCount,
                boundaryNodeId = summary.boundaryNodeId,
                createdAt = summary.createdAt.toEpochMilli(),
                areaFingerprint = summary.areaFingerprint,
            )
        )
        return summary.copy(id = id)
    }

    /** 只保留最新检查点，删除更早的行（新摘要已合并旧摘要时调用） */
    suspend fun trimCompressionCheckpoints(conversationId: Uuid) {
        checkpointDAO.trimToLatest(conversationId.toString())
    }

    /** 删除会话的全部检查点（取消压缩） */
    suspend fun deleteCompressionCheckpoints(conversationId: Uuid) {
        checkpointDAO.deleteOfConversation(conversationId.toString())
    }

    /** 加载会话的压缩检查点（末行 = active）；失败静默返回空（不阻塞会话打开） */
    internal suspend fun loadCompressionSummaries(conversationId: String): List<CompressionSummary> =
        runCatching { checkpointDAO.getOfConversation(conversationId).map { it.toSummary() } }
            .getOrElse { emptyList() }

    private fun CompressionCheckpointEntity.toSummary(): CompressionSummary = CompressionSummary(
        id = id,
        conversationId = Uuid.parse(conversationId),
        content = content,
        messageCount = messageCount,
        boundaryNodeId = boundaryNodeId,
        createdAt = Instant.ofEpochMilli(createdAt),
        areaFingerprint = areaFingerprint,
    )
}
