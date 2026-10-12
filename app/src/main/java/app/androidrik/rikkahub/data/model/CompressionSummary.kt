package app.androidrik.rikkahub.data.model

import kotlinx.serialization.Serializable
import app.androidrik.ai.util.InstantSerializer
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 压缩检查点的领域模型（对应 [app.androidrik.rikkahub.data.db.entity.CompressionCheckpointEntity]）。
 *
 * 设计见 `docs/design/design-auto-compaction.md` §4.1 三边界对齐 + §4.4 独立表方案：
 * - **轨迹只追加**：压缩时只往 compression_checkpoint 表追加一行，不删改 message_node
 * - **请求期组装**：发送时用检查点合成消息替换 boundaryNodeId 之前的早期历史
 * - **失效判定**：boundaryNodeId 不存在（被 regenerate 截断/分支删除）时检查点失效，回退全量
 * - **合并重写**：新摘要与旧摘要合并为单一摘要（避免层层累积），合并后 trimToLatest 清理旧行
 *
 * 一个会话可能有多行（多次压缩），取最后一行（末行 = 最新边界 = active）。
 * 加载时机：ConversationRepository 打开会话时从 DAO 查出注入 Conversation.compressionSummaries。
 */
@Serializable
data class CompressionSummary(
    /** 检查点 id（DB 自增；内存对象可为 0 表示未落库） */
    val id: Long = 0,
    /** 所属会话 id */
    val conversationId: Uuid,
    /** 压缩后的摘要正文（结构化 markdown，见 CompressPrompt） */
    val content: String,
    /** 被压缩的消息条数（含历史检查点累计，供 UI 展示「已压缩 N 条」） */
    val messageCount: Int,
    /** 压缩边界：保留窗口的起点 message_node id；不存在则检查点失效 */
    val boundaryNodeId: String? = null,
    /** 创建时间 */
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
    /**
     * 被摘要区域（含边界节点）的指纹：各节点 id + 选中消息 id 的哈希。
     *
     * 用于检测区域内消息被改动（编辑/删除/重新生成/切换分支）→ 摘要过时 → 检查点失效。
     * null = 旧数据或未记录，退化为仅做边界存在性校验。
     */
    val areaFingerprint: String? = null,
) {
    /** boundaryNodeId 为空时永远生效（无失效条件）；非空时由调用方校验节点存在性 */
    val canExpire: Boolean get() = boundaryNodeId != null
}
