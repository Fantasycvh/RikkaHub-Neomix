package app.androidrik.rikkahub.ui.floating

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalView
import android.graphics.Rect
import androidx.compose.runtime.DisposableEffect
import android.view.ViewTreeObserver
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
import android.app.Application
import android.app.AppOpsManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.CancellationException
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.petterp.floatingx.compose.compose
import com.petterp.floatingx.core.FloatingX
import com.petterp.floatingx.core.FxControl
import com.petterp.floatingx.core.animation.FxAnimations
import com.petterp.floatingx.core.gesture.FxDrag
import com.petterp.floatingx.core.gesture.FxGesture
import com.petterp.floatingx.core.layout.FxAdsorb
import com.petterp.floatingx.core.layout.FxGravity
import com.petterp.floatingx.core.storage.FxSpStorage
import com.petterp.floatingx.system.permission.FxPermission
import com.petterp.floatingx.system.permission.FxPermissionStrategy
import com.petterp.floatingx.system.systemHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.ai.SubagentApprovalQueue
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.conversationIntent
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.GenerationPhase
import app.androidrik.rikkahub.data.model.generationPhase
import app.androidrik.rikkahub.data.model.pendingToolCalls
import app.androidrik.rikkahub.service.ChatError
import app.androidrik.rikkahub.service.ChatFloatingTracker
import app.androidrik.rikkahub.service.ChatService
import app.androidrik.rikkahub.service.MessageQueueState
import app.androidrik.rikkahub.ui.theme.RikkahubTheme
import app.androidrik.rikkahub.ui.context.LocalSettings
import app.androidrik.rikkahub.ui.hooks.rememberUserSettingsState
import kotlin.uuid.Uuid

private const val TAG = "ChatFloatingWindows"
private const val BUBBLE_TAG = "chat_floating_bubble"
private const val PANEL_TAG = "chat_floating_panel"
private const val PANEL_TOP_OFFSET_DP = 8f

