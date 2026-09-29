package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 知识库文档：导入时即解析为**纯文本**存进 [content]，之后向量化只依赖文本——
 * 不保留对源文件 URI 的依赖（SAF 授权不持久化的老问题按构造消失），索引重建也不需要源文件还在。
 */
@Entity(
    tableName = "knowledge_base_documents",
    indices = [Index("kb_id")],
)
data class KnowledgeBaseDocumentEntity(
    @PrimaryKey
    @ColumnInfo("id")
    val id: String,
    @ColumnInfo("kb_id")
    val kbId: String,
    @ColumnInfo("file_name")
    val fileName: String,
    /** 解析后的纯文本（导入时定档，上限见 RagRepository.MAX_DOCUMENT_CHARS） */
    @ColumnInfo("content")
    val content: String = "",
    /** 是否已成功向量化（源内容变更或模型更换后需重建） */
    @ColumnInfo("indexed", defaultValue = "0")
    val indexed: Boolean = false,
    @ColumnInfo("chunk_count", defaultValue = "0")
    val chunkCount: Int = 0,
    /** 最近一次索引失败的错误信息（null = 无错误或未尝试） */
    @ColumnInfo("error", defaultValue = "NULL")
    val error: String? = null,
    @ColumnInfo("created_at", defaultValue = "0")
    val createdAt: Long = 0L,
)
