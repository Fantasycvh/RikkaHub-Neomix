package app.androidrik.rikkahub.ui.pages.assistant.detail

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.data.ai.memoryId
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.ai.MemoryConsolidationScheduler
import app.androidrik.rikkahub.data.ai.MemoryConsolidationStatus
import app.androidrik.rikkahub.data.ai.memoryId
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.files.SkillMetadata
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import app.androidrik.rikkahub.data.model.Tag
import app.androidrik.rikkahub.data.repository.MemoryProposalApplyResult
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "AssistantDetailVM"

class AssistantDetailVM(
    private val id: String,
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    /** 做梦（V3）：状态流与手动整理入口（见 docs/design/design-memory-consolidation.md §9.2） */
    private val consolidationScheduler: MemoryConsolidationScheduler,
    /**
     * L1 锚点快照的刷新入口（`docs/design/design-memory-injection-tiering.md` §5）。
     *
     * 快照的权威态在**会话**上（`Conversation.frozenAnchors`），而会话内存态由 ChatService 的
     * session 持有——记忆页只改库是改不动它的（拼装预览读的正是那份内存态）。所以固化之后
     * 必须回到 ChatService 走一次「先同步内存、再批量落库」。
     */
    private val chatService: ChatService,
) : ViewModel() {
    private val assistantId = Uuid.parse(id)

    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _skills.value = skillManager.listSkills()
        }
    }

    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val mcpServerConfigs = settingsStore
        .settingsFlow.map { settings ->
            settings.mcpServers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val assistant: StateFlow<Assistant> = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistants.find { it.id == assistantId } ?: Assistant()
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Assistant()
        )

    val memories = assistant
        .flatMapLatest { currentAssistant ->
            if (currentAssistant.useGlobalMemory) {
                memoryRepository.getGlobalMemoriesFlow()
            } else {
                memoryRepository.getMemoriesOfAssistantFlow(assistantId.toString())
            }
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    /**
     * 做梦（V3）的状态：待整理轮数 / 是否在跑 / 上次结果（含**本次建议**，见决策 B）。
     *
     * 跟着当前助手的记忆库走（全局库与助手库是两套进度），与 [memories] 同款按 flatMapLatest 切换。
     */
    val consolidationStatus: StateFlow<MemoryConsolidationStatus> = assistant
        .flatMapLatest { current ->
            consolidationScheduler.statusOf(current.memoryId())
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = MemoryConsolidationStatus()
        )

    /** 手动「整理一下」：不受自动频次上限约束（用户显式点击即授权），也顺带做一遍全库复核 */
    fun organizeMemory() {
        viewModelScope.launch {
            consolidationScheduler.organize(assistant.value.memoryId())
        }
    }

    /**
     * 应用整理建议（**真正改记忆库**）。
     *
     * 界面必须先走过二次确认再调它——本函数不做任何确认，它是那一步之后才允许到达的写口。
     * 返回条数供界面说明结果（应用了几条、几条因目标已变而作废）。
     */
    suspend fun applyProposals(ids: Set<Long>): MemoryProposalApplyResult =
        consolidationScheduler.applyProposals(assistant.value.memoryId(), ids)

    /** 拒绝建议（不写记忆库，只是别再从界面上烦用户） */
    suspend fun rejectProposals(ids: Set<Long>): Int =
        consolidationScheduler.rejectProposals(assistant.value.memoryId(), ids)

    val providers = settingsStore
        .settingsFlow
        .map { settings ->
            settings.providers
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val tags = settingsStore
        .settingsFlow
        .map { settings ->
            settings.assistantTags
        }.stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList()
        )

    val workspaces: StateFlow<List<WorkspaceEntity>> = workspaceRepository
        .listFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun updateTags(tagIds: List<Uuid>, tags: List<Tag>) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistantTags = tags
                )
            )
            update(
                assistant.value.copy(
                    tags = tagIds.toList()
                )
            )
            Log.d(TAG, "updateTags: ${tagIds.joinToString(",")}")
            cleanupUnusedTags()
        }
    }

    fun cleanupUnusedTags() {
        viewModelScope.launch {
            val settings = settings.value
            val validTagIds = settings.assistantTags.map { it.id }.toSet()

            // 清理 assistant 中的无效 tag id
            val cleanedAssistants = settings.assistants.map { assistant ->
                val validTags = assistant.tags.filter { tagId ->
                    validTagIds.contains(tagId)
                }
                if (validTags.size != assistant.tags.size) {
                    assistant.copy(tags = validTags)
                } else {
                    assistant
                }
            }

            // 获取清理后的 assistant 中使用的 tag id
            val usedTagIds = cleanedAssistants.flatMap { it.tags }.toSet()

            // 清理未使用的 tags
            val cleanedTags = settings.assistantTags.filter { tag ->
                usedTagIds.contains(tag.id)
            }

            // 检查是否需要更新
            val needUpdateAssistants = cleanedAssistants != settings.assistants
            val needUpdateTags = cleanedTags.size != settings.assistantTags.size

            if (needUpdateAssistants || needUpdateTags) {
                settingsStore.update(
                    settings = settings.copy(
                        assistants = cleanedAssistants,
                        assistantTags = cleanedTags
                    )
                )
            }
        }
    }

    fun update(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            settingsStore.update(
                settings = settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            checkAvatarDelete(old = it, new = assistant) // 删除旧头像
                            checkBackgroundDelete(old = it, new = assistant) // 删除旧背景
                            assistant
                        } else {
                            it
                        }
                    })
            )
        }
    }

    /** The UI owns error feedback and cancellation; do not hide failures in a detached launch. */
    suspend fun addMemory(memory: AssistantMemory, storeId: String = assistant.value.memoryId()) {
        // User-only path atomically combines creation and the chosen anchor (setAnchor).
        // Model addMemory deliberately cannot accept an anchor flag.
        memoryRepository.createMemoryInStore(
            storeId = storeId,
            content = memory.content,
            importance = memory.importance,
            description = memory.description,
            isAnchor = memory.isAnchor,
            reason = "User memory editor",
            kind = memory.kind,
            dueAt = memory.dueAt,
        )
    }

    /**
     * 手动「固化记忆」：把当前锚点集合重新写进该助手的**全部会话**（设计稿 §5 的手动刷新点）。
     *
     * 为什么需要一个明确的按钮，而不是只让它当保存的副作用：会话只在**开始时**冻结一次，
     * 之后改锚点只会顺带刷新，用户既看不见、也无从表达「我现在就要让已有会话采纳」。
     *
     * @return 更新到的会话数（调用方拿去 toast —— 这个动作本身没有任何可见结果，没有反馈
     *   就会看起来像没生效）
     */
    suspend fun freezeMemoryAnchors(): Int = chatService.refreshFrozenAnchorsForAssistant(assistantId)

    suspend fun updateMemory(storeId: String, memory: AssistantMemory) {
        // One user edit, one CAS and one history boundary (including the anchor field).
        memoryRepository.updateMemoryInStore(
            storeId = storeId,
            id = memory.id,
            content = memory.content,
            importance = memory.importance,
            description = memory.description,
            isAnchor = memory.isAnchor,
            expectedRevision = memory.revision,
            reason = "User memory editor",
            kind = memory.kind,
            dueAt = memory.dueAt,
        )
        // Deliberately do not refresh frozen anchors in existing conversations.
    }

    suspend fun setMemoryTaskDone(storeId: String, memory: AssistantMemory, done: Boolean) =
        setMemoryTaskStatus(storeId, memory, if (done) MemoryTaskStatus.DONE else MemoryTaskStatus.OPEN)

    suspend fun setMemoryTaskStatus(storeId: String, memory: AssistantMemory, status: MemoryTaskStatus) {
        memoryRepository.setTaskStatusInStore(
            storeId = storeId,
            id = memory.id,
            status = status,
            expectedRevision = memory.revision,
            actor = "USER",
            reason = "User changed task status",
        )
    }

    suspend fun deleteMemory(storeId: String, memory: AssistantMemory) {
        check(memoryRepository.deleteMemoryInStore(
            storeId, memory.id, expectedRevision = memory.revision,
            actor = "USER", reason = "User memory editor",
        )) { "Memory no longer exists" }
    }

    suspend fun memoryHistory(storeId: String, id: String) = memoryRepository.listHistory(storeId, id)

    suspend fun memoryTrash(storeId: String) = memoryRepository.listTrash(storeId)

    suspend fun restoreMemoryRevision(storeId: String, memory: AssistantMemory, revision: Long) =
        memoryRepository.restoreRevision(
            storeId, memory.id, targetRevision = revision, expectedRevision = memory.revision,
            reason = "User restored a previous revision",
        )

    suspend fun restoreTrashedMemory(storeId: String, memoryId: Int, revision: Long) =
        memoryRepository.restoreTrash(
            storeId, memoryId, expectedRevision = revision, reason = "User restored from recycle bin",
        )

    fun checkAvatarDelete(old: Assistant, new: Assistant) {
        if (old.avatar is Avatar.Image && old.avatar != new.avatar) {
            filesManager.deleteChatFiles(listOf(old.avatar.url.toUri()))
        }
    }

    fun checkBackgroundDelete(old: Assistant, new: Assistant) {
        val oldBackground = old.background
        val newBackground = new.background

        if (oldBackground != null && oldBackground != newBackground) {
            try {
                val oldUri = oldBackground.toUri()
                if (oldUri.scheme == "content" || oldUri.scheme == "file") {
                    filesManager.deleteChatFiles(listOf(oldUri))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete background file: $oldBackground", e)
            }
        }
    }
}
