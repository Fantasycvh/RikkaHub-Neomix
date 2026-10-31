package app.androidrik.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.isEmptyInputMessage
import app.androidrik.rikkahub.data.ai.LoopGateConfig
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
    /**
     * 收尾闸门（design-deliverable-gate.md §三）：**随队列项走**而不是随会话走——
     * 普通发送/插队/重生成等一切用户发起的入队都不传（null = 不挂闸），只有定时任务
     * 执行层显式传入。挂在队列项上保证「这一轮用哪个闸门」由触发方决定，无跨轮残留。
     */
    val loopGate: LoopGateConfig? = null,
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
     * ## 已知边界（2026-10-13 留档，勿当新 bug 报）
     *
     * - **队列内重排（把某条挪到队首）仍未做**，UI 也没给（`MessageQueuePanel` 只有编辑/撤销）。
     * - **「打断当前轮次插队发送」已经做了**，但**不在本类里**——见 [ChatService.sendInterrupting]：
     *   那条路径是停掉当前轮次后**正常入队**再派发，队列本身依旧只看队首，不需要新原语。
     * - **编辑队首会卡住整个队列**：见 [takeNext] 的注释（`isEditing` 只拦队首自己）。
     *   用户 2026-10-13 明确表示**该行为可接受**（编辑本来就是短暂占用），故不修、只注明。
     */
    @Synchronized
    fun enqueue(
        parts: List<UIMessagePart>,
        answer: Boolean = true,
        reply: CompletableDeferred<String?>? = null,
        annotations: List<UIMessageAnnotation> = emptyList(),
        loopGate: LoopGateConfig? = null,
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
                loopGate = loopGate,
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

    /**
     * 把某条挪到**队首**（面板上的「置顶」）——即「下一条就发它」。
     *
     * 与 [ChatService.sendInterrupting] 的区别：那是**打断当前轮次**；这个只改队列顺序，
     * 当前轮次照常跑完。两者正交互补：不想等就打断，只想插到下一条就置顶。
     *
     * 顺序取 [MessageQueueState.messages] 里**第一条匹配 id** 的项（id 由 `Uuid.random()` 生成，
     * 同一队列内不会重复）。已在队首时为空操作、返回 `false`——UI 靠它把按钮显示成「取消置顶」。
     *
     * **不碰 `isEditing`**：置顶是改顺序，不是改内容。但要注意编辑队首会挡住整个队列
     * （见 [takeNext]），所以把一条**正在编辑中**的项置顶会连带卡住队列——这与既有行为一致，
     * 不是本次新引入的。
     *
     * @return 是否真的发生了移动（false = 未找到该项或已在队首）
     */
    @Synchronized
    fun moveToFront(id: Uuid): Boolean {
        val current = state.value.messages
        val index = current.indexOfFirst { it.id == id }
        if (index <= 0) return false
        val reordered = buildList {
            add(current[index])
            current.forEachIndexed { i, message -> if (i != index) add(message) }
        }
        mutableState.value = state.value.copy(messages = reordered)
        return true
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
