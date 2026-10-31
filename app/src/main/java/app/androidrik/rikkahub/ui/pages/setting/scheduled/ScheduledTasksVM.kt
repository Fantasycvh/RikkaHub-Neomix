package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.db.entity.TargetMode
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.scheduled.SchedulePayload
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.service.ScheduledTaskSweeperKeeper
import kotlin.uuid.Uuid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledTasksVM(
    private val taskRepository: ScheduledTaskRepository,
    private val conversationRepository: ConversationRepository,
    private val sweeperKeeper: ScheduledTaskSweeperKeeper,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    val tasks = taskRepository.flowAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _conversationQuery = MutableStateFlow("")
    val conversationQuery = _conversationQuery.asStateFlow()

    /** 新建表单的会话选择器：按标题过滤的会话列表（空关键字 = 全部）；展示时按助手分组 */
    val conversations = _conversationQuery
        .flatMapLatest { conversationRepository.searchConversations(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 任务列表里显示目标会话标题用的全量映射 */
    val conversationTitles = conversationRepository.searchConversations("")
        .map { list -> list.associate { it.id.toString() to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * 助手列表：会话选择器的分组标题、以及「专属新会话 / 每轮新建」的助手选择器都用它。
     * 直接读 settings（助手顺序即用户在设置里的顺序），不另建仓库。
     */
    val assistants = settingsStore.settingsFlow
        .map { it.assistants }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** id → 助手名（任务列表的目标描述用） */
    val assistantNames = settingsStore.settingsFlow
        .map { settings -> settings.assistants.associate { it.id.toString() to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setConversationQuery(query: String) {
        _conversationQuery.value = query
    }

    /** 会话内「为此会话创建定时任务」入口：按 id 取会话做目标预填（不在列表里也允许） */
    suspend fun conversationById(id: String): Conversation? =
        runCatching { conversationRepository.getConversationById(Uuid.parse(id)) }.getOrNull()

    /**
     * 新建任务。目标形态三选一（见 [TargetMode]）：
     * - [TargetMode.EXISTING]：用选中的已有会话；
     * - [TargetMode.DEDICATED]：**现在**用选中助手建一条专属会话，之后每轮都在它里面跑；
     * - [TargetMode.NEW_EACH_RUN]：不建会话，每轮执行时现建（标题见 `scheduledRunConversationTitle`）。
     *
     * 建会话（DEDICATED）放在这里而不是执行层：这是**用户在场**的显式动作，会话应当在保存的
     * 那一刻就出现在会话列表里；执行层建会话只用于 NEW_EACH_RUN。
     *
     * [onFailed] 只在**竞态**里触发（选完目标后它被删了 / 建会话的 DB 写入失败）：静默 return
     * 会让用户以为保存按钮坏了，所以由页面显示一行错误。
     */
    fun create(
        targetMode: TargetMode,
        conversation: Conversation?,
        assistant: Assistant?,
        title: String,
        prompt: String,
        scheduleType: ScheduleType,
        payload: SchedulePayload,
        missPolicy: MissPolicy,
        idleOnly: Boolean,
        withGoal: Boolean = false,
        keepLastConversations: Int = 0,
        deliverablesJson: String? = null,
        onCreated: () -> Unit = {},
        onFailed: (TargetFailure) -> Unit = {},
    ) {
        viewModelScope.launch {
            val conversationId = when (targetMode) {
                TargetMode.EXISTING -> conversation?.id?.toString()
                    ?: return@launch onFailed(TargetFailure.CONVERSATION_MISSING)

                TargetMode.DEDICATED -> {
                    val created = assistant?.let {
                        chatService.createTaskConversation(it.id, title.ifBlank { it.name })
                    }
                    created?.toString() ?: return@launch onFailed(TargetFailure.ASSISTANT_MISSING)
                }

                TargetMode.NEW_EACH_RUN -> ""
            }
            taskRepository.create(
                conversationId = conversationId,
                targetMode = targetMode,
                assistantId = assistant?.id?.toString(),
                title = title,
                prompt = prompt,
                scheduleType = scheduleType,
                payload = payload,
                missPolicy = missPolicy,
                idleOnly = idleOnly,
                withGoal = withGoal,
                keepLastConversations = keepLastConversations,
                deliverables = deliverablesJson,
            )
            sweeperKeeper.rearm(taskRepository)
            onCreated()
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            taskRepository.setEnabled(id, enabled)
            sweeperKeeper.rearm(taskRepository)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            taskRepository.delete(id)
            sweeperKeeper.rearm(taskRepository)
        }
    }
}
