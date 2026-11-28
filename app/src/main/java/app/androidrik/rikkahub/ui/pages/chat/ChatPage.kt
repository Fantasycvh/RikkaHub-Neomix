package app.androidrik.rikkahub.ui.pages.chat

import app.androidrik.rikkahub.ui.components.message.PlanBar
import app.androidrik.rikkahub.data.ai.openai.resolveSubscriptionProviderLock
import app.androidrik.rikkahub.data.ai.tools.local.latestTodoPlan
import app.androidrik.rikkahub.ui.components.ai.CompressContextDialog
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import app.androidrik.rikkahub.utils.formatContextLength
import app.androidrik.rikkahub.data.ai.computeContextCapacity
import app.androidrik.rikkahub.utils.CONTEXT_USAGE_WARNING_RATIO
import app.androidrik.rikkahub.data.ai.ComposerCredentialDetector
import app.androidrik.rikkahub.data.ai.ContextCapacity
import app.androidrik.rikkahub.data.ai.compression.CompressionProgress
import app.androidrik.rikkahub.data.ai.compression.CompressionProgressStage
import app.androidrik.rikkahub.service.CompressionEvent
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import app.androidrik.rikkahub.ui.components.ui.AppTextButton as TextButton
import androidx.compose.material3.TopAppBar
import androidx.lifecycle.compose.LifecycleStartEffect
import app.androidrik.rikkahub.data.task.TaskVisibility
import app.androidrik.rikkahub.ui.pages.taskcenter.ConversationTasksSheet
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.ListTree
import com.composables.icons.lucide.Shrink
import com.composables.icons.lucide.X
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import dev.chrisbanes.haze.HazeState
import app.androidrik.rikkahub.ui.components.haze.hazeSourceCompat
import app.androidrik.rikkahub.ui.components.haze.liquidGlassChromeEffect
import app.androidrik.rikkahub.ui.components.haze.rememberRecoverableHazeState
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.ui.pages.share.handler.SharedFileInfo
import app.androidrik.rikkahub.ui.pages.share.handler.importSharedAttachments
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isEmptyInputMessage
import app.androidrik.ai.ui.toQuotedMessage
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
import app.androidrik.rikkahub.data.files.SkillManager
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.subtreeIdsOf
import app.androidrik.rikkahub.data.repository.WorkspaceRepository
import app.androidrik.rikkahub.service.ChatError
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.ui.pages.chat.VoiceSessionState
import app.androidrik.rikkahub.ui.pages.chat.rememberVoiceModeStarter
import app.androidrik.rikkahub.ui.components.ai.ChatInput
import app.androidrik.rikkahub.ui.components.ai.ContextUsage
import app.androidrik.rikkahub.ui.components.ai.FilesPicker
import app.androidrik.rikkahub.ui.components.ai.completion.BuiltinSlashCommand
import app.androidrik.rikkahub.ui.components.ai.completion.ChatCompletionProvider
import app.androidrik.rikkahub.ui.components.ai.completion.SendSlashParseResult
import app.androidrik.rikkahub.ui.components.ai.completion.SkillCompletionProvider
import app.androidrik.rikkahub.ui.components.ai.completion.SlashCommandAvailability
import app.androidrik.rikkahub.ui.components.ai.completion.SlashCommandCall
import app.androidrik.rikkahub.ui.components.ai.completion.SlashCommandCompletionProvider
import app.androidrik.rikkahub.ui.components.ai.completion.WorkspaceCompletionProvider
import app.androidrik.rikkahub.ui.components.ai.completion.parseSendTimeSlashInput
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
import app.androidrik.rikkahub.utils.base64Decode
import app.androidrik.rikkahub.ui.navigation.isAllowedFileType
import app.androidrik.rikkahub.Screen
import app.androidrik.rikkahub.ui.navigation.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import java.io.IOException
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

/**
 * 手动压缩后台化提示条（design-manual-compress-background §4.3）：常驻输入框上方，
 * 阶段文案三档 + 取消按钮，无进度条。只渲染**当前会话**的进度（调用方按会话 id 过滤），
 * 不参与消息列表滚动；无任务时不渲染。
 */
