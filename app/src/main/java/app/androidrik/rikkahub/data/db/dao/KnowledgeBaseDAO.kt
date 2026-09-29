package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.KnowledgeBaseDocumentEntity
import app.androidrik.rikkahub.data.db.entity.KnowledgeBaseEntity
import app.androidrik.rikkahub.data.db.entity.KnowledgeEmbeddingEntity
import kotlinx.coroutines.flow.Flow

/** 每个知识库的文档数（GROUP BY 投影行，Room 按字段名映射） */
data class KnowledgeBaseDocumentCount(
    val kbId: String,
    val count: Int,
)

@Dao
interface KnowledgeBaseDAO {
    @Query("SELECT * FROM knowledge_bases ORDER BY created_at ASC")
    fun getAllKnowledgeBasesFlow(): Flow<List<KnowledgeBaseEntity>>

    @Query("SELECT * FROM knowledge_bases ORDER BY created_at ASC")
    suspend fun getAllKnowledgeBases(): List<KnowledgeBaseEntity>

    @Query("SELECT * FROM knowledge_bases WHERE id = :id")
    suspend fun getKnowledgeBase(id: String): KnowledgeBaseEntity?

    @Query("SELECT * FROM knowledge_bases WHERE id IN (:ids)")
    suspend fun getKnowledgeBases(ids: Collection<String>): List<KnowledgeBaseEntity>

    @Insert
    suspend fun insertKnowledgeBase(kb: KnowledgeBaseEntity)

    @Query("UPDATE knowledge_bases SET name = :name, embedding_model_id = :embeddingModelId, top_k = :topK, similarity_threshold = :similarityThreshold, dimensions = :dimensions WHERE id = :id")
    suspend fun updateKnowledgeBase(
        id: String,
        name: String,
        embeddingModelId: String,
        topK: Int,
        similarityThreshold: Float,
        dimensions: Int?,
    )

    @Query("DELETE FROM knowledge_bases WHERE id = :id")
    suspend fun deleteKnowledgeBase(id: String)

    // ---- 文档 ----

    @Query("SELECT * FROM knowledge_base_documents WHERE kb_id = :kbId ORDER BY created_at ASC")
    fun getDocumentsOfKnowledgeBaseFlow(kbId: String): Flow<List<KnowledgeBaseDocumentEntity>>

    @Query("SELECT * FROM knowledge_base_documents WHERE kb_id = :kbId ORDER BY created_at ASC")
    suspend fun getDocumentsOfKnowledgeBase(kbId: String): List<KnowledgeBaseDocumentEntity>

    @Query("SELECT * FROM knowledge_base_documents WHERE id = :id")
    suspend fun getDocument(id: String): KnowledgeBaseDocumentEntity?

    @Query("SELECT COUNT(*) FROM knowledge_base_documents WHERE kb_id = :kbId")
    fun getDocumentCountFlow(kbId: String): Flow<Int>

    @Query("SELECT kb_id AS kbId, COUNT(*) AS count FROM knowledge_base_documents GROUP BY kb_id")
    fun getDocumentCountsFlow(): Flow<List<KnowledgeBaseDocumentCount>>

    @Insert
    suspend fun insertDocument(document: KnowledgeBaseDocumentEntity)

    @Query("UPDATE knowledge_base_documents SET indexed = :indexed, chunk_count = :chunkCount, error = :error WHERE id = :id")
    suspend fun updateDocumentIndexState(id: String, indexed: Boolean, chunkCount: Int, error: String?)

    @Query("UPDATE knowledge_base_documents SET content = :content WHERE id = :id")
    suspend fun updateDocumentContent(id: String, content: String)

    @Query("DELETE FROM knowledge_base_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query("DELETE FROM knowledge_base_documents WHERE kb_id = :kbId")
    suspend fun deleteDocumentsOfKnowledgeBase(kbId: String)

    // ---- 向量 ----

    @Query("SELECT * FROM knowledge_base_embeddings WHERE kb_id IN (:kbIds)")
    suspend fun getEmbeddingsOfKnowledgeBases(kbIds: Collection<String>): List<KnowledgeEmbeddingEntity>

    @Insert
    suspend fun insertEmbeddings(embeddings: List<KnowledgeEmbeddingEntity>)

    @Query("DELETE FROM knowledge_base_embeddings WHERE document_id = :documentId")
    suspend fun deleteEmbeddingsOfDocument(documentId: String)

    @Query("DELETE FROM knowledge_base_embeddings WHERE kb_id = :kbId")
    suspend fun deleteEmbeddingsOfKnowledgeBase(kbId: String)
}
