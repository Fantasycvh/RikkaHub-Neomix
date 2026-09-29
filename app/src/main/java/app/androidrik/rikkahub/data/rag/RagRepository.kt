package app.androidrik.rikkahub.data.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import app.androidrik.ai.provider.EmbeddingGenerationParams
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.db.dao.KnowledgeBaseDAO
import app.androidrik.rikkahub.data.db.entity.KnowledgeBaseDocumentEntity
import app.androidrik.rikkahub.data.db.entity.KnowledgeBaseEntity
import app.androidrik.rikkahub.data.db.entity.KnowledgeEmbeddingEntity
import app.androidrik.document.DocxParser
import app.androidrik.document.EpubParser
import app.androidrik.document.PdfParser
import app.androidrik.document.PptxParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

private const val TAG = "RagRepository"

/** 单文档解析后的纯文本上限（与 DocumentAsPromptTransformer 同数量级的保守值） */
private const val MAX_DOCUMENT_CHARS = 500_000

/** embedding 批大小：一次请求携带的块数（太大易触发请求体/token 上限） */
private const val EMBED_BATCH_SIZE = 20

/**
 * 知识库检索（RAG-as-tool）仓储：文档解析入库、分块向量化、余弦检索。
 *
 * 依赖 fork ai 模块现成的 `generateEmbedding`（OpenAI /embeddings，多 key 轮换）；
 * 模型解析用 [Model.findProvider]——embedding 模型必须挂在 OpenAI 型提供商下
 * （Google/Claude 的 generateEmbedding 未实现，调用会抛错并如实记到文档 [KnowledgeBaseDocumentEntity.error]）。
 */
