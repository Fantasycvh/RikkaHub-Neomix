package app.androidrik.rikkahub.ui.pages.chat

import app.androidrik.rikkahub.ui.components.message.PlanBar
import app.androidrik.rikkahub.data.ai.tools.local.latestTodoPlan
import app.androidrik.rikkahub.ui.components.ai.CompressContextDialog
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import app.androidrik.rikkahub.utils.formatContextLength
import app.androidrik.rikkahub.utils.computeContextCapacity
import app.androidrik.rikkahub.utils.CONTEXT_USAGE_WARNING_RATIO
import app.androidrik.rikkahub.utils.ContextCapacity
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import app.androidrik.rikkahub.ui.components.drawer.ChatScalingDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import com.dokar.sonner.TextToastAction
import app.androidrik.rikkahub.data.model.followingCount
import app.androidrik.rikkahub.data.model.followingMessages
import app.androidrik.rikkahub.service.ChatService
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toQuotedMessage
import app.androidrik.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LeftToRightListBullet
import me.rerere.hugeicons.stroke.Menu03
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Share01
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findProvider
import app.androidrik.rikkahub.data.datastore.getCurrentAssistant
import app.androidrik.rikkahub.data.datastore.getCurrentChatModel
import app.androidrik.rikkahub.data.files.FilesManager
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.service.ChatError
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.ui.pages.chat.VoiceSessionState
import app.androidrik.rikkahub.ui.pages.chat.rememberVoiceModeStarter
import app.androidrik.rikkahub.ui.components.ai.ChatInput
import app.androidrik.rikkahub.ui.components.ai.ContextUsage
import app.androidrik.rikkahub.ui.components.ai.FilesPicker
import app.androidrik.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import app.androidrik.rikkahub.ui.components.ai.useCropLauncher
import app.androidrik.rikkahub.utils.DEFAULT_CONTEXT_LENGTH
import app.androidrik.rikkahub.utils.effectiveContextLength
import app.androidrik.rikkahub.utils.estimateWindowTokens
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionCamera
import app.androidrik.rikkahub.ui.components.ui.permission.PermissionManager
import app.androidrik.rikkahub.ui.components.ui.permission.rememberPermissionState
import app.androidrik.rikkahub.ui.context.LocalNavController
import app.androidrik.rikkahub.ui.context.LocalToaster
import app.androidrik.rikkahub.ui.context.Navigator
import app.androidrik.rikkahub.ui.hooks.ChatInputState
import app.androidrik.rikkahub.ui.hooks.EditStateContent
import app.androidrik.rikkahub.ui.hooks.useEditState
import app.androidrik.rikkahub.utils.ImageUtils
import app.androidrik.rikkahub.utils.base64Decode
import app.androidrik.rikkahub.utils.isAllowedFileType
import app.androidrik.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid

/**
 * 「编辑消息」的**生成中守卫**（与 `onDelete` 同款）：返回 true = 已拒绝并已提示。
 *
 * 为什么必须拦：编辑的落点会改写 `messageNodes`，而生成中每一块流式内容都在往同一个节点上
 * 回写。放行编辑就会出现两份写入互相覆盖——用户的编辑被下一块流冲掉，或者反过来把在途正文
 * 冲掉（`ChatVM.updateConversation` 的旧落点还是「整对象替换」，丢得更彻底）。
 *
 * 抽成顶层纯函数是为了能证伪（`onDelete` 的同类守卫在 Compose 回调里，JVM 单测够不到）。
 */
internal fun rejectMessageEditWhileGenerating(
    loadingJob: Job?,
    onReject: () -> Unit,
): Boolean {
    if (loadingJob == null) return false
    onReject()
    return true
}

