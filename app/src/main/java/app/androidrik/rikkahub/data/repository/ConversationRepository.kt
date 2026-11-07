package app.androidrik.rikkahub.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.fts.MessageFtsManager
import app.androidrik.rikkahub.data.db.fts.MessageSearchSort
import app.androidrik.rikkahub.data.db.dao.CompressionCheckpointDAO
import app.androidrik.rikkahub.data.db.dao.ConversationDAO
import app.androidrik.rikkahub.data.db.dao.FavoriteDAO
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity
import app.androidrik.rikkahub.data.export.ExportDisplayConfig
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.decideTierMove
import app.androidrik.rikkahub.data.model.TierMoveAction
import app.androidrik.rikkahub.data.model.planOrderWrite
import app.androidrik.rikkahub.data.model.OrderWrite
import app.androidrik.rikkahub.data.model.desiredTierOrder
import app.androidrik.rikkahub.data.model.placementOf
import app.androidrik.rikkahub.data.model.ConversationOrdering
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
    private val checkpointDAO: CompressionCheckpointDAO,
) {
    // 批次 9 拆出的两个存储：Koin 不动，仓库内部组装（调用面保持原样）。
    private val checkpointStore = CompressionCheckpointStore(checkpointDAO)
    private val nodeStore = ConversationNodeStore(messageNodeDAO, favoriteDAO, database)
    suspend fun updateConversationWorkspaceCwd(conversationId: Uuid, cwd: String?) {
        conversationDAO.updateWorkspaceCwd(conversationId.toString(), cwd.orEmpty())
    }

    suspend fun updateConversationUpstreamConfig(
        conversationId: Uuid,
        config: app.androidrik.rikkahub.data.model.UpstreamConversationConfig?,
    ) {
        conversationDAO.updateUpstreamConfig(
            conversationId.toString(),
            config?.let { JsonInstant.encodeToString(it) },
        )
    }

    // 队列附件清理：判断某 file:// URL 是否仍被任何消息 JSON 引用（保守保留所有分支出现的 URL）
    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl))

    companion object {
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
    }

    /** 委托到节点存储（批次 9 拆出，见 [ConversationNodeStore]）。 */
    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> =
        nodeStore.loadMessageNodes(conversationId)

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) =
        nodeStore.saveMessageNodes(conversationId, nodes)

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

    /** 某助手下的会话标题集合（分叉序号避让用；只查标题列，不加载节点）。 */
    suspend fun getTitlesOfAssistant(assistantId: Uuid): Set<String> =
        conversationDAO.getTitlesOfAssistant(assistantId.toString()).toSet()

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

    /**
     * 聊天记录中引用的助手 id → 对话数（数据恢复页用）。
     *
     * 解析失败的 id 直接跳过（脏数据不该让整张统计表崩掉）。
     */
    suspend fun countConversationsByAssistant(): Map<Uuid, Int> {
        return conversationDAO.countByAssistant().mapNotNull { row ->
            runCatching { Uuid.parse(row.assistantId) }.getOrNull()?.let { it to row.count }
        }.toMap()
    }

    suspend fun insertConversation(conversation: Conversation) {
        database.withTransaction {
            val entity = conversationToConversationEntity(conversation)
            // 自定义顺序：新会话落在**所在档位的最前**（设计稿 §一.1）。
            // 自动模式（该档无位置值）不需要任何写入——新会话的 update_at 最新，本来就排最前；
            // 只有该档已物化时才需要分配 min-STEP（否则它会因 null 沉到已排序项之后）。
            val pinned = conversation.isPinned
            val sortOrder = if (conversationDAO.isSortOrderMaterialized(
                    conversation.assistantId.toString(),
                    pinned,
                )
            ) {
                ConversationOrdering.topSlot(
                    conversationDAO.getSortOrders(conversation.assistantId.toString(), pinned)
                )
            } else {
                null
            }
            conversationDAO.insert(entity.copy(sortOrder = sortOrder))
            saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
        }
        messageFtsManager.indexConversation(conversation)
    }

    /** 某档是否已进入自定义顺序（UI 与写入决策共用的判定）。 */
    suspend fun isTierMaterialized(assistantId: Uuid, pinned: Boolean): Boolean =
        conversationDAO.isSortOrderMaterialized(assistantId.toString(), pinned)

    /** 观察某档是否已进入自定义顺序（UI：隐藏日期分组头 / 显示「恢复默认顺序」）。 */
    fun observeTierMaterialized(assistantId: Uuid, pinned: Boolean): Flow<Boolean> =
        conversationDAO.observeTierMaterialized(assistantId.toString(), pinned)

    /**
     * 「编辑顺序」模式的**非分页**列表：该助手全部会话（轻量投影，不含消息），按当前显示顺序。
     *
     * 为什么编辑模式不分页：拖拽需要一个**完整且稳定**的顺序（分页只加载已滚动到的部分，
     * 缺项时算出的兄弟节点是错的）。会话数在此量级（几十~几百）下，一次性加载代价可接受。
     */
    fun getLightConversationsForOrdering(
        assistantId: Uuid,
        folderId: Uuid?,
    ): Flow<List<Conversation>> =
        conversationDAO.getLightConversationsForOrdering(
            assistantId.toString(),
            folderId?.toString() ?: "",
        ).map { entities -> entities.map(::conversationSummaryToConversation) }

    /**
     * 提交一次手工排序（「编辑顺序」模式松手时调用）——`sort_order` 的**唯一**业务写入口。
     *
     * 入参是「被拖项 + 它的新邻居」而**不是整表**：抽屉列表是分页的，未滚到底时全量 id 并不完整，
     * 而邻居一定是已加载的项（分页安全）。整档写入只在必要时发生：
     * - **常规**：只把被拖那条写到前后邻居的中值（1 行）；
     * - **首次物化 / 间距耗尽**：由仓库读该档完整顺序后按目标位次整档重排
     *   （归一化只在**提交时**发生，绝不在拖拽过程中改数据）。
     */
    suspend fun applyManualOrder(
        assistantId: Uuid,
        pinned: Boolean,
        movedId: String,
        beforeId: String?,
        afterId: String?,
    ) {
        database.withTransaction {
            val tierIds = conversationDAO.getTierIds(assistantId.toString(), pinned)
            if (movedId !in tierIds) return@withTransaction
            val currentValues = conversationDAO.getTierOrderRows(assistantId.toString(), pinned)
                .associate { it.id to it.sortOrder }

            // 目标顺序：把 movedId 摘出后按邻居信息插回。锚点语义与落点换算都下沉到
            // ConversationOrdering 的纯函数（desiredTierOrder / placementOf），单测钉住了
            // 两个真机踩过的错：afterId 必须插在它**前**面（否则整体差一位）、
            // 末尾落点必须是 Bottom（否则拖到最底会跳到最前）。
            val desired = desiredTierOrder(tierIds, movedId, beforeId, afterId)

            val write: OrderWrite = if (currentValues.isEmpty()) {
                // 该档还没进过自定义顺序：按目标顺序一次性物化（顺带覆盖「间距耗尽」的整档重排）
                OrderWrite.Renormalize(desired, ConversationOrdering.materialize(desired.size))
            } else {
                planOrderWrite(
                    movedId,
                    placementOf(desired, movedId, currentValues),
                    currentValues.values.toList(),
                    desired,
                )
            }

            when (write) {
                is OrderWrite.Single -> conversationDAO.updateSortOrder(write.conversationId, write.sortOrder)
                is OrderWrite.Renormalize -> write.orderedIds.forEachIndexed { index, id ->
                    conversationDAO.updateSortOrder(id, write.values[index])
                }
            }
        }
    }

    /**
     * 「恢复默认顺序」：清空该助手**两档**（置顶 + 未置顶）的全部位置值 ⇒ 回到按 `update_at`
     * 的自动模式（设计稿 §一.7）。
     *
     * 为什么必须清两档：编辑模式里**两档都能拖**（置顶档也有自己的编号空间），只清未置顶档会
     * 留下"清不干净"的手工顺序——用户拖过的置顶档仍按旧值排，而对话框文案承诺的是
     * 「列表回到按更新时间的排序」。用户 2026-10-04 手动验证时正是先拖了置顶档才暴露这点。
     * 两条 `clearSortOrder` 放在同一事务里，避免出现"一档清了、另一档没清"的中间态。
     */
    suspend fun resetTierOrder(assistantId: Uuid) {
        database.withTransaction {
            conversationDAO.clearSortOrder(assistantId.toString(), isPinned = false)
            conversationDAO.clearSortOrder(assistantId.toString(), isPinned = true)
        }
    }

    suspend fun updateConversation(conversation: Conversation) {
        database.withTransaction {
            // 目标列 UPDATE（不是全行）——见 ConversationDAO.updateMetadata 的注释：
            // 全行覆盖会把 sort_order 写回内存态的旧值，把刚拖好的顺序改回去。
            conversationToConversationEntity(conversation).let { entity ->
                conversationDAO.updateMetadata(
                    id = entity.id,
                    assistantId = entity.assistantId,
                    title = entity.title,
                    createAt = entity.createAt,
                    updateAt = entity.updateAt,
                    chatSuggestions = entity.chatSuggestions,
                    isPinned = entity.isPinned,
                    customSystemPrompt = entity.customSystemPrompt,
                    goal = entity.goal,
                    modeInjectionIds = entity.modeInjectionIds,
                    lorebookIds = entity.lorebookIds,
                    workspaceCwd = entity.workspaceCwd,
                    folderId = entity.folderId,
                    lastMessageAt = entity.lastMessageAt,
                    memoryDisabled = entity.memoryDisabled,
                    hidden = entity.hidden,
                    upstreamConfig = entity.upstreamConfig,
                )
            }
            // 增量保存（不再删全表重插；见 saveMessageNodes）。
            // **保护**：messageNodes 为空时不动节点——避免误传「轻量 Conversation」
            // （如 conversationSummaryToConversation 产物）导致整会话节点被清空（不可逆）。
            if (conversation.messageNodes.isNotEmpty()) {
                saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
            }
        }
        messageFtsManager.indexConversation(conversation)
    }

    /**
     * 流式生成期间的**检查点落库**：只 upsert 正在生成的那**一个节点**，使进程被杀/崩溃后
     * 重进会话仍能看到断点前的输出（此前流式内容只存在于内存，见 [saveMessageNodes] 的落库点）。
     *
     * 为什么不是复用 [updateConversation] / [saveMessageNodes]：
     * - 后两者按「公共前缀」增量保存，需要先把**整会话所有节点**序列化一遍求签名。检查点每 2 秒
     *   一次，长会话下这个代价远超收益；这里只序列化末尾一个节点、只写一行。
     * - **不写 FTS 索引**：索引是整会话重建，每 2 秒重建一次不可接受；库内节点已最新，索引在
     *   下一次正常保存（轮次结束）时补齐。进程若恰在生成中被杀，该会话暂不参与搜索，
     *   下次任意交互即恢复——可接受的取舍。
     *
     * 语义约束（调用方保证）：[nodeIndex] 是**正在生成的那个节点**在 `conversation.messageNodes`
     * 中的索引——它等于本次流回写的 `chunk.messages.lastIndex`（`updateCurrentMessages` 按索引映射），
     * **不能假定它是 `messageNodes.lastIndex`**：`THIS_ONLY` scope 的重生成只改动中间节点，
     * 其后仍有下游节点。节点 id 与已落库行一致（节点原地 `copy`）⇒ `@Insert(REPLACE)` 按主键
     * 覆盖即 upsert，不需要前缀比对。
     *
     * 会话行尚不存在时（首条消息生成中）整会话落库——否则节点外键指向不存在的会话。
     */
    suspend fun checkpointGeneratingNode(conversation: Conversation, nodeIndex: Int) {
        val node = conversation.messageNodes.getOrNull(nodeIndex) ?: return
        database.withTransaction {
            if (!conversationDAO.existsById(conversation.id.toString())) {
                conversationDAO.insert(conversationToConversationEntity(conversation))
                // 新会话：全部节点一并写入（只写变动节点会缺历史节点）
                saveMessageNodes(conversation.id.toString(), conversation.messageNodes)
                return@withTransaction
            }
            messageNodeDAO.insert(
                MessageNodeEntity(
                    id = node.id.toString(),
                    conversationId = conversation.id.toString(),
                    nodeIndex = nodeIndex,
                    messages = JsonInstant.encodeToString(node.messages),
                    selectIndex = node.selectIndex,
                )
            )
        }
    }

    suspend fun deleteConversation(conversation: Conversation, deleteFiles: Boolean = true) {        // 获取完整的 Conversation（包含 messageNodes）以正确清理文件
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
        // 整段包在**一个** Room 事务里：indexConversation/deleteAll 内部再 withTransaction 会加入
        // 外层事务，避免每个会话都提交一次 WAL（真机 269 次提交约 10s 级开销）。
        database.withTransaction {
            messageFtsManager.deleteAll()
            val allIds = conversationDAO.getAllIds()
            val total = allIds.size
            allIds.forEachIndexed { index, id ->
                val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
                val nodes = loadMessageNodes(entity.id)
                val conversation = conversationEntityToConversation(entity, nodes)
                messageFtsManager.indexConversation(conversation, incremental = false)
                onProgress(index + 1, total)
            }
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    // ---- 压缩检查点读写（批次 9 拆出，见 [CompressionCheckpointStore]）----

    /** 加载会话的全部检查点（末行 = 最新边界 = active） */
    suspend fun getCompressionCheckpoints(conversationId: Uuid): List<CompressionSummary> =
        checkpointStore.getCompressionCheckpoints(conversationId)

    /**
     * 追加一条检查点（轨迹只追加）。返回落库后的 Summary（含自增 id）。
     * 调用方负责在合并旧摘要后调 [trimCompressionCheckpoints] 清理冗余行。
     */
    suspend fun addCompressionCheckpoint(summary: CompressionSummary): CompressionSummary =
        checkpointStore.addCompressionCheckpoint(summary)

    /** 只保留最新检查点，删除更早的行（新摘要已合并旧摘要时调用） */
    suspend fun trimCompressionCheckpoints(conversationId: Uuid) {
        checkpointStore.trimCompressionCheckpoints(conversationId)
    }

    /** 删除会话的全部检查点（取消压缩） */
    suspend fun deleteCompressionCheckpoints(conversationId: Uuid) {
        checkpointStore.deleteCompressionCheckpoints(conversationId)
    }

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
        database.withTransaction {
            conversationDAO.updatePinStatus(conversationId.toString(), isPinned)

            // 档位变动时的位置值一致性（设计稿 §一.6 + ConversationOrdering.decideTierMove）：
            // 「保留原值」只覆盖「两边都已物化」；源无值/目标自动等组合各有规则，
            // 不处理就会出现**档内混用 null 与非 null**（局部顺序无法解释）。
            val assistantId = conversationDAO.getAssistantIdOf(conversationId.toString()) ?: return@withTransaction
            val action = decideTierMove(
                hasValue = conversationDAO.getSortOrder(conversationId.toString()) != null,
                destinationMaterialized = conversationDAO.isSortOrderMaterialized(assistantId, isPinned),
            )
            when (action) {
                TierMoveAction.NOTHING, TierMoveAction.KEEP_VALUE -> Unit

                // 目标档还是自动模式：先按现状物化，再让这条带着原值进来
                // （位次由该值决定，通常是新档的最前——正是置顶想要的观感）
                TierMoveAction.MATERIALIZE_THEN_KEEP -> materializeTier(assistantId, isPinned)

                // 源从未有过位置值（null 会沉到已排序项之后）⇒ 物化目标档并给它档位最前
                TierMoveAction.MATERIALIZE_AND_PLACE_TOP -> {
                    materializeTier(assistantId, isPinned)
                    conversationDAO.updateSortOrder(
                        conversationId.toString(),
                        ConversationOrdering.topSlot(conversationDAO.getSortOrders(assistantId, isPinned)),
                    )
                }
            }
        }
    }

    /** 按当前显示顺序把某档整档物化为 1000 步长序列（已有的值会被覆盖为规范序列）。 */
    private suspend fun materializeTier(assistantId: String, pinned: Boolean) {
        val ids = conversationDAO.getTierIds(assistantId, pinned)
        ConversationOrdering.materialize(ids.size).forEachIndexed { index, value ->
            conversationDAO.updateSortOrder(ids[index], value)
        }
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

    /**
     * 设置会话级「不使用记忆」（隐身对话；设计见 `docs/design/design-memory-discipline.md` §4.4）。
     *
     * 与 [updateConversationPinStatus] 同款：**调用方算好新值并同步内存态**，两边用同一个值。
     * 入口在 `ChatService.setConversationMemoryDisabled`——它持有 session，能同时改内存。
     */
    suspend fun updateConversationMemoryDisabled(conversationId: Uuid, disabled: Boolean) {
        conversationDAO.updateMemoryDisabled(conversationId.toString(), disabled)
    }

    /** 隐藏会话的单列回写（「移出隐藏 / 移入隐藏」；见 [ConversationDAO.setHidden]）。 */
    suspend fun updateConversationHidden(conversationId: Uuid, hidden: Boolean) {
        conversationDAO.setHidden(conversationId.toString(), hidden)
    }

    /**
     * 自动起名的**单列回写**（只写 `title`）。
     *
     * 为什么不走 [updateConversation]：起名是数秒的网络请求，返回时调用方手里的会话快照
     * 已经过期（期间还在流式接收）；整对象写回会把旧快照的 `messageNodes` 盖到活跃内存态上
     * ⇒ 标题回来的那一刻，在途正文当场消失。这个入口**每轮自动触发**，是默认路径。
     */
    suspend fun updateTitle(conversationId: Uuid, title: String) {
        conversationDAO.updateTitle(conversationId.toString(), title)
    }

    /**
     * 聊天建议的**单列回写**（只写 `suggestions`，JSON 编码与整对象保存共用同一格式）。
     *
     * 理由与 [updateTitle] 同款（网络请求 + 每轮自动触发 + 生成中回写）。
     */
    suspend fun updateChatSuggestions(conversationId: Uuid, suggestions: List<String>) {
        conversationDAO.updateChatSuggestions(
            conversationId.toString(),
            JsonInstant.encodeToString(suggestions),
        )
    }

    /**
     * 会话级提示词注入绑定（mode / lorebook）的**单列回写**（两条 `*_ids` 列，同一条语句）。
     *
     * 理由与 [updateTitle] 同族：Web/API 的编辑入口当时手里只有「读到的整对象」，
     * 整对象写回会把正在流式生成的在途正文换成旧快照。只动这两列 ⇒ 与生成互不干扰。
     */
    suspend fun updateConversationInjections(
        conversationId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
    ) {
        conversationDAO.updateConversationInjections(
            id = conversationId.toString(),
            modeInjectionIds = encodeConversationInjectionIds(modeInjectionIds),
            lorebookIds = encodeConversationInjectionIds(lorebookIds),
        )
    }

    /**
     * L1 锚点快照的**单会话**写入点（会话开始冻结；见 `ConversationDAO.updateFrozenAnchors`）。
     *
     * 只写 `frozen_anchors` 一列 —— 不走 [updateConversation]，否则会把调用方手里的
     * 旧快照（尤其是刚冻结之前的 `null`）一并写回其他列。
     */
    suspend fun updateConversationFrozenAnchors(conversationId: Uuid, frozen: String?) {
        conversationDAO.updateFrozenAnchors(conversationId.toString(), frozen)
    }

    /**
     * L1 锚点快照的**按助手批量**写入点（记忆页「固化为锚点」的手动刷新；见设计稿 §5）。
     *
     * 用户改的是助手的锚点集合，而记忆页没有「当前会话」上下文 ⇒ 该助手所有会话一起换档。
     */
    suspend fun updateFrozenAnchorsForAssistant(assistantId: Uuid, frozen: String?): Int =
        conversationDAO.updateFrozenAnchorsOfAssistant(assistantId.toString(), frozen)

    /**
     * 会话级导出显示覆盖的**单列**写入点（`null` = 恢复跟随全局设置）。
     *
     * 只写 `export_display_config` 一列 —— 不走 [updateConversation]，理由同
     * [updateConversationFrozenAnchors]：列表轻投影不带该列，整对象保存会把覆盖写回 null。
     */
    suspend fun updateConversationExportDisplayConfig(
        conversationId: Uuid,
        config: ExportDisplayConfig?,
    ) {
        conversationDAO.updateExportDisplayConfig(
            id = conversationId.toString(),
            config = config?.let { JsonInstant.encodeToString(it) },
        )
    }

    /**
     * **按消息覆盖导出显示**的整列写入点（`messageId → config`；空 map 存 null）。
     *
     * 多选设置/清除用整列替换，保证「一次操作要么全生效、要么全不生效」。
     * 理由同 [updateConversationExportDisplayConfig]：列表轻投影不带该列。
     */
    suspend fun updateConversationExportDisplayOverrides(
        conversationId: Uuid,
        overrides: Map<String, ExportDisplayConfig>,
    ) {
        conversationDAO.updateExportDisplayOverrides(
            id = conversationId.toString(),
            overrides = overrides.takeIf { it.isNotEmpty() }?.let { JsonInstant.encodeToString(it) },
        )
    }

    /** 加载会话的压缩检查点（末行 = active）；失败静默返回空（不阻塞会话打开） */
    private suspend fun loadCompressionSummaries(conversationId: String): List<CompressionSummary> =
        checkpointStore.loadCompressionSummaries(conversationId)
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
    /** 末尾消息的 createdAt（毫秒）；旧数据为 null，UI 回退 `updateAt`（见 ConversationEntity 注释） */
    val lastMessageAt: Long? = null,
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)
