package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 知识库（RAG-as-tool，移植自 MineChat rag 模块的设计）。
 *
 * 每个知识库绑定一个 embedding 模型（[embeddingModelId] 指向 [app.androidrik.ai.provider.Model.id]），
 * 文档导入时解析为纯文本分块向量化，AI 经 `search_knowledge_base` 工具按需检索——
 * 与已否决的「注入时语义检索」不同物种：不进每轮 prompt，只在模型调用时产生 embedding 请求。
 */
@Entity(tableName = "knowledge_bases")
data class KnowledgeBaseEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("name")
    val name: String = "",
    @ColumnInfo("embedding_model_id")
    val embeddingModelId: String = "",
    /** 检索返回的最大块数 */
    @ColumnInfo("top_k", defaultValue = "5")
    val topK: Int = 5,
    /** 相似度低于该阈值的结果丢弃 */
    @ColumnInfo("similarity_threshold", defaultValue = "0.5")
    val similarityThreshold: Float = 0.5f,
    /** 传给 embedding API 的维度（text-embedding-3 系列支持；null = 不传由模型默认） */
    @ColumnInfo("dimensions", defaultValue = "NULL")
    val dimensions: Int? = null,
    @ColumnInfo("created_at", defaultValue = "0")
    val createdAt: Long = 0L,
)
