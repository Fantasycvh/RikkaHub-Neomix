package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.scheduled.SchedulePayload
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import app.androidrik.rikkahub.service.ScheduledTaskSweeperKeeper
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
) : ViewModel() {
    val tasks = taskRepository.flowAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _conversationQuery = MutableStateFlow("")
    val conversationQuery = _conversationQuery.asStateFlow()

    /** 新建表单的会话选择器：按标题过滤的会话列表（空关键字 = 全部） */
    val conversations = _conversationQuery
        .flatMapLatest { conversationRepository.searchConversations(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 任务列表里显示目标会话标题用的全量映射 */
    val conversationTitles = conversationRepository.searchConversations("")
        .map { list -> list.associate { it.id.toString() to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun setConversationQuery(query: String) {
        _conversationQuery.value = query
    }

    fun create(
        conversation: Conversation,
        title: String,
        prompt: String,
        scheduleType: ScheduleType,
        payload: SchedulePayload,
        missPolicy: MissPolicy,
        idleOnly: Boolean,
    ) {
        viewModelScope.launch {
            taskRepository.create(
                conversationId = conversation.id.toString(),
                title = title,
                prompt = prompt,
                scheduleType = scheduleType,
                payload = payload,
                missPolicy = missPolicy,
                idleOnly = idleOnly,
            )
            sweeperKeeper.rearm(taskRepository)
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