class RagRepository(
    private val context: Context,
    private val dao: KnowledgeBaseDAO,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    // ---- 知识库 ----

    fun getAllKnowledgeBasesFlow() = dao.getAllKnowledgeBasesFlow()

    /** kb.id → 文档数（列表页展示用） */
    fun observeDocumentCounts() = dao.getDocumentCountsFlow()
        .map { counts -> counts.associate { it.kbId to it.count } }

    /** kb.id → embedding 模型显示名（列表页展示用；模型已删时回退原始 id 前缀） */
    fun observeEmbeddingModelNames() = combine(
        dao.getAllKnowledgeBasesFlow(),
        settingsStore.settingsFlow,
    ) { bases, settings ->
        val models = settings.providers.flatMap { it.models }.associateBy { it.id.toString() }
        bases.associate { kb ->
            kb.id to models[kb.embeddingModelId]?.let { "${it.displayName}" }.orEmpty()
        }
    }

    suspend fun getKnowledgeBases(ids: Set<String>): List<KnowledgeBaseEntity> =
        if (ids.isEmpty()) emptyList() else dao.getKnowledgeBases(ids)

    suspend fun createKnowledgeBase(name: String): KnowledgeBaseEntity {
        val kb = KnowledgeBaseEntity(
            id = java.util.UUID.randomUUID().toString(),
            name = name.ifBlank { "知识库" },
            createdAt = System.currentTimeMillis(),
        )
        dao.insertKnowledgeBase(kb)
        return kb
    }

    suspend fun updateKnowledgeBase(kb: KnowledgeBaseEntity) {
        dao.updateKnowledgeBase(
            id = kb.id,
            name = kb.name,
            embeddingModelId = kb.embeddingModelId,
            topK = kb.topK,
            similarityThreshold = kb.similarityThreshold,
            dimensions = kb.dimensions,
        )
    }

    suspend fun deleteKnowledgeBase(id: String) {
        dao.deleteEmbeddingsOfKnowledgeBase(id)
        dao.deleteDocumentsOfKnowledgeBase(id)
        dao.deleteKnowledgeBase(id)
    }

    // ---- 文档 ----

    fun getDocumentsFlow(kbId: String) = dao.getDocumentsOfKnowledgeBaseFlow(kbId)

    /**
     * 导入文档：读取 SAF Uri → 按类型解析为纯文本 → 落库（未索引状态）。
     * 返回 null = 解析失败（错误信息写入文档行的 error 列，仍保留行以便重试或删除）。
     */
    suspend fun importDocument(kb: KnowledgeBaseEntity, uri: Uri, displayName: String): KnowledgeBaseDocumentEntity? =
        withContext(Dispatchers.IO) {
            val doc = KnowledgeBaseDocumentEntity(
                id = java.util.UUID.randomUUID().toString(),
                kbId = kb.id,
                fileName = displayName.ifBlank { "document" },
                content = "",
                createdAt = System.currentTimeMillis(),
            )
            dao.insertDocument(doc)

            val content = runCatching { readAndParseDocument(uri, displayName) }.getOrElse {
                Log.w(TAG, "importDocument: failed to parse $displayName", it)
                null
            }
            if (content.isNullOrBlank()) {
                dao.updateDocumentIndexState(doc.id, indexed = false, chunkCount = 0, error = "无法读取文件内容")
                return@withContext doc.copy(error = "无法读取文件内容")
            }
            val trimmed = content.take(MAX_DOCUMENT_CHARS)
            dao.updateDocumentContent(doc.id, trimmed)
            doc.copy(content = trimmed)
        }

    suspend fun updateDocumentContent(documentId: String, content: String) {
        val doc = dao.getDocument(documentId) ?: return
        // 内容变化使既有向量失效
        dao.deleteEmbeddingsOfDocument(documentId)
        dao.updateDocumentIndexState(documentId, indexed = false, chunkCount = 0, error = null)
        dao.updateDocumentContent(documentId, content)
        Log.i(TAG, "updateDocumentContent: ${doc.fileName} → ${content.length} chars, embeddings invalidated")
    }

    suspend fun deleteDocument(documentId: String) {
        dao.deleteEmbeddingsOfDocument(documentId)
        dao.deleteDocument(documentId)
    }

    // ---- 索引 ----

    /**
     * 对单个文档（重新）向量化：删除旧向量 → 分块 → 分批调 embedding → 写入。
     * 结果写回文档行（indexed/chunkCount/error），**不抛异常**——UI/工具都按状态行消费。
     */
    suspend fun embedDocument(kb: KnowledgeBaseEntity, documentId: String) = withContext(Dispatchers.IO) {
        val doc = dao.getDocument(documentId) ?: return@withContext
        try {
            val (model, providerSetting) = resolveEmbeddingModel(kb)
                ?: throw IllegalStateException("未配置嵌入模型")
            val provider = providerManager.getProviderByType(providerSetting)

            val chunks = TextChunker.chunk(doc.content)
            if (chunks.isEmpty()) throw IllegalStateException("文档内容为空，无法索引")

            dao.deleteEmbeddingsOfDocument(documentId)
            val entities = mutableListOf<KnowledgeEmbeddingEntity>()
            for (batchStart in chunks.indices step EMBED_BATCH_SIZE) {
                val batch = chunks.subList(batchStart, (batchStart + EMBED_BATCH_SIZE).coerceAtMost(chunks.size))
                val result = provider.generateEmbedding(
                    providerSetting = providerSetting,
                    params = EmbeddingGenerationParams(
                        model = model,
                        input = batch,
                        dimensions = kb.dimensions,
                    ),
                )
                result.embeddings.forEachIndexed { i, vector ->
                    entities += KnowledgeEmbeddingEntity(
                        kbId = kb.id,
                        documentId = documentId,
                        chunkIndex = batchStart + i,
                        chunkText = batch[i],
                        vector = json.encodeToString(ListSerializer(Float.serializer()), vector),
                    )
                }
            }
            dao.insertEmbeddings(entities)
            dao.updateDocumentIndexState(documentId, indexed = true, chunkCount = chunks.size, error = null)
            Log.i(TAG, "embedDocument: ${doc.fileName} indexed ${chunks.size} chunks")
        } catch (e: Exception) {
            Log.w(TAG, "embedDocument: failed for ${doc.fileName}", e)
            dao.updateDocumentIndexState(documentId, indexed = false, chunkCount = 0, error = e.message ?: e.toString())
        }
    }

    // ---- 检索 ----

    /**
     * 跨库语义检索：查询文本向量化后与所选库全部向量算余弦，按相似度排序取 topK。
     * 任一环节失败返回空列表（工具侧如实告知模型「检索不可用/无结果」）。
     */
    suspend fun search(
        kbIds: Set<String>,
        query: String,
        topK: Int,
    ): List<KbSearchResult> = withContext(Dispatchers.IO) {
        if (kbIds.isEmpty() || query.isBlank()) return@withContext emptyList()
        val kbs = dao.getKnowledgeBases(kbIds).associateBy { it.id }
        if (kbs.isEmpty()) return@withContext emptyList()

        // 各库可能绑定不同 embedding 模型，查询向量必须按库分别生成
        kbs.values.mapNotNull { kb ->
            runCatching { searchInKnowledgeBase(kb, query) }.getOrElse { emptyList() }
        }.flatten()
            .sortedByDescending { it.similarity }
            .take(topK)
    }

    private suspend fun searchInKnowledgeBase(kb: KnowledgeBaseEntity, query: String): List<KbSearchResult> {
        val (model, providerSetting) = resolveEmbeddingModel(kb)
            ?: return emptyList()
        val provider = providerManager.getProviderByType(providerSetting)
        val result = provider.generateEmbedding(
            providerSetting = providerSetting,
            params = EmbeddingGenerationParams(
                model = model,
                input = listOf(query),
                dimensions = kb.dimensions,
            ),
        )
        val queryVector = result.embeddings.firstOrNull() ?: return emptyList()

        return dao.getEmbeddingsOfKnowledgeBases(listOf(kb.id))
            .mapNotNull { entity ->
                val vector = runCatching {
                    json.decodeFromString(ListSerializer(Float.serializer()), entity.vector)
                }.getOrNull() ?: return@mapNotNull null
                val similarity = cosineSimilarity(queryVector, vector)
                if (similarity < kb.similarityThreshold) return@mapNotNull null
                KbSearchResult(
                    kbId = kb.id,
                    kbName = kb.name,
                    chunkText = entity.chunkText,
                    similarity = similarity,
                )
            }
            .sortedByDescending { it.similarity }
            .take(kb.topK)
    }

    private fun resolveEmbeddingModel(kb: KnowledgeBaseEntity): Pair<Model, app.androidrik.ai.provider.ProviderSetting>? {
        if (kb.embeddingModelId.isBlank()) return null
        val modelId = runCatching { java.util.UUID.fromString(kb.embeddingModelId) }.getOrNull() ?: return null
        val settings = settingsStore.settingsFlow.value
        val model = settings.providers.flatMap { it.models }.firstOrNull { it.id == modelId } ?: return null
        val providerSetting = model.findProvider(settings.providers, checkOverwrite = false) ?: return null
        return model to providerSetting
    }

    // ---- 解析 ----

    private fun readAndParseDocument(uri: Uri, fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        // 先按扩展名分流：文本直读对二进制格式会「成功」读出乱码文本，绝不能先走
        when (ext) {
            "pdf", "docx", "pptx", "epub" -> return parseBinaryDocument(uri, ext, fileName)
        }
        return readTextLike(uri) ?: parseBinaryDocument(uri, ext, fileName)
    }

    /** 二进制文档格式：复制到缓存目录再走 document 模块解析（解析器只吃 File） */
    private fun parseBinaryDocument(uri: Uri, ext: String, fileName: String): String? {
        val tmp = File.createTempFile("kb_import", ".$ext", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return null
            return when (ext) {
                "pdf" -> PdfParser.parserPdf(tmp)
                "docx" -> DocxParser.parse(tmp)
                "pptx" -> PptxParser.parse(tmp)
                "epub" -> EpubParser.parse(tmp)
                else -> null
            }?.takeIf { it.isNotBlank() }
        } finally {
            tmp.delete()
        }
    }

    /** 文本类类型直接读取；返回 null = 非文本类型，交给文档解析器 */
    private fun readTextLike(uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            }
        }.getOrNull()
    }
}

/** 单条检索结果（跨库扁平化后交给工具层分组渲染） */
data class KbSearchResult(
    val kbId: String,
    val kbName: String,
    val chunkText: String,
    val similarity: Float,
)
