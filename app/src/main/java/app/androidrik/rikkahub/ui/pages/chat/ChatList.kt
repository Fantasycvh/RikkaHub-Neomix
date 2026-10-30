package app.androidrik.rikkahub.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import app.androidrik.rikkahub.ui.components.ui.HeightClamp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.getAssistantById
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.service.ChatError
import app.androidrik.rikkahub.ui.components.message.ChatMessage
import app.androidrik.rikkahub.ui.components.message.CompactionCheckpointRow
import app.androidrik.rikkahub.ui.components.ui.ErrorCardsDisplay
import app.androidrik.rikkahub.ui.components.ui.ListSelectableItem
import app.androidrik.rikkahub.ui.components.ui.LocalConversationImageUrls
import app.androidrik.rikkahub.ui.components.ui.RabbitLoadingIndicator
import app.androidrik.rikkahub.ui.components.ui.ScrollJumperBar
import app.androidrik.rikkahub.ui.components.ui.Tooltip
import app.androidrik.rikkahub.ui.hooks.ImeLazyListAutoScroller
import app.androidrik.rikkahub.ui.theme.ChatFontProvider
import app.androidrik.rikkahub.utils.collectConversationImageUrls
import app.androidrik.rikkahub.utils.normalizeLocalFileUrl
import app.androidrik.rikkahub.utils.plus
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
@Composable
private fun glassHazeStyle(): HazeBlurStyle = HazeBlurStyle.Material3 {
    blurRadius(16.dp)
}

private const val ScrollBottomKey = "ScrollBottomKey"

/**
 * #1906-B：引用块跳转——目标消息在**当前渲染列表**中的下标（已含压缩检查点行的偏移）。
 *
 * 两处容易算错的地方，抽出来单测：
 * - 列表可能是压缩折叠后的 `windowNodes()`（只有边界之后的尾巴），所以按 **messageId** 查找，
 *   不能用「完整节点列表下标」（两者下标空间不一致）；
 * - 折叠与展开两种状态下，检查点行都恒为列表第一项 ⇒ 下标要 +1。
 *
 * @return 目标所在节点下标；**不在当前可见列表**（在折叠的历史里、在别的分支、或已被删除）
 *   返回 -1，由调用方决定「先展开再说」还是放弃。
 */
internal fun List<MessageNode>.quoteJumpTargetIndex(messageId: Uuid, hasCheckpointRow: Boolean): Int {
    val index = indexOfFirst { node -> node.messages.any { it.id == messageId } }
    if (index < 0) return -1
    return index + if (hasCheckpointRow) 1 else 0
}