@Composable
fun ChatPage(id: Uuid, text: String?, files: List<Uri>, nodeId: Uuid? = null) {
    val vm: ChatVM = koinViewModel(
        parameters = {
            parametersOf(id.toString())
        }
    )
    val filesManager: FilesManager = koinInject()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()

    val setting by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val messageQueueState by vm.messageQueue.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val currentChatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val enableWebSearch by vm.enableWebSearch.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val voiceState by vm.voiceSession.state.collectAsStateWithLifecycle()
    val startVoiceMode = rememberVoiceModeStarter(vm, setting)

    // 上下文占用**只算一份**：标题旁读数、输入栏指示器、容量面板共用同一个来源（2026-10-02 决定）。
    // **计算必须离开主线程**（ANR 实测 2026-10-02，trace：main 线程 97s CPU 全在
    // ByteLevelBpe）：真 tokenizer 全窗分词 + 流式期间每个 chunk 都触发重组，在组合期
    // 同步算会被打满。这里 300ms 节流 + Default 线程计算 + latest-wins（慢计算被新
    // 实例取代即取消）；首帧 0/上限，首个计算 ≤300ms 后到达。
    val contextUsage by produceState(
        initialValue = ContextUsage(
            usedTokens = 0,
            windowTokens = currentChatModel?.effectiveContextLength(setting.contextLengthOverride)
                ?: DEFAULT_CONTEXT_LENGTH,
        ),
        currentChatModel,
        setting.contextLengthOverride,
    ) {
        var lastComputedAt = 0L
        var job: Job? = null
        snapshotFlow { conversation }.collect { convo ->
            val now = System.currentTimeMillis()
            if (now - lastComputedAt < 300) return@collect
            lastComputedAt = now
            job?.cancel()
            job = launch(Dispatchers.Default) {
                val usedTokens = convo.estimateWindowTokens(currentChatModel)
                value = ContextUsage(
                    usedTokens = usedTokens,
                    windowTokens = currentChatModel?.effectiveContextLength(setting.contextLengthOverride)
                        ?: DEFAULT_CONTEXT_LENGTH,
                )
            }
        }
    }

    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    val softwareKeyboardController = LocalSoftwareKeyboardController.current

    // Handle back press when drawer is open (small screen only)
    val windowAdaptiveInfo = currentWindowDpSize()
    val isBigScreen =
        windowAdaptiveInfo.width > windowAdaptiveInfo.height && windowAdaptiveInfo.width >= 1100.dp

    // Hide keyboard when drawer opens
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            softwareKeyboardController?.hide()
        }
    }

    // Reset drawer state when entering big screen mode
    LaunchedEffect(isBigScreen) {
        if (isBigScreen && drawerOpen) {
            drawerOpen = false
        }
    }

    val inputState = vm.inputState

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (files.isNotEmpty()) {
            val localFiles = filesManager.createChatFilesByContents(files)
            val contentTypes = files.mapNotNull { file ->
                filesManager.getFileMimeType(file)
            }
            val parts = buildList {
                localFiles.forEachIndexed { index, file ->
                    val type = contentTypes.getOrNull(index)
                    if (type?.startsWith("image/") == true) {
                        add(UIMessagePart.Image(url = file.toString()))
                    } else if (type?.startsWith("video/") == true) {
                        add(UIMessagePart.Video(url = file.toString()))
                    } else if (type?.startsWith("audio/") == true) {
                        add(UIMessagePart.Audio(url = file.toString()))
                    }
                }
            }
            inputState.messageContent = parts
        }
        text?.base64Decode()?.let { decodedText ->
            if (decodedText.isNotEmpty()) {
                inputState.setMessageText(decodedText)
            }
        }
    }

    val chatListState = rememberLazyListState()
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                val index = conversation.messageNodes.indexOfFirst { it.id == nodeId }
                if (index >= 0) {
                    chatListState.scrollToItem(index)
                }
            } else {
                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
            }
            vm.chatListInitialized = true
        }
    }

    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    conversation = conversation,
                    contextUsage = contextUsage,
                    drawerOpen = false,
                    onOpenDrawer = {},
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    enableWebSearch = enableWebSearch,
                    currentChatModel = currentChatModel,
                    messageQueueState = messageQueueState,
                    voiceState = voiceState,
                    startVoiceMode = startVoiceMode,
                    bigScreen = true,
                    errors = errors,
                    onDismissError = { vm.dismissError(it) },
                    onClearAllErrors = { vm.clearAllErrors() },
                )
            }
        }

        else -> {
            ChatScalingDrawer(
                open = drawerOpen,
                onOpenChange = { drawerOpen = it },
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting
                    )
                },
                content = {
                    ChatPageContent(
                        inputState = inputState,
                        loadingJob = loadingJob,
                        processingStatus = processingStatus,
                        setting = setting,
                        conversation = conversation,
                        contextUsage = contextUsage,
                        drawerOpen = drawerOpen,
                        onOpenDrawer = { drawerOpen = true },
                        navController = navController,
                        vm = vm,
                        chatListState = chatListState,
                        enableWebSearch = enableWebSearch,
                        currentChatModel = currentChatModel,
                        messageQueueState = messageQueueState,
                        voiceState = voiceState,
                        startVoiceMode = startVoiceMode,
                        bigScreen = false,
                        errors = errors,
                        onDismissError = { vm.dismissError(it) },
                        onClearAllErrors = { vm.clearAllErrors() },
                    )
                }
            )
        }
    }
}

