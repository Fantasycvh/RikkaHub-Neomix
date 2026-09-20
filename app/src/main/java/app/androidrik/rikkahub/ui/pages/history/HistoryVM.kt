package app.androidrik.rikkahub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        conversationRepo.getConversationsOfAssistant(assistant?.id ?: Uuid.random())
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            // 历史页滑动删除有撤销：先只删 DB（保留附件文件），
            // 撤销窗口结束未撤销时再由页面调用 deleteConversationFiles 清理文件。
            conversationRepo.deleteConversation(conversation, deleteFiles = false)
        }
    }

    fun deleteConversationFiles(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.deleteConversationFiles(conversation)
        }
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            // 走 ChatService 而非 repo：该会话可能有活跃 session（如后台生成中），
            // 只改 DB 单列会被后续整对象保存用内存里的旧 isPinned 覆盖回去（星标静默丢失）。
            chatService.togglePinStatus(conversationId)
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        conversationRepo.getPinnedConversations()

    fun restoreConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.insertConversation(conversation)
        }
    }

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        return conversationRepo.getConversationById(conversationId)
    }
}
