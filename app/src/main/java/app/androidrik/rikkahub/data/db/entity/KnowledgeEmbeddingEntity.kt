package app.androidrik.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 向量块：一个文档分块后的每块文本与其 embedding（JSON 数组存 Float 列表）。
 *
 * 个人规模（每库几十~几百块）全量载入内存算余弦即可，无需向量索引/近似检索——
 * 真到十万块量级再考虑 SQLite vector 扩展或 HNSW。
 */
@Entity(
    tableName = "knowledge_base_embeddings",
    indices = [
        Index("kb_id"),
        Index("kb_id", "document_id"),
    ],
)
data class KnowledgeEmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("id")
    val id: Long = 0,
    @ColumnInfo("kb_id")
    val kbId: String,
    @ColumnInfo("document_id")
    val documentId: String,
    @ColumnInfo("chunk_index")
    val chunkIndex: Int,
    @ColumnInfo("chunk_text")
    val chunkText: String,
    @ColumnInfo("vector")
    val vector: String,
)