@Composable
fun ChatList(
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    previewMode: Boolean,
    settings: Settings,
    hazeState: HazeState,
    glassHazeState: HazeState,
    errors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    /** 手动「继续生成」：把被截断的末条消息接着写下去（本批：裸断流/撞上限时可用） */
    onContinue: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    /** 多文本段消息按段编辑；null = 动作面板只给整体编辑入口 */
    onEditSegment: ((UIMessage, Int) -> Unit)? = null,
    onQuoteMessage: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)? = null,
    onClearTranslation: (UIMessage) -> Unit = {},
    /** 预览搜索跳转：参数为目标节点（外层借此退出预览模式；滚动由列表内部解析） */
    onJumpToMessage: (MessageNode) -> Unit = {},
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToolSkip: ((toolCallId: String) -> Unit)? = null,
    onApproveAll: (() -> Unit)? = null,
    onEditReasoning: ((messageId: Uuid, reasoning: UIMessagePart.Reasoning, text: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    /** 会话长期目标（Conversation.goal）编辑回调；null = 不显示入口 */
    onConversationGoalChange: ((String?) -> Unit)? = null,
    /** 「为此会话创建定时任务」入口；null = 不显示 */
    onCreateScheduledTask: (() -> Unit)? = null,
) {
    // 「点击消息里的图片 ⇒ 全屏后在**本会话**的图片间左右切换」：
    // 候选集合在这里算一份（当前分支的顶层 Image part），经 CompositionLocal 下发给
    // 消息里的图片渲染点。放在 ChatList 而不是 ChatPage：两种模式（普通/预览）都从这里过，
    // 且不需要把列表逐层透传到深层的消息组件里。
    val context = LocalContext.current
    val conversationImageUrls = remember(conversation.currentMessages) {
        collectConversationImageUrls(conversation.currentMessages)
            .map { context.normalizeLocalFileUrl(it) }
    }
    CompositionLocalProvider(LocalConversationImageUrls provides conversationImageUrls) {
        // 预览搜索跳转：目标以**节点**表达（不是完整列表下标——压缩折叠态的下标空间不同），
        // 交给 ChatListNormal 的 quoteJump 机制解析（含「目标在折叠历史里先展开」）
        var pendingPreviewJump by remember(conversation.id) { mutableStateOf<Uuid?>(null) }
        AnimatedContent(
            targetState = previewMode,
            label = "ChatListMode",
            transitionSpec = {
                (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
            }
        ) { target ->
            if (target) {
                ChatListPreview(
                    innerPadding = innerPadding,
                    conversation = conversation,
                    settings = settings,
                    hazeState = hazeState,
                    onJumpToMessage = { node ->
                        pendingPreviewJump = node.id
                        onJumpToMessage(node)
                    },
                    animatedVisibilityScope = this@AnimatedContent,
                )
            } else {
                ChatListNormal(
                    pendingPreviewJump = pendingPreviewJump,
                    onPreviewJumpConsumed = { pendingPreviewJump = null },
                    innerPadding = innerPadding,
                    conversation = conversation,
                    state = state,
                    loading = loading,
                    processingStatus = processingStatus,
                    settings = settings,
                    hazeState = hazeState,
                    glassHazeState = glassHazeState,
                    errors = errors,
                    onDismissError = onDismissError,
                    onClearAllErrors = onClearAllErrors,
                    onRegenerate = onRegenerate,
                    onContinue = onContinue,
                    onEdit = onEdit,
                    onEditSegment = onEditSegment,
                    onQuoteMessage = onQuoteMessage,
                    onForkMessage = onForkMessage,
                    onDelete = onDelete,
                    onUpdateMessage = onUpdateMessage,
                    onClickSuggestion = onClickSuggestion,
                    onTranslate = onTranslate,
                    onClearTranslation = onClearTranslation,
                    animatedVisibilityScope = this@AnimatedContent,
                    onToolApproval = onToolApproval,
                    onToolAnswer = onToolAnswer,
                    onToolSkip = onToolSkip,
                    onApproveAll = onApproveAll,
                    onEditReasoning = onEditReasoning,
                    onToggleFavorite = onToggleFavorite,
                    onConversationSystemPromptChange = onConversationSystemPromptChange,
                    onConversationGoalChange = onConversationGoalChange,
                    onCreateScheduledTask = onCreateScheduledTask,
                )
            }
        }
    }
}

@Composable
private fun ChatListNormal(
    /** 预览搜索跳转的待办目标（节点 id）；由外层（ChatList）写入、消费后回调清空 */
    pendingPreviewJump: Uuid? = null,
    onPreviewJumpConsumed: () -> Unit = {},
    innerPadding: PaddingValues,
    conversation: Conversation,
    state: LazyListState,
    loading: Boolean,
    processingStatus: String? = null,
    settings: Settings,
    hazeState: HazeState,
    glassHazeState: HazeState,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onContinue: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onEditSegment: ((UIMessage, Int) -> Unit)? = null,
    onQuoteMessage: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onTranslate: ((UIMessage, java.util.Locale) -> Unit)?,
    onClearTranslation: (UIMessage) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onToolSkip: ((toolCallId: String) -> Unit)? = null,
    onApproveAll: (() -> Unit)? = null,
    onEditReasoning: ((messageId: Uuid, reasoning: UIMessagePart.Reasoning, text: String) -> Unit)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
    /** 会话长期目标（Conversation.goal）编辑回调；null = 不显示入口 */
    onConversationGoalChange: ((String?) -> Unit)? = null,
    /** 「为此会话创建定时任务」入口；null = 不显示 */
    onCreateScheduledTask: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val loadingState by rememberUpdatedState(loading)
    var isRecentScroll by remember { mutableStateOf(false) }
    val conversationUpdated by rememberUpdatedState(conversation)
    val density = LocalDensity.current
    val activity = LocalActivity.current as? app.androidrik.rikkahub.RouteActivity

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                scope.launch { state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount) }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    fun List<LazyListItemInfo>.isAtBottom(): Boolean {
        val lastItem = lastOrNull() ?: return false
        val inputBarHeight = with(density) { innerPadding.calculateBottomPadding().toPx() }
        val lastPos = lastItem.offset + lastItem.size
        val inputPos = (state.layoutInfo.viewportEndOffset - inputBarHeight.roundToInt())
        // println("lastPos = $lastPos, inputPos = $inputPos  | ${lastPos <= inputPos - 8}")
        return lastPos <= inputPos - 8
    }

    // 聊天选择
    val selectedItems = remember { mutableStateListOf<Uuid>() }
    var selecting by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }

    // 自动跟随键盘滚动
    ImeLazyListAutoScroller(lazyListState = state)

    // 对话大小警告对话框
    val sizeInfo = rememberConversationSizeInfo(conversation)
    var showSizeWarningDialog by rememberSaveable(conversation.id) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    val assistant = remember(settings.assistants, conversation.assistantId) {
        settings.getAssistantById(conversation.assistantId)
    }
    val modelById = remember(settings.providers) {
        settings.providers
            .flatMap { it.models }
            .associateBy { it.id }
    }
    // 压缩检查点（design §4.1 界面侧三边界对齐）：
    // 收起 = 检查点之后的窗口（= AI 实际看到的内容）；展开 = 完整轨迹。
    // 展开态直接复用同一渲染块 -> 历史消息的编辑/重新生成/删除/分支/收藏等操作全部可用。
    val activeCheckpoint = conversation.displayCompression()
    var showCompressedHistory by remember(conversation.id) { mutableStateOf(false) }
    val visibleNodes = if (activeCheckpoint != null && !showCompressedHistory) {
        conversation.windowNodes()
    } else {
        conversation.messageNodes
    }
    val lastMessageIndex = visibleNodes.lastIndex

    // #1906-B：引用块点击 → 跳到被引用的原消息。
    //
    // 不能直接复用 onJumpToMessage(index)：那个 index 是**完整节点列表**下标，而这里渲染的
    // 可能是压缩折叠后的 `windowNodes()`（只有边界之后的尾巴），两者下标空间不一致；折叠时
    // 列表顶部还多一个检查点行。所以按 **messageId** 定位，并处理两种边界：
    //  1. 目标在折叠的历史里（很常见——引用往往指向更早的消息）⇒ 先展开完整轨迹，
    //     由 visibleNodes 变化触发本 effect 重跑，再滚动；
    //  2. 目标在**未选中的候选分支**里 ⇒ 只滚动到所在节点（显示的是当前选中分支）。
    //     不自动切换 selectIndex：那会静默改变会话展示分支并影响后续发送，代价远大于收益。
    // 完全找不到（消息已被删除）时放弃，不报错。
    var pendingQuoteJump by remember(conversation.id) { mutableStateOf<Uuid?>(null) }
    // 预览搜索跳转并入同一条 quoteJump 管线（含检查点行偏移与「折叠历史先展开」）
    LaunchedEffect(pendingPreviewJump) {
        val target = pendingPreviewJump ?: return@LaunchedEffect
        onPreviewJumpConsumed()
        pendingQuoteJump = target
    }
    LaunchedEffect(pendingQuoteJump, visibleNodes, activeCheckpoint) {
        val targetId = pendingQuoteJump ?: return@LaunchedEffect
        val index = visibleNodes.quoteJumpTargetIndex(
            messageId = targetId,
            hasCheckpointRow = activeCheckpoint != null,
        )
        if (index < 0) {
            // 折叠态找不到 ⇒ 目标很可能在折叠的历史里，先展开完整轨迹再重试
            if (activeCheckpoint != null && !showCompressedHistory) {
                showCompressedHistory = true
                return@LaunchedEffect
            }
            pendingQuoteJump = null
            return@LaunchedEffect
        }
        state.animateScrollToItem(index)
        pendingQuoteJump = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        // 自动滚动到底部
        if (settings.displaySetting.enableAutoScroll) {
            // 贴底闩锁（#1662）：生成中内容增长会把视口「顶离」底部——布局增长瞬间
            // isAtBottom() 判 false ⇒ 老逻辑错失贴底请求，此后每帧检查依然 false，
            // 视口永远停在中间。闩锁只在**用户拖动**时更新（拖动中贴底=true、离开=false），
            // 非拖动帧只要闩锁在就持续请求贴底；用户上滑 = 明确放手，不再跟随。
            // requestScrollToItem 是瞬时重定位，不会置 isScrollInProgress，无自锁风险。
            var stickToBottom by remember { mutableStateOf(true) }
            LaunchedEffect(state) {
                snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
                    if (state.isScrollInProgress) {
                        stickToBottom = visibleItemsInfo.isAtBottom()
                    } else if (loadingState && stickToBottom) {
                        state.requestScrollToItem(conversationUpdated.messageNodes.lastIndex + 10)
                        // Log.i(TAG, "ChatList: scroll to ${conversationUpdated.messageNodes.lastIndex}")
                    }
                }
            }
        }

        // 判断最近是否滚动
        LaunchedEffect(state.isScrollInProgress) {
            if (state.isScrollInProgress) {
                isRecentScroll = true
                delay(1500)
                isRecentScroll = false
            } else {
                delay(1500)
                isRecentScroll = false
            }
        }

        ChatFontProvider(displaySetting = settings.displaySetting) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
            ) {
            // 液态玻璃层：固定尺寸兄弟节点，blur 只消费背景 source（glassHazeState）
            if (settings.displaySetting.enableGlassTheme) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeBlur(
                            input = HazeInput.Sources(glassHazeState),
                            style = glassHazeStyle(),
                        )
                )
            }
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(state = hazeState),
            ) {
            // 压缩检查点折叠行：展开/收起切换 visibleNodes（历史消息仍是完整可操作的行）
            if (activeCheckpoint != null) {
                item(key = "CompactionCheckpoint") {
                    CompactionCheckpointRow(
                        messageCount = activeCheckpoint.messageCount,
                        summaryPreview = activeCheckpoint.content.lineSequence().firstOrNull().orEmpty(),
                        expanded = showCompressedHistory,
                        onToggle = { showCompressedHistory = !showCompressedHistory },
                    )
                }
            }

            itemsIndexed(
                items = visibleNodes,
                key = { index, item -> item.id },
            ) { index, node ->
                // 哨兵只兜异常高度（防 Int.MAX 崩溃），正常长内容不再被裁剪
                HeightClamp(normalLimit = 100_000.dp) {
                Column {
                    ListSelectableItem(
                        key = node.id,
                        onSelectChange = {
                            if (!selectedItems.contains(node.id)) {
                                selectedItems.add(node.id)
                            } else {
                                selectedItems.remove(node.id)
                            }
                        },
                        selectedKeys = selectedItems,
                        enabled = selecting,
                    ) {
                        if (node.currentMessage.isContextCheckpoint) {
                            var showCheckpointBody by remember(node.currentMessage.id) { mutableStateOf(false) }
                            Column {
                                CompactionCheckpointRow(
                                    messageCount = conversation.messageNodes.takeWhile { it.id != node.id }
                                        .count { !it.currentMessage.isContextCheckpoint },
                                    summaryPreview = node.currentMessage.toText().lineSequence().firstOrNull().orEmpty(),
                                    expanded = showCheckpointBody,
                                    onToggle = { showCheckpointBody = !showCheckpointBody },
                                )
                                if (showCheckpointBody) {
                                    Text(
                                        text = node.currentMessage.toText(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else ChatMessage(
                            node = node,
                            model = node.currentMessage.modelId?.let(modelById::get),
                            assistant = assistant,
                            loading = loading && index == lastMessageIndex,
                            onRegenerate = {
                                onRegenerate(node.currentMessage)
                            },
                            onContinue = {
                                onContinue(node.currentMessage)
                            },
                            onEdit = {
                                onEdit(node.currentMessage)
                            },
                            onEditSegment = onEditSegment,
                            onQuote = {
                                onQuoteMessage(node.currentMessage)
                            },
                            onQuoteClick = { quotedMessageId ->
                                pendingQuoteJump = quotedMessageId
                            },
                            onFork = {
                                onForkMessage(node.currentMessage)
                            },
                            onDelete = {
                                onDelete(node.currentMessage)
                            },
                            onShare = {
                                selecting = true  // 使用 CoroutineScope 延迟状态更新
                                selectedItems.clear()
                                selectedItems.addAll(conversation.messageNodes.map { it.id }
                                    .subList(0, conversation.messageNodes.indexOf(node) + 1))
                            },
                            onUpdate = {
                                onUpdateMessage(it)
                            },
                            isFavorite = node.isFavorite,
                            onToggleFavorite = {
                                onToggleFavorite?.invoke(node)
                            },
                            onTranslate = onTranslate,
                            onClearTranslation = onClearTranslation,
                            onToolApproval = onToolApproval,
                            onToolAnswer = onToolAnswer,
                            onApproveAll = onApproveAll,
                            onEditReasoning = onEditReasoning,
                            lastMessage = index == lastMessageIndex,
                        )
                    }
                }
                }
                }

            if (!loading && assistant?.allowConversationSystemPrompt == true && onConversationSystemPromptChange != null) {
                item(key = "ConversationSystemPrompt") {
                    ConversationSystemPromptButton(
                        customSystemPrompt = conversation.customSystemPrompt,
                        onSystemPromptChange = onConversationSystemPromptChange,
                    )
                }
            }

            // 会话目标编辑入口（定时任务 P2）：目标是会话的属性，写在会话上而非任务上
            if (!loading && onConversationGoalChange != null) {
                item(key = "ConversationGoal") {
                    ConversationGoalButton(
                        goal = conversation.goal,
                        onGoalChange = onConversationGoalChange,
                    )
                }
            }
            // 为本会话创建定时任务：跳创建页，目标已预选
            if (!loading && onCreateScheduledTask != null) {
                item(key = "CreateScheduledTask") {
                    CreateScheduledTaskButton(onClick = onCreateScheduledTask)
                }
            }

            if (loading) {
                item(LoadingIndicatorKey) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(28.dp)
                        )
                        AnimatedVisibility(
                            visible = processingStatus != null,
                        ) {
                            Text(
                                text = processingStatus ?: "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // 为了能正确滚动到这
            item(ScrollBottomKey) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                )
            }
            }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 错误消息卡片。
            // 按当前会话过滤：errors 是 ChatService 的全局流，后台会话（切走后仍在生成的）
            // 报错时不该落在当前浏览的会话底部；conversationId 为 null 的全局错误照常显示
            ErrorCardsDisplay(
                errors = errors.filter { it.conversationId == null || it.conversationId == conversation.id },
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
            )

            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text("Clear selection")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Select all")
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (selectedItems.isNotEmpty()) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.addAll(conversation.messageNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text("Confirm")
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = conversation.messageNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(HugeIcons.Tick01, null)
                        }
                    }
                }
            }

            // 导出对话框
            ChatExportSheet(
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversation = conversation,
                selectedMessages = conversation.messageNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage }
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 消息快速跳转（共享组件；拼装预览页复用同一份）
            ScrollJumperBar(
                show = isRecentScroll && !state.isScrollInProgress && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state
            )

            // Suggestion
            if (conversation.chatSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    conversation = conversation,
                    onClickSuggestion = onClickSuggestion,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
            }
        }
    }

