package app.androidrik.rikkahub.ui.pages.setting.scheduled

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.androidrik.rikkahub.data.db.entity.MissPolicy
import app.androidrik.rikkahub.data.db.entity.ScheduleType
import app.androidrik.rikkahub.data.db.entity.ScheduledTaskEntity
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.files.uploadDestinationFingerprint
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAction
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAsset
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaAssetStore
import app.androidrik.rikkahub.data.scheduled.ScheduledMediaOperation
import app.androidrik.rikkahub.data.scheduled.SchedulePayload
import app.androidrik.rikkahub.data.scheduled.ScheduledTaskRepository
import app.androidrik.rikkahub.data.scheduled.mediaGrant
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
    private val settingsStore: SettingsStore,
    private val assetStore: ScheduledMediaAssetStore,
) : ViewModel() {
    private val _task = MutableStateFlow<ScheduledTaskEntity?>(null)
    val task = _task.asStateFlow()

    /** 详情页只读展示目标会话标题 */
    val conversationTitles = conversationRepository.searchConversations("")
        .map { list -> list.associate { it.id.toString() to it.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 目标会话是否写了长期目标（Conversation.goal）——「附带长期目标」开关的可用条件 */
    val conversationGoals = conversationRepository.searchConversations("")
        .map { list -> list.associate { it.id.toString() to it.goal } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 运行账本（P2）：运行历史（新→旧，至账本保留上限；二级页整页摊开） */
    val runs = taskRepository.flowRuns(taskId, ScheduledTaskRepository.RUNS_KEEP_PER_TASK)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 目标描述里的助手名（NEW_EACH_RUN / DEDICATED） */
    val assistantNames = settingsStore.settingsFlow
        .map { settings -> settings.assistants.associate { it.id.toString() to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 素材上传目的地指纹：保存授权时写进 grant，运行时由 ScheduledMediaRunner 核对。 */
    val uploadFingerprint = settingsStore.settingsFlow
        .map { it.uploadS3Config.withHttpsByDefault().uploadDestinationFingerprint() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 媒体模板素材：相册选中的图片立即复制进应用私有目录，授权只引用副本。 */
    fun importMediaAssets(form: ScheduledTaskFormState, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val dirId = form.mediaAssetDirId ?: ScheduledMediaAssetStore.newFormDirId().also { form.mediaAssetDirId = it }
            val paths = assetStore.importImages(uris, dirId)
            // 首帧视频的素材按首帧角色锁定；其余操作当参考图
            val role = if (form.mediaOperation == ScheduledMediaOperation.FIRST_FRAME) {
                app.androidrik.mediagen.model.ImageRole.FIRST_FRAME
            } else {
                app.androidrik.mediagen.model.ImageRole.REFERENCE
            }
            form.mediaAssets = form.mediaAssets + paths.map { ScheduledMediaAsset(it, role = role) }
            // 素材变了授权就要重新批准
            if (paths.isNotEmpty()) form.authorizeMedia = false
        }
    }

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
            // 授权里锁定的素材副本跟着任务一起删
            taskRepository.byId(taskId)?.mediaGrant()?.templateAssets?.let { assetStore.deleteAssets(it) }
            taskRepository.delete(taskId)
            sweeperKeeper.rearm(taskRepository)
            _task.value = null
        }
    }

    /** 保存编辑：周期字段变更由仓库侧重算 nextRunAt，保存后重排唤醒作业 */
    fun save(form: ScheduledTaskFormState, mediaAction: ScheduledMediaAction?) {
        viewModelScope.launch {
            taskRepository.update(
                id = taskId,
                title = form.title,
                prompt = form.prompt,
                scheduleType = form.type,
                payload = form.payload(),
                missPolicy = form.missPolicy,
                idleOnly = form.idleOnly,
                withGoal = form.withGoal,
                keepLastConversations = form.keepLast ?: 0,
                deliverables = form.deliverablesJson().takeUnless { form.mediaTemplate },
                editMediaAction = true,
                mediaAction = mediaAction,
            )
            sweeperKeeper.rearm(taskRepository)
            // 授权里引用的素材才算数：目录里被替换或删除掉的副本顺手清掉
            form.mediaAssetDirId?.let { dirId ->
                assetStore.pruneUnreferenced(
                    dirId,
                    mediaAction?.templateAssets.orEmpty().map(ScheduledMediaAsset::path).toSet(),
                )
            }
            refresh()
        }
    }
}