/** 显示 tracker 选择的会话。FloatingX 操作均运行在主线程 AppScope 上。 */
class ChatFloatingWindows(
    private val context: Application,
    private val appScope: AppScope,
    private val tracker: ChatFloatingTracker,
    lazyChatService: Lazy<ChatService>,
) {
    private val chatService by lazyChatService
    private var bubble: FxControl? = null
    private var panel: FxControl? = null
    private val sizePreferences = context.getSharedPreferences("floating_chat_window", Context.MODE_PRIVATE)
    private var preferredSize by mutableStateOf(FloatingPanelSize(
        sizePreferences.getFloat("width_dp", 560f),
        sizePreferences.getFloat("height_dp", 520f),
    ))

    private fun saveSize() {
        sizePreferences.edit().putFloat("width_dp", preferredSize.width)
            .putFloat("height_dp", preferredSize.height).apply()
    }

    private val uiState: StateFlow<FloatingChatUiState?> = tracker.window
        .map { it?.conversationId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else chatState(id) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    // Application Context 打开链接必须带 NEW_TASK。
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            val intent = Intent(Intent.ACTION_VIEW, uri.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "openUri: no activity for $uri", e)
            }
        }
    }

    init {
        appScope.launch {
            combine(tracker.window, overlayPermission().map { granted ->
                tracker.setOverlayAllowed(granted)
                granted
            }) { window, granted ->
                window?.expanded.takeIf { granted }
            }.distinctUntilChanged().collect { expanded ->
                try {
                    render(expanded)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Permission can be revoked between checking AppOps and adding a system window.
                    Log.w(TAG, "Unable to render floating chat", e)
                    runCatching { bubble?.cancel() }
                    runCatching { panel?.cancel() }
                    bubble = null
                    panel = null
                }
            }
        }
    }

    private fun overlayPermission(): Flow<Boolean> = callbackFlow {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val listener = AppOpsManager.OnOpChangedListener { op, packageName ->
            if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == context.packageName) {
                trySend(FxPermission.isGranted(context))
            }
        }
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, listener)
        trySend(FxPermission.isGranted(context))
        awaitClose { appOps.stopWatchingMode(listener) }
    }

    private fun chatState(id: Uuid): Flow<FloatingChatUiState> {
        // 时间来自启动生成的落点，不能在观察任务时取，以免漏掉快速失败。
        val error = combine(chatService.errors, chatService.getGenerationStartedAtFlow(id)) { errors, since ->
            since?.let { errors.lastOrNull { it.conversationId == id && it.timestamp >= since } }
        }
        val conversation = combine(chatService.getConversationFlow(id), chatService.getReplyMessageIdFlow(id), ::Pair)
        return combine(
            conversation,
            chatService.getGenerationJobStateFlow(id).map { it != null },
            chatService.getProcessingStatusFlow(id),
            chatService.getMessageQueueFlow(id),
            error,
        ) { (conversation, replyMessageId), generating, processingStatus, queue, error ->
            buildFloatingChatUiState(conversation, replyMessageId, generating, processingStatus, queue, error)
        }.combine(SubagentApprovalQueue.requests) { state, requests ->
            state.copy(subagentApprovals = requests.filter { it.parentConversationId == id.toString() })
        }.flowOn(Dispatchers.Default)
    }

    private fun render(expanded: Boolean?) {
        // 后台不申请权限；没有权限直接销毁两个窗口。
        if (expanded == null || !FxPermission.isGranted(context)) {
            bubble?.cancel()
            panel?.cancel()
            bubble = null
            panel = null
            return
        }
        val bubble = bubble ?: installBubble().also { bubble = it }
        if (expanded) {
            bubble.hide()
            (panel ?: installPanel().also { panel = it }).show()
        } else {
            panel?.hide()
            bubble.show()
        }
    }

    private fun installBubble(): FxControl = FloatingX.install(BUBBLE_TAG) {
        compose {
            WindowContent { state -> ChatFloatingBubble(state = state, onClick = tracker::expand) }
        }
        anchor(FxGravity.CENTER_END, dy = -dp(96f))
        margin(left = dp(8f), right = dp(8f))
        adsorb(FxAdsorb.horizontal())
        persist(FxSpStorage(context))
        animation(FxAnimations.scale())
        systemHost(context) { permission(FxPermissionStrategy.skip()) }
    }

    private fun installPanel(): FxControl = FloatingX.install(PANEL_TAG) {
        compose {
            WindowContent { state ->
                val conversationId = state.conversationId
                val configuration = LocalConfiguration.current
                val density = LocalDensity.current
                val screenHeight = rememberPanelMaxHeight()
                val view = LocalView.current
                var visibleHeight by remember { mutableStateOf(screenHeight) }
                DisposableEffect(view, screenHeight, density) {
                    val listener = ViewTreeObserver.OnGlobalLayoutListener {
                        val rect = Rect()
                        view.getWindowVisibleDisplayFrame(rect)
                        if (rect.height() > 0) {
                            visibleHeight = with(density) { rect.height().toDp() }.coerceAtMost(screenHeight)
                        }
                    }
                    view.viewTreeObserver.addOnGlobalLayoutListener(listener)
                    onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
                }
                val imeHeight = with(density) { WindowInsets.ime.getBottom(this).toDp() }
                val availableHeight = minOf(visibleHeight, screenHeight - imeHeight).coerceAtLeast(1.dp)
                val availableWidth = configuration.screenWidthDp.toFloat()
                val displayedSize = preferredSize.bounded(availableWidth, availableHeight.value)
                ChatFloatingPanel(
                    state = state,
                    maxHeight = displayedSize.height.dp,
                    panelWidth = displayedSize.width.dp,
                    onResize = { dx, dy ->
                        // The centered host grows equally on each side, so the right edge moves by dx.
                        preferredSize = FloatingPanelSize(displayedSize.width, preferredSize.height)
                            .resized(dx * 2f, dy, availableWidth, screenHeight.value)
                    },
                    onResizeFinished = ::saveSize,
                    onResetSize = {
                        preferredSize = FloatingPanelSize()
                        saveSize()
                    },
                    onCollapse = tracker::collapse,
                    onDismiss = tracker::dismiss,
                    onOpenConversation = { openConversation(conversationId) },
                    onToolApproval = { toolCallId, approved ->
                        // 仅普通会话工具；子代理审批必须走独立队列/回调。
                        chatService.handleToolApproval(conversationId, toolCallId, approved)
                    },
                    onSubagentApproval = { request, approved ->
                        // Revalidate membership: stale panel buttons must never answer another parent's run.
                        if (request.parentConversationId == conversationId.toString()) {
                            SubagentApprovalQueue.resolve(request, approved)
                        }
                    },
                    onStop = { appScope.launch { chatService.stopGeneration(conversationId) } },
                    onSend = { text -> chatService.sendMessage(conversationId, listOf(UIMessagePart.Text(text))) },
                    onResumeQueue = { chatService.resumeMessageQueue(conversationId) },
                )
            }
        }
        anchor(FxGravity.TOP_CENTER, dy = dp(PANEL_TOP_OFFSET_DP))
        gesture(FxGesture(click = false, longPress = false, drag = FxDrag.DISABLED))
        animation(FxAnimations.fade())
        systemHost(context) {
            permission(FxPermissionStrategy.skip())
            layoutParams {
                it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                it.width = WindowManager.LayoutParams.MATCH_PARENT
                it.softInputMode = SOFT_INPUT_ADJUST_RESIZE
            }
            onBackPressed {
                tracker.collapse()
                true
            }
        }
    }

    // 隐藏时生命周期退回 STARTED，暂停流式输出引发的重组。
    @Composable
    private fun WindowContent(content: @Composable (FloatingChatUiState) -> Unit) {
        val state by uiState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
        val settings by rememberUserSettingsState()
        RikkahubTheme {
            CompositionLocalProvider(LocalUriHandler provides uriHandler, LocalSettings provides settings) {
                state?.let { content(it) }
            }
        }
    }

    @Composable
    private fun rememberPanelMaxHeight(): Dp {
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        return remember(configuration, density) {
            with(density) { (safeScreenHeight() - dp(PANEL_TOP_OFFSET_DP)).toDp() }
        }
    }

    private fun safeScreenHeight(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return context.resources.displayMetrics.heightPixels
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val insets = WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
            .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return metrics.bounds.height() - insets.top - insets.bottom
    }

    private fun openConversation(conversationId: Uuid) {
        context.startActivity(
            conversationIntent(context, conversationId.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun dp(value: Float): Float = value * context.resources.displayMetrics.density
}

/** replyMessageId 指向本轮回复，允许其位于较早的节点或未选中的分支。 */
internal fun buildFloatingChatUiState(
    conversation: Conversation,
    replyMessageId: Uuid?,
    generating: Boolean,
    processingStatus: String?,
    queue: MessageQueueState,
    error: ChatError?,
): FloatingChatUiState {
    val reply = replyMessageId?.let { id ->
        conversation.messageNodes.firstNotNullOfOrNull { node -> node.messages.firstOrNull { it.id == id } }
    }
    val text = reply?.parts?.filterIsInstance<UIMessagePart.Text>()?.lastOrNull()?.text?.trim().orEmpty()
    val reasoning = reply?.parts?.filterIsInstance<UIMessagePart.Reasoning>()?.lastOrNull()?.reasoning?.trim().orEmpty()
    return FloatingChatUiState(
        conversationId = conversation.id,
        title = conversation.title,
        generating = generating,
        processingStatus = processingStatus,
        phase = reply?.generationPhase() ?: GenerationPhase.Waiting,
        hasReply = reply != null,
        // Markdown 不能截断，否则切在代码块中间会破坏后续渲染。
        preview = text.ifEmpty { reasoning },
        previewIsReasoning = text.isEmpty(),
        pendingTools = conversation.pendingToolCalls,
        queuedCount = queue.messages.size,
        queuePaused = queue.paused,
        error = error,
    )
}