/**
 * 提取包含搜索词的文本片段，确保匹配词在开头可见
 */
private fun extractMatchingSnippet(
    text: String,
    query: String
): String {
    if (query.isBlank()) {
        return text
    }

    val matchIndex = text.indexOf(query, ignoreCase = true)
    if (matchIndex == -1) {
        return text
    }

    // 直接从匹配词开始显示，确保匹配词在最前面
    val snippet = text.substring(matchIndex)

    // 只在前面有内容时添加省略号
    return if (matchIndex > 0) {
        "...$snippet"
    } else {
        snippet
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (query.isBlank()) {
        return AnnotatedString(text)
    }

    return buildAnnotatedString {
        var startIndex = 0
        var index = text.indexOf(query, startIndex, ignoreCase = true)

        while (index >= 0) {
            // 添加高亮前的文本
            append(text.substring(startIndex, index))

            // 添加高亮文本
            withStyle(
                style = SpanStyle(
                    background = highlightColor,
                    color = Color.Black
                )
            ) {
                append(text.substring(index, index + query.length))
            }

            startIndex = index + query.length
            index = text.indexOf(query, startIndex, ignoreCase = true)
        }

        // 添加剩余文本
        if (startIndex < text.length) {
            append(text.substring(startIndex))
        }
    }
}

@Composable
private fun ChatListPreview(
    innerPadding: PaddingValues,
    conversation: Conversation,
    settings: Settings,
    hazeState: HazeState,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onJumpToMessage: (MessageNode) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    // 过滤消息（跳转按**节点**解析，不再携带完整列表下标——压缩折叠态两套下标空间不可混用）
    val filteredMessages = remember(conversation.messageNodes, searchQuery) {
        if (searchQuery.isBlank()) {
            conversation.messageNodes
        } else {
            conversation.messageNodes.filter { node ->
                node.currentMessage.toText().contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(top = innerPadding.calculateTopPadding())
            .fillMaxSize()
            .hazeSource(state = hazeState),
    ) {
        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.history_page_search)) },
            leadingIcon = {
                Icon(
                    imageVector = HugeIcons.Search01,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "Clear",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
            maxLines = 1,
        )

        // 消息预览
        LazyColumn(
            contentPadding = PaddingValues(16.dp) + PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            itemsIndexed(
                items = filteredMessages,
                key = { _, item -> item.id },
            ) { _, node ->
                val message = node.currentMessage
                val isUser = message.role == app.androidrik.ai.core.MessageRole.USER
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isUser) Modifier.padding(end = 24.dp) else Modifier
                        ),
                    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onJumpToMessage(node)
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
                            val highlightedText = remember(searchQuery, message) {
                                val fullText = message.toText().trim().ifBlank { "[...]" }
                                val messageText = extractMatchingSnippet(
                                    text = fullText,
                                    query = searchQuery
                                )
                                buildHighlightedText(
                                    text = messageText,
                                    query = searchQuery,
                                    highlightColor = highlightColor
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    conversation: Conversation,
    onClickSuggestion: (String) -> Unit
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(conversation.chatSuggestions) { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onClickSuggestion(suggestion)
                    }
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
