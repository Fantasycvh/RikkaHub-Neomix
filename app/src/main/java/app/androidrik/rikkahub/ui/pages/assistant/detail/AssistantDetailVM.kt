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
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.ai.memoryId
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.db.entity.WorkspaceEntity
import app.androidrik.rikkahub.data.ai.MemoryConsolidationScheduler
import app.androidrik.rikkahub.data.ai.MemoryConsolidationStatus
import app.androidrik.rikkahub.data.ai.consolidationMemoryId
import app.androidrik.rikkahub.data.ai.memoryId
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.files.SkillMetadata
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.AssistantRegex
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.model.CardSlot
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import app.androidrik.rikkahub.data.repository.MemoryFileRepository
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
    val memoryFiles: MemoryFileRepository,
    /**
     * 文本域去抖提交/离开页面补写的**取消安全**写入口。
     *
     * 为什么不能用 `viewModelScope`：助手名称框的去抖窗口（250ms）内按返回，补写发生在
     * 组合销毁时，此刻 ViewModel 也可能正在 `onCleared()` ⇒ `viewModelScope` 的协程会被
     * 取消，最后一个（或一串）字符静默丢失。`AppScope` 与进程同寿命，是仓内同类写入
     * （`SettingWebPage` 的端口/口令去抖补写）已验证的做法。
     */
    private val appScope: AppScope,
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
            consolidationScheduler.statusOf(current.consolidationMemoryId())
        }
        .stateIn(
            scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = MemoryConsolidationStatus()
        )

    /** 手动「整理一下」：不受自动频次上限约束（用户显式点击即授权），也顺带做一遍全库复核 */
    fun consolidationStatusOf(memoryId: String) = consolidationScheduler.statusOf(memoryId)

    fun organizeMemory(memoryId: String = assistant.value.consolidationMemoryId()) {
        viewModelScope.launch {
            consolidationScheduler.organize(memoryId)
        }
    }

    /**
     * 应用整理建议（**真正改记忆库**）。
     *
     * 界面必须先走过二次确认再调它——本函数不做任何确认，它是那一步之后才允许到达的写口。
     * 返回条数供界面说明结果（应用了几条、几条因目标已变而作废）。
     */
    suspend fun applyProposals(ids: Set<Long>, memoryId: String = assistant.value.consolidationMemoryId()): MemoryProposalApplyResult =
        consolidationScheduler.applyProposals(memoryId, ids)

    /** 拒绝建议（不写记忆库，只是别再从界面上烦用户） */
    suspend fun rejectProposals(ids: Set<Long>, memoryId: String = assistant.value.consolidationMemoryId()): Int =
        consolidationScheduler.rejectProposals(memoryId, ids)

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

    /**
     * 助手字段的**定点**写出：读-改-写（transform），不是拿组合期快照整对象覆盖。
     *
     * 为什么需要：文本域是逐键/去抖提交的高频路径。旧的 `update(assistant)` 先读
     * `settings.value` 再把该助手整对象替换——两次并发读-改-写会互相覆盖（本仓
     * 「整对象写丢更新」旧案）。transform 版让 DataStore 的串行 `edit` 承担原子性。
     *
     * 为什么走 [appScope]：去抖窗口内按返回时，补写发生在组合销毁、ViewModel 也可能
     * `onCleared()` 的时刻；`viewModelScope` 会被取消 ⇒ 最后几个字符静默丢失。
     */
    private fun updateAssistantField(transform: (Assistant) -> Assistant) {
        appScope.launch {
            settingsStore.update { current ->
                current.copy(
                    assistants = current.assistants.map {
                        if (it.id == assistantId) transform(it) else it
                    }
                )
            }
        }
    }

    /**
     * 助手名称：UI 侧用 `rememberPersistedTextFieldState` 去抖提交 + 离开页面补写。
     *
     * 历史（2026-11-12 真机定案）：名称框原先 `value = assistant.name` 直连异步持久态，
     * 每键整份 Settings 落盘后旧值回灌活跃编辑框 ⇒ 「逐字符抖动 / 丢字」（120ms/字符注入
     * `abcdefghij` 得到 `bcdefghija`）。本地权威态 + 本方法才是正确链路。
     */
    fun updateName(name: String) = updateAssistantField { it.copy(name = name) }

    /** 最大 token 数：空串 = 不限制（null）；非法中间态由 UI 侧拦住、不落库。 */
    fun updateMaxTokens(maxTokens: Int?) = updateAssistantField { it.copy(maxTokens = maxTokens) }

    // ---------------------------------------------------------------------------------
    // 提示词页 / 自定义请求头与请求体的文本域：与 [updateName] 同一条链路 —— UI 侧
    // `rememberPersistedTextFieldState` 去抖提交 + 离开页面补写，这里做字段级 transform。
    // 文本域是高频路径，旧的 `update(assistant)` 读 `settings.value` 再整对象替换，两次
    // 并发读-改-写会互相覆盖（「整对象写丢更新」旧案）；transform 让 DataStore 的串行
    // `edit` 承担原子性。
    // ---------------------------------------------------------------------------------

    /**
     * 系统提示词大文本域：与 [updateName] 同链路 —— 本地权威 + 去抖提交 / 离页补写。
     * 旧实现是 UI 里 `snapshotFlow` 逐键 `onUpdate(assistant.copy(systemPrompt = ...))`，
     * 每键重写整份 Settings 且带整对象读-改-写竞态；这里改成字段级 transform。
     */
    fun updateSystemPrompt(systemPrompt: String) =
        updateAssistantField { it.copy(systemPrompt = systemPrompt) }

    fun updateMessageTemplate(messageTemplate: String) =
        updateAssistantField { it.copy(messageTemplate = messageTemplate) }

    fun updateSystemMessageTemplate(systemMessageTemplate: String) =
        updateAssistantField { it.copy(systemMessageTemplate = systemMessageTemplate) }

    /**
     * 预设消息正文：按消息 id 定位，不用列表下标 —— 去抖窗口内列表可能被增删，
     * 下标会漂移到别的条目上。
     */
    fun updatePresetMessageText(messageId: Uuid, text: String) = updateAssistantField { assistant ->
        assistant.copy(
            presetMessages = assistant.presetMessages.map { message ->
                if (message.id == messageId) {
                    message.copy(parts = listOf(UIMessagePart.Text(text)))
                } else {
                    message
                }
            }
        )
    }

    /** 角色卡槽位正文：槽位本身即稳定身份（每个槽位最多一条内容）。 */
    fun updateCardSlotValue(slot: CardSlot, text: String) = updateAssistantField { assistant ->
        when (slot) {
            CardSlot.SYSTEM_PROMPT -> assistant.copy(cardSystemPrompt = text)
            CardSlot.DESCRIPTION -> assistant.copy(cardDescription = text)
            CardSlot.PERSONALITY -> assistant.copy(cardPersonality = text)
            CardSlot.SCENARIO -> assistant.copy(cardScenario = text)
            CardSlot.EXAMPLE -> assistant.copy(cardExampleDialogue = text)
            // 开场白内容由姓名插值，没有可编辑字段
            CardSlot.GREETING -> assistant
        }
    }

    /** 规则名：按正则 id 定位（拖拽排序 / 增删时下标会漂移）。 */
    fun updateRegexName(regexId: Uuid, name: String) = updateRegex(regexId) { it.copy(name = name) }

    /** 查找正则：`trim()` 只在提交时做 —— 逐键 trim 会让正则里打不出空格/尾随空白。 */
    fun updateRegexFindRegex(regexId: Uuid, findRegex: String) =
        updateRegex(regexId) { it.copy(findRegex = findRegex.trim()) }

    fun updateRegexReplaceString(regexId: Uuid, replaceString: String) =
        updateRegex(regexId) { it.copy(replaceString = replaceString) }

    private fun updateRegex(
        regexId: Uuid,
        transform: (AssistantRegex) -> AssistantRegex,
    ) = updateAssistantField { assistant ->
        assistant.copy(
            regexes = assistant.regexes.map { regex ->
                if (regex.id == regexId) transform(regex) else regex
            }
        )
    }

    fun updateMemorySettings(transform: (Assistant) -> Assistant) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(assistants = current.assistants.map {
                    if (it.id == assistantId) transform(it) else it
                })
            }
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

    suspend fun moveMemory(storeId: String, memoryId: String, beforeId: String?) =
        memoryRepository.moveMemory(storeId, memoryId, beforeId)

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
