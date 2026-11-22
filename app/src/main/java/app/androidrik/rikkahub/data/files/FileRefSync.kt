package app.androidrik.rikkahub.data.files

import app.androidrik.rikkahub.data.db.dao.MessageFileRefDAO
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import app.androidrik.rikkahub.data.db.entity.MessageFileRefEntity
import app.androidrik.rikkahub.data.model.MessageNode
import java.io.File
import kotlin.uuid.Uuid

/**
 * 文件→会话关联（message_file_ref）的**同步核心**（design-file-conversation-link.md §4）。
 *
 * 无事务所有权：调用方（ConversationRepository 的保存边界 / RikkaHubApp 回填 /
 * SettingFilesVM 兜底）负责把它放进自己的事务/调度里。抽成独立类是为了让
 * 稀疏合并、diff 重写、幂等语义可以在 JVM 单测里用 Fake DAO 直接钉住
 * （本仓无 Robolectric，惯例见 ScheduledTaskRepositoryTest）。
 *
 * 语义：
 * - **稀疏保存**（isSparse=true，窗口只含尾部，见 updateConversation 对 FTS 的同款约束）：
 *   只做增量合并（IGNORE）+ 被删节点的精确移除——未加载前缀的关联不能按「未出现=已删」清掉；
 * - **全量保存**（isSparse=false）：diff 重写该会话全部关联（编辑/分支删除后失效的引用得以清除）；
 * - **回填/兜底**：扫描结果灌库（重写 / 合并两种幂等口径）。
 */
class FileRefSync(
    private val messageNodeDAO: MessageNodeDAO,
    private val messageFileRefDAO: MessageFileRefDAO,
    private val filesDir: File,
) {
    /**
     * 保存边界同步。必须在 [app.androidrik.rikkahub.data.repository.ConversationRepository]
     * 的 saveMessageNodes 之后、**同一事务内**调用；checkpointGeneratingNode 不收口
     * （流式半截消息不落关联，轮次结束的正常保存会补齐）。
     */
    suspend fun syncOnSave(
        conversationId: String,
        messageNodes: List<MessageNode>,
        deletedNodeIds: Collection<Uuid>,
        sparse: Boolean,
    ) {
        val refs = messageNodes
            .flatMap { it.messages }
            .flatMap { FileConversationScanner.extractRelativePathsFromParts(it.parts, filesDir) }
            .toSet()
        if (sparse) {
            // 被删节点的关联先读节点行精确移除（稀疏窗口不知其内容；全量重写时由 rewrite 覆盖）
            if (deletedNodeIds.isNotEmpty()) {
                messageNodeDAO.getNodesByIds(deletedNodeIds.map(Uuid::toString))
                    .flatMap { FileConversationScanner.extractRelativePathsFromJson(it.messages, filesDir) }
                    .forEach { messageFileRefDAO.deleteRef(conversationId, it) }
            }
            if (refs.isNotEmpty()) {
                messageFileRefDAO.insertAll(refs.map { MessageFileRefEntity(conversationId, it, now()) })
            }
        } else {
            messageFileRefDAO.deleteByConversation(conversationId)
            if (refs.isNotEmpty()) {
                messageFileRefDAO.insertAll(refs.map { MessageFileRefEntity(conversationId, it, now()) })
            }
        }
    }

    /** 存量回填（RikkaHubApp Ready 后）：全量重写，幂等（重复跑结果一致）。 */
    suspend fun rewriteFromScan(scanResult: Map<String, Set<String>>) {
        messageFileRefDAO.deleteAll()
        messageFileRefDAO.insertAll(scanResult.toEntities())
    }

    /** VM 兜底（关联表空且未兜底过时）：只增量合并，不清表（IGNORE 去重，幂等）。 */
    suspend fun mergeFromScan(scanResult: Map<String, Set<String>>) {
        messageFileRefDAO.insertAll(scanResult.toEntities())
    }

    private fun Map<String, Set<String>>.toEntities(): List<MessageFileRefEntity> {
        val now = now()
        return flatMap { (path, ids) -> ids.map { MessageFileRefEntity(conversationId = it, relativePath = path, firstSeenAt = now) } }
    }

    private fun now(): Long = System.currentTimeMillis()
}