@Composable
private fun CompressBackgroundBar(
    progress: CompressionProgress?,
    onCancel: () -> Unit,
) {
    val current = progress ?: return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                Lucide.Shrink,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                // 通用前缀 + 三档阶段文案（§6）：解析上下文 → 生成摘要 → 写回检查点
                text = stringResource(R.string.chat_page_compress_background_running) +
                    " · " + compressBackgroundStageText(current),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCancel) {
                Icon(
                    Lucide.X,
                    contentDescription = stringResource(R.string.chat_page_compress_background_cancel),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** [CompressionProgressStage] → 三档文案映射（§4.3）：校验/兜底并入末档「写回检查点」 */
@Composable
private fun compressBackgroundStageText(progress: CompressionProgress): String = when (progress.stage) {
    CompressionProgressStage.PREPARING -> stringResource(R.string.chat_page_compress_stage_parsing)
    CompressionProgressStage.CHUNK, CompressionProgressStage.REFINING, CompressionProgressStage.SINGLE_PASS ->
        stringResource(R.string.chat_page_compress_stage_summarizing)
    CompressionProgressStage.VALIDATING, CompressionProgressStage.FALLBACK ->
        stringResource(R.string.chat_page_compress_stage_writing)
}

@Composable
fun ChatPage(
    id: Uuid,
    text: String?,
    files: List<Uri>,
    nodeId: Uuid? = null,
) {
    LifecycleStartEffect(id) {
        val lease = TaskVisibility.acquire("conversation:$id")
        onStopOrDispose { lease.close() }
    }
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

    // 手动压缩后台化（design-manual-compress-background §4.2）：事件（完成 toast / fork 结果 /
    // 失败兜底）在本页一次性消费；阶段进度在 ChatPageContent 内收集渲染提示条
    val compressFailureToaster = LocalToaster.current
    val compressFailureResources = LocalResources.current
    LaunchedEffect(vm) {
        vm.compressionEvents.collect { event ->
            when (event) {
                is CompressionEvent.SavedTokens -> compressFailureToaster.show(
                    message = compressFailureResources.getString(
                        R.string.chat_page_compress_background_done,
                        event.savings.savedTokens,
                        event.savings.beforeTokens,
                        event.savings.afterTokens,
                    ),
                    type = ToastType.Success,
                )

                is CompressionEvent.ForkReady ->
                    if (event.fromId == id) {
                        // 仍是源会话页：fork 目标替换当前页（返回仍回列表，不回到旧长会话）
                        navController.navigate(Screen.Chat(id = event.forkId.toString())) {
                            popUpToScreen = Screen.Chat(id = id.toString())
                            popUpToInclusive = true
                        }
                    } else {
                        // 已离开源会话：仅提示，新会话在会话列表可见
                        compressFailureToaster.show(
                            compressFailureResources.getString(R.string.chat_page_compress_background_fork_ready),
                            type = ToastType.Success,
                        )
                    }

                is CompressionEvent.ForkFailed -> compressFailureToaster.show(
                    compressFailureResources.getString(R.string.chat_page_compress_new_window_failed),
                    type = ToastType.Error,
                )

                is CompressionEvent.Failed ->
                    // 源会话页上错误卡已可见，不重复 toast；离开场景用 toast 兜底
                    if (event.conversationId != id) {
                        compressFailureToaster.show(
                            compressFailureResources.getString(R.string.chat_page_compress_background_failed),
                            type = ToastType.Error,
                        )
                    }
            }
        }
    }

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
        var trailingJob: Job? = null
        var latestConvo: Conversation? = null
        fun compute(convo: Conversation) {
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
        snapshotFlow { conversation }.collect { convo ->
            latestConvo = convo
            val now = System.currentTimeMillis()
            val since = now - lastComputedAt
            if (since >= 300) {
                trailingJob?.cancel()
                lastComputedAt = now
                compute(convo)
            } else if (trailingJob?.isActive != true) {
                // 尾沿补偿：节流窗口内的更新不丢弃（旧实现直接 return，
                // 流式收尾前最后一批 chunk 落在窗口内时读数永远停在旧值）
                trailingJob = launch {
                    delay(300 - since)
                    lastComputedAt = System.currentTimeMillis()
                    latestConvo?.let { compute(it) }
                }
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

    val shareContext = LocalContext.current
    val shareToaster = LocalToaster.current

    var sharedImportJob by remember { mutableStateOf<Job?>(null) }
    if (sharedImportJob != null) {
        AlertDialog(
            onDismissRequest = { sharedImportJob?.cancel() },
            text = { Text("Importing attachments…") },
            confirmButton = {
                TextButton(onClick = { sharedImportJob?.cancel() }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // 初始化输入状态（处理传入的 files 和 text 参数）
    LaunchedEffect(files, text) {
        if (vm.initializedShareText != text) {
            text?.base64Decode()?.takeIf { it.isNotEmpty() }?.let { inputState.setMessageText(it) }
            vm.initializedShareText = text
        }
        if (files.isNotEmpty() && vm.initializedShareFiles != files) {
            sharedImportJob = currentCoroutineContext().job
            try {
                val imported = withContext(Dispatchers.IO) {
                    importSharedAttachments(
                        sources = files.map { it.toString() },
                        describe = { source ->
                            val uri = source.toUri()
                            val name = filesManager.getFileNameFromUri(uri) ?: "file"
                            SharedFileInfo(
                                name = name,
                                mime = filesManager.getFileMimeType(uri)
                                    ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                                    ?: "application/octet-stream",
                            )
                        },
                        copy = { source ->
                            val saved = filesManager.saveManagedFromUri(
                                app.androidrik.rikkahub.data.files.FileFolders.UPLOAD, source.toUri(),
                            )
                            filesManager.getFile(saved).toUri().toString()
                        },
                    )
                }
                inputState.messageContent = inputState.messageContent + imported.parts
                vm.initializedShareFiles = files
                imported.failures.forEach { failure ->
                    shareToaster.show(
                        shareContext.getString(
                            if (failure.unsupported) R.string.chat_input_unsupported_file_type else R.string.chat_input_file_read_failed,
                            failure.name,
                        ),
                        type = ToastType.Error,
                    )
                }
            } finally {
                sharedImportJob = null
            }
        }
    }

    val chatListState = rememberLazyListState()
    val chatListScrollController = rememberSaveable(conversation.id, saver = ChatListScrollController.Saver) { ChatListScrollController() }
    LaunchedEffect(nodeId, conversation.messageNodes.size) {
        if (!vm.chatListInitialized && conversation.messageNodes.isNotEmpty()) {
            if (nodeId != null) {
                // 稀疏会话的目标可能在窗口外（搜索/引用直达）：先按 id 补齐，滚动请求挂起后消费。
                if (conversation.isSparse && conversation.messageNodes.none { it.id == nodeId }) {
                    vm.ensureNodeLoaded(nodeId)
                }
                chatListScrollController.scrollToMessage(nodeId)
            } else if (chatListScrollController.readAnchor == null) {
                chatListScrollController.scrollToBottom()
            }
            vm.chatListInitialized = true
        }
    }

    // 抽屉玻璃的采样 state（方向 C）：抽屉只采背景（垫底层），源注册在 ChatPageContent 的背景上
    val drawerGlassState = rememberRecoverableHazeState(userEnabled = setting.displaySetting.enablePageGlassTheme)
    when {
        isBigScreen -> {
            PermanentNavigationDrawer(
                drawerContent = {
                    ChatDrawerContent(
                        navController = navController,
                        current = conversation,
                        vm = vm,
                        settings = setting,
                        glassState = drawerGlassState
                    )
                }
            ) {
                ChatPageContent(
                    inputState = inputState,
                    loadingJob = loadingJob,
                    processingStatus = processingStatus,
                    setting = setting,
                    drawerGlassState = drawerGlassState,
                    conversation = conversation,
                    contextUsage = contextUsage,
                    drawerOpen = false,
                    onOpenDrawer = {},
                    navController = navController,
                    vm = vm,
                    chatListState = chatListState,
                    chatListScrollController = chatListScrollController,
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
                        settings = setting,
                        glassState = drawerGlassState
                    )
                },
                content = {
                    ChatPageContent(
                        inputState = inputState,
                        loadingJob = loadingJob,
                        processingStatus = processingStatus,
                        setting = setting,
                        drawerGlassState = drawerGlassState,
                        conversation = conversation,
                        contextUsage = contextUsage,
                        drawerOpen = drawerOpen,
                        onOpenDrawer = { drawerOpen = true },
                        navController = navController,
                        vm = vm,
                        chatListState = chatListState,
                        chatListScrollController = chatListScrollController,
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
    drawerGlassState: HazeState,
    conversation: Conversation,
    contextUsage: ContextUsage,
    drawerOpen: Boolean,
    onOpenDrawer: () -> Unit,
    navController: Navigator,
    vm: ChatVM,
    chatListState: LazyListState,
    chatListScrollController: ChatListScrollController,
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
    val skillManager: SkillManager = koinInject()
    val mediaFilesManager: FilesManager = koinInject()
    // 树面板的轻量树信息（稀疏会话）；全量会话为空列表，面板按内存节点渲染
    val treeNodes by vm.treeNodes.collectAsStateWithLifecycle()
    val switchingBranch by vm.switchingBranch.collectAsStateWithLifecycle()
    // 手动压缩阶段进度（design-manual-compress-background §4.2）：按当前会话 id 过滤渲染提示条
    val compressionStates by vm.compressionStates.collectAsStateWithLifecycle()
    // 树面板点选后保持打开、显示切换进度，切换完成再收起并定位
    var pendingTreeSwitchNodeId by remember { mutableStateOf<Uuid?>(null) }

    /**
     * 执行重生成。scope B = **非破坏分支**（树形分支 P1）：旧时间线保留、新回复开新子树，
     * 数据零丢失 ⇒ 无需撤销（撤销机制已随 P1 移除，见设计稿 §四.3）。
     */
    fun doRegenerate(message: UIMessage, regenerateScope: ChatService.RegenerateScope) {
        vm.regenerateAtMessage(message, regenerateScope)
        // 重新生成也要贴底跟随（与发送路径同语义）：贴底闩锁只在「拖动到底」或
        // 「Bottom 滚动请求」时置 true——用户拖动过（比如滚上来点重新生成按钮）闩锁
        // 就被清了，此前 regen 没有任何滚动请求 ⇒ 生成中内容增长视口永远停在原地。
        // scrollToBottomAfter 带 waitForChange：等最后一个消息 id 变化（新候选节点出现）
        // 才跳，不会跳到尚在显示的旧内容上。
        chatListScrollController.scrollToBottomAfter(conversation.currentMessages.lastOrNull()?.id)
    }
    var previewMode by rememberSaveable { mutableStateOf(false) }
    val hazeState = rememberRecoverableHazeState()
    val glassHazeState = rememberRecoverableHazeState()
    val assistant = conversation.effectiveAssistant(setting)
    /**
     * 订阅对话的提供商锁定：Codex 后端只认它自家生成的加密推理，换到其它 provider 后
     * 这些块既解不开也回传不了，所以这类对话里不允许切换提供商（同提供商换模型照旧）。
     *
     * 只看**对话历史**：模型选择是助手级的，按"当前选中的模型"判定会把该助手所有历史对话
     * 一起锁死（连切回去的路都没有）。
     */
    val subscriptionProviderLock = remember(setting.providers, conversation) {
        resolveSubscriptionProviderLock(
            providers = setting.providers,
            historyModelIds = conversation.currentMessages.mapNotNull { it.modelId },
        )
    }
    // UI-only projection: never persist this whole settings object back to the global store.
    val effectiveSettings = remember(setting, assistant, currentChatModel) {
        setting.copy(
            assistantId = assistant.id,
            assistants = setting.assistants.map { if (it.id == assistant.id) assistant else it },
            providers = setting.providers.map { provider ->
                currentChatModel?.let { provider.editModel(it) } ?: provider
            },
        )
    }
    var showFilesSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    // 会话树视图（树形分支 P2 §7.3）：顶栏菜单入口；剪枝确认待办挂在页面级
    var showTreeSheet by remember { mutableStateOf(false) }
    var treePruneConfirmFor by remember { mutableStateOf<MessageNode?>(null) }
    // 树面板点选 → 切换分支完成后才收起面板并定位到该节点
    LaunchedEffect(pendingTreeSwitchNodeId, switchingBranch) {
        val nodeId = pendingTreeSwitchNodeId ?: return@LaunchedEffect
        if (switchingBranch) return@LaunchedEffect
        pendingTreeSwitchNodeId = null
        showTreeSheet = false
        chatListScrollController.scrollToMessage(nodeId)
    }
    // 上下文容量面板 + 压缩参数对话框（压缩入口在面板里，参数界面与 + 菜单共用同一个）
    var showContextSheet by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    // 重生成 scope 选择：非空时显示 RegenerateScopeDialog（仅当有下游时才需要选/确认）
    var regenerateScopeFor by remember { mutableStateOf<UIMessage?>(null) }
    // 「导出后继续」：导出下游消息的 sheet；关闭后执行 scope B
    var exportThenRegenerateFor by remember { mutableStateOf<UIMessage?>(null) }
    // 剪枝确认：删除整节点 = 剪掉其子树（含全部下游，不可恢复），有下游时先确认（树形分支 P1）
    var deleteConfirmFor by remember { mutableStateOf<UIMessage?>(null) }
    // 编辑「保留后续」开关（§四.4）：开 = 候选追加（下游共享，默认）；关 = 纯分支（下游归旧版本）
    var editKeepFollowing by rememberSaveable { mutableStateOf(true) }
    val showEditKeepFollowing = inputState.isEditing() &&
        (inputState.editingMessage?.let { conversation.followingCount(it) } ?: 0) > 0

    val isEditing = inputState.isEditing()
    val lastActiveMessage = conversation.currentMessages.lastOrNull()
    val canContinueLastMessage = loadingJob == null &&
        lastActiveMessage?.role == MessageRole.ASSISTANT &&
        lastActiveMessage.parts.none { it is UIMessagePart.ServerTool }
    val slashAvailability = remember(
        conversation.currentMessages.size,
        canContinueLastMessage,
        loadingJob != null,
        compressionStates.containsKey(conversation.id),
        conversation.goal,
    ) {
        SlashCommandAvailability(
            hasMessages = conversation.currentMessages.isNotEmpty(),
            canContinue = canContinueLastMessage,
            isGenerating = loadingJob != null,
            isCompressing = compressionStates.containsKey(conversation.id),
            hasGoal = !conversation.goal.isNullOrBlank(),
        )
    }
    val completionProviders = remember(
        assistant.workspaceId, conversation.workspaceCwd, workspaceRepository,
        assistant.enabledSkills, skillManager, isEditing,
        slashAvailability, inputState.pendingSkills,
    ) {
        buildList<ChatCompletionProvider> {
            assistant.workspaceId?.let { workspaceId ->
                add(
                    WorkspaceCompletionProvider(
                        workspaceId = workspaceId.toString(),
                        repository = workspaceRepository,
                        currentCwd = conversation.workspaceCwd,
                    )
                )
            }
            if (!isEditing) {
                add(SlashCommandCompletionProvider(slashAvailability))
                add(
                    SkillCompletionProvider(
                        skillManager = skillManager,
                        enabledSkills = assistant.enabledSkills,
                        excludedSkills = inputState.pendingSkills.toSet(),
                    )
                )
            }
        }
    }

    var preparingSkill by remember(inputState) { mutableStateOf(false) }
    // 发送前凭据拦截（借鉴 Miffan 51c6b0f2）：命中高置信凭据时先弹确认框，保留输入框内容不丢失
    var pendingCredentialConfirm by remember {
        mutableStateOf<Pair<ComposerCredentialDetector.Warning, () -> Unit>?>(null)
    }
    fun guardCredentials(parts: List<UIMessagePart>, proceed: () -> Unit) {
        val warning = ComposerCredentialDetector.scanParts(parts, setting.providers, setting.assistants)
        if (warning.hasFindings) {
            pendingCredentialConfirm = warning to proceed
        } else {
            proceed()
        }
    }

    suspend fun dispatchWithSkills(
        baseParts: List<UIMessagePart>,
        skillNames: List<String>,
        quote: UIMessageAnnotation.QuotedMessage?,
        expectedText: String,
        expectedChipSkills: List<String>,
        requireModel: Boolean,
        send: (List<UIMessagePart>, List<UIMessageAnnotation>) -> Unit,
    ) {
        if (requireModel && currentChatModel == null) {
            toaster.show(resources.getString(R.string.media_creation_page_select_model_first), type = ToastType.Error)
            return
        }
        val annotations = listOfNotNull(quote)
        val distinctSkills = skillNames.distinct()
        if (distinctSkills.isEmpty()) {
            if (baseParts.isEmptyInputMessage()) return
            guardCredentials(baseParts) {
                send(baseParts, annotations)
                inputState.clearInput()
            }
            return
        }

        val loaded = withContext(Dispatchers.IO) {
            distinctSkills.map { skillName ->
                val body = try {
                    skillManager.readSkillBody(skillName)
                } catch (_: IOException) {
                    null
                }
                skillName to body
            }
        }
        if (inputState.textContent.text.toString() != expectedText ||
            inputState.pendingSkills != expectedChipSkills ||
            inputState.isEditing() ||
            inputState.pendingQuote != quote
        ) return

        val failed = loaded.firstOrNull { it.second.isNullOrBlank() }
        if (failed != null) {
            toaster.show(
                resources.getString(R.string.skills_page_read_failed_path, "/${failed.first}"),
                type = ToastType.Error,
            )
            return
        }

        val skillAnnotations = loaded.map { (name, body) ->
            UIMessageAnnotation.InvokedSkill(name, body!!)
        }
        val content = if (baseParts.isEmptyInputMessage()) {
            listOf(UIMessagePart.Text(distinctSkills.joinToString(" ") { "/$it" }))
        } else {
            baseParts
        }
        guardCredentials(content) {
            send(content, annotations + skillAnnotations)
            inputState.clearInput()
        }
    }

    fun executeSlashCommand(call: SlashCommandCall) {
        when (call.command) {
            BuiltinSlashCommand.NEW -> {
                inputState.clearInput()
                navigateToChatPage(navController)
            }

            BuiltinSlashCommand.COMPRESS -> {
                inputState.clearInput()
                if (call.argument.isBlank()) {
                    showCompressDialog = true
                } else {
                    vm.handleCompressContext(
                        additionalPrompt = call.argument,
                        targetTokens = setting.autoCompressTargetTokens,
                        retainRatio = setting.autoCompressRetainPercent / 100.0,
                        keepRecentMessages = 0,
                        continueInNewWindow = false,
                    )
                }
            }

            BuiltinSlashCommand.RETRY -> {
                val target = conversation.currentMessages.lastOrNull()
                if (loadingJob != null) {
                    toaster.show(resources.getString(R.string.chat_vm_stop_generation_before_edit), type = ToastType.Error)
                } else if (target == null) {
                    toaster.show("当前没有可重新生成的消息", type = ToastType.Info)
                } else {
                    inputState.clearInput()
                    if (conversation.followingCount(target.id) > 0) {
                        regenerateScopeFor = target
                    } else {
                        doRegenerate(target, ChatService.RegenerateScope.THIS_ONLY)
                    }
                }
            }

            BuiltinSlashCommand.CONTINUE -> {
                val target = conversation.currentMessages.lastOrNull()
                val canContinue = loadingJob == null &&
                    target?.role == MessageRole.ASSISTANT &&
                    target.parts.none { it is UIMessagePart.ServerTool }
                if (loadingJob != null) {
                    toaster.show(resources.getString(R.string.chat_vm_stop_generation_before_edit), type = ToastType.Error)
                } else if (!canContinue) {
                    toaster.show("当前末条消息不支持继续生成", type = ToastType.Info)
                } else {
                    inputState.clearInput()
                    vm.continueMessage()
                    chatListScrollController.scrollToBottom()
                }
            }

            BuiltinSlashCommand.FORK -> {
                val target = conversation.currentMessages.lastOrNull()
                if (target == null) {
                    toaster.show("当前没有可分叉的消息", type = ToastType.Info)
                } else {
                    inputState.clearInput()
                    scope.launch {
                        val fork = vm.forkMessage(message = target)
                        navigateToChatPage(navController, chatId = fork.id)
                    }
                }
            }

            BuiltinSlashCommand.STOP -> {
                inputState.clearInput()
                when {
                    loadingJob != null -> vm.stopGeneration()
                    compressionStates.containsKey(conversation.id) -> vm.cancelManualCompression()
                    else -> toaster.show("当前没有正在进行的生成或压缩", type = ToastType.Info)
                }
            }

            BuiltinSlashCommand.NOTE -> {
                val attachments = inputState.messageContent
                val chipSkills = inputState.pendingSkills
                if (call.argument.isBlank() && attachments.isEmpty() && chipSkills.isEmpty()) {
                    toaster.show("用法：/note <备忘内容>", type = ToastType.Info)
                    return
                }
                val noteParts = if (call.argument.isBlank()) {
                    attachments
                } else {
                    listOf(UIMessagePart.Text(call.argument)) + attachments
                }
                val expectedText = inputState.textContent.text.toString()
                val quote = inputState.pendingQuote
                if (preparingSkill) return
                preparingSkill = true
                scope.launch {
                    try {
                        dispatchWithSkills(
                            baseParts = noteParts,
                            skillNames = chipSkills,
                            quote = quote,
                            expectedText = expectedText,
                            expectedChipSkills = chipSkills,
                            requireModel = false,
                        ) { content, annotations ->
                            vm.handleMessageSend(content, answer = false, annotations = annotations)
                            chatListScrollController.scrollToBottomAfter(conversation.currentMessages.lastOrNull()?.id)
                        }
                    } finally {
                        preparingSkill = false
                    }
                }
            }

            BuiltinSlashCommand.TREE -> {
                inputState.clearInput()
                vm.prepareTreeView()
                showTreeSheet = true
            }

            BuiltinSlashCommand.EXPORT -> {
                inputState.clearInput()
                showExportSheet = true
            }

            BuiltinSlashCommand.INSPECT -> {
                inputState.clearInput()
                navController.navigate(Screen.PromptInspect(conversation.id.toString()))
            }

            BuiltinSlashCommand.GOAL -> {
                val arg = call.argument.trim()
                if (arg.isBlank()) {
                    val currentGoal = conversation.goal?.takeIf { it.isNotBlank() }
                    toaster.show(
                        message = if (currentGoal != null) {
                            "当前长期目标：$currentGoal（发送 /goal clear 清除）"
                        } else {
                            "用法：/goal <长期目标> 或 /goal clear"
                        },
                        type = ToastType.Info,
                    )
                } else if (arg.equals("clear", ignoreCase = true) || arg.equals("off", ignoreCase = true)) {
                    inputState.clearInput()
                    vm.updateConversationGoal(null)
                    toaster.show("已清除会话长期目标", type = ToastType.Success)
                } else {
                    inputState.clearInput()
                    vm.updateConversationGoal(arg)
                    toaster.show("已更新会话长期目标", type = ToastType.Success)
                }
            }
        }
    }

    fun submitMessage(
        requireModel: Boolean = true,
        send: (List<UIMessagePart>, List<UIMessageAnnotation>) -> Unit,
    ) {
        if (preparingSkill) return
        val rawText = inputState.textContent.text.toString()
        val attachments = inputState.messageContent
        val quote = inputState.pendingQuote
        val chipSkills = inputState.pendingSkills

        if (!rawText.trimStart().startsWith("/") && chipSkills.isEmpty()) {
            if (requireModel && currentChatModel == null) {
                toaster.show(resources.getString(R.string.media_creation_page_select_model_first), type = ToastType.Error)
                return
            }
            val parts = inputState.getContents()
            if (parts.isEmptyInputMessage()) return
            guardCredentials(parts) {
                send(parts, listOfNotNull(quote))
                inputState.clearInput()
            }
            return
        }

        preparingSkill = true
        scope.launch {
            try {
                if (!rawText.trimStart().startsWith("/")) {
                    dispatchWithSkills(
                        baseParts = inputState.getContents(),
                        skillNames = chipSkills,
                        quote = quote,
                        expectedText = rawText,
                        expectedChipSkills = chipSkills,
                        requireModel = requireModel,
                        send = send,
                    )
                    return@launch
                }

                val installedSkills = withContext(Dispatchers.IO) {
                    skillManager.listSkills().mapTo(HashSet()) { it.name }
                }
                if (inputState.textContent.text.toString() != rawText ||
                    inputState.pendingSkills != chipSkills ||
                    inputState.isEditing() ||
                    inputState.pendingQuote != quote
                ) return@launch

                when (val parsed = parseSendTimeSlashInput(
                    rawText = rawText,
                    allowBuiltinCommands = true,
                    skillExists = { it in installedSkills },
                )) {
                    is SendSlashParseResult.ClientCommand -> {
                        if (parsed.call.command == BuiltinSlashCommand.NOTE) {
                            val noteArg = parsed.call.argument
                            if (noteArg.isBlank() && attachments.isEmpty() && chipSkills.isEmpty()) {
                                toaster.show("用法：/note <备忘内容>", type = ToastType.Info)
                                return@launch
                            }
                            val noteParts = if (noteArg.isBlank()) {
                                attachments
                            } else {
                                listOf(UIMessagePart.Text(noteArg)) + attachments
                            }
                            dispatchWithSkills(
                                baseParts = noteParts,
                                skillNames = chipSkills,
                                quote = quote,
                                expectedText = rawText,
                                expectedChipSkills = chipSkills,
                                requireModel = false,
                            ) { content, annotations ->
                                vm.handleMessageSend(content, answer = false, annotations = annotations)
                                chatListScrollController.scrollToBottomAfter(conversation.currentMessages.lastOrNull()?.id)
                            }
                        } else {
                            executeSlashCommand(parsed.call)
                        }
                    }

                    is SendSlashParseResult.UnknownExplicitSkill -> {
                        toaster.show(
                            resources.getString(R.string.skills_page_read_failed_path, "/${parsed.skillName}"),
                            type = ToastType.Error,
                        )
                    }

                    is SendSlashParseResult.SkillChain -> {
                        val combinedSkills = (chipSkills + parsed.skillNames).distinct()
                        val effectiveParts = if (parsed.remainingText.isBlank()) {
                            attachments
                        } else {
                            listOf(UIMessagePart.Text(parsed.remainingText)) + attachments
                        }
                        dispatchWithSkills(
                            baseParts = effectiveParts,
                            skillNames = combinedSkills,
                            quote = quote,
                            expectedText = rawText,
                            expectedChipSkills = chipSkills,
                            requireModel = requireModel,
                            send = send,
                        )
                    }

                    SendSlashParseResult.Plain -> {
                        dispatchWithSkills(
                            baseParts = inputState.getContents(),
                            skillNames = chipSkills,
                            quote = quote,
                            expectedText = rawText,
                            expectedChipSkills = chipSkills,
                            requireModel = requireModel,
                            send = send,
                        )
                    }
                }
            } finally {
                preparingSkill = false
            }
        }
    }

    // 计划条（todo_write）：当前分支里最后一次 todo_write 调用的参数即当前清单，
    // 消息正文不再渲染计划卡（借鉴 Yuihub 最终形态），输入栏上方的 PlanBar 是唯一展示面
    val planTodos = remember(conversation) { latestTodoPlan(conversation) }

    TTSAutoPlay(vm = vm, setting = setting, conversation = conversation)

    // 顶栏胶囊档（上游 b40d7a273）：消息滚到标题栏下方才淡入胶囊背景，
    // 空页或列表在顶部时按钮直接浮在背景上；previewMode（截图预览）不显示
    val listScrolledUnderTopBar by remember(chatListState) {
        derivedStateOf { chatListState.canScrollBackward }
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        // 抽屉玻璃（方向 C）采样同一份背景：抽屉是垫底层，绝不采聊天内容
        AssistantBackground(
            setting = setting,
            modifier = Modifier.hazeSourceCompat(hazeState).hazeSourceCompat(glassHazeState)
                .then(if (setting.displaySetting.enablePageGlassTheme) Modifier.hazeSourceCompat(drawerGlassState) else Modifier))
        Scaffold(
            topBar = {
                TopBar(
                    settings = effectiveSettings,
                    conversation = conversation,
                    contextUsage = contextUsage,
                    drawerOpen = drawerOpen,
                    onOpenDrawer = onOpenDrawer,
                    bigScreen = bigScreen,
                    previewMode = previewMode,
                    hazeState = hazeState,
                    glassHazeState = glassHazeState,
                    showCapsuleBackground = !previewMode && listScrolledUnderTopBar,
                    onNewChat = {
                        navigateToChatPage(navController)
                    },
                    onClickMenu = {
                        previewMode = !previewMode
                    },
                    onShowTreeView = {
                        // 树面板：稀疏会话用轻量树信息（SQL 抽取角色/预览/候选数）渲染，
                        // 不再全量补齐正文；未加载节点的分叉/剪枝入口置灰（需正文）。
                        vm.prepareTreeView()
                        showTreeSheet = true
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
                // AI 反问/工具审批待答（design-ask-user-interrupt）：待答时生成已结束、
                // isGenerating=false，但用户同样需要打断出口、定位入口与队列滞留提示
                val (pendingInteractionCount, pendingInteractionNodeId) = remember(conversation) {
                    findPendingInteractionSummary(conversation)
                }
                val hasPendingInteraction = pendingInteractionCount > 0
                val statusDockModel = remember(
                    pendingInteractionCount,
                    pendingInteractionNodeId,
                    compressionStates[conversation.id],
                    planTodos,
                ) {
                    resolveChatStatusDock(
                        pendingInteractionCount = pendingInteractionCount,
                        pendingTargetNodeId = pendingInteractionNodeId,
                        compressionProgress = compressionStates[conversation.id],
                        todos = planTodos,
                    )
                }
                ChatStatusDock(
                    model = statusDockModel,
                    onJumpToPending = { targetNodeId ->
                        chatListScrollController.scrollToMessage(targetNodeId)
                    },
                    onCancelCompression = { vm.cancelManualCompression() },
                    renderCompressionBar = { progress, onCancel ->
                        CompressBackgroundBar(progress = progress, onCancel = onCancel)
                    },
                )
                ChatInput(
                    state = inputState,
                    loading = loadingJob != null,
                    hasPendingInteraction = hasPendingInteraction,
                    settings = effectiveSettings,
                    modelProviderLock = subscriptionProviderLock,
                    hazeState = hazeState,
                    completionProviders = completionProviders,
                    onExecuteSlashCommand = ::executeSlashCommand,
                    contextUsage = contextUsage,
                    onShowContextSheet = { showContextSheet = true },
                    onCancelClick = {
                        vm.stopGeneration()
                    },
                    enableSearch = enableWebSearch,
                    onToggleSearch = { enabled ->
                        vm.updateAssistantControls(assistant, assistant.copy(enableWebSearch = enabled))
                    },
                    onUpdateBuiltInSearch = if (conversation.upstreamConfig != null) {
                        { enabled -> vm.setBuiltInSearch(enabled); Unit }
                    } else null,
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
                        if (inputState.isEditing()) {
                            if (currentChatModel == null) {
                                toaster.show(resources.getString(R.string.media_creation_page_select_model_first), type = ToastType.Error)
                                return@ChatInput
                            }
                            val parts = inputState.getContents()
                            val editingId = inputState.editingMessage!!
                            guardCredentials(parts) {
                                vm.handleMessageEdit(
                                    parts = parts,
                                    messageId = editingId,
                                    keepFollowing = editKeepFollowing,
                                )
                                inputState.clearInput()
                            }
                        } else {
                            submitMessage(requireModel = true) { content, annotations ->
                                vm.handleMessageSend(content, annotations = annotations)
                                chatListScrollController.scrollToBottomAfter(conversation.currentMessages.lastOrNull()?.id)
                            }
                        }
                    },
                    onLongSendClick = {
                        if (inputState.isEditing()) {
                            val parts = inputState.getContents()
                            val editingId = inputState.editingMessage!!
                            guardCredentials(parts) {
                                vm.handleMessageEdit(
                                    parts = parts,
                                    messageId = editingId,
                                    keepFollowing = editKeepFollowing,
                                )
                                inputState.clearInput()
                            }
                        } else {
                            submitMessage(requireModel = false) { content, annotations ->
                                vm.handleMessageSend(content, answer = false, annotations = annotations)
                                chatListScrollController.scrollToBottomAfter(conversation.currentMessages.lastOrNull()?.id)
                            }
                        }
                    },
                    // 插队发送：打断当前生成、立刻发这条（半截落成可编辑候选、待审批工具默认拒绝）。
                    // AI 反问（ask_user）待答时同样可见，语义变为「跳过提问并发送」
                    // （design-ask-user-interrupt §3.2）——不放宽的话，用户在提问待答期间
                    // 既没有插队入口、普通发送又被 pending 闸静默滞留。
                    // 编辑态下不提供——编辑历史消息与「打断当前轮次」是两件事，混在一起语义不清。
                    onInterruptSendClick = {
                        submitMessage { content, annotations ->
                            vm.handleMessageSendInterrupting(
                                content = content,
                                annotations = annotations,
                                // 生成中 = 常规插队；仅 pending（提问/审批待答）= 跳过语义
                                skipQuestion = loadingJob == null && hasPendingInteraction,
                            )
                            chatListScrollController.scrollToBottomAfter(conversation.currentMessages.lastOrNull()?.id)
                        }
                    },
                    onRetainTasksSendClick = {
                        submitMessage { content, annotations ->
                            vm.handleMessageSendRetainingTasks(content, annotations)
                            chatListScrollController.scrollToBottomAfter(conversation.currentMessages.lastOrNull()?.id)
                        }
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
                        vm.setChatModel(assistant = assistant, model = it)
                    },
                    onUpdateAssistant = {
                        vm.updateAssistantControls(assistant, it)
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
                    // 编辑「保留后续」开关（树形分支 P1 §四.4）：仅编辑有下游的消息时显示
                    editKeepFollowing = editKeepFollowing,
                    onEditKeepFollowingChange = { editKeepFollowing = it },
                    showEditKeepFollowing = showEditKeepFollowing,
                )
                }
            },
            containerColor = Color.Transparent,
        ) { innerPadding ->
            Box {
            androidx.compose.runtime.CompositionLocalProvider(
                app.androidrik.rikkahub.ui.components.message.tools.LocalChatMediaReference provides { path, sourceRecordId ->
                    scope.launch {
                        if (inputState.isEditing()) {
                            toaster.show("请先完成当前消息编辑")
                            return@launch
                        }
                        val copied = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            mediaFilesManager.createChatFilesByContents(listOf(android.net.Uri.fromFile(java.io.File(path))))
                        }
                        if (copied.isEmpty()) {
                            toaster.show("无法读取参考图")
                        } else {
                            if (inputState.isEditing() || vm.conversation.value.id != conversation.id) {
                                toaster.show("请先完成当前消息编辑")
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    copied.forEach { uri -> java.io.File(requireNotNull(uri.path)).delete() }
                                }
                            } else {
                                inputState.addMediaReference(copied, sourceRecordId)
                                toaster.show("已添加参考图，输入修改要求后发送")
                            }
                        }
                    }
                },
            ) {
            ChatList(
                scrollController = chatListScrollController,
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
                onLoadEarlierNodes = { vm.loadEarlierMessages() },
                onHydrateNode = { vm.ensureNodeLoaded(it) },
                onUpdateCompressionSummary = { summaryId, content, onResult ->
                    vm.updateCompressionSummary(summaryId, content, onResult)
                },
                onDeleteCompressionSummary = { summaryId, onResult ->
                    vm.deleteCompressionSummary(summaryId, onResult)
                },
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
                    // 续写往**同一条消息**追加内容（id 不变），scrollToBottomAfter 的
                    // waitForChange（等 id 变化）永远等不到 ⇒ 用立即贴底请求重立闩锁
                    chatListScrollController.scrollToBottom()
                },
                onEdit = {
                    editKeepFollowing = true // 每次进入编辑默认「保留后续」（最小惊讶）
                    inputState.editingMessage = it.id
                    inputState.setContents(it.parts)
                },
                onEditSegment = { message, textPartIndex ->
                    // 多文本段消息（工具轮续跑形状）按段编辑：装的是被选中那段文本，
                    // 提交时 mergeEditedParts 只替换该段、原样保留 Tool/Reasoning。
                    editKeepFollowing = true
                    inputState.editingMessage = message.id
                    inputState.setContents(message.parts, textPartIndex)
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
                onForkBranch = {
                    // 树内分叉（§7.3）：改写 messageNodes，与编辑同款生成中守卫
                    if (rejectMessageEditWhileGenerating(
                            loadingJob = loadingJob,
                            onReject = vm::showEditBlockedWhileGeneratingError,
                        )
                    ) {
                        return@ChatList
                    }
                    vm.forkBranchAtMessage(it)
                },
                onDelete = {
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else if (conversation.followingCount(it.id) > 0) {
                        // 删整节点 = 剪枝子树（下游一并消失）：先确认
                        deleteConfirmFor = it
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
                onSwitchBranch = { nodeId ->
                    // 分支（时间线）切换：改 activeLeafNodeId，整条下游换新
                    if (loadingJob != null) {
                        vm.showEditBlockedWhileGeneratingError()
                    } else {
                        vm.switchMessageBranch(nodeId)
                    }
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onLongClickSuggestion = { suggestion ->
                    if (currentChatModel == null) {
                        toaster.show(
                            resources.getString(R.string.media_creation_page_select_model_first),
                            type = ToastType.Error,
                        )
                    } else if (suggestion.isNotBlank()) {
                        inputState.editingMessage = null
                        vm.handleMessageSend(listOf(UIMessagePart.Text(suggestion)))
                        chatListScrollController.scrollToBottomAfter(conversation.currentMessages.lastOrNull()?.id)
                    }
                },
                onTranslate = { message, locale ->
                    vm.translateMessage(message, locale)
                },
                onClearTranslation = { message ->
                    vm.clearTranslationField(message.id)
                },
                onJumpToMessage = { _ ->
                    // 只负责退出预览：滚动由 ChatListNormal 的 quoteJump 管线解析
                    // （按节点定位，含检查点行偏移与「目标在折叠历史里先展开」）
                    previewMode = false
                },
                onToolApproval = { toolCallId, approved, reason ->
                    vm.handleToolApproval(toolCallId, approved, reason)
                },
                onToolAnswer = { toolCallId, answer ->
                    vm.handleToolAnswer(toolCallId, answer)
                },
                onToolSkip = { toolCallId ->
                    vm.handleToolSkip(toolCallId)
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
                onConversationGoalChange = { newGoal ->
                    // 强制落库：新聊天上写目标必须真实持久化，创建页的 withGoal 开关按 DB 的 goal 判断
                    vm.updateConversationGoal(newGoal)
                },
            )
            }
            // 隐形会话（design-proactive-task-management.md §二.3）：任务工作台的唯一 UI 开关。
            // 覆盖在列表顶部（不挤占 ChatList 的 padding）；「移出隐藏」= 收养进列表。
            if (conversation.hidden) {
                HiddenSessionBanner(
                    onUnhide = {
                        vm.setHidden(conversation, false)
                        toaster.show(resources.getString(R.string.chat_hidden_adopted_toast))
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            }
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
                onConfirm = { additionalPrompt, targetTokens, retainRatio, keepRecentMessages, continueInNewWindow ->
                    vm.handleCompressContext(
                        additionalPrompt = additionalPrompt,
                        targetTokens = targetTokens,
                        retainRatio = retainRatio,
                        keepRecentMessages = keepRecentMessages,
                        continueInNewWindow = continueInNewWindow,
                    )
                },
                // 阶段 2 范围预览（#2294 R1）：只读切分，与执行同一段代码同窗口口径
                onPreview = { retainRatio, keepRecentMessages ->
                    vm.previewCompression(
                        retainRatio = retainRatio,
                        keepRecentMessages = keepRecentMessages,
                    )
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
                onCreateScheduledTask = {
                    // 新聊天还没落库：先保存再跳，否则创建页按 id 查不到会话、目标预选落空
                    scope.launch {
                        vm.saveConversationNow()
                        navController.navigate(
                            Screen.SettingScheduledTaskCreate(conversationId = conversation.id.toString())
                        )
                    }
                },
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
                // 本会话导出显示覆盖：写回会话（null = 恢复跟随全局）。只影响本会话的导出渲染。
                onUpdateExportDisplayConfig = { config ->
                    vm.setExportDisplayConfig(conversation, config)
                },
                // 按消息覆盖（多选）：整张 map 替换，空 map = 全部清除
                onUpdateExportDisplayOverrides = { overrides ->
                    vm.setExportDisplayOverrides(conversation, overrides)
                },
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

        // 剪枝确认：删除整节点会连子树（全部下游）一起移除且不可恢复
        deleteConfirmFor?.let { message ->
            AlertDialog(
                onDismissRequest = { deleteConfirmFor = null },
                title = { Text(stringResource(R.string.chat_page_delete)) },
                text = {
                    Text(
                        stringResource(
                            R.string.chat_delete_branch_confirm,
                            conversation.followingCount(message.id),
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteConfirmFor = null
                        vm.deleteMessage(message)
                    }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteConfirmFor = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        // 会话树视图（树形分支 P2 §7.3）：缩进树形列表，切换/分叉/剪枝
        if (showTreeSheet) {
            ConversationTreeSheet(
                conversation = conversation,
                sparseTree = treeNodes,
                switching = switchingBranch,
                onDismissRequest = { showTreeSheet = false },
                onSwitchToNode = { nodeId ->
                    if (loadingJob != null) {
                        vm.showEditBlockedWhileGeneratingError()
                    } else {
                        // 面板先留在原地显示切换进度，完成后由上面的 LaunchedEffect 收起并定位
                        pendingTreeSwitchNodeId = nodeId
                        vm.switchMessageBranch(nodeId)
                    }
                },
                onForkBranchAt = { message ->
                    showTreeSheet = false
                    if (loadingJob == null) {
                        vm.forkBranchAtMessage(message)
                    } else {
                        vm.showEditBlockedWhileGeneratingError()
                    }
                },
                onDeleteBranch = { node ->
                    // 剪枝是破坏性操作：关树视图、页面级确认弹窗（计数 = 子树内全部消息）
                    showTreeSheet = false
                    treePruneConfirmFor = node
                },
            )
        }

        // 树视图剪枝确认：节点 + 全部后代（含候选）一并移除且不可恢复
        treePruneConfirmFor?.let { node ->
            val pruneCount = conversation.messageNodes
                .subtreeIdsOf(node.id)
                .sumOf { id -> conversation.messageNodes.first { it.id == id }.messages.size }
            AlertDialog(
                onDismissRequest = { treePruneConfirmFor = null },
                title = { Text(stringResource(R.string.chat_page_delete)) },
                text = {
                    Text(stringResource(R.string.chat_delete_branch_confirm, pruneCount))
                },
                confirmButton = {
                    TextButton(onClick = {
                        treePruneConfirmFor = null
                        vm.pruneNodeSubtree(node)
                    }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { treePruneConfirmFor = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

        pendingCredentialConfirm?.let { (warning, proceed) ->
            val kindLabels = warning.kinds.map { kind ->
                stringResource(
                    when (kind) {
                        ComposerCredentialDetector.Kind.PRIVATE_KEY -> R.string.chat_credential_kind_private_key
                        ComposerCredentialDetector.Kind.REGISTERED_CREDENTIAL -> R.string.chat_credential_kind_registered
                        ComposerCredentialDetector.Kind.API_KEY -> R.string.chat_credential_kind_api_key
                        ComposerCredentialDetector.Kind.TOKEN -> R.string.chat_credential_kind_token
                        ComposerCredentialDetector.Kind.BEARER_TOKEN -> R.string.chat_credential_kind_bearer
                    }
                )
            }.joinToString(", ")
            AlertDialog(
                onDismissRequest = { pendingCredentialConfirm = null },
                title = { Text(stringResource(R.string.chat_credential_warning_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.chat_credential_warning_desc,
                            warning.totalCount,
                            kindLabels,
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingCredentialConfirm = null
                        proceed()
                    }) {
                        Text(
                            stringResource(R.string.chat_credential_warning_send_anyway),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCredentialConfirm = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}
