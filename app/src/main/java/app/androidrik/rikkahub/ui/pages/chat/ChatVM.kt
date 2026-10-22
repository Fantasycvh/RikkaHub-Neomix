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
import kotlinx.coroutines.flow.combine
import app.androidrik.rikkahub.data.datastore.getAssistantById
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.model.UpstreamConversationConfig
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
import app.androidrik.rikkahub.data.ai.PromptInspection
import app.androidrik.rikkahub.ui.pages.promptinspect.buildPromptInspectionLabels
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.service.QueuedMessage
import app.androidrik.rikkahub.ui.hooks.writeStringPreference
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

internal fun Conversation.effectiveAssistant(settings: Settings): Assistant {
    val base = settings.getAssistantById(assistantId) ?: settings.getCurrentAssistant()
    return upstreamConfig?.applyTo(base) ?: base
}

/** Apply only intentional edits; an empty/false/null frozen field is never an inheritance marker. */
internal fun UpstreamConversationConfig.withAssistantChanges(old: Assistant, new: Assistant) = copy(
    chatModelId = if (old.chatModelId != new.chatModelId) new.chatModelId else chatModelId,
    reasoningLevel = if (old.reasoningLevel != new.reasoningLevel) new.reasoningLevel else reasoningLevel,
    enableWebSearch = if (old.enableWebSearch != new.enableWebSearch) new.enableWebSearch else enableWebSearch,
    mcpServers = if (old.mcpServers != new.mcpServers) new.mcpServers else mcpServers,
    workspaceId = if (old.workspaceId != new.workspaceId) new.workspaceId else workspaceId,
    enabledSkills = if (old.enabledSkills != new.enabledSkills) new.enabledSkills else enabledSkills,
)

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
            // 初始化之后再预热：此时 settings/conversation 快照已稳定，预览 join 才对得上
            chatService.prewarmPromptTokenCounts(
                _conversationId,
                labels = app.androidrik.rikkahub.ui.pages.promptinspect.buildPromptInspectionLabels(context),
            )
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
    val enableWebSearch = combine(settings, conversation) { settings, conversation ->
        conversation.effectiveAssistant(settings).enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 与生成使用同一份会话配置（null 模型 id 仍跟随全局默认模型）。
    val currentChatModel = combine(settings, conversation) { settings, conversation ->
        val assistant = conversation.effectiveAssistant(settings)
        settings.findModelById(assistant.chatModelId ?: settings.chatModelId)?.let { model ->
            conversation.upstreamConfig?.applyToModel(model) ?: model
        }
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
            val assistantId = conversation.value.assistantId
            val oldAssistant = oldSettings.getAssistantById(assistantId) ?: oldSettings.getCurrentAssistant()
            val newAssistant = newSettings.getAssistantById(oldAssistant.id)
            if (newAssistant != null && newAssistant != oldAssistant) {
                // Only fields changed against the GLOBAL baseline are intentional settings edits.
                // Copying the entire assistant would thaw unrelated false/empty/null snapshot fields.
                chatService.updateConversationUpstreamConfig(_conversationId) { config ->
                    config.withAssistantChanges(oldAssistant, newAssistant)
                }
            }
            settingsStore.update(newSettings)
        }
    }

    /** Controls render an effective assistant, so compare to that same baseline, not a stale global one. */
    fun updateAssistantControls(previous: Assistant, updated: Assistant): Job = viewModelScope.launch {
        if (conversation.value.upstreamConfig != null) {
            chatService.updateConversationUpstreamConfig(_conversationId) { config ->
                config.withAssistantChanges(previous, updated)
            }
            // Other controls (e.g. injection bindings) still belong to the global assistant.
            // Never leak the UI's effective frozen fields into that assistant as a side effect.
            settingsStore.update { settings ->
                settings.copy(assistants = settings.assistants.map { global ->
                    if (global.id == updated.id) updated.copy(
                        chatModelId = global.chatModelId,
                        reasoningLevel = global.reasoningLevel,
                        enableWebSearch = global.enableWebSearch,
                        mcpServers = global.mcpServers,
                        workspaceId = global.workspaceId,
                        enabledSkills = global.enabledSkills,
                    ) else global
                })
            }
        } else {
            settingsStore.update { settings ->
                settings.copy(assistants = settings.assistants.map {
                    if (it.id == updated.id) updated else it
                })
            }
        }
    }

    fun setBuiltInSearch(enabled: Boolean) = viewModelScope.launch {
        chatService.updateConversationUpstreamConfig(_conversationId) { it.copy(builtInSearch = enabled) }
    }

    fun setWorkspaceCwd(cwd: String?) = viewModelScope.launch {
        chatService.setConversationWorkspaceCwd(_conversationId, cwd)
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
            if (conversation.value.upstreamConfig != null) {
                chatService.updateConversationUpstreamConfig(_conversationId) { it.copy(chatModelId = model.id) }
                return@launch
            }
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

    /**
     * **插队发送**：打断当前生成轮次，立刻发这条（用户 2026-10-13 拍板的语义）。
     *
     * 与 [handleMessageSend] 的区别只在「当前有轮次在跑」时才有意义：
     * 那边是排队等它跑完，这边是让当前轮次让路（半截落草稿、待审批工具默认拒绝）。
     * 没有东西可打断时自然退化成普通发送。
     */
    fun handleMessageSendInterrupting(
        content: List<UIMessagePart>,
        answer: Boolean = true,
        annotations: List<UIMessageAnnotation> = emptyList(),
    ) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message_interrupting", null)

        chatService.sendInterrupting(_conversationId, content, answer, annotations)
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

    /** 把某条待发消息置顶（下一条就发它）；只改顺序，不打断当前轮次。 */
    fun moveQueuedMessageToFront(messageId: Uuid) =
        chatService.moveQueuedMessageToFront(_conversationId, messageId)

    fun stopVoiceMode() = voiceSession.stop()

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun updateMessageReasoning(messageId: Uuid, reasoningCreatedAt: kotlin.time.Instant, text: String) {
        viewModelScope.launch {
            chatService.updateMessageReasoning(_conversationId, messageId, reasoningCreatedAt, text)
        }
    }

    fun handleCompressContext(
        additionalPrompt: String,
        targetTokens: Int,
        retainRatio: Double,
        keepRecentMessages: Int = 0,
    ): Job {
        return viewModelScope.launch {
            // 检查点式压缩：切分由 token 驱动的保留比例决定（对齐 design-auto-compaction §4.1）；
            // keepRecentMessages > 0 时作为「保留最近 N 条」的下限
            val currentSettings = settings.value
            val model = currentChatModel.value
            val windowTokens = model?.effectiveContextLength(currentSettings.contextLengthOverride)
                ?: DEFAULT_CONTEXT_LENGTH
            val result = chatService.compressToCheckpoint(
                conversationId = _conversationId,
                windowTokens = windowTokens,
                additionalPrompt = additionalPrompt,
                targetTokens = targetTokens,
                retainRatio = retainRatio,
                keepRecentMessages = keepRecentMessages,
                // 「跟随对话模型」时使用当前对话模型
                followModel = model,
            )
            result
                .onSuccess { checkpoint ->
                    if (checkpoint == null) {
                        // 历史尚不足以切分（非错误）——明确提示，避免用户以为点击无效
                        val message = context.getString(
                            if (keepRecentMessages > 0) {
                                R.string.chat_page_compress_not_enough_messages_keep_recent
                            } else {
                                R.string.chat_page_compress_not_enough_messages
                            }
                        )
                        chatService.addError(
                            IllegalStateException(message),
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

    /**
     * 只读地组装一次「这次请求会发什么」，供上下文容量面板做组成明细与轮数预测。
     *
     * 与真实发送**同源**（[ChatService.inspectPromptAssembly]：共用 PromptAssembly 与同一个
     * transformer 链、同一套工具装配），因此面板里的分段与实际发出的请求一致；不落库、不发请求。
     */
    suspend fun inspectPromptAssembly(caller: String = "preview"): PromptInspection? = chatService.inspectPromptAssembly(
        caller = caller,
        conversationId = _conversationId,
        labels = buildPromptInspectionLabels(context),
    )

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

    /** [showDeleteBlockedWhileGeneratingError] 的编辑版（`onUpdateMessage` 的守卫提示）。 */
    fun showEditBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再编辑消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    /**
     * 消息编辑的落点：**基于当前内存值的纯函数变换**（只替换目标节点）。
     *
     * 为什么不再 `updateConversation(conversation.copy(messageNodes = ...))`：
     * 那是拿回调闭包里的快照（可能已落后若干块流式内容）**整对象替换**当前会话，
     * 生成中会把在途正文一并丢掉；即便不在生成中，也会把期间其它字段的改动（标题、
     * 建议、冻结锚点等）一起回滚。变换见 [Conversation.withReplacedNode]（可单测）。
     */
    fun updateMessageNode(node: MessageNode) {
        chatService.updateConversationState(_conversationId) { current ->
            current.withReplacedNode(node)
        }
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

    /** 手动「继续生成」：把被截断的末条助手消息接着写下去（见 ChatService.continueMessage）。 */
    fun continueMessage() {
        chatService.continueMessage(_conversationId)
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

    /**
     * 切换会话级「不使用记忆」（隐身对话）。
     *
     * 走 ChatService 的统一元数据原语（同 [updatePinnedStatus] 的理由）：只改 DB 单列会被
     * 下一次整对象保存用内存里的旧值覆盖回去。
     */
    fun setMemoryDisabled(conversation: Conversation, disabled: Boolean) {
        viewModelScope.launch {
            chatService.setConversationMemoryDisabled(conversation.id, disabled)
        }
    }

    /**
     * 会话级「固化记忆」：让**这一条**会话立刻采纳当前的锚点集合（设计稿 §5）。
     *
     * 与记忆页的助手指级入口分工：这里只让选中的这一条付出一次前缀失效，那里一次改完
     * 该助手的所有会话。两者都是**显式**动作 —— 改锚点本身不会再顺带刷新任何已有会话。
     *
     * @return 固化进去的锚点条数（调用方拿去 toast：这个动作没有可见结果，没反馈就像没生效）
     */
    suspend fun freezeMemoryAnchors(conversation: Conversation): Int =
        chatService.freezeAnchorsForConversation(conversation.id, conversation.assistantId)

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

/**
 * 消息编辑的**纯函数**落点：只把 id 相同的那个节点换成 [node]，其余节点（含其候选消息）原样保留。
 *
 * 独立的顶层函数只为可测：编辑必须作用在**当前内存值**上（`updateConversationState` 的入参
 * 就是当前值），而不是拿某个回调闭包里的旧快照整对象替换——后者在生成中会把在途正文一起丢掉，
 * 不在生成中也会回滚期间发生过的其它字段改动。
 *
 * 目标节点不存在时**原样返回当前会话**（不新增节点）：编辑不存在的消息说明状态已过期，
 * 静默追加一个节点比丢弃这次编辑更糟。
 */
internal fun Conversation.withReplacedNode(node: MessageNode): Conversation {
    if (messageNodes.none { it.id == node.id }) return this
    return copy(
        messageNodes = messageNodes.map { existing ->
            if (existing.id == node.id) node else existing
        },
    )
}