@Composable
private fun ChatPageContent(
    inputState: ChatInputState,
    loadingJob: Job?,
    processingStatus: String? = null,
    setting: Settings,
    bigScreen: Boolean,
    conversation: Conversation,
    contextUsage: ContextUsage,
    drawerOpen: Boolean,
    onOpenDrawer: () -> Unit,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    enableWebSearch: Boolean,
    currentChatModel: Model?,
    messageQueueState: MessageQueueState,
    voiceState: VoiceSessionState,
    startVoiceMode: () -> Unit,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val workspaceRepository: WorkspaceRepository = koinInject()

    /**
     * 执行重生成；scope B 发生截断时弹「撤销」（snackbar）。
     *
     * 撤销用现有 toaster（sonner）的 action 能力，**不需要替换页面 scaffold**。
     * 撤销覆盖范围含"已开始生成"——[app.androidrik.rikkahub.service.ChatService.undoRegenerateTruncation]
     * 会先取消在飞生成再还原（见 `docs/design/design-regenerate-scope.md` §五）。
     */
    fun doRegenerate(message: UIMessage, regenerateScope: ChatService.RegenerateScope) {
        vm.regenerateAtMessage(message, regenerateScope) { truncation ->
            toaster.show(
                message = resources.getString(R.string.regenerate_truncated, truncation.removedNodes.size),
                action = TextToastAction(resources.getString(R.string.regenerate_truncated_undo)) {
                    vm.undoRegenerateTruncation(truncation)
                },
                type = ToastType.Info,
                duration = 6.seconds,
            )
        }
    }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberHazeState()
    val glassHazeState = rememberHazeState()
    val assistant = setting.getCurrentAssistant()
    var showFilesSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    // 上下文容量面板 + 压缩参数对话框（压缩入口在面板里，参数界面与 + 菜单共用同一个）
    var showContextSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    // 重生成 scope 选择：非空时显示 RegenerateScopeDialog（仅当有下游时才需要选/确认）
    var regenerateScopeFor by remember { mutableStateOf<UIMessage?>(null) }
    // 「导出后继续」：导出下游消息的 sheet；关闭后执行 scope B
    var exportThenRegenerateFor by remember { mutableStateOf<UIMessage?>(null) }

    val completionProviders = remember(assistant.workspaceId, conversation.workspaceCwd, workspaceRepository) {
        assistant.workspaceId?.let { workspaceId ->
            listOf(
                WorkspaceCompletionProvider(
                    workspaceId = workspaceId.toString(),
                    repository = workspaceRepository,
                    currentCwd = conversation.workspaceCwd,
                )
            )
        }.orEmpty()
    }

    // 计划条（todo_write）：当前分支里最后一次 todo_write 调用的参数即当前清单，
    // 消息正文不再渲染计划卡（借鉴 Yuihub 最终形态），输入栏上方的 PlanBar 是唯一展示面
    val planTodos = remember(conversation) { latestTodoPlan(conversation) }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        AssistantBackground(setting = setting, modifier = Modifier.hazeSource(hazeState).hazeSource(glassHazeState))
        Scaffold(
            topBar = {
                TopBar(
                    settings = setting,
                    conversation = conversation,
                    contextUsage = contextUsage,
                    drawerOpen = drawerOpen,
                    onOpenDrawer = onOpenDrawer,
                    bigScreen = bigScreen,
                    previewMode = previewMode,
                    hazeState = hazeState,
                    glassHazeState = glassHazeState,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onExport = {
                        showExportSheet = true
                    },
                    onUpdateTitle = {
                        vm.updateTitle(it)
                    }
                )
            },
            bottomBar = {
                Column {
                PlanBar(todos = planTodos)
                ChatInput(
                    state = inputState,
                    loading = loadingJob != null,
                    settings = setting,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    contextUsage = contextUsage,
                    onShowContextSheet = { showContextSheet = true },
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = {
                        val current = setting.getCurrentAssistant()
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == current.id) {
                                        assistant.copy(enableWebSearch = !enableWebSearch)
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    // 会话内记忆入口（docs/design/design-memory-injection-tiering.md §8.3）。
                    // 与抽屉菜单里的同名项是**同一套语义**，只是作用对象固定为"当前这条会话"。
                    memoryDisabled = conversation.memoryDisabled,
                    onToggleMemoryDisabled = { disabled ->
                        vm.setMemoryDisabled(conversation, disabled)
                    },
                    onFreezeMemory = {
                        // 固化要拿返回值才知道作用面（几条锚点），所以 toast 放在这里而不是按钮里
                        scope.launch {
                            val count = vm.freezeMemoryAnchors(conversation)
                            toaster.show(
                                message = resources.getString(R.string.chat_memory_freeze_toast, count)
                            )
                        }
                    },
                    onSendClick = {
                        if (currentChatModel == null) {
                            toaster.show("请先选择模型", type = ToastType.Error)
                            return@ChatInput
                        }
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(
                                content = inputState.getContents(),
                                annotations = inputState.pendingQuote?.let { listOf(it) }.orEmpty(),
                            )
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            vm.handleMessageEdit(
                                parts = inputState.getContents(),
                                messageId = inputState.editingMessage!!,
                            )
                        } else {
                            vm.handleMessageSend(
                                content = inputState.getContents(),
                                answer = false,
                                annotations = inputState.pendingQuote?.let { listOf(it) }.orEmpty(),
                            )
                            scope.launch {
                                chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                            }
                        }
                        inputState.clearInput()
                    },
                    // 插队发送：打断当前生成、立刻发这条（半截落成可编辑候选、待审批工具默认拒绝）。
                    // 编辑态下不提供——编辑历史消息与「打断当前轮次」是两件事，混在一起语义不清。
                    onInterruptSendClick = {
                        vm.handleMessageSendInterrupting(
                            content = inputState.getContents(),
                            annotations = inputState.pendingQuote?.let { listOf(it) }.orEmpty(),
                        )
                        scope.launch {
                            chatListState.requestScrollToItem(conversation.currentMessages.size + 5)
                        }
                        inputState.clearInput()
                    },
                    messageQueue = messageQueueState,
                    onRemoveQueuedMessage = vm::removeQueuedMessage,
                    onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
                    onFinishEditQueuedMessage = { id, parts -> vm.finishEditQueuedMessage(id, parts) },
                    onMoveQueuedMessageToFront = vm::moveQueuedMessageToFront,
                    onResumeMessageQueue = vm::resumeMessageQueue,
                    voiceState = voiceState,
                    onStartVoiceMode = startVoiceMode,
                    onStopVoiceMode = vm.voiceSession::stop,
                    onUpdateChatModel = {
                        vm.setChatModel(assistant = setting.getCurrentAssistant(), model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateSettings(
                            setting.copy(
                                assistants = setting.assistants.map { assistant ->
                                    if (assistant.id == it.id) {
                                        it
                                    } else {
                                        assistant
                                    }
                                }
                            )
                        )
                    },
                    onUpdateSearchService = { index ->
                        vm.updateSettings(
                            setting.copy(
                                searchServiceSelected = index
                            )
                        )
                    },
                    onMoreClick = {
                        showFilesSheet = true
                    },
                    // #1906：待发引用预览。只在非编辑态显示——编辑历史消息时引用来自
                    // 该消息自身的注解（ChatService.editMessage 保留），不经过这里。
                    pendingQuote = inputState.pendingQuote.takeUnless { inputState.isEditing() },
                    onCancelQuote = { inputState.pendingQuote = null },
                )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            ChatList(
                innerPadding = innerPadding,
                conversation = conversation,
                state = chatListState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = previewMode,
                settings = setting,
                hazeState = hazeState,
                glassHazeState = glassHazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRegenerate = { message ->
                    // 有下游才需要选择/确认；无下游时两个 scope 等价，直接执行非破坏的 A
                    if (conversation.followingCount(message.id) > 0) {
                        regenerateScopeFor = message
                    } else {
                        doRegenerate(message, ChatService.RegenerateScope.THIS_ONLY)
                    }
                },
                onContinue = {
                    // 续写是「同一条消息往下写」，无下游语义问题（只作用于末条）
                    vm.continueMessage()
                },
                onEdit = {
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onQuoteMessage = { message ->
                    // 引用是「下一条新消息」的行为：进入引用即退出编辑态，
                    // 否则发送会走编辑分支、引用被丢掉。
                    inputState.editingMessage = null
                    inputState.pendingQuote = message.toQuotedMessage()
                },
                onForkMessage = {
                    scope.launch {
                        val fork = vm.forkMessage(message = it)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(it)
                    }
                },
                onUpdateMessage = { newNode ->
                    // 与 onDelete 同款守卫：生成中拒绝编辑。
                    // 为什么：编辑的落点会改写 messageNodes，而生成中每一块流式内容都在往同一个
                    // 节点上回写；两份写入互相覆盖（用户的编辑被下一块流冲掉，或反过来冲掉正文）。
                    // 判定抽成 [rejectMessageEditWhileGenerating]，可在 JVM 单测里证伪
                    //（见 `ChatMessageEditGuardTest`）。
                    val blocked = rejectMessageEditWhileGenerating(
                        loadingJob = loadingJob,
                        onReject = vm::showEditBlockedWhileGeneratingError,
                    )
                    if (!blocked) {
                        // 落点是**基于 current 的纯函数变换**（只换目标节点），
                        // 不再用回调闭包里的 `conversation` 快照整对象替换。
                        vm.updateMessageNode(newNode)
                        vm.saveConversationAsync()
                    }
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { index ->
                    previewMode = false
                    scope.launch {
                        chatListState.requestScrollToItem(index)
                    }
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onApproveAll = {
                    vm.handleApproveAllPending()
                },
                onEditReasoning = { messageId, reasoning, text ->
                    vm.updateMessageReasoning(messageId, reasoning.createdAt, text)
                },
                onToggleFavorite = { node ->
                    vm.toggleMessageFavorite(node)
                },
                onConversationSystemPromptChange = { newPrompt ->
                    vm.updateConversation(conversation.copy(customSystemPrompt = newPrompt))
                    vm.saveConversationAsync()
                },
            )
        }

        if (showContextSheet) {
            ChatContextCapacitySheet(
                vm = vm,
                assistantName = assistant.name,
                modelName = currentChatModel?.displayName.orEmpty(),
                contextUsage = contextUsage,
                onCompress = {
                    showContextSheet = false
                    showCompressDialog = true
                },
                onDismiss = { showContextSheet = false },
            )
        }

        // 压缩参数对话框：入口有两个（+ 菜单与容量面板），故渲染在页面级
        if (showCompressDialog) {
            CompressContextDialog(
                onDismiss = { showCompressDialog = false },
                onConfirm = { additionalPrompt, targetTokens, retainRatio ->
                    vm.handleCompressContext(additionalPrompt, targetTokens, retainRatio)
                },
                // 初值取当前设置（与 + 菜单里的入口同一约定）
                initialRetainPercent = setting.autoCompressRetainPercent,
                initialTargetTokens = setting.autoCompressTargetTokens,
                customPresets = setting.compressPresets,
            )
        }

        if (showFilesSheet) {
            ChatFilesPickerSheet(
                inputState = inputState,
                setting = setting,
                conversation = conversation,
                assistant = assistant,
                vm = vm,
                onRequestCompress = { showCompressDialog = true },
                onDismiss = { showFilesSheet = false },
            )
        }

        if (showExportSheet) {
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    // 单纯关闭 = **取消**：清掉待续作（用户可能反悔，不该触发破坏性动作）
                    showExportSheet = false
                    exportThenRegenerateFor = null
                },
                conversation = conversation,
                // 「先导出再移除」时只导**将被移除的下游**（那才是要留存的）；否则导当前全量
                selectedMessages = exportThenRegenerateFor
                    ?.let { conversation.followingMessages(it.id) }
                    ?: conversation.currentMessages,
                // 「先导出再继续」= 破坏性操作前的留存，故要求**真落地**（SAF 另存）。
                // 只有用户真正选定位置并写盘成功才续作 scope B；取消另存 = 不续作
                // （内容已在留存区，见 docs/design/design-export-retention.md §二 决策 1）。
                saveToDocument = exportThenRegenerateFor != null,
                onExported = {
                    exportThenRegenerateFor?.let { pending ->
                        exportThenRegenerateFor = null
                        doRegenerate(pending, ChatService.RegenerateScope.INCLUDE_FOLLOWING)
                    }
                },
            )
        }

        // 重生成 scope 选择（不按角色区分；仅 B 需确认，且提供「导出后继续」）
        regenerateScopeFor?.let { message ->
            RegenerateScopeDialog(
                followingCount = conversation.followingCount(message.id),
                onDismiss = { regenerateScopeFor = null },
                onConfirm = { chosen ->
                    regenerateScopeFor = null
                    doRegenerate(message, chosen)
                },
                onExportThenContinue = {
                    regenerateScopeFor = null
                    // 先打开导出（只含将被移除的下游）；导出成功后由 onExported 续作移除（取消则不续作）
                    exportThenRegenerateFor = message
                    showExportSheet = true
                },
            )
        }
    }
}

/**
 * 标题旁的上下文占用读数：状态点 + `已用/上限（余 X）`。
 *
 * 与输入栏指示器**分工**：这里给精确数字、那里只给状态（2026-10-02 决定）。
 * 颜色落在状态点、常态浅蓝，达共用的警戒阈值（[CONTEXT_USAGE_WARNING_RATIO]）才切红。
 * 读数不可省略（左侧助手/模型名可省略）。
 */
@Composable
private fun ContextUsageReadout(
    usedTokens: Int,
    windowTokens: Int,
) {
    val fraction = if (windowTokens > 0) (usedTokens.toFloat() / windowTokens).coerceIn(0f, 1f) else 0f
    val isWarning = windowTokens > 0 && fraction >= CONTEXT_USAGE_WARNING_RATIO
    val remaining = (windowTokens - usedTokens).coerceAtLeast(0)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (isWarning) MaterialTheme.colorScheme.error else ContextStatusDotNormal),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = stringResource(
                R.string.context_capacity_titlebar_readout,
                formatContextLength(usedTokens),
                formatContextLength(windowTokens),
                formatContextLength(remaining),
            ),
            maxLines = 1,
            color = if (isWarning) MaterialTheme.colorScheme.error else LocalContentColor.current.copy(0.65f),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
        )
    }
}

