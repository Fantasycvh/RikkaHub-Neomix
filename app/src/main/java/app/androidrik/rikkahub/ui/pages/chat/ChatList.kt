package app.androidrik.rikkahub.ui.pages.chat

import app.androidrik.rikkahub.ui.components.ui.appSkinButtonShape
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.androidrik.rikkahub.ui.components.ui.AppOutlinedTextField as OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.platform.testTag
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
import dev.chrisbanes.haze.HazeState
import app.androidrik.rikkahub.ui.components.haze.hazeSourceCompat
import app.androidrik.rikkahub.ui.components.haze.liquidGlassChromeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.R
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.getAssistantById
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.pathNodes
import app.androidrik.rikkahub.service.ChatError
import app.androidrik.rikkahub.ui.components.message.ChatMessage
import app.androidrik.rikkahub.ui.components.message.ChatBranchController
import app.androidrik.rikkahub.ui.components.message.LocalChatBranchController
import app.androidrik.rikkahub.ui.components.message.MAX_RENDER_TEXT_LENGTH
import app.androidrik.rikkahub.ui.components.richtext.SharedSelectableContent
import app.androidrik.rikkahub.ui.components.message.CompactionCheckpointRow
import app.androidrik.rikkahub.ui.components.ui.ErrorCardsDisplay
import app.androidrik.rikkahub.ui.components.ui.ListSelectableItem
import app.androidrik.rikkahub.ui.components.ui.LocalConversationImageUrls
import app.androidrik.rikkahub.ui.components.ui.RabbitLoadingIndicator
import app.androidrik.rikkahub.ui.modifier.shimmer
import app.androidrik.rikkahub.ui.components.ui.ScrollJumperBar
import app.androidrik.rikkahub.ui.components.ui.Tooltip
import app.androidrik.rikkahub.ui.hooks.ImeLazyListAutoScroller
import app.androidrik.rikkahub.ui.theme.rememberChatAppearance
import app.androidrik.rikkahub.ui.theme.ChatFontProvider
import app.androidrik.rikkahub.utils.collectConversationImageUrls
import app.androidrik.rikkahub.utils.normalizeLocalFileUrl
import app.androidrik.rikkahub.utils.plus
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
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
    /** 树内分叉（§7.3）：在该消息处开新时间线（本会话内兄弟节点） */
    onForkBranch: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    /** 分支切换（时间线）：切到某兄弟子树（服务端落到该子树最近更新叶子）；树形分支 P1 */
    onSwitchBranch: (Uuid) -> Unit = {},
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
    scrollController: ChatListScrollController? = null,
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
        val defaultScrollController = rememberSaveable(conversation.id, saver = ChatListScrollController.Saver) { ChatListScrollController() }
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
                // 分支（时间线）切换上下文：全量节点集 + 切换回调，经 CompositionLocal 下发，
                // ChatMessageBranchSelector 消费（避免给 ChatMessage 全链路加参数）
                CompositionLocalProvider(
                    LocalChatBranchController provides ChatBranchController(
                        nodes = conversation.messageNodes,
                        onSwitchBranch = onSwitchBranch,
                    )
                ) {
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
                    onForkBranch = onForkBranch,
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
                    scrollController = scrollController ?: defaultScrollController,
                )
                }
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
    onForkBranch: (UIMessage) -> Unit = {},
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
    scrollController: ChatListScrollController? = null,
) {
    val scope = rememberCoroutineScope()
    val loadingState by rememberUpdatedState(loading)
    var isRecentScroll by remember { mutableStateOf(false) }
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
        if (lastItem.index != state.layoutInfo.totalItemsCount - 1) return false
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
    // P1：渲染 = **激活路径**（树形分支后 messageNodes 是全量节点集，含 off-path 兄弟子树）
    val activeCheckpoint = conversation.displayCompression()
    var showCompressedHistory by remember(conversation.id) { mutableStateOf(false) }
    val visibleNodes = if (activeCheckpoint != null && !showCompressedHistory) {
        conversation.windowNodes()
    } else {
        conversation.pathNodes()
    }
    val lastMessageIndex = visibleNodes.lastIndex
    // 活动感知加载态（方向 I §9.1）：当前阶段一行（思考中/工具执行中/输出中），
    // processingStatus（OCR/压缩/重试显式状态）优先；见 ChatActivityStage.kt 的事件映射
    val activityStage = rememberChatActivityStage(visibleNodes.lastOrNull(), processingStatus)
    val appearance = rememberChatAppearance(settings.displaySetting)
    val truncatedNotice = stringResource(R.string.chat_message_truncated_notice, MAX_RENDER_TEXT_LENGTH)
    val staticDocuments = rememberStaticDocuments(conversation.id, visibleNodes, assistant, appearance,
        settings.displaySetting.enableLongMessageLazyRendering, loading, settings.displaySetting.wholeTurnFolding, truncatedNotice)
    val tailKeys = buildList {
        if (!loading && assistant?.allowConversationSystemPrompt == true && onConversationSystemPromptChange != null) add("ConversationSystemPrompt")
        if (!loading && onConversationGoalChange != null) add("ConversationGoal")
        if (loading) add(LoadingIndicatorKey)
        add(ScrollBottomKey)
    }
    val alreadySplit = remember(conversation.id) { mutableSetOf<Uuid>() }
    val lastNode by rememberUpdatedState(visibleNodes.lastOrNull()?.id)
    // A whole-message row has no source coordinate for its interior pixel offset. Defer its
    // first split while the reader is inside it; promote at its start/bottom instead of jumping.
    val readingWholeTail by remember(state) { derivedStateOf {
        lastNode?.takeIf { state.firstVisibleItemScrollOffset > 0 && state.canScrollForward &&
            state.layoutInfo.visibleItemsInfo.firstOrNull()?.key == it.toString() }
    } }
    val deferredNodes = setOfNotNull(readingWholeTail).filterNot { it in alreadySplit }.toSet()
    val renderIndex = remember(visibleNodes, staticDocuments, appearance.layout, activeCheckpoint != null, tailKeys, deferredNodes) {
        buildChatRenderIndex(visibleNodes, staticDocuments, appearance, activeCheckpoint != null, tailKeys, deferredNodes)
    }
    SideEffect { renderIndex.rows.filter { it.slice is app.androidrik.rikkahub.ui.components.message.ChatMessageSlice.Markdown }
        .forEach { alreadySplit += it.nodeId } }
    val renderIndexUpdated by rememberUpdatedState(renderIndex)
    val initialAnchor = remember(conversation.id) { scrollController?.readAnchor }
    var stickToBottom by remember(conversation.id) { mutableStateOf(initialAnchor == null || scrollController?.readAtBottom == true) }
    var initialAnchorConsumed by remember(conversation.id) { mutableStateOf(initialAnchor == null) }
    val canRecordAnchor by rememberUpdatedState(initialAnchorConsumed && staticDocuments.pending.isEmpty())
    LaunchedEffect(state, scrollController) {
        snapshotFlow {
            if (!canRecordAnchor) null else {
                val first = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }
                renderIndexUpdated.anchor(first?.key, state.firstVisibleItemScrollOffset)?.let { it to state.layoutInfo.visibleItemsInfo.isAtBottom() }
            }
        }.collect { position -> if (position != null) {
            scrollController?.readAnchor = position.first
            scrollController?.readAtBottom = position.second
        } }
    }
    val copyDocuments = remember(staticDocuments.ready, visibleNodes) {
        buildMap<Any, String> {
            staticDocuments.ready.values.forEach { doc -> doc.markdown.keys.forEach { partIndex ->
                (visibleNodes.firstOrNull { it.id == doc.input.nodeId }?.currentMessage?.parts?.getOrNull(partIndex) as? UIMessagePart.Text)?.let {
                    put(StaticTextGroup(doc.input.message.id, partIndex, doc.epoch), it.text)
                }
            } }
        }
    }
    val previousIndex = remember(conversation.id) { arrayOfNulls<ChatRenderIndex>(1) }
    val restoreAnchor = if (previousIndex[0] !== renderIndex) {
        val first = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }
        previousIndex[0]?.anchor(first?.key, state.firstVisibleItemScrollOffset)
    } else null
    SideEffect {
        previousIndex[0] = renderIndex
    }
    LaunchedEffect(renderIndex) {
        // Compose keeps surviving keys anchored itself. Only remap keys removed by a document/mode change.
        if (restoreAnchor != null && restoreAnchor.key !in renderIndex.keyToIndex && !state.isScrollInProgress && scrollController?.request == null) {
            renderIndex.restore(restoreAnchor)?.let { (index, offset) -> state.requestScrollToItem(index, offset) }
        }
    }
    val dragging by state.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) { if (dragging) { stickToBottom = false; scrollController?.cancel(); initialAnchorConsumed = true } }

    // #1906-B：引用块点击 → 跳到被引用的原消息。
    //
    // 不能直接复用 onJumpToMessage(index)：那个 index 是**完整节点列表**下标，而这里渲染的
    // 可能是压缩折叠后的 `windowNodes()`（只有边界之后的尾巴），两者下标空间不一致；折叠时
    // 列表顶部还多一个检查点行。所以按 **messageId** 定位，并处理三种边界：
    //  1. 目标在折叠的历史里（很常见——引用往往指向更早的消息）⇒ 先展开完整轨迹，
    //     由 visibleNodes 变化触发本 effect 重跑，再滚动；
    //  2. 目标在**off-path 分支**里 ⇒ 先切到目标所在子树（跨分支定位，P2 §7.2），再滚动；
    //  3. 完全找不到（消息已被删除）或生成中（切分支被守卫拒绝）时放弃，不报错。
    var pendingQuoteJump by remember(conversation.id) { mutableStateOf<Uuid?>(null) }

    // 分支切换滚动锚点（树形分支 P2 §7.2）：时间线切换后滚到目标节点——pendingQuoteJump
    // 以节点 id 表达（ChatRenderIndex.target 节点 id/消息 id 双寻址）。切换落库前 renderIndex
    // 仍走旧路径，quoteJump 跨分支逻辑会保留待办并随 renderIndex 重算重试。
    val outerBranchController = LocalChatBranchController.current
    fun requestBranchSwitch(nodeId: Uuid) {
        if (!loadingState) pendingQuoteJump = nodeId
        outerBranchController?.onSwitchBranch(nodeId)
    }
    val anchoredBranchController = outerBranchController?.let {
        ChatBranchController(nodes = it.nodes, onSwitchBranch = ::requestBranchSwitch)
    }
    LaunchedEffect(initialAnchor, renderIndex, staticDocuments.pending) {
        if (initialAnchorConsumed || staticDocuments.pending.isNotEmpty()) return@LaunchedEffect
        if (initialAnchor != null && scrollController?.request == null && pendingPreviewJump == null && pendingQuoteJump == null) {
            renderIndex.restore(initialAnchor)?.let { (index, offset) -> state.scrollToItem(index, offset) }
        }
        initialAnchorConsumed = true
    }
    // 预览搜索跳转并入同一条 quoteJump 管线（含检查点行偏移与「折叠历史先展开」）
    LaunchedEffect(pendingPreviewJump) {
        val target = pendingPreviewJump ?: return@LaunchedEffect
        onPreviewJumpConsumed()
        pendingQuoteJump = target
    }
    LaunchedEffect(scrollController?.request, renderIndex, staticDocuments.pending) {
        when (val request = scrollController?.request) {
            is ChatListScrollController.Request.Message -> { stickToBottom = false; pendingQuoteJump = request.id; scrollController.consume(request.serial) }
            is ChatListScrollController.Request.Bottom -> {
                if (staticDocuments.pending.isNotEmpty()) return@LaunchedEffect
                if (request.waitForChange && request.waitForChangeFrom == conversation.currentMessages.lastOrNull()?.id) return@LaunchedEffect
                state.requestScrollToItem(renderIndex.lastIndex)
                stickToBottom = true
                scrollController.consume(request.serial)
            }
            null -> Unit
        }
    }
    LaunchedEffect(pendingQuoteJump, renderIndex, activeCheckpoint, staticDocuments.pending) {
        val targetId = pendingQuoteJump ?: return@LaunchedEffect
        stickToBottom = false
        val index = renderIndex.target(targetId)
        if (index < 0) {
            // 折叠态找不到 ⇒ 目标很可能在折叠的历史里，先展开完整轨迹再重试
            if (activeCheckpoint != null && !showCompressedHistory) {
                showCompressedHistory = true
                return@LaunchedEffect
            }
            // 跨分支（P2 §7.2）：目标存在于节点集但不在激活路径 ⇒ 先切到目标所在子树
            // （服务端落到该子树最近更新叶子），保留待办，待 renderIndex（新路径）重算重试。
            // 目标已删除、或生成中（切分支被守卫拒绝）时放弃。
            val targetNode = conversation.messageNodes.firstOrNull {
                it.id == targetId || it.messages.any { message -> message.id == targetId }
            }
            if (targetNode != null && !loadingState) {
                outerBranchController?.onSwitchBranch(targetNode.id)
                return@LaunchedEffect
            }
            pendingQuoteJump = null
            return@LaunchedEffect
        }
        state.animateScrollToItem(index)
        pendingQuoteJump = null
    }

    // 内容整体包一层：把「带滚动锚点」的分支切换器下发给消息组件（覆盖 ChatList 的外层提供）
    CompositionLocalProvider(LocalChatBranchController provides anchoredBranchController) {
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
            var hasGenerated by remember(conversation.id) { mutableStateOf(false) }
            SideEffect { if (loading) hasGenerated = true }
            LaunchedEffect(state) {
                snapshotFlow { state.layoutInfo.visibleItemsInfo }.collect { visibleItemsInfo ->
                    if (state.isScrollInProgress) {
                        // An animated logical/volume jump is a request to read elsewhere. Only
                        // an actual finger drag reaching the bottom opts back into following.
                        stickToBottom = dragging && visibleItemsInfo.isAtBottom()
                    } else if ((loadingState || hasGenerated) && stickToBottom && !visibleItemsInfo.isAtBottom()) {
                        state.requestScrollToItem(renderIndexUpdated.lastIndex)
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

        ChatFontProvider(displaySetting = settings.displaySetting, backdropState = glassHazeState) {
            // 容器不再被顶栏 padding 挡住：列表与玻璃层都延伸到顶栏底下，
            // 滚动时消息从顶栏下穿过被 blur（顶栏消费 hazeState，见 ChatPage.TopBar）
            //
            // ⚠️ 父子层结构实验（2026-10-31）：效果挂在**包含 LazyColumn 的容器**上——
            // 这正是 2.4.10~2.4.12 闪退时代的拓扑（22552aaa6 包固定 Box 仍闪退 /
            // 76910162a 定位「blur 容器不能含可滚动子节点」后改兄弟层）。haze 2.0.1 +
            // 门控层下重试真机是否可行。仍只消费 glassHazeState（背景）——hazeState 里
            // 注册了 LazyColumn 自己，父层采样会把列表内容模糊进自己的背景（重影）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (settings.displaySetting.enableGlassTheme) Modifier.liquidGlassChromeEffect(
                            hazeState = glassHazeState,
                            effectType = settings.displaySetting.listGlassEffectType,
                            readabilityMode = settings.displaySetting.liquidGlassReadabilityMode,
                        ) else Modifier
                    ),
            ) {
            SharedSelectableContent(documentText = { copyDocuments[it] }, modifier = Modifier.fillMaxSize(), obscuredPadding = innerPadding) {
            LazyColumn(
                state = state,
                // top 进 contentPadding 而非容器 padding：静止时首条消息仍从顶栏下缘起排，
                // 滚动时消息可穿过顶栏被 blur（与 bottom 穿过输入栏同款）
                contentPadding = PaddingValues(appearance.layout.listPaddingDp.dp) +
                    PaddingValues(top = innerPadding.calculateTopPadding()) +
                    PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSourceCompat(state = hazeState),
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

            items(
                items = renderIndex.rows,
                key = { it.key },
            ) { row ->
                val index = row.nodeIndex
                val node = visibleNodes[index]
                // 哨兵只兜异常高度（防 Int.MAX 崩溃），正常长内容不再被裁剪
                HeightClamp(normalLimit = 100_000.dp) {
                Column(Modifier.padding(top = row.topGapDp.dp).testTag("chat_row:${row.key}")) {
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
                        showCheckbox = row.firstInMessage,
                    ) {
                        if (node.currentMessage.isContextCheckpoint) {
                            var showCheckpointBody by remember(node.currentMessage.id) { mutableStateOf(false) }
                            Column {
                                CompactionCheckpointRow(
                                    messageCount = conversation.pathNodes().takeWhile { it.id != node.id }
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
                            slice = row.slice,
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
                            onForkBranch = {
                                onForkBranch(node.currentMessage)
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
                            onToolSkip = onToolSkip,
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
                    Box(Modifier.padding(top = if (renderIndex.keyToIndex.getValue("ConversationSystemPrompt") > 0) appearance.layout.listSpacingDp.dp else 0.dp)) {
                    ConversationSystemPromptButton(
                        customSystemPrompt = conversation.customSystemPrompt,
                        onSystemPromptChange = onConversationSystemPromptChange,
                    )
                    }
                }
            }

            // 会话目标编辑入口（定时任务 P2）：目标是会话的属性，写在会话上而非任务上
            if (!loading && onConversationGoalChange != null) {
                item(key = "ConversationGoal") {
                    Box(Modifier.padding(top = if (renderIndex.keyToIndex.getValue("ConversationGoal") > 0) appearance.layout.listSpacingDp.dp else 0.dp)) {
                    ConversationGoalButton(
                        goal = conversation.goal,
                        onGoalChange = onConversationGoalChange,
                    )
                    }
                }
            }
            if (loading) {
                item(LoadingIndicatorKey) {
                    Row(
                        modifier = Modifier.padding(top = if (renderIndex.keyToIndex.getValue(LoadingIndicatorKey) > 0) appearance.layout.listSpacingDp.dp else 0.dp).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(28.dp)
                        )
                        // 阶段步进文字：shimmer 扫光为 AI 加载态的行业标志视觉（§9.1）
                        AnimatedVisibility(
                            visible = activityStage != null,
                        ) {
                            Text(
                                text = activityStage.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.shimmer(isLoading = true),
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
                        .padding(top = if (renderIndex.keyToIndex.getValue(ScrollBottomKey) > 0) appearance.layout.listSpacingDp.dp else 0.dp)
                        .height(5.dp)
                )
            }
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
                state = state,
                onTop = { stickToBottom = false; state.requestScrollToItem(0) },
                onBottom = { stickToBottom = true; state.requestScrollToItem(renderIndex.lastIndex) },
                onPrevious = { scope.launch { state.animateScrollToItem(renderIndex.previousMessage(state.firstVisibleItemIndex)) } },
                onNext = { scope.launch { state.animateScrollToItem(renderIndex.nextMessage(state.firstVisibleItemIndex)) } },
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
    }

/**
 * 概览卡交给 `Text` 的字符串上限。
 *
 * 判据：`maxLines` 只决定「显示几行」，**不省**排版成本——文本排版要先对整段字符串做
 * span 解析与断行扫描，才知道第一行断在哪；几万字的回复每滑进一张卡片，主线程就要扫一遍
 * 全文，这是长对话概览滑动卡顿的主因。显示前截断到这里即可：一行实际只要几十字，
 * 300 是给宽屏/小字号留的余量；搜索匹配仍用全文（见 `ChatListPreview` 的后台过滤与
 * `extractMatchingSnippet`）。
 */
private const val PreviewCardMaxLength = 300

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

    // 搜索词变化时把预览列表拉回顶部（上游 5863c4e4c 残件）：过滤结果换了以后，旧滚动位置
    // 可能停在很深处，命中项在视口外，用户只能手动往回滚。结果由后台异步算出，先滚到顶
    // 再逐帧填充也能看到第一条命中。
    val listState = rememberLazyListState()
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) listState.scrollToItem(0)
    }

    // 过滤消息（跳转按**节点**解析，不再携带完整列表下标——压缩折叠态两套下标空间不可混用）。
    //
    // 搜索要对每条消息 `toText()`（拼接全部文本 part）后做全文匹配：长对话 × 长回复是
    // O(节点数 × 全文) 的主线程工作，而生成中每个 chunk 都会改 `messageNodes`。
    // 关键词非空时挪到后台线程算，并用 `conflate` 丢掉中间状态——只算最新的节点列表 + 关键词；
    // 空关键词不需要过滤（旧行为就是原列表，也省一次后台往返），那条路不进后台。
    // 已有结果在最新一次算完前继续沿用（不闪空）；首次为 null 时列表短暂为空一帧，可接受。
    // `messageNodes` 是普通参数（不是 State），要经 `rememberUpdatedState` 包一层才能在
    // `snapshotFlow` 里被观察到；`searchQuery` 本身就是 State，直接读即可。
    val previewNodes by rememberUpdatedState(conversation.messageNodes)
    val searchMatches by produceState<List<MessageNode>?>(initialValue = null) {
        snapshotFlow { previewNodes to searchQuery }
            .conflate()
            .collect { (nodes, query) ->
                if (query.isBlank()) return@collect
                value = withContext(Dispatchers.Default) {
                    nodes.filter { node ->
                        node.currentMessage.toText().contains(query, ignoreCase = true)
                    }
                }
            }
    }
    val filteredMessages =
        if (searchQuery.isBlank()) conversation.messageNodes else searchMatches.orEmpty()

    // 与 ChatListNormal 统一：列表全尺寸延伸到顶栏底下（contentPadding 穿底），
    // 搜索框悬浮在顶栏下缘（自带底色保证从内容上滚过时可读）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .hazeSourceCompat(state = hazeState),
    ) {
        // 消息预览
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp) +
                // 顶栏 + 悬浮搜索框高度（singleLine 输入框 56dp + 上下 8dp = 72dp）
                PaddingValues(top = innerPadding.calculateTopPadding() + 72.dp) +
                PaddingValues(bottom = 32.dp + innerPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize(),
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
                                // 匹配在全文里找（片段从命中处开始），交给 Text 的字符串只留前
                                // PreviewCardMaxLength 个字符：maxLines 只裁显示，不省排版的
                                // span 解析/断行扫描，长回复每滑进一张卡片都要主线程扫全文。
                                val messageText = extractMatchingSnippet(
                                    text = fullText,
                                    query = searchQuery
                                ).take(PreviewCardMaxLength)
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

        // 搜索框：悬浮在顶栏下缘（Box 第二子元素叠在列表上），自带底色
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = innerPadding.calculateTopPadding())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
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
                    .clip(appSkinButtonShape())
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
