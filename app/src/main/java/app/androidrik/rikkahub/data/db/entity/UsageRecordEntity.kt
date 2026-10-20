package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次模型请求的 token 用量流水：每个请求各记一行（多步工具调用的每一步、每次重试都算各自的行）。
 *
 * **为什么不是从消息里算**：消息 JSON 里的 usage 汇总在「选中分支的最后一条助手消息」上，
 * 删掉会话或消息、切分支、重新生成都会让历史用量一起消失；标题、建议、压缩、翻译、记忆整理、
 * OCR 这些**不落到消息里**的请求则完全不可见。流水独立成表后：
 * - 删会话/消息不影响已发生的花费（这也是「真实花费」的定义）；
 * - 统计页不再对 `message_node.messages` 跑 `json_each()`，打开不用等；
 * - 能回答「这些 token 花在哪儿」（[source]）。
 *
 * 引用的模型、助手与会话都可能已经被删除，所以**不加外键**；[modelName]/[providerName]
 * 只是当时的名称快照，用来在模型被删后仍能显示。
 */
@Entity(
    tableName = "usage_record",
    indices = [Index(value = ["created_at"])]
)
data class UsageRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 请求发生时刻（epoch millis）；统计按设备本地时区分桶 */
    @ColumnInfo("created_at")
    val createdAt: Long,
    /** 请求来源，存 `UsageSource` 的名称 */
    @ColumnInfo("source")
    val source: String,
    @ColumnInfo("provider_id")
    val providerId: String?,
    /** 提供商显示名快照（如 `OpenAI`），提供商类型配置被删后仍可展示 */
    @ColumnInfo("provider_name")
    val providerName: String?,
    /** `Model.id`（应用内 UUID），用于按当前设置解析最新展示名 */
    @ColumnInfo("model_id")
    val modelId: String?,
    /** 模型展示名快照（模型被删除后兜底） */
    @ColumnInfo("model_name")
    val modelName: String?,
    @ColumnInfo("assistant_id")
    val assistantId: String?,
    @ColumnInfo("conversation_id")
    val conversationId: String?,
    @ColumnInfo("prompt_tokens")
    val promptTokens: Int,
    @ColumnInfo("completion_tokens")
    val completionTokens: Int,
    @ColumnInfo("cached_tokens")
    val cachedTokens: Int,
)
