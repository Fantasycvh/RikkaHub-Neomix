package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 文件 → 会话 关联表（design-file-conversation-link.md Phase B，DB v80）。
 *
 * **为什么按 relative_path 字符串对齐而不设外键**：`managed_files` 行会被
 * syncFolder/清理删除重插（主键漂移），FK 会让关联随文件登记生命周期抖动；
 * 孤儿关联（文件已删）在展示层 LEFT JOIN 自然不可见，无需维护。
 * 关联键与 `MessageNodeDAO.hasFileReference` 的 JSON 子串匹配粒度一致。
 *
 * **为什么不是 managed_files 上的列**：同一文件可被多个会话引用（转发/多分支），
 * 一对多必须独立关联表；PK 设计天然去重（会话 × 文件一行）。
 *
 * 回填：存量数据由运行时回填 Job 灌入（FTS pending 标记先例），迁移不回填
 * （JSON 全表解析放 Migration 会拖慢启动）。
 */
@Entity(tableName = "message_file_ref", primaryKeys = ["conversation_id", "relative_path"])
data class MessageFileRefEntity(
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("relative_path") val relativePath: String,
    @ColumnInfo("first_seen_at") val firstSeenAt: Long,
)
