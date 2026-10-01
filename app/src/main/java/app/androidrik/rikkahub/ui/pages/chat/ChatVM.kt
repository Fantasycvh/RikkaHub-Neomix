package app.androidrik.rikkahub.ui.pages.chat

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isEmptyInputMessage
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.datastore.getCurrentChatModel
import app.androidrik.rikkahub.utils.DEFAULT_CONTEXT_LENGTH
import app.androidrik.rikkahub.utils.effectiveContextLength
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Avatar
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.TruncationPlan
import app.androidrik.rikkahub.data.model.NodeFavoriteTarget
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.FavoriteRepository
import app.androidrik.rikkahub.service.ChatError
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.service.QueuedMessage
import app.androidrik.rikkahub.ui.hooks.writeStringPreference
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(newSettings: Settings): Job {
        return viewModelScope.launch {
            val oldSettings = settings.value
            // 检查用户头像是否有变化，如果有则删除旧头像
            checkUserAvatarDelete(oldSettings, newSettings)
            settingsStore.update(newSettings)
        }
    }

    // 检查用户头像删除
    private fun checkUserAvatarDelete(oldSettings: Settings, newSettings: Settings) {
        val oldAvatar = oldSettings.displaySetting.userAvatar
        val newAvatar = newSettings.displaySetting.userAvatar

        if (oldAvatar is Avatar.Image && oldAvatar != newAvatar) {
            filesManager.deleteChatFiles(listOf(oldAvatar.url.toUri()))
        }
    }

    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     * @param annotations 随消息落库的注解（#1906 引用快照）；
     *   展开成请求文本由 QuoteTransformer 负责，这里只管携带
     */
    fun handleMessageSend(
        content: List<UIMessagePart>,
        answer: Boolean = true,
        annotations: List<UIMessageAnnotation> = emptyList(),
    ) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        chatService.sendMessage(_conversationId, content, answer, annotations)
    }

    // ---- 消息队列（发送不打断生成：入队等当前轮次结束后自动派发） ----

    val messageQueue: StateFlow<MessageQueueState> = chatService.getMessageQueueFlow(_conversationId)

    val voiceSession = VoiceSessionController(viewModelScope, context::getString) {
        chatService.enqueueVoiceMessage(_conversationId, it)
    }

    fun removeQueuedMessage(messageId: Uuid) = chatService.removeQueuedMessage(_conversationId, messageId)

    fun beginEditQueuedMessage(messageId: Uuid): QueuedMessage? =
        chatService.beginEditQueuedMessage(_conversationId, messageId)

    fun finishEditQueuedMessage(
        messageId: Uuid,
        parts: List<UIMessagePart>?,
        annotations: List<UIMessageAnnotation>? = null,
    ) = chatService.finishEditQueuedMessage(_conversationId, messageId, parts, annotations)

    fun resumeMessageQueue() = chatService.resumeMessageQueue(_conversationId)

    fun stopVoiceMode() = voiceSession.stop()

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, retainRatio: Double): Job {
        return viewModelScope.launch {
            // 检查点式压缩：切分由 token 驱动的保留比例决定（对齐 design-auto-compaction §4.1）
            val currentSettings = settings.value
            val model = currentSettings.getCurrentChatModel()
            val windowTokens = model?.effectiveContextLength(currentSettings.contextLengthOverride)
                ?: DEFAULT_CONTEXT_LENGTH
            val result = chatService.compressToCheckpoint(
                conversationId = _conversationId,
                windowTokens = windowTokens,
                additionalPrompt = additionalPrompt,
                targetTokens = targetTokens,
                retainRatio = retainRatio,
                // 「跟随对话模型」时使用当前对话模型
                followModel = model,
            )
            result
                .onSuccess { checkpoint ->
                    if (checkpoint == null) {
                        // 历史尚不足以切分（非错误）——明确提示，避免用户以为点击无效
                        chatService.addError(
                            IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages)),
                            conversationId = _conversationId,
                            title = context.getString(R.string.error_title_compress_conversation),
                        )
                    }
                }
                .onFailure {
                    chatService.addError(
                        it,
                        conversationId = _conversationId,
                        title = context.getString(R.string.error_title_compress_conversation),
                    )
                }
        }
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        scope: ChatService.RegenerateScope = ChatService.RegenerateScope.INCLUDE_FOLLOWING,
        onTruncated: ((TruncationPlan) -> Unit)? = null,
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, scope, onTruncated)
    }

    /** 撤销一次 scope B 的截断（snackbar 撤销）。 */
    fun undoRegenerateTruncation(truncation: TruncationPlan) {
        chatService.undoRegenerateTruncation(_conversationId, truncation)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleApproveAllPending() {
        analytics.logEvent("ai_tool_approve_all", null)
        chatService.handleApproveAllPending(_conversationId)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveConversation(_conversationId, conversation.value)
        }
    }

    fun updateTitle(title: String) {
        viewModelScope.launch {
            val updatedConversation = conversation.value.copy(title = title)
            chatService.saveConversation(_conversationId, updatedConversation)
        }
    }

    fun deleteConversation(conversation: Conversation): Job =
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            // 走 ChatService：本会话必有活跃 session（正在查看），只改 DB 单列会被下一次
            // 整对象保存（生成结束/改标题）用内存里的旧 isPinned 覆盖回去。
            chatService.togglePinStatus(conversation.id)
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            // 走 ChatService 的统一元数据原语：改的是**内存最新态** + 只写单列。
            // 旧写法「读 DB 整对象 → 改字段 → saveConversation」会用旧快照覆盖活跃 session，
            // 生成中途切助手时已在流式接收的内容会被换成 DB 里的旧 messageNodes。
            chatService.moveConversationToAssistant(conversation.id, targetAssistantId)
            if (conversation.id == _conversationId) {
                settingsStore.updateAssistant(targetAssistantId)
            }
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            regenerateTitle(conversation, force)
        }
    }

    /**
     * 生成标题的**可等待**版本：返回 false = 标题模型没配好（服务已上报错误卡）。
     *
     * 抽屉路径需要立即反馈（错误卡在聊天页、被抽屉挡着），故让调用方拿到结果自己补 toast。
     * 前置条件判定只有服务里一处，这里不做任何重复判断（防两处真相源漂移）。
     */
    suspend fun regenerateTitle(conversation: Conversation, force: Boolean = false): Boolean {
        val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return true
        return chatService.generateTitle(_conversationId, conversationFull, force)
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(newConversation: Conversation) {
        chatService.updateConversationState(_conversationId) {
            newConversation
        }
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
