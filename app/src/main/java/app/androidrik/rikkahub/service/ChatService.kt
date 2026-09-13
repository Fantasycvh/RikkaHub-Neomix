package app.androidrik.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderManager
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.ToolApprovalState
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.canResumeToolExecution
import app.androidrik.ai.ui.finishPendingTools
import app.androidrik.ai.ui.finishReasoning
import app.androidrik.ai.ui.isEmptyInputMessage
import app.androidrik.common.android.Logging
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.ai.GenerationChunk
import app.androidrik.rikkahub.data.ai.GenerationPartialFailureException
import app.androidrik.rikkahub.data.ai.GenerationHandler
import app.androidrik.rikkahub.data.ai.mcp.McpManager
import app.androidrik.rikkahub.data.ai.mcp.McpNameSanitizer
import app.androidrik.rikkahub.data.ai.tools.createConversationTools
import app.androidrik.rikkahub.data.ai.tools.local.LocalTools
import app.androidrik.rikkahub.data.ai.tools.createSearchTools
import app.androidrik.rikkahub.data.ai.tools.createSkillTools
import app.androidrik.rikkahub.data.ai.tools.createSkillInstallTools
import app.androidrik.rikkahub.data.files.SkillInstaller
import app.androidrik.rikkahub.data.ai.tools.createWorkspaceTools
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import app.androidrik.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import app.androidrik.rikkahub.data.ai.transformers.OcrTransformer
import app.androidrik.rikkahub.data.ai.transformers.PlaceholderTransformer
import app.androidrik.rikkahub.data.ai.transformers.PromptInjectionTransformer
import app.androidrik.rikkahub.data.ai.transformers.RegexOutputTransformer
import app.androidrik.rikkahub.data.ai.transformers.TemplateTransformer
import app.androidrik.rikkahub.data.ai.transformers.ThinkTagTransformer
import app.androidrik.rikkahub.data.ai.transformers.TimeReminderTransformer
import app.androidrik.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import app.androidrik.rikkahub.data.event.AppEvent
import app.androidrik.rikkahub.data.event.AppEventBus
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.datastore.getAssistantById
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.datastore.getCurrentChatModel
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.utils.DEFAULT_CONTEXT_LENGTH
import app.androidrik.rikkahub.utils.effectiveContextLength
import app.androidrik.rikkahub.utils.estimateWindowTokens
import app.androidrik.rikkahub.data.ai.compression.shouldAutoCompress
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_RETAIN_RATIO
import app.androidrik.rikkahub.data.ai.compression.COMPRESS_TARGET_TOKENS
import app.androidrik.rikkahub.data.ai.compression.estimateCompressionPlan
import app.androidrik.rikkahub.data.ai.compression.splitIntoChunks
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.service.QueuedMessage
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.data.model.localFileUrls
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.AssistantAffectScope
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.replaceRegexes
import app.androidrik.rikkahub.data.model.toMessageNode
import app.androidrik.rikkahub.data.repository.ConversationRepository
import app.androidrik.rikkahub.data.repository.FolderRepository
import app.androidrik.rikkahub.data.repository.MemoryRepository
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.web.BadRequestException
import app.androidrik.rikkahub.web.NotFoundException
import app.androidrik.rikkahub.utils.applyPlaceholders
import app.androidrik.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/** 用户消息单点最大字符数：超长消息存库膨胀 + 上下文爆表 + API 413 */
private const val MAX_USER_INPUT_CHARS = 200_000

/**
 * 将压缩结果合并到最新会话状态，而不是用压缩开始时的快照整体覆盖。
 *
 * compressConversation 要跑多个 LLM 请求（长对话可达数十秒~数分钟），期间用户可能继续
 * 发消息/生成回复（追加在会话末尾）。旧实现用快照 copy 后整体 saveConversation，会把
 * 压缩期间的新消息连内存带库一起覆盖丢失。此函数保留快照之后追加的新节点，并基于
 * latest 而非快照做 copy，避免把期间更新的 title/folderId 等字段回滚成旧值。
 */
fun mergeCompressedConversation(
    snapshot: Conversation,
    latest: Conversation,
    compressedNodes: List<MessageNode>,
): Conversation = latest.copy(
    messageNodes = compressedNodes + latest.messageNodes.drop(snapshot.messageNodes.size),
    chatSuggestions = emptyList(),
)

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

