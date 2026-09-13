package app.androidrik.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.util.InstantSerializer
import app.androidrik.rikkahub.data.ai.prompts.buildCompactionCheckpointText
import app.androidrik.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    /**
     * 压缩检查点列表（末行 = 最新边界 = active）。从 compression_checkpoint 表按需加载注入，
     * 不存入 conversations 表（与 messageNodes 同模式——独立表管理，避免污染元数据列）。
     * 默认空 = 未压缩。设计见 [CompressionSummary] 与 `docs/design-auto-compaction.md` §4.4。
     */
    val compressionSummaries: List<CompressionSummary> = emptyList(),
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    // ---- 压缩检查点（设计见 docs/design-auto-compaction.md §4.1 三边界对齐）----

    /**
     * 当前生效的压缩检查点（末行 = 最新边界 = active）。
     *
     * **失效判定**：boundaryNodeId 指向的节点不存在时（被 regenerate 截断 / 分支切换删除 /
     * 会话重新加载后节点消失）检查点失效，返回 null 回退全量。boundaryNodeId 为空视为永不失效。
     */
    fun activeCompression(): CompressionSummary? {
        val checkpoint = compressionSummaries.lastOrNull() ?: return null
        val boundaryId = checkpoint.boundaryNodeId ?: return checkpoint
        return if (messageNodes.any { it.id.toString() == boundaryId }) checkpoint else null
    }

    /**
     * 发送窗口（检查点作为新段起点，边界之前的历史不发送）：
     * `[检查点合成消息] + 边界节点之后的全部消息`；无有效检查点或边界找不到时返回全量。
     *
     * **轨迹只追加**：不改动 messageNodes，仅在发送时组装——历史可回溯 + 前缀稳定利于 prompt cache。
     * 检查点合成消息为 isSynthetic（不落库），带前导语与 `<compressed-summary>` 包裹。
     */
    fun requestWindowMessages(): List<UIMessage> {
        val checkpoint = activeCompression() ?: return currentMessages
        val boundaryId = checkpoint.boundaryNodeId ?: return currentMessages
        val boundaryIndex = messageNodes.indexOfFirst { it.id.toString() == boundaryId }
        if (boundaryIndex < 0) return currentMessages
        val checkpointMessage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(buildCompactionCheckpointText(checkpoint.content))
            ),
            isSynthetic = true,
        )
        return listOf(checkpointMessage) +
            messageNodes.drop(boundaryIndex + 1).map { it.currentMessage }
    }

    /**
     * 边界之后的节点（存储侧视图，UI 折叠与压缩输入都以它为准）；无有效检查点时返回全量。
     */
    fun windowNodes(): List<MessageNode> {
        val checkpoint = activeCompression() ?: return messageNodes
        val boundaryId = checkpoint.boundaryNodeId ?: return messageNodes
        val boundaryIndex = messageNodes.indexOfFirst { it.id.toString() == boundaryId }
        if (boundaryIndex < 0) return messageNodes
        return messageNodes.drop(boundaryIndex + 1)
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 * 深度上限：恶意/异常深嵌套工具结果曾触发 StackOverflowError。
 */
private const val MAX_PARTS_DEPTH = 16

private fun List<UIMessagePart>.collectAllParts(depth: Int = 0): List<UIMessagePart> =
    if (depth >= MAX_PARTS_DEPTH) {
        this
    } else {
        this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts(depth + 1) }
    }

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}

/** 递归提取 parts（含工具输出嵌套）中引用的本地文件 URL——消息队列附件清理用它判断引用。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }
            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}
