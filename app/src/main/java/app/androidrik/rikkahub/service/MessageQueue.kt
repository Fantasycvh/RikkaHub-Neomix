package app.androidrik.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isEmptyInputMessage
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.localFileUrls
import kotlin.uuid.Uuid

data class QueuedMessage(
    val id: Uuid = Uuid.random(),
    val parts: List<UIMessagePart>,
    val answer: Boolean = true,
    val isEditing: Boolean = false,
    // 可选内存观察者：语音模式等回复用；null 结果 = 队列项被撤下
    val reply: CompletableDeferred<String?>? = null,
    // 引用快照：派发时原样落到 user message 的 annotations。
    val annotations: List<UIMessageAnnotation> = emptyList(),
)

data class MessageQueueState(
    val messages: List<QueuedMessage> = emptyList(),
    val paused: Boolean = false,
)

/** 队列项被移除/编辑后，找它的附件里不再被任何会话历史或待发消息引用的 URL。 */
internal fun unreferencedQueuedAttachmentUrls(
    previous: QueuedMessage,
    conversations: List<Conversation>,
    pendingMessages: List<QueuedMessage>,
): Set<String> {
    val retainedParts = conversations.flatMap { conversation ->
        conversation.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }
    } + pendingMessages.flatMap { it.parts }
    return previous.parts.localFileUrls() - retainedParts.localFileUrls()
}

/** 队列暂停时回复等待者（语音模式感知「队列被暂停」而非卡死）。 */
class MessageQueuePausedException : IllegalStateException()

/** 待发消息保持在会话历史之外，直到被派发。 */
class MessageQueue {
    private val mutableState = MutableStateFlow(MessageQueueState())
    val state = mutableState.asStateFlow()

    /**
     * 入队（**只能追加**：本队列是严格 FIFO，没有插入/重排原语）。
     *
     * ## 已知且暂不修的边界（2026-10-13 明确留档，勿当新 bug 报）
     *
     * - **没有「插队」能力**：`enqueue` 只追加、UI 也只给「编辑/撤销」两个操作
     *   （`MessageQueuePanel` 无拖拽/置顶）。用户若想要「这条下一条发」，需要新增重排原语
     *   （队列内重排）或打断当前轮次（真插队）——两者语义不同，属**未立项**。
     * - **编辑队首会卡住整个队列**：见 [takeNext] 的注释（`isEditing` 只拦队首自己）。
     *   用户 2026-10-13 明确表示**该行为可接受**（编辑本来就是短暂占用），故不修、只注明。
     */
    @Synchronized
    fun enqueue(
        parts: List<UIMessagePart>,
        answer: Boolean = true,
        reply: CompletableDeferred<String?>? = null,
        annotations: List<UIMessageAnnotation> = emptyList(),
    ) {
        if (parts.isEmptyInputMessage()) {
            reply?.complete(null)
            return
        }
        mutableState.value = state.value.copy(
            messages = state.value.messages + QueuedMessage(
                parts = parts.toList(),
                answer = answer,
                reply = reply,
                annotations = annotations.toList(),
            ),
        )
    }

    /**
     * 取队首派发。
     *
     * **已知边界（有意保留）**：`isEditing` 是**队首自己**的属性，而本方法只取队首 ⇒
     * 一旦开始编辑队首那条，整个队列就停住（后面就绪的消息也发不出去），直到保存/取消编辑。
     * 用户 2026-10-13 明确表示可接受（编辑是短暂占用，且「编辑中」在面板上有文案提示），
     * **故不修**。若将来要修，正确形态是**跳过**编辑中的项去取后面就绪的（而不是只看队首），
     * 但那会让「编辑中的消息被插到后面」——顺序语义要一并想清楚。
     */
    @Synchronized
    fun takeNext(): QueuedMessage? {
        val current = state.value
        if (current.paused) return null
        val next = current.messages.firstOrNull()?.takeUnless { it.isEditing } ?: return null
        mutableState.value = current.copy(messages = current.messages.drop(1))
        return next
    }

    @Synchronized
    fun remove(id: Uuid): QueuedMessage? {
        val removed = state.value.messages.find { it.id == id } ?: return null
        mutableState.value =
            state.value.copy(messages = state.value.messages.filterNot { it.id == id })
        removed.reply?.complete(null)
        return removed
    }

    @Synchronized
    fun beginEdit(id: Uuid): QueuedMessage? {
        val message = state.value.messages.find { it.id == id && !it.isEditing } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map { if (it.id == id) it.copy(isEditing = true) else it },
        )
        return message
    }

    @Synchronized
    fun finishEdit(
        id: Uuid,
        parts: List<UIMessagePart>? = null,
        annotations: List<UIMessageAnnotation>? = null,
    ): QueuedMessage? {
        if (parts != null && parts.isEmptyInputMessage()) return null
        val previous = state.value.messages.find { it.id == id } ?: return null
        mutableState.value = state.value.copy(
            messages = state.value.messages.map {
                if (it.id == id) it.copy(
                    parts = parts?.toList() ?: it.parts,
                    annotations = if (parts != null) annotations?.toList() ?: it.annotations else it.annotations,
                    isEditing = false
                ) else it
            },
        )
        // 取消编辑只释放占位，不能清理原附件。
        return previous.takeIf { parts != null }
    }

    @Synchronized
    fun pause() {
        mutableState.value = state.value.copy(paused = true)
        state.value.messages.forEach { it.reply?.completeExceptionally(MessageQueuePausedException()) }
    }

    fun failReplyWaiters(message: String) {
        state.value.messages.forEach {
            it.reply?.completeExceptionally(IllegalStateException(message))
        }
    }

    @Synchronized
    fun resume() {
        mutableState.value = state.value.copy(paused = false)
    }
}
