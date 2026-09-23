package app.androidrik.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.util.Log
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.fts.MessageFtsManager
import app.androidrik.rikkahub.data.db.fts.MessageSearchSort
import app.androidrik.rikkahub.data.db.dao.CompressionCheckpointDAO
import app.androidrik.rikkahub.data.db.dao.ConversationDAO
import app.androidrik.rikkahub.data.db.dao.FavoriteDAO
import app.androidrik.rikkahub.data.ai.prompts.filterOutCheckpointMessages
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import app.androidrik.rikkahub.data.db.entity.CompressionCheckpointEntity
import app.androidrik.rikkahub.data.db.entity.ConversationEntity
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.repairCorruptedCandidates
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.utils.JsonInstant
import java.time.Instant
import kotlin.uuid.Uuid

/** 列表加载耗时埋点（诊断会话打开加载耗时，供判断是否需要窗口化） */
private const val TAG = "ConversationRepository"

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    private val checkpointDAO: CompressionCheckpointDAO,
) {
    // 队列附件清理：判断某 file:// URL 是否仍被任何消息 JSON 引用（保守保留所有分支出现的 URL）
    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl))

    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40

        /** 签名分隔符（控制字符 U+0001，JSON 与 UUID 中不会出现，避免拼接歧义） */
        internal const val SEP = "\u0001"
    }

    /**
     * 最近会话。[加载节点] 默认 false（仅需 id/标题/时间的调用方不应付出
     * 全量 JSON 解析代价——如 get_recent_chats 工具）。
     */
    suspend fun getRecentConversations(
        assistantId: Uuid,
        limit: Int = 10,
        loadNodes: Boolean = false,
    ): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            if (loadNodes) {
                val nodes = loadMessageNodes(entity.id)
                conversationEntityToConversation(entity, nodes, loadCompressionSummaries(entity.id))
            } else {
                conversationEntityToConversation(entity, emptyList())
            }
        }
    }

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(folderId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfFolderPaging(folderId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString()),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString()),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    fun searchConversations(titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversations(titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsPaging(titleKeyword: String): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.searchConversationsPaging(titleKeyword) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun searchConversationsOfAssistant(assistantId: Uuid, titleKeyword: String): Flow<List<Conversation>> {
        return conversationDAO
            .searchConversationsOfAssistant(assistantId.toString(), titleKeyword)
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun searchConversationsOfAssistantPaging(assistantId: Uuid, titleKeyword: String): Flow<PagingData<Conversation>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                conversationDAO.searchConversationsOfAssistantPaging(
                    assistantId.toString(),
                    titleKeyword
                )
            }
        ).flow.map { pagingData ->
            pagingData.map { entity ->
                conversationSummaryToConversation(entity)
            }
        }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes, loadCompressionSummaries(entity.id))
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.insert(
                conversationToConversationEntity(conversation)
            )
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun updateConversation(conversation: Conversation) {
        database.withTransaction {
            conversationDAO.update(
                conversationToConversationEntity(conversation)
            )
            // 增量保存（不再删全表重插；见 saveMessageNodes）。
            // **保护**：messageNodes 为空时不动节点——避免误传「轻量 Conversation」
            // （如 conversationSummaryToConversation 产物）导致整会话节点被清空（不可逆）。
            if (conversation.messageNodes.isNotEmpty()) {
                saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
            }
        }
        messageFtsManager.indexConversation(conversation)
    }

    suspend fun deleteConversation(conversation: Conversation, deleteFiles: Boolean = true) {
        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        messageFtsManager.deleteConversation(conversation.id.toString())
        database.withTransaction {
            // message_node 会通过 CASCADE 自动删除
            conversationDAO.delete(
                conversationToConversationEntity(conversation)
            )
        }
        // 历史页撤销场景：deleteFiles=false 时保留附件文件，
        // 撤销窗口结束后由调用方清理（否则撤销恢复的对话附件会永久丢失）。
        if (deleteFiles) {
            filesManager.deleteChatFiles(fullConversation.files)
        }
    }

    suspend fun deleteConversationFiles(conversation: Conversation) {
        filesManager.deleteChatFiles(conversation.files)
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ) = messageFtsManager.search(keyword, sort)

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        // 注意：compressionSummaries 不在此持久化（独立表 compression_checkpoint 管理，见
        // getCompressionCheckpoints/addCompressionCheckpoint）。整对象保存不会误删检查点；
        // 但内存态若构造时漏填 compressionSummaries，会导致该会话内存中压缩态「看起来丢失」，
        // 重新从 DB 加载即恢复（加载路径 loadCompressionSummaries 已填充）。
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            folderId = conversation.folderId?.toString() ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>,
        compressionSummaries: List<CompressionSummary> = emptyList(),
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            compressionSummaries = compressionSummaries,
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    // ---- 压缩检查点读写（设计见 docs/design-auto-compaction.md §4.4 独立表方案）----

    /** 加载会话的全部检查点（末行 = 最新边界 = active） */
    suspend fun getCompressionCheckpoints(conversationId: Uuid): List<CompressionSummary> =
        checkpointDAO.getOfConversation(conversationId.toString()).map { it.toSummary() }

    /**
     * 追加一条检查点（轨迹只追加）。返回落库后的 Summary（含自增 id）。
     * 调用方负责在合并旧摘要后调 [trimCompressionCheckpoints] 清理冗余行。
     */
    suspend fun addCompressionCheckpoint(summary: CompressionSummary): CompressionSummary {
        val id = checkpointDAO.insert(
            CompressionCheckpointEntity(
                conversationId = summary.conversationId.toString(),
                content = summary.content,
                messageCount = summary.messageCount,
                boundaryNodeId = summary.boundaryNodeId,
                createdAt = summary.createdAt.toEpochMilli(),
                areaFingerprint = summary.areaFingerprint,
            )
        )
        return summary.copy(id = id)
    }

    /** 只保留最新检查点，删除更早的行（新摘要已合并旧摘要时调用） */
    suspend fun trimCompressionCheckpoints(conversationId: Uuid) {
        checkpointDAO.trimToLatest(conversationId.toString())
    }

    /** 删除会话的全部检查点（取消压缩） */
    suspend fun deleteCompressionCheckpoints(conversationId: Uuid) {
        checkpointDAO.deleteOfConversation(conversationId.toString())
    }

    private fun CompressionCheckpointEntity.toSummary(): CompressionSummary = CompressionSummary(
        id = id,
        conversationId = Uuid.parse(conversationId),
        content = content,
        messageCount = messageCount,
        boundaryNodeId = boundaryNodeId,
        createdAt = Instant.ofEpochMilli(createdAt),
        areaFingerprint = areaFingerprint,
    )

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    /**
     * 设置置顶状态（由调用方算好新值并负责同步内存态）。
     *
     * 为什么由调用方传值而不在此处「自行取反」：一次切换要同时更新**内存态与数据库**，
     * 两边必须用同一个新值。若这里取反、调用方再取反，两者读到的基线不同就会分叉
     * （内存说已置顶、DB 说未置顶，下一次整对象保存即把 DB 覆盖回去）。
     * 入口在 `ChatService.togglePinStatus`——它持有 session，能同时改内存。
     */
    suspend fun updateConversationPinStatus(conversationId: Uuid, isPinned: Boolean) {
        conversationDAO.updatePinStatus(conversationId.toString(), isPinned)
    }

    /**
     * 单列更新会话归属的助手（并清空 folder_id，见 DAO 注释）。
     *
     * 由 `ChatService.moveConversationToAssistant` 经统一元数据原语调用——
     * 不整对象写回，故不会用旧快照覆盖活跃 session 的在途内容。
     */
    suspend fun updateConversationAssistant(conversationId: Uuid, assistantId: Uuid) {
        conversationDAO.updateAssistantId(conversationId.toString(), assistantId.toString())
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    /** 加载会话的压缩检查点（末行 = active）；失败静默返回空（不阻塞会话打开） */
    private suspend fun loadCompressionSummaries(conversationId: String): List<CompressionSummary> =
        runCatching { checkpointDAO.getOfConversation(conversationId).map { it.toSummary() } }
            .getOrElse { emptyList() }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val startNs = System.nanoTime()
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: SQLiteBlobTooBigException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                } catch (e: IllegalStateException) {
                    e.printStackTrace()
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val rawMessages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    // 自愈历史 bug：早前版本把检查点合成消息误持久化为消息候选（界面只看到摘要）。
                    // 清洗后 selectIndex 需回落到有效范围（指向被移除的候选时回退到最后一条）。
                    val messages = rawMessages.filterOutCheckpointMessages()
                    // 未发生清洗（条数不变）时保持原 selectIndex；有清洗时按 id 重定位，
                    // 原选中项被移除则回退到最后一条（保持节点可显示）
                    val cleanSelectIndex = when {
                        messages.isEmpty() -> 0
                        messages.size == rawMessages.size -> entity.selectIndex
                        else -> {
                            val selected = rawMessages.getOrNull(entity.selectIndex)
                            val relocated = selected?.let { sel ->
                                messages.indexOfFirst { it.id == sel.id }
                            } ?: -1
                            if (relocated >= 0) relocated else messages.lastIndex
                        }
                    }
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = cleanSelectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            // 自愈早期压缩 bug 造成的跨节点候选副本（详见 repairCorruptedCandidates）
            nodes.repairCorruptedCandidates()
        }.also { loaded ->
            // 耗时埋点：打开会话、列表加载都经过这里。
            // 触发窗口化的阈值（2026-09-20 实测修正，design-auto-compaction 附录 A §5.2）：
            // 驱动因素是**字节量而非节点数**——1024 节点/334KB 只要 232ms，而节点更少（912）
            // 但 16MB 的要 1706ms。故判据 = 单会话 >1MB 字节量、或此日志 >300ms。
            // 字节量可直接查：SELECT SUM(LENGTH(messages)) FROM message_node GROUP BY conversation_id
            val ms = (System.nanoTime() - startNs) / 1_000_000
            Log.i(TAG, "loadMessageNodes: ${loaded.size} nodes in ${ms}ms (conv=$conversationId)")
        }
    }

    /**
     * 增量保存消息节点：只写「与库中公共前缀之后」的部分，并清理已不存在的尾部节点。
     *
     * **为何**：原实现每次保存都 `deleteByConversation` **删除全表再按下标重插**——
     * 5000 节点的会话发一条消息就重写 5000 行；且该写法意味着
     * 「内存列表 ≡ 全量」是写入侧依赖的不变量（窗口化前置障碍）。
     *
     * 算法：求「库中签名序列」与「内存签名序列」的公共**内容**前缀
     * （签名 = id + select_index + messages，盖住「编辑后候选变但 id 不变」）
     * -> 删除库中前缀之后的旧行 -> 插入内存中前缀之后的新行。
     * 常见场景（尾部追加/修改最后一条）即只写 1 行。
     *
     * `node_index` 用**绝对位置**（common + i）而非分片内下标，为将来窗口化留余地。
     */
    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val existing = messageNodeDAO.getNodeSignaturesOfConversation(conversationId)
        val incoming = nodes.map { node ->
            node.id.toString() + SEP + node.selectIndex + SEP + JsonInstant.encodeToString(node.messages)
        }
        val common = commonPrefixLength(existing, incoming)

        // 库中前缀之后的旧行已不存在于内存 -> 删除（含 node_index = common）
        if (common < existing.size) {
            messageNodeDAO.deleteFromIndex(conversationId, common)
        }
        if (common >= nodes.size) return
        val entities = nodes.drop(common).mapIndexed { offset, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = common + offset,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }

    /** 两个签名序列的公共前缀长度（签名 = id + select_index + messages） */
    private fun commonPrefixLength(a: List<String>, b: List<String>): Int {
        val max = minOf(a.size, b.size)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
