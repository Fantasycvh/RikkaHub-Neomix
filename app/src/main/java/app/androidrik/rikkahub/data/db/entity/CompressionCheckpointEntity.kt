package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 压缩检查点：会话历史超过阈值时把早期消息压缩为摘要，摘要内容 + 压缩边界（boundary_node_id）
 * 落库为此表的一行。
 *
 * - **轨迹只追加**：不删改 message_node，只追加一行 checkpoint；请求组装时用检查点合成消息替换
 *   边界前的历史（见 docs/design/design-auto-compaction.md §4.1 三边界对齐）。
 * - **boundary_node_id**：压缩时保留窗口的起点节点 id；若该节点被 regenerate 截断/分支删除，
 *   检查点自动失效，回退全量发送。
 * - 一个会话可能有多行（多次压缩），取最后一行（最新边界）为 active。
 */
@Entity(
    tableName = "compression_checkpoint",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversation_id")],
)
data class CompressionCheckpointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo("conversation_id")
    val conversationId: String,
    /** 压缩后的摘要正文（结构化 markdown，见 CompressPrompt） */
    @ColumnInfo("content")
    val content: String,
    /** 被压缩的消息条数（含历史检查点的累计，供 UI 展示「已压缩 N 条」） */
    @ColumnInfo("message_count")
    val messageCount: Int,
    /** 压缩边界：保留窗口的起点 message_node id；不存在则检查点失效 */
    @ColumnInfo("boundary_node_id")
    val boundaryNodeId: String?,
    /** 创建时间 epoch millis（取最后一行 = 最新检查点 = active） */
    @ColumnInfo("created_at")
    val createdAt: Long,
    /**
     * 被摘要区域（边界含之前）的指纹：各节点 id + 选中消息 id 的哈希。
     *
     * 用于检测「区域内消息被改动」（编辑/删除/重新生成/切换分支）——摘要已过时则检查点失效，
     * 避免 AI 上下文与用户所见不一致。null = 旧数据 / 未记录，不做指纹校验（保持原边界存在性校验）。
     */
    @ColumnInfo("area_fingerprint")
    val areaFingerprint: String? = null,
)
