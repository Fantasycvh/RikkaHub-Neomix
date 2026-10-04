package app.androidrik.rikkahub.ui.pages.chat

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.Folder
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.FolderRepository
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.utils.toLocalString
import app.androidrik.workspace.WorkspaceFileEntry
import app.androidrik.workspace.WorkspaceStorageArea
import java.time.LocalDate
import java.time.ZoneId
import kotlin.uuid.Uuid

class ChatDrawerVM(
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val chatService: ChatService,
    private val workspaceRepo: WorkspaceRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val assistantIdFlow = settingsStore.settingsFlow
        .map { it.assistantId }
        .distinctUntilChanged()

    // 当前选中的文件夹筛选，null 表示「未归类」视图
    private val _selectedFolderId = MutableStateFlow<Uuid?>(null)
    val selectedFolderId: StateFlow<Uuid?> = _selectedFolderId.asStateFlow()

    // 当前助手的文件夹列表（Room Flow，增删改自动刷新）
    val folders: StateFlow<List<Folder>> = assistantIdFlow
        .flatMapLatest { folderRepo.getFoldersOfAssistant(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * 该助手是否已进入**自定义顺序**（非置顶档存在位置值）——UI 据此隐藏日期分组头
     * （顺序由用户定义后，按日期分组已无意义）并决定「恢复默认顺序」是否可用。
     */
    val customOrderActive: StateFlow<Boolean> = assistantIdFlow
        .flatMapLatest { conversationRepo.observeTierMaterialized(it, pinned = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * 「编辑顺序」模式的数据源：**非分页**轻量列表（镜像当前视图：未归类或选中的文件夹）。
     *
     * 为什么编辑模式不分页：拖拽需要一个完整且稳定的顺序——分页只加载已滚动到的部分，
     * 缺项时算出的邻居是错的（见仓库 applyManualOrder 的注释）。
     */
    val editableConversations: Flow<List<Conversation>> =
        combine(assistantIdFlow, _selectedFolderId) { assistantId, folderId -> assistantId to folderId }
            .flatMapLatest { (assistantId, folderId) ->
                conversationRepo.getLightConversationsForOrdering(assistantId, folderId)
            }

    val conversations: Flow<PagingData<ConversationListItem>> =
        // 「是否自定义顺序」决定要不要插日期分组头，因此它也是**分页数据的输入**，
        // 必须与助手/文件夹同层进 flatMapLatest。
        //
        // 不要在分页流之后用 `combine(customOrderActive)` 换标志：PagingData 只能被收集一次，
        // combine 会把**同一个** PagingData 再包一层（pagingData.map{}.insertSeparators{}），
        // 底层 PageFetcherSnapshot.pageEventFlow 因此被二次收集 ⇒
        // `IllegalStateException: Attempt to collect twice from pageEventFlow`。
        // 真机事故（2026-10-04）：某档首次落值（标志 false→true）当帧崩溃——自定义顺序用户必踩。
        // 放进 flatMapLatest 后，标志变化会新建 Pager（新 PagingSource／新快照），合法且顺序正确。
        combine(assistantIdFlow, _selectedFolderId, customOrderActive) { assistantId, folderId, customOrder ->
            Triple(assistantId, folderId, customOrder)
        }
            .flatMapLatest { (assistantId, folderId, customOrder) ->
                val paging = if (folderId == null) {
                    conversationRepo.getUnfiledConversationsOfAssistantPaging(assistantId)
                } else {
                    conversationRepo.getConversationsOfFolderPaging(folderId)
                }
                paging.map { pagingData ->
                    pagingData
                        .map { ConversationListItem.Item(it) }
                        .insertSeparators<ConversationListItem.Item, ConversationListItem> { before, after ->
                            // 自定义顺序下**不插日期分组头**：顺序由用户定义，按日期分组只会
                            // 把同一档切成无意义的段落（置顶头仍保留——它标记的是档位，不是日期）
                            val wantDateHeader = !customOrder
                            when {
                                before == null && after is ConversationListItem.Item -> {
                                    if (after.conversation.isPinned) {
                                        ConversationListItem.PinnedHeader
                                    } else if (!wantDateHeader) {
                                        null
                                    } else {
                                        val afterDate = after.conversation.updateAt
                                            .atZone(ZoneId.systemDefault())
                                            .toLocalDate()
                                        ConversationListItem.DateHeader(
                                            date = afterDate,
                                            label = getDateLabel(afterDate)
                                        )
                                    }
                                }

                                before is ConversationListItem.Item && after is ConversationListItem.Item -> {
                                    if (before.conversation.isPinned && !after.conversation.isPinned) {
                                        if (!wantDateHeader) {
                                            null
                                        } else {
                                            val afterDate = after.conversation.updateAt
                                                .atZone(ZoneId.systemDefault())
                                                .toLocalDate()
                                            ConversationListItem.DateHeader(
                                                date = afterDate,
                                                label = getDateLabel(afterDate)
                                            )
                                        }
                                    } else if (!after.conversation.isPinned && wantDateHeader) {
                                        val beforeDate = before.conversation.updateAt
                                            .atZone(ZoneId.systemDefault())
                                            .toLocalDate()
                                        val afterDate = after.conversation.updateAt
                                            .atZone(ZoneId.systemDefault())
                                            .toLocalDate()

                                        if (beforeDate != afterDate) {
                                            ConversationListItem.DateHeader(
                                                date = afterDate,
                                                label = getDateLabel(afterDate)
                                            )
                                        } else {
                                            null
                                        }
                                    } else {
                                        null
                                    }
                                }

                                else -> null
                            }
                        }
                }
            }
            .cachedIn(viewModelScope)

    val scrollIndex: Int get() = savedStateHandle["scrollIndex"] ?: 0
    val scrollOffset: Int get() = savedStateHandle["scrollOffset"] ?: 0

    init {
        // 助手切换时重置文件夹筛选，回到「聊天」视图，
        // 避免继续显示上一个助手文件夹内的会话（文件夹是助手内分组）
        viewModelScope.launch {
            assistantIdFlow.collect {
                _selectedFolderId.value = null
            }
        }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        savedStateHandle["scrollIndex"] = index
        savedStateHandle["scrollOffset"] = offset
    }

    /**
     * 提交一次拖拽（「编辑顺序」模式）。
     *
     * 只传**被拖项与它的新邻居**而非整表：抽屉列表是分页的，全量 id 在未滚到底时并不完整，
     * 而邻居一定是已加载的项。归一化/首次物化的整档写入由仓库按需完成（见 applyManualOrder）。
     */
    fun applyManualOrder(conversationId: Uuid, pinned: Boolean, beforeId: Uuid?, afterId: Uuid?) {
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            runCatching {
                conversationRepo.applyManualOrder(
                    assistantId = assistantId,
                    pinned = pinned,
                    movedId = conversationId.toString(),
                    beforeId = beforeId?.toString(),
                    afterId = afterId?.toString(),
                )
            }.onFailure { Log.w("ChatDrawerVM", "applyManualOrder failed", it) }
        }
    }

    /** 恢复默认顺序：清空非置顶档的位置值 ⇒ 回到按更新时间（设计稿 §一.7）。 */
    fun resetCustomOrder() {
        viewModelScope.launch {
            runCatching { conversationRepo.resetTierOrder(assistantIdFlow.first()) }
                .onFailure { Log.w("ChatDrawerVM", "resetCustomOrder failed", it) }
        }
    }

    fun selectFolder(folderId: Uuid?) {
        _selectedFolderId.value = folderId
    }

    fun createFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val assistantId = assistantIdFlow.first()
            folderRepo.createFolder(assistantId, trimmed)
        }
    }

    fun renameFolder(folderId: Uuid, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            folderRepo.renameFolder(folderId, trimmed)
        }
    }

    /**
     * 删除文件夹。若文件夹内有正在生成回复的会话，拒绝删除并返回 false（UI 层据此提示用户）。
     */
    fun deleteFolder(folderId: Uuid): Boolean {
        if (chatService.hasGeneratingConversationInFolder(folderId)) {
            return false
        }
        viewModelScope.launch {
            // 经 ChatService 删除：会同步清空活跃 session 内存态的 folderId，避免整对象保存写回已删文件夹
            chatService.deleteFolder(folderId)
            if (_selectedFolderId.value == folderId) {
                _selectedFolderId.value = null
            }
        }
        return true
    }

    fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        viewModelScope.launch {
            // 经 ChatService 移动：活跃会话会先同步内存态，避免后续整对象保存覆盖 folder_id
            chatService.moveConversationToFolder(conversationId, folderId)
        }
    }

    // ---- 抽屉「文件」面板（工作区文件浏览，子集：目录导航 + 缓存 + 文件名搜索）----

    /** 目录级缓存：path（""=根）→ 文件列表，切回时秒开 */
    private val filesCache: MutableMap<String, List<WorkspaceFileEntry>> = mutableMapOf()

    private val _filesPath = MutableStateFlow("")
    val filesPath: StateFlow<String> = _filesPath.asStateFlow()

    private val _workspaceFiles = MutableStateFlow<List<WorkspaceFileEntry>>(emptyList())
    val workspaceFiles: StateFlow<List<WorkspaceFileEntry>> = _workspaceFiles.asStateFlow()

    private val _filesSearchQuery = MutableStateFlow("")
    val filesSearchQuery: StateFlow<String> = _filesSearchQuery.asStateFlow()

    private val _filesSearchResults = MutableStateFlow<List<WorkspaceFileEntry>?>(null)
    val filesSearchResults: StateFlow<List<WorkspaceFileEntry>?> = _filesSearchResults.asStateFlow()

    /** 当前助手绑定的工作区 id；未绑定返回 null */
    val boundWorkspaceId: Uuid? get() = settingsStore.settingsFlow.value.getCurrentAssistant().workspaceId

    fun enterFilesDir(path: String) {
        _filesPath.value = path
        loadWorkspaceFiles()
    }

    fun navigateFilesUp() {
        val parent = _filesPath.value.substringBeforeLast('/', "").trim('/')
        enterFilesDir(parent)
    }

    fun invalidateFilesCache() {
        filesCache.clear()
    }

    fun loadWorkspaceFiles() {
        val workspaceId = boundWorkspaceId ?: return
        val path = _filesPath.value
        viewModelScope.launch {
            val entries = filesCache[path] ?: run {
                val loaded = workspaceRepo.listFiles(workspaceId.toString(), WorkspaceStorageArea.FILES, path)
                filesCache[path] = loaded
                loaded
            }
            _workspaceFiles.value = entries
        }
    }

    fun searchWorkspaceFiles(query: String) {
        _filesSearchQuery.value = query
        val workspaceId = boundWorkspaceId ?: return
        if (query.isBlank()) {
            _filesSearchResults.value = null
            return
        }
        viewModelScope.launch {
            _filesSearchResults.value = workspaceRepo.searchFilesByName(workspaceId.toString(), query)
        }
    }

    private fun getDateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        return when (date) {
            today -> context.getString(R.string.chat_page_today)
            yesterday -> context.getString(R.string.chat_page_yesterday)
            else -> date.toLocalString(date.year != today.year)
        }
    }
}
