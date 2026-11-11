package app.androidrik.rikkahub.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.AppScope
import app.androidrik.rikkahub.data.ai.tools.local.PHONE_USE_TOOL_NAME
import app.androidrik.rikkahub.data.datastore.SettingsStore
import kotlin.uuid.Uuid

/** The target conversation and whether its background panel is expanded. */
data class FloatingChatWindow(val conversationId: Uuid, val expanded: Boolean)

/** Only tracks while enabled and backgrounded; retains completed targets for quick replies. */
class ChatFloatingTracker(
    appScope: AppScope,
    lazyChatService: Lazy<ChatService>,
    settingsStore: SettingsStore,
) {
    private val chatService by lazyChatService
    private val isForeground = MutableStateFlow(true)
    private val overlayAllowed = MutableStateFlow(false)

    fun setOverlayAllowed(allowed: Boolean) { overlayAllowed.value = allowed }
    private val actions = MutableSharedFlow<Action>(extraBufferCapacity = 8)

    val window: StateFlow<FloatingChatWindow?> = combine(
        settingsStore.settingsFlow.map { it.displaySetting.enableFloatingChatWindow },
        isForeground,
        overlayAllowed,
    ) { enabled, foreground, allowed -> enabled && !foreground && allowed }
        .distinctUntilChanged()
        .flatMapLatest { active -> if (active) trackInBackground() else flowOf(null) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    init {
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> isForeground.value = true
                        Lifecycle.Event.ON_STOP -> isForeground.value = false
                        else -> Unit
                    }
                }
            )
        }
        appScope.launch {
            window.map { it?.conversationId }.distinctUntilChanged().collectLatest { id ->
                if (id == null) return@collectLatest
                chatService.addConversationReference(id)
                try {
                    awaitCancellation()
                } finally {
                    chatService.removeConversationReference(id)
                }
            }
        }
    }

    fun expand() { actions.tryEmit(Action.Expand) }
    fun collapse() { actions.tryEmit(Action.Collapse) }
    fun dismiss() { actions.tryEmit(Action.Dismiss) }

    private fun trackInBackground(): Flow<FloatingChatWindow?> =
        merge(chatService.getFloatingConversationIds().map { Action.TurnsChanged(it) }, actions)
            .scan(TrackingState()) { state, action -> state.reduce(action, ::usedPhoneUse) }
            .map { it.window }

    /**
     * 本轮回复里用过 Phone Use 吗？用过就在轮末自动展开面板，让用户看到结果/错误/待处理事项。
     * 普通聊天不展开，用户切出去做别的事时不被打断。
     */
    private fun usedPhoneUse(conversationId: Uuid): Boolean {
        val replyId = chatService.getReplyMessageIdFlow(conversationId).value ?: return false
        return chatService.getConversationFlow(conversationId).value.messageNodes.any { node ->
            node.messages.any { message ->
                message.id == replyId && message.getTools().any { it.toolName == PHONE_USE_TOOL_NAME }
            }
        }
    }

    internal sealed interface Action {
        data class JobsChanged(val running: Set<Uuid>) : Action
        data class TurnsChanged(val turns: Map<Uuid, Long>) : Action
        data object Expand : Action
        data object Collapse : Action
        data object Dismiss : Action
    }

    internal data class TrackingState(
        val running: Set<Uuid> = emptySet(),
        val target: Uuid? = null,
        val expanded: Boolean = false,
        val turns: Map<Uuid, Long> = emptyMap(),
    ) {
        val window: FloatingChatWindow? get() = target?.let { FloatingChatWindow(it, expanded) }

        /**
         * [expandWhenFinished] 决定目标会话这一轮结束时要不要自动展开面板。
         * Phone Use 的轮次停下来（做完、出错、等审批）时用户正看着别的应用，结果直接展开给他看。
         */
        fun reduce(
            action: Action,
            expandWhenFinished: (Uuid) -> Boolean = { false },
        ): TrackingState = when (action) {
            is Action.JobsChanged -> copy(
                running = action.running,
                target = (action.running - running).lastOrNull() ?: target,
            )
            is Action.TurnsChanged -> {
                val started = action.turns.entries
                    .lastOrNull { (id, revision) -> turns[id] != revision }?.key
                val finished = target?.takeIf { it in running && it !in action.turns }
                copy(
                    running = action.turns.keys,
                    turns = action.turns,
                    target = started ?: target,
                    expanded = expanded || (finished != null && expandWhenFinished(finished)),
                )
            }
            Action.Expand -> copy(expanded = target != null)
            Action.Collapse -> copy(expanded = false)
            Action.Dismiss -> copy(target = null, expanded = false)
        }
    }
}