/**
 * 容量面板的外壳：**打开时只读组装一次**（跑一遍与真实请求同源的 transformer 链），
 * 再交给 [ContextCapacitySheet] 渲染。不落库、不发请求。
 */
@Composable
private fun ChatContextCapacitySheet(
    vm: ChatVM,
    assistantName: String,
    modelName: String,
    contextUsage: ContextUsage,
    onCompress: () -> Unit,
    onDismiss: () -> Unit,
) {
    val capacity by produceState<ContextCapacity?>(initialValue = null, vm) {
        val inspection = runCatching { vm.inspectPromptAssembly() }.getOrNull()
        value = inspection?.let {
            computeContextCapacity(
                usedTokens = contextUsage.usedTokens,
                windowTokens = contextUsage.windowTokens,
                segments = it.segments,
                sentHistoryCount = it.sentHistoryCount,
                totalMessageCount = it.totalMessageCount,
                localTotalTokens = it.totalTokens,
            )
        }
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss,
    ) {
        ContextCapacitySheet(
            assistantName = assistantName,
            modelName = modelName,
            capacity = capacity,
            onCompress = onCompress,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ChatFilesPickerSheet(
    inputState: ChatInputState,
    setting: Settings,
    conversation: Conversation,
    assistant: Assistant,
    vm: ChatVM,
    onRequestCompress: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    var showInjectionSheet by remember { mutableStateOf(false) }

    fun dismissAll() {
        showInjectionSheet = false
        onDismiss()
    }

    val cameraPermission = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = cameraPermission)

    var cameraOutputUri by remember { mutableStateOf<Uri?>(null) }
    var cameraOutputFile by remember { mutableStateOf<File?>(null) }
    val (_, launchCameraCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    )
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captureSuccessful ->
        if (captureSuccessful && cameraOutputUri != null) {
            if (setting.displaySetting.skipCropImage) {
                inputState.addImages(filesManager.createChatFilesByContents(listOf(cameraOutputUri!!)))
                cameraOutputFile?.delete()
                cameraOutputFile = null
                cameraOutputUri = null
                dismissAll()
            } else {
                launchCameraCrop(cameraOutputUri!!)
            }
        } else {
            cameraOutputFile?.delete()
            cameraOutputFile = null
            cameraOutputUri = null
        }
    }
    val onLaunchCamera: () -> Unit = {
        if (cameraPermission.allRequiredPermissionsGranted) {
            cameraOutputFile = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
            cameraOutputUri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", cameraOutputFile!!
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } else {
            cameraPermission.requestPermissions()
        }
    }

    var preCropTempFile by remember { mutableStateOf<File?>(null) }
    val (_, launchImageCrop) = useCropLauncher(
        onCroppedImageReady = { croppedUri ->
            inputState.addImages(filesManager.createChatFilesByContents(listOf(croppedUri)))
            dismissAll()
        },
        onCleanup = {
            preCropTempFile?.delete()
            preCropTempFile = null
        }
    )
    // GetMultipleContents 而非 PickMultipleVisualMedia：保留文件浏览入口
    // （系统 Photo Picker 只给相册视图、无目录浏览，照片多时翻找慢；上游 4b36640a 亦已 revert 回此形态）。
    // 视频/音频仍是 GetMultipleContents（PickVisualMedia 本就不覆盖音频）。
    val imagePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                Log.d("ImagePickButton", "Selected URIs: $selectedUris")
                if (setting.displaySetting.skipCropImage) {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                } else if (selectedUris.size == 1) {
                    val tempFile = File(context.appTempFolder, "pick_temp_${System.currentTimeMillis()}.jpg")
                    runCatching {
                        val source = selectedUris.first()
                        // HEIF/HEIC（尤其 HDR HEIF）交给 UCrop 前先解码转为 JPEG，规避裁剪解码失败
                        val converted = ImageUtils.isHeifImage(context, source) &&
                            ImageUtils.convertHeifToJpeg(context, source, tempFile)
                        if (!converted) {
                            context.contentResolver.openInputStream(source)?.use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        preCropTempFile = tempFile
                        launchImageCrop(tempFile.toUri())
                    }.onFailure {
                        // 拷贝失败时**不能**把原始 URI 交给 UCrop（上游 #1976：系统相册返回的
                        // media-store URI 在 Android 11 上可能无有效读授权，UCrop 的
                        // BitmapLoadTask 会直接抛 SecurityException 崩溃——异常发生在库内，
                        // 我们这边 catch 不到）。降级为「不裁剪、按原图添加」并明确告知，
                        // 与「跳过裁剪」开关的行为一致。
                        Log.e("ImagePickButton", "Failed to copy image to temp, adding without crop", it)
                        preCropTempFile?.delete()
                        preCropTempFile = null
                        inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                        toaster.show(
                            message = resources.getString(R.string.chat_image_crop_unavailable),
                            type = ToastType.Warning,
                        )
                        dismissAll()
                    }
                } else {
                    inputState.addImages(filesManager.createChatFilesByContents(selectedUris))
                    dismissAll()
                }
            } else {
                Log.d("ImagePickButton", "No images selected")
            }
        }

    val videoPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addVideos(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val audioPickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { selectedUris ->
            if (selectedUris.isNotEmpty()) {
                inputState.addAudios(filesManager.createChatFilesByContents(selectedUris))
                dismissAll()
            }
        }

    val filePickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                val documents = uris.mapNotNull { uri ->
                    val fileName = filesManager.getFileNameFromUri(uri) ?: "file"
                    val mime = filesManager.getFileMimeType(uri) ?: "text/plain"
                    if (isAllowedFileType(fileName, mime)) {
                        val localUri = filesManager.createChatFilesByContents(listOf(uri)).firstOrNull()
                            ?: run {
                                toaster.show(
                                    resources.getString(R.string.chat_input_file_read_failed, fileName),
                                    type = ToastType.Error
                                )
                                return@mapNotNull null
                            }
                        UIMessagePart.Document(url = localUri.toString(), fileName = fileName, mime = mime)
                    } else {
                        toaster.show(
                            resources.getString(R.string.chat_input_unsupported_file_type, fileName),
                            type = ToastType.Error
                        )
                        null
                    }
                }
                if (documents.isNotEmpty()) {
                    inputState.addFiles(documents)
                    dismissAll()
                }
            }
        }

    val filesSheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    ModalBottomSheet(
        sheetState = filesSheetState,
        onDismissRequest = { dismissAll() },
    ) {
        FilesPicker(
            conversation = conversation,
            state = inputState,
            assistant = assistant,
            mcpManager = vm.mcpManager,
            onRequestCompress = {
                // 压缩参数对话框由页面级渲染（容量面板的「压缩上下文」也要用它）
                onRequestCompress()
            },
            onUpdateAssistant = {
                vm.updateSettings(
                    setting.copy(
                        assistants = setting.assistants.map { assistant ->
                            if (assistant.id == it.id) {
                                it
                            } else {
                                assistant
                            }
                        }
                    )
                )
            },
            onUpdateConversation = {
                vm.updateConversation(it)
                vm.saveConversationAsync()
            },
            showInjectionSheet = showInjectionSheet,
            onShowInjectionSheetChange = { showInjectionSheet = it },
            onDismiss = { dismissAll() },
            onTakePic = onLaunchCamera,
            onPickImage = { imagePickerLauncher.launch("image/*") },
            onPickVideo = { videoPickerLauncher.launch("video/*") },
            onPickAudio = { audioPickerLauncher.launch("audio/*") },
            onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
        )
    }
}

