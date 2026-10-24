package app.androidrik.rikkahub.ui.pages.history

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.androidrik.rikkahub.AppScope
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
    private val appScope: AppScope,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        conversationRepo.getConversationsOfAssistant(assistant?.id ?: Uuid.random())
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 撤销条目保留时长：须大于 snackbar 展示时长（Short ≈ 4s；被抢占链下用户点撤销最晚在最后一条
     * snackbar 的窗口内）。到期未撤销 → 清理该会话附件文件（防磁盘泄漏）。仅测试注入用。
     */
    @VisibleForTesting
    var undoRetentionMs = 10_000L

    private class PendingDelete(val conversation: Conversation, val cleanupJob: Job)

    private var pendingDeletes = mutableListOf<PendingDelete>()
    private val pendingDeletesMutex = Mutex()

    /**
     * 删除会话（挂起，先删完再弹 snackbar；**保留附件文件**供撤销）。
     *
     * 实体进撤销队列并为每条安排**到期清理**（到期未撤销 → `deleteConversationFiles`，替代原先
     * 页面在 snackbar `Dismissed` 分支里的清理——被新 snackbar 抢占时 Dismissed 会把还能撤销的
     * 条目连文件一起清掉）。抢占语义与收藏页同族修复（`FavoriteVM`）：被抢占的条目留在队列，
     * 任意一次撤销动作恢复全部未过期条目。
     */
    suspend fun deleteConversation(conversation: Conversation) {
        conversationRepo.deleteConversation(conversation, deleteFiles = false)
        val cleanupJob = viewModelScope.launch {
            delay(undoRetentionMs)
            val entry = pendingDeletesMutex.withLock {
                pendingDeletes.firstOrNull { it.conversation.id == conversation.id }
                    ?.also { pendingDeletes.remove(it) }
            }
            if (entry != null) conversationRepo.deleteConversationFiles(entry.conversation)
        }
        pendingDeletesMutex.withLock { pendingDeletes += PendingDelete(conversation, cleanupJob) }
    }

    /** 撤销删除：恢复队列里**全部**待撤销会话（含被 snackbar 抢占的），并取消其到期清理。 */
    suspend fun undoConversationDeletes() {
        val entries = pendingDeletesMutex.withLock {
            pendingDeletes.also { pendingDeletes = mutableListOf() }
        }
        entries.forEach {
            it.cleanupJob.cancel()
            conversationRepo.insertConversation(it.conversation)
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

    suspend fun getFullConversation(conversationId: Uuid): Conversation? {
        return conversationRepo.getConversationById(conversationId)
    }

    /**
     * 页面销毁时撤销已不可能（snackbar 随页面协程消亡，viewModelScope 的到期清理也被取消），
     * 把剩余条目的附件文件清掉防泄漏。此时不会再有写入方（页面/VM 协程均已消亡），
     * 主线程读列表无需加锁；文件清理挪到 appScope 收尾。
     */
    override fun onCleared() {
        val entries = pendingDeletes.toList()
        pendingDeletes.clear()
        if (entries.isNotEmpty()) {
            appScope.launch {
                entries.forEach {
                    runCatching { conversationRepo.deleteConversationFiles(it.conversation) }
                        .onFailure { Log.e(TAG, "cleanup pending delete files failed", it) }
                }
            }
        }
    }
}
