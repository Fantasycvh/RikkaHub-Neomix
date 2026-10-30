package app.androidrik.rikkahub.ui.pages.setting.scheduled

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.scheduled.SchedulePayload
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import app.androidrik.rikkahub.service.ScheduledTaskSweeperKeeper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ScheduledTaskDetailVM(
    private val taskId: String,
    private val taskRepository: ScheduledTaskRepository,
    conversationRepository: ConversationRepository,
    private val sweeperKeeper: ScheduledTaskSweeperKeeper,
    settingsStore: SettingsStore,
) : ViewModel() {
    private val _task = MutableStateFlow<ScheduledTaskEntity?>(null)
    val task = _task.asStateFlow()

    /** 详情页只读展示目标会话标题 */
    val conversationTitles = conversationRepository.searchConversations("")
        .map { list -> list.associate { it.id.toString() to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 目标描述里的助手名（NEW_EACH_RUN / DEDICATED） */
    val assistantNames = settingsStore.settingsFlow
        .map { settings -> settings.assistants.associate { it.id.toString() to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch { _task.value = taskRepository.byId(taskId) }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            taskRepository.setEnabled(taskId, enabled)
            sweeperKeeper.rearm(taskRepository)
            refresh()
        }
    }

    fun delete() {
        viewModelScope.launch {
            taskRepository.delete(taskId)
            sweeperKeeper.rearm(taskRepository)
            _task.value = null
        }
    }

    /** 保存编辑：周期字段变更由仓库侧重算 nextRunAt，保存后重排唤醒作业 */
    fun save(form: ScheduledTaskFormState) {
        viewModelScope.launch {
            taskRepository.update(
                id = taskId,
                title = form.title,
                prompt = form.prompt,
                scheduleType = form.type,
                payload = form.payload(),
                missPolicy = form.missPolicy,
                idleOnly = form.idleOnly,
            )
            sweeperKeeper.rearm(taskRepository)
            refresh()
        }
    }
}
