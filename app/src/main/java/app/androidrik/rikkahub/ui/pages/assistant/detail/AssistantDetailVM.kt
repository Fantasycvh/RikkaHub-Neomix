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
import app.androidrik.rikkahub.data.model.Tag
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
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

    fun addMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.addMemory(
                assistantId = assistant.value.memoryId(),
                content = memory.content,
                importance = memory.importance,
                description = memory.description,
            )
        }
    }

    fun updateMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            // 一并写入 importance：用户在记忆页的滑块覆盖会走这里，不传就等于「改了不生效」
            memoryRepository.updateContent(
                id = memory.id,
                content = memory.content,
                importance = memory.importance,
                description = memory.description,
            )
            // L1 锚点单独走 setAnchor（**不改 updatedAt**）：固化是标注、不是改写内容
            memoryRepository.setAnchor(id = memory.id, isAnchor = memory.isAnchor)
        }
    }

    fun deleteMemory(memory: AssistantMemory) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id = memory.id)
        }
    }

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
