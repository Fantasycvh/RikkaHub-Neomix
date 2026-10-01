package app.androidrik.rikkahub.ui.pages.assistant

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.data.datastore.DEFAULT_ASSISTANTS_IDS
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.ui.pages.assistant.detail.appendAssistant
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.MemoryRepository

class AssistantVM(
    private val settingsStore: SettingsStore,
    private val memoryRepository: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val filesManager: FilesManager,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun addAssistant(assistant: Assistant) {
        viewModelScope.launch {
            // 落库走唯一实现（与对话内 JSON 代码块导入共用），避免两处各写一遍追加逻辑
            settingsStore.appendAssistant(assistant)
        }
    }

    fun removeAssistant(assistant: Assistant) {
        viewModelScope.launch {
            cleanupAssistantFiles(assistant)

            val settings = settings.value
            // 防删光：assistants 清空会让 getCurrentAssistant 的 first() 兜底崩溃
            if (settings.assistants.size <= 1) return@launch

            // 内置助手删除需记墓碑（与提供商 removedBuiltInProviderIds 同款）：
            // 读路径的默认回填会把「不在列表里的内置 id」加回来，不记墓碑等于删不掉
            val isBuiltIn = assistant.id in DEFAULT_ASSISTANTS_IDS
            val removedIds =
                if (isBuiltIn) settings.removedAssistantIds + assistant.id
                else settings.removedAssistantIds

            // 被删的是当前助手时，把指针拨到剩余第一个（getCurrentAssistant 的
            // find{} ?: first() 兜底虽能自动回落，但显式重置让指针不悬空）
            val newAssistantId =
                if (settings.assistantId == assistant.id) {
                    settings.assistants.firstOrNull { it.id != assistant.id }?.id ?: assistant.id
                } else {
                    settings.assistantId
                }

            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.filter { it.id != assistant.id },
                    removedAssistantIds = removedIds,
                    assistantId = newAssistantId,
                )
            )
            memoryRepository.deleteMemoriesOfAssistant(assistant.id.toString())
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    private fun cleanupAssistantFiles(assistant: Assistant) {
        val uris = buildList {
            (assistant.avatar as? Avatar.Image)?.let { add(it.url.toUri()) }
            assistant.background?.let { add(it.toUri()) }
        }

        if (uris.isNotEmpty()) {
            filesManager.deleteChatFiles(uris)
        }
    }

    fun copyAssistant(assistant: Assistant) {
        viewModelScope.launch {
            val settings = settings.value
            val copiedAssistant = assistant.copy(
                id = kotlin.uuid.Uuid.random(),
                name = "${assistant.name} (Clone)",
                avatar = if(assistant.avatar is Avatar.Image) Avatar.Dummy else assistant.avatar,
            )
            settingsStore.update(
                settings.copy(
                    assistants = settings.assistants.plus(copiedAssistant)
                )
            )
        }
    }

    fun getMemories(assistant: Assistant) =
        if (assistant.useGlobalMemory) {
            memoryRepository.getGlobalMemoriesFlow()
        } else {
            memoryRepository.getMemoriesOfAssistantFlow(assistant.id.toString())
        }
}