@Composable
private fun glassHazeStyle(): HazeBlurStyle = HazeBlurStyle.Material3 {
    blurRadius(16.dp)
}

@Composable
private fun TopBar(
    settings: Settings,
    conversation: Conversation,
    contextUsage: ContextUsage,
    drawerOpen: Boolean,
    onOpenDrawer: () -> Unit,
    bigScreen: Boolean,
    previewMode: Boolean,
    hazeState: HazeState,
    glassHazeState: HazeState,
    onClickMenu: () -> Unit,
    onNewChat: () -> Unit,
    onExport: () -> Unit,
    onUpdateTitle: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val titleState = useEditState<String> {
        onUpdateTitle(it)
    }

    TopAppBar(
        modifier = Modifier.then(
            if (settings.displaySetting.enableTopBarBlur) Modifier.hazeBlur(
                input = HazeInput.Sources(glassHazeState),
                style = glassHazeStyle(),
            ) else Modifier
        ),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        navigationIcon = {
            if (!bigScreen) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(HugeIcons.Menu03, "Messages")
                }
            }
        },
        title = {
            val editTitleWarning = stringResource(R.string.chat_page_edit_title_warning)
            Surface(
                onClick = {
                    if (conversation.messageNodes.isNotEmpty()) {
                        titleState.open(conversation.title)
                    } else {
                        toaster.show(editTitleWarning, type = ToastType.Warning)
                    }
                },
                color = Color.Transparent,
            ) {
                Column {
                    val assistant = settings.getCurrentAssistant()
                    val model = settings.getCurrentChatModel()
                    val provider = model?.findProvider(providers = settings.providers, checkOverwrite = false)
                    Text(
                        text = conversation.title.ifBlank { stringResource(R.string.chat_page_new_chat) },
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model != null && provider != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }} / ${model.displayName} (${provider.name})",
                                overflow = TextOverflow.Ellipsis,
                                maxLines = 1,
                                // 助手/模型可省略，右侧占用读数是精确读数、不可省略
                                modifier = Modifier.weight(1f, fill = false),
                                color = LocalContentColor.current.copy(0.65f),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 8.sp,
                                )
                            )
                            ContextUsageReadout(
                                usedTokens = contextUsage.usedTokens,
                                windowTokens = contextUsage.windowTokens,
                            )
                        }
                    }
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onClickMenu()
                }
            ) {
                Icon(if (previewMode) HugeIcons.Cancel01 else HugeIcons.LeftToRightListBullet, "Chat Options")
            }

            IconButton(
                onClick = {
                    onExport()
                }
            ) {
                Icon(HugeIcons.Share01, "Export Conversation")
            }

            IconButton(
                onClick = {
                    onNewChat()
                }
            ) {
                Icon(HugeIcons.MessageAdd01, "New Message")
            }
        },
    )
    titleState.EditStateContent { title, onUpdate ->
        AlertDialog(
            onDismissRequest = {
                titleState.dismiss()
            },
            title = {
                Text(stringResource(R.string.chat_page_edit_title))
            },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        titleState.confirm()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        titleState.dismiss()
                    }
                ) {
                    Text(stringResource(R.string.chat_page_cancel))
                }
            }
        )
    }
}