internal fun createForkConversation(
    source: Conversation,
    messageNodes: List<MessageNode>,
): Conversation = Conversation(
    id = Uuid.random(),
    assistantId = source.assistantId,
    messageNodes = messageNodes,
    customSystemPrompt = source.customSystemPrompt,
    modeInjectionIds = source.modeInjectionIds,
    lorebookIds = source.lorebookIds,
    workspaceCwd = source.workspaceCwd,
    folderId = source.folderId,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) },
                onGenerationFinished = { sessionId, cause ->
                    val session = sessions[sessionId]
                    if (cause != null) session?.messageQueue?.pause()
                    // 本轮停在工具审批：语音模式的 reply 等待者等不到文本，直接失败提示
                    if (session?.state?.value?.currentMessages?.any { message ->
                            message.parts.any { it is UIMessagePart.Tool && it.isPending }
                        } == true) {
                        session.messageQueue.failReplyWaiters(
                            context.getString(R.string.chat_page_voice_tool_approval)
                        )
                    }
                    appScope.launch { dispatchNextQueuedMessage(sessionId) }
                }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        synchronized(session) {
            // 生成期间不再打断当前轮次——入队，等当前轮次（含待审批工具）结束后自动派发
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(content, answer)
            dispatchNextQueuedMessage(conversationId)
        }
    }

    /** 语音模式：立即入队，返回的 Deferred 在本轮生成完成后 resolve 为新增的 assistant 文本。 */
    fun enqueueVoiceMessage(conversationId: Uuid, text: String): Deferred<String?> {
        val session = getOrCreateSession(conversationId)
        val reply = CompletableDeferred<String?>()
        synchronized(session) {
            check(text.isNotBlank()) { context.getString(R.string.chat_page_voice_empty) }
            check(!session.messageQueue.state.value.paused || session.messageQueue.state.value.messages.isEmpty()) {
                context.getString(R.string.chat_page_voice_resume_queue)
            }
            check(session.state.value.currentMessages.none { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            }) { context.getString(R.string.chat_page_voice_tools_before_resume) }
            if (session.messageQueue.state.value.messages.isEmpty()) session.messageQueue.resume()
            session.messageQueue.enqueue(listOf(UIMessagePart.Text(text)), reply = reply)
            dispatchNextQueuedMessage(conversationId)
        }
        return reply
    }

    fun getMessageQueueFlow(conversationId: Uuid): StateFlow<MessageQueueState> =
        getOrCreateSession(conversationId).messageQueue.state

    fun removeQueuedMessage(conversationId: Uuid, messageId: Uuid) {
        sessions[conversationId]?.messageQueue?.remove(messageId)?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun beginEditQueuedMessage(conversationId: Uuid, messageId: Uuid): QueuedMessage? =
        sessions[conversationId]?.messageQueue?.beginEdit(messageId)

    fun finishEditQueuedMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>? = null
    ) {
        sessions[conversationId]?.messageQueue?.finishEdit(messageId, parts)
            ?.let(::cleanupQueuedAttachments)
        dispatchNextQueuedMessage(conversationId)
    }

    fun resumeMessageQueue(conversationId: Uuid) {
        sessions[conversationId]?.messageQueue?.resume()
        dispatchNextQueuedMessage(conversationId)
    }

    private fun cleanupQueuedAttachments(previous: QueuedMessage) {
        val candidates = previous.parts.localFileUrls()
        if (candidates.isEmpty()) return
        appScope.launch {
            try {
                // 未打开的会话及未选中的分支也可能引用同一附件。
                val persistedReferences = buildSet {
                    for (candidate in candidates) {
                        if (conversationRepo.hasFileReference(candidate)) add(candidate)
                    }
                }
                // 数据库查询挂起期间队列可能已推进，删除前重新读取内存引用。
                val currentSessions = sessions.values.toList()
                val unusedFiles = unreferencedQueuedAttachmentUrls(
                    previous = previous,
                    conversations = currentSessions.map { it.state.value },
                    pendingMessages = currentSessions.flatMap {
                        it.messageQueue.state.value.messages + listOfNotNull(it.submittingMessage)
                    },
                ) - persistedReferences
                if (unusedFiles.isNotEmpty()) {
                    filesManager.deleteChatFiles(unusedFiles.map { it.toUri() })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // 无法确认引用时保留文件，避免误删。
                Log.w(TAG, "Failed to clean queued attachments", e)
            }
        }
    }

    /** 当前无生成任务且无待审批工具时才派发下一条（与审批串行化天然对齐）。返回派发的 Job（供语音模式观测）。 */
    private fun dispatchNextQueuedMessage(conversationId: Uuid): Job? {
        val session = sessions[conversationId] ?: return null
        synchronized(session) {
            if (session.getJob() != null || session.state.value.currentMessages.any { message ->
                    message.parts.any { it is UIMessagePart.Tool && it.isPending }
                }) return null
            val next = session.messageQueue.takeNext() ?: return null
            session.submittingMessage = next
            return sendQueuedMessage(session, next)
        }
    }

    private fun sendQueuedMessage(session: ConversationSession, queued: QueuedMessage): Job {
        val conversationId = session.id
        val content = queued.parts
        val answer = queued.answer
        val previousAssistantIds = session.state.value.currentMessages.map { it.id }.toSet()
        val job = appScope.launch {
            try {
                finishInterruptedPendingTools(conversationId)

                val currentConversation = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val newConversation = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, newConversation)
                session.submittingMessage = null

                // 开始补全
                if (answer) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
                // 语音模式：等本轮生成真正落库后，把新增的 assistant 文本 resolve 给 reply。
                queued.reply?.completeWith(runCatching {
                    val messages = session.state.value.currentMessages
                    check(!session.messageQueue.state.value.paused) {
                        context.getString(R.string.chat_page_voice_generation_failed)
                    }
                    check(messages.none { message ->
                        message.parts.any { it is UIMessagePart.Tool && it.isPending }
                    }) { context.getString(R.string.chat_page_voice_tool_approval) }
                    messages
                        .filter { it.id !in previousAssistantIds && it.role == MessageRole.ASSISTANT }
                        .joinToString("\n") { it.toText() }
                })
            } catch (e: Exception) {
                e.printStackTrace()
                if (e is CancellationException) throw e
                session.messageQueue.pause()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        job.invokeOnCompletion { cause ->
            synchronized(session) {
                if (session.submittingMessage?.id == queued.id) session.submittingMessage = null
            }
            if (cause != null) queued.reply?.completeExceptionally(cause)
            appScope.launch { dispatchNextQueuedMessage(conversationId) }
        }
        session.setJob(job)
        return job
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        ).take(MAX_USER_INPUT_CHARS)
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value

                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val node = conversation.getMessageNodeByMessage(message)
                    val indexAt = conversation.messageNodes.indexOf(node)
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        val node = conversation.getMessageNodeByMessage(message)
                        val nodeIndex = conversation.messageNodes.indexOf(node)
                        handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()

        val job = appScope.launch {
            try {
                // 串行化审批落地：等前任决策保存完成后再应用本决策——连续审批不互相打断
                //（55506496 修复：旧实现 setJob 会 cancel 前任 → 连续工具审批丢失）。
                afterPreviousGeneration(previousJob) {
                    val conversation = session.state.value
                    // 忽略双击与对已完成/不活跃工具的过期审批：已执行（isExecuted）或已取消
                    // 的工具不再等待审批，直接跳过本次落地。
                    if (conversation.currentMessages.none { message ->
                            message.getTools().any { it.toolCallId == toolCallId && it.isPending }
                        }) return@afterPreviousGeneration
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // 内存态更新：短路重建，只有包含目标 tool 的分支才深拷贝，
                    // 避免每次批准都全量重建整棵消息树（大量命令批准时主线程累积阻塞 → ANR）。
                    val updatedNodes = conversation.messageNodes.map { node ->
                        val targetMsgIndex = node.messages.indexOfFirst { msg ->
                            msg.parts.any { it is UIMessagePart.Tool && it.toolCallId == toolCallId }
                        }
                        if (targetMsgIndex < 0) {
                            node
                        } else {
                            val targetMsg = node.messages[targetMsgIndex]
                            val updatedParts = targetMsg.parts.map { part ->
                                if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                                    part.copy(approvalState = newApprovalState)
                                } else {
                                    part
                                }
                            }
                            node.copy(
                                messages = node.messages.mapIndexed { i, msg ->
                                    if (i == targetMsgIndex) msg.copy(parts = updatedParts) else msg
                                }
                            )
                        }
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)

                    // 持久化移 IO 线程：Room 写 + 深拷贝不在主线程执行
                    withContext(Dispatchers.IO) {
                        saveConversation(conversationId, updatedConversation)
                    }

                    // Check if there are still pending tools
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled
                    if (!hasPendingTools) {
                        // β: yield 让出主线程，先让"批准状态更新 → Compose 重组"完成渲染，
                        // 再重启生成，避免状态更新与生成首帧双重组在同一帧竞争主线程（交互卡顿）
                        yield()
                        handleMessageComplete(conversationId)
                    }

                    _generationDoneFlow.emit(conversationId)
                }
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job, cancelPrevious = false)
    }

    // ---- 处理消息补全 ----

    /**
     * 批量批准当前所有待批准的工具调用（方案 C：减少逐条批准次数，缓解大量命令场景下的 ANR）。
     */
    fun handleApproveAllPending(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val job = appScope.launch {
            try {
                val conversation = session.state.value
                // 找到所有 pending tools，统一置 Approved
                var approvedCount = 0
                val updatedNodes = conversation.messageNodes.map { node ->
                    var nodeChanged = false
                    val updatedMessages = node.messages.map { msg ->
                        val updatedParts = msg.parts.map { part ->
                            if (part is UIMessagePart.Tool && part.isPending) {
                                nodeChanged = true
                                approvedCount++
                                part.copy(approvalState = ToolApprovalState.Approved)
                            } else {
                                part
                            }
                        }
                        if (nodeChanged) msg.copy(parts = updatedParts) else msg
                    }
                    if (nodeChanged) node.copy(messages = updatedMessages) else node
                }
                if (approvedCount == 0) return@launch // 没有待批准工具，无需处理

                val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                withContext(Dispatchers.IO) {
                    saveConversation(conversationId, updatedConversation)
                }

                // 批量批准后没有 pending，继续生成
                // β: yield 让出主线程，先完成"批量批准状态 → 重组"渲染，再重启生成
                yield()
                handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }

        session.setJob(job)
    }

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        val settings = settingsStore.settingsFlow.first()
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId) ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)

            // 自动压缩（design §4.2 两层分工）：占用达阈值时先把早期历史摘要为检查点，
            // 再按发送窗口组装上下文。失败静默（不影响本次发送，limitContext 仍作兜底）。
            // 重生成历史消息时跳过（走完整轨迹，压缩不改变重生成语义）。
            if (messageRange == null) {
                maybeAutoCompress(conversationId, settings, model)
            }

            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = if (messageRange != null) {
                    // 重新生成历史消息：保持原始轨迹（被压缩的原始消息仍在，上下文完整）
                    // —— 压缩是「发送窗口」优化，不应改变对历史的重新生成语义
                    conversation.currentMessages.let {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    }
                } else {
                    // 发送窗口：压缩检查点作为新段起点，边界之前的旧历史不发送（design §4.1 三边界对齐）；
                    // 无有效检查点时返回全量（与旧行为一致）
                    conversation.requestWindowMessages()
                },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (assistant.enableWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    addAll(localTools.getTools(assistant.localTools))
                    if (assistant.enableRecentChatsReference) {
                        addAll(createConversationTools(conversationRepo, assistant.id))
                    }
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    // 动态添加 Skill（上游 #1031）：AI 可从 GitHub 安装 / 把对话经验保存为技能。始终注册（不依赖已启用技能）。
                    addAll(createSkillInstallTools(SkillInstaller(skillManager)))
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                            )
                        )
                    }
                    // 服务器名/工具名允许含中文等非 ASCII 字符，这里净化为 provider 可接受的
                    // function name（^[a-zA-Z0-9_-]{1,64}$），显示层仍用原名。
                    // 不同原名可能净化后相同，用 usedNames 保证发给模型的名字唯一。
                    val usedToolNames = mutableSetOf<String>()
                    mcpManager.getAllAvailableTools().forEach { (serverId, serverName, tool) ->
                        add(
                            Tool(
                                name = McpNameSanitizer.buildUniqueFunctionName(
                                    serverName = serverName,
                                    toolName = tool.name,
                                    used = usedToolNames,
                                ),
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                needsApproval = { tool.needsApproval },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(chunk.messages)
                        updateConversation(conversationId, updatedConversation)

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            if (it is GenerationPartialFailureException) {
                // #1812：半截输出失败 → 保留为草稿候选并立即落库。
                // 这是失败路径的第一个写库点（正常流程只在 onSuccess 落库），
                // 同时缓解 #1820（切对话后内存态丢失）。
                val annotated = it.partialMessages.mapIndexed { index, message ->
                    if (index == it.partialMessages.lastIndex && message.role == MessageRole.ASSISTANT) {
                        message.copy(
                            annotations = message.annotations + UIMessageAnnotation.PartialFailure
                        )
                    } else {
                        message
                    }
                }
                val updatedConversation = getConversationFlow(conversationId).value
                    .updateCurrentMessages(annotated)
                updateConversation(conversationId, updatedConversation)
                // D1：落库防护——超大内容(>2MB blob)等写入失败时降级为可见错误，
                // 不能让草稿保存异常在 onFailure 内静默上抛
                runCatching { saveConversation(conversationId, updatedConversation) }
                    .onFailure { saveError ->
                        addError(
                            saveError,
                            conversationId,
                            title = context.getString(R.string.error_title_generation),
                        )
                        Logging.log(TAG, "handleMessageComplete: partial draft save failed: $saveError")
                    }
                Logging.log(TAG, "handleMessageComplete: partial generation kept as draft")
                return@onFailure
            }

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            // D1：落库防护——与 onFailure 半截落库同款降级，防止成功路径写入静默失败
            runCatching { saveConversation(conversationId, finalConversation) }
                .onFailure { saveError ->
                    addError(
                        saveError,
                        conversationId,
                        title = context.getString(R.string.error_title_generation),
                    )
                    Logging.log(TAG, "handleMessageComplete: save failed: $saveError")
                }

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            // 不再标 Denied：cancel 后 output 非空 → isExecuted=true → isPending=false
            //（isPending = !isExecuted && Pending），取消状态不会误标成"拒绝待处理"。
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckTitleModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            it.printStackTrace()
        }
    }

    /**
     * 检查点式压缩（design-auto-compaction §4.1 三边界对齐 + §4.4 独立表）：
     *
     * 1. 保留策略 token 驱动：从尾部往前累计，预算 = 模型窗口 × [COMPRESS_RETAIN_RATIO]（至少 2 条）；
     * 2. 其余发送窗口历史分块并行摘要，与旧检查点（prior）合并为单一新摘要（避免层层累积）；
     * 3. **轨迹只追加**：不改动 message_node，只往 compression_checkpoint 表追加一行
     *    （content + boundaryNodeId），并 trimToLatest 清理旧行；
     * 4. 更新内存态 conversation.compressionSummaries（发送窗口与 UI 据此生效）。
     *
     * @param windowTokens 模型上下文窗口 token 数（调用方用 effectiveContextLength 算好）
     * @param additionalPrompt 用户附加指令（与 prior 检查点拼接后注入 {additional_context}）
     * @param targetTokens 摘要目标 token（注入 {target_tokens}）
     * @param retainRatio 保留窗口占比（0.05~0.9 钳制；默认 COMPRESS_RETAIN_RATIO = 0.4）
     * @return 成功时 Success(新检查点)，无可压缩内容时 Success(null)，失败时 Failure
     */
    suspend fun compressToCheckpoint(
        conversationId: Uuid,
        windowTokens: Int,
        additionalPrompt: String = "",
        targetTokens: Int = COMPRESS_TARGET_TOKENS,
        retainRatio: Double = COMPRESS_RETAIN_RATIO,
    ): Result<CompressionSummary?> = runCatching {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        val providerHandler = providerManager.getProviderByType(provider)

        // 当前会话快照（含内存态最新）
        val conversation = getConversationFlow(conversationId).value
        // 只看发送窗口内的历史：边界前的旧内容已由 prior 检查点覆盖
        val window = conversation.windowNodes()
        val budget = (windowTokens * retainRatio.coerceIn(0.05, 0.9)).toInt()
        val plan = estimateCompressionPlan(window, budget)
        if (!plan.shouldCompress) return@runCatching null  // 无可压缩内容（非错误）

        val messagesToCompress = plan.toCompress.map { it.currentMessage }
        val priorSummary = conversation.compressionSummaries.lastOrNull()?.content.orEmpty()
        val priorContext = if (priorSummary.isNotBlank()) {
            "PRIOR CHECKPOINT (merge this with the new conversation into one consolidated checkpoint):\n$priorSummary"
        } else {
            ""
        }

        suspend fun compressChunk(chunk: List<UIMessage>): String {
            val contentToCompress = chunk.joinToString("\n\n") { it.summaryAsText(maxLength = 2000) }
            val additional = buildString {
                if (additionalPrompt.isNotBlank()) {
                    appendLine("Additional instructions from user: $additionalPrompt")
                    appendLine()
                }
                append(priorContext)
            }.trim()
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to additional,
                "locale" to Locale.getDefault().displayName,
            )
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model),
            )
            return result.choices[0].message?.toText()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        val combined = coroutineScope {
            splitIntoChunks(messagesToCompress)
                .map { chunk -> async { compressChunk(chunk) } }
                .awaitAll()
        }.joinToString("\n\n")

        val boundaryNodeId = plan.toCompress.lastOrNull()?.id?.toString()
            ?: return@runCatching null
        val priorCount = conversation.compressionSummaries.lastOrNull()?.messageCount ?: 0
        val checkpoint = CompressionSummary(
            conversationId = conversationId,
            content = combined,
            messageCount = priorCount + messagesToCompress.size,
            boundaryNodeId = boundaryNodeId,
            createdAt = Instant.now(),
        )

        // 轨迹只追加：落库新检查点（含自增 id），清理更早的检查点行（摘要已合并）
        val saved = conversationRepo.addCompressionCheckpoint(checkpoint)
        conversationRepo.trimCompressionCheckpoints(conversationId)

        // 更新内存态：发送窗口与 UI 据此生效（不改 messageNodes——历史可回溯）
        updateConversationState(conversationId) { latest ->
            latest.copy(compressionSummaries = listOf(saved))
        }
        saved
    }.onFailure {
        Log.e(TAG, "compressToCheckpoint failed for $conversationId", it)
    }

    /**
     * 自动压缩：占用达到阈值时调用 [compressToCheckpoint]。
     *
     * - 未启用（默认关）或未达阈值时不动作
     * - 失败/无可压缩内容时静默（不打断发送；`limitContext` 仍是同步兜底）
     * - 占用估算复用 [estimateWindowTokens]（usage 优先 + 本地兜底），与 UI 进度条同源
     */
    private suspend fun maybeAutoCompress(
        conversationId: Uuid,
        settings: Settings,
        model: Model,
    ) {
        if (!settings.autoCompressEnabled) return
        val conversation = getConversationFlow(conversationId).value
        // 已有检查点时仍按窗口占用判断（检查点已把边界前内容移出窗口，不会重复压缩同一段）
        val windowTokens = model.effectiveContextLength(settings.contextLengthOverride)
        val usedTokens = conversation.estimateWindowTokens(model)
        if (!shouldAutoCompress(usedTokens, windowTokens, settings.autoCompressThresholdPercent)) return

        Log.i(TAG, "auto compress triggered: $usedTokens / $windowTokens tokens")
        compressToCheckpoint(
            conversationId = conversationId,
            windowTokens = windowTokens,
            targetTokens = settings.autoCompressTargetTokens,
            retainRatio = settings.autoCompressRetainPercent / 100.0,
        ).onFailure {
            // 失败静默：不打断发送（limitContext 兜底），仅记录
            Log.w(TAG, "auto compress failed for $conversationId", it)
        }
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val current = getConversationFlow(conversationId).value
        updateConversation(conversationId, update(current))
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false
        var editedIndex = -1

        val updatedNodes = currentConversation.messageNodes.mapIndexed { index, node ->
            if (!node.messages.any { it.id == messageId }) {
                return@mapIndexed node
            }
            edited = true
            editedIndex = index

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        // 编辑落在压缩边界内 -> 摘要已与实际历史不一致，丢弃检查点（回退全量发送）
        invalidateCheckpointIfWithinBoundary(conversationId, currentConversation, editedIndex)

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    /**
     * 若被改动节点落在压缩边界内（含边界），删除该会话的检查点并清空内存态。
     *
     * 摘要反映压缩时刻的历史；边界内消息被编辑/删除后摘要不再准确，继续用它会让 AI
     * 的上下文与用户所见对不上。失效后回退全量发送，下次达阈值可重新压缩。
     */
    private suspend fun invalidateCheckpointIfWithinBoundary(
        conversationId: Uuid,
        conversation: Conversation,
        affectedNodeIndex: Int,
    ) {
        if (affectedNodeIndex < 0) return
        if (!conversation.isWithinCompressionBoundary(affectedNodeIndex)) return
        conversationRepo.deleteCompressionCheckpoints(conversationId)
        updateConversationState(conversationId) { it.copy(compressionSummaries = emptyList()) }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = createForkConversation(currentConversation, copiedNodes)

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        // 删除的消息在压缩边界内 -> 摘要失效（节点消失本身也会让 activeCompression 失效，
        // 但边界内非边界节点的删除不会触发该判定，故显式清理）
        val targetIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        invalidateCheckpointIfWithinBoundary(conversationId, currentConversation, targetIndex)

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        val jobs = synchronized(session) {
            session.cancelJobs()
        }
        if (jobs.isEmpty()) return
        jobs.forEach { it.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
