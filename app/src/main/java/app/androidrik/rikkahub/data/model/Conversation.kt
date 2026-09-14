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
        val boundaryIndex = messageNodes.indexOfFirst { it.id.toString() == boundaryId }
        if (boundaryIndex < 0) return null
        // 区域指纹校验：区域内节点或其选中消息被改动（编辑/删除/重新生成/切换分支）时，
        // 摘要已过时 -> 失效回退全量发送（避免 AI 上下文与用户所见不一致）。
        // 指纹为 null（旧数据/未记录）时退化为仅边界存在性校验。
        val fingerprint = checkpoint.areaFingerprint ?: return checkpoint
        return if (computeAreaFingerprint(boundaryIndex) == fingerprint) checkpoint else null
    }

    /**
     * 计算被摘要区域指纹：`nodes[0..boundaryIndex]` 各节点的 id 与其**选中消息** id 的拼接哈希。
     *
     * 覆盖四种改动路径：编辑（节点候选追加 + 选中项变）、删除（节点/候选减少）、
     * 重新生成（新增候选并选中）、切换分支（selectIndex 变）——都反映为「节点 id 序列」
     * 或「选中消息 id」的变化。窗口（边界之后）的改动不影响指纹：那部分本就原样发送。
     */
    private fun computeAreaFingerprint(boundaryIndex: Int): String {
        val builder = StringBuilder()
        for (index in 0..boundaryIndex) {
            val node = messageNodes.getOrNull(index) ?: break
            builder.append(node.id).append(':')
            node.messages.getOrNull(node.selectIndex)?.let { builder.append(it.id) }
            builder.append('|')
        }
        return builder.toString().sha256()
    }

    /**
     * 压缩发送窗口的**可重算**描述：只固化「检查点合成消息 + 边界下标」，不固化消息列表。
     *
     * **为什么不直接给窗口列表**：一个回合内工具调用会让 live messages 增长（新增 assistant
     * 消息及其工具结果）。若沿用回合初的窗口快照，模型在下一步就**看不到自己刚发出的 tool call
     * 的结果** → 会重复调用同一工具（实测 memory_tool create 被重复执行、search_web 同一 query
     * 跑两次）。改为每步对 live messages 重算即可让工具结果自然进入上下文。
     *
     * @param checkpointMessage 检查点合成消息（isSynthetic，不落库）
     * @param boundaryIndex turn-start messages 中被摘要的最后一个节点下标；其后为原文
     */
    data class CompressionWindow(
        val checkpointMessage: UIMessage,
        val boundaryIndex: Int,
    ) {
        /**
         * 应用到给定消息列表：`[检查点] + drop(boundaryIndex + 1)`。
         *
         * 传入的 [messages] 允许是**本回合 live** 的列表——生成只向尾部追加，只要它以前缀形式
         * 保留 turn-start 消息，边界下标的语义就不变，回合内新增的工具调用与结果因而自动带上。
         */
        fun applyTo(messages: List<UIMessage>): List<UIMessage> {
            if (boundaryIndex !in messages.indices) return messages
            return listOf(checkpointMessage) + messages.drop(boundaryIndex + 1)
        }
    }

    /**
     * 当前生效的压缩窗口（可重算）；无有效检查点 / 无边界 / 边界节点已消失时返回 null
     * （= 直接发送 live messages）。
     */
    fun compressionWindow(): CompressionWindow? {
        val checkpoint = activeCompression() ?: return null
        val boundaryId = checkpoint.boundaryNodeId ?: return null
        val boundaryIndex = messageNodes.indexOfFirst { it.id.toString() == boundaryId }
        if (boundaryIndex < 0) return null
        val checkpointMessage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(buildCompactionCheckpointText(checkpoint.content))
            ),
            isSynthetic = true,
        )
        return CompressionWindow(checkpointMessage, boundaryIndex)
    }

    /**
     * 发送窗口（检查点作为新段起点，边界之前的历史不发送）：
     * `[检查点合成消息] + 边界节点之后的全部消息`；无有效检查点或边界找不到时返回全量。
     *
     * **轨迹只追加**：不改动 messageNodes，仅在发送时组装——历史可回溯 + 前缀稳定利于 prompt cache。
     * 检查点合成消息为 isSynthetic（不落库），带前导语与 `<compressed-summary>` 包裹。
     *
     * 注：这是**回合初**的一次性组装（对 turn-start messages 求值）。生成循环内应改用
     * [compressionWindow] + 逐步重算，否则工具结果进不了上下文（见 [CompressionWindow]）。
     */
    fun requestWindowMessages(): List<UIMessage> =
        compressionWindow()?.applyTo(currentMessages) ?: currentMessages

    /**
     * 以 [boundaryNodeIndex] 为边界时应记录的指纹（压缩落库时调用，与 [activeCompression] 校验同源）。
     * 越界返回 null。
     */
    fun areaFingerprintFor(boundaryNodeIndex: Int): String? =
        if (boundaryNodeIndex in messageNodes.indices) computeAreaFingerprint(boundaryNodeIndex) else null

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

    /**
     * 判断某节点位置是否落在压缩边界内（含边界节点本身）。
     *
     * 用途：编辑/删除边界内的历史消息后，摘要与实际历史不一致（摘要反映的是压缩时刻的内容），
     * 调用方据此丢弃检查点回退全量发送，避免 AI 的上下文与用户所见对不上。
     * 无有效检查点时恒为 false。
     */
    fun isWithinCompressionBoundary(nodeIndex: Int): Boolean {
        val checkpoint = activeCompression() ?: return false
        val boundaryId = checkpoint.boundaryNodeId ?: return false
        val boundaryIndex = messageNodes.indexOfFirst { it.id.toString() == boundaryId }
        return boundaryIndex >= 0 && nodeIndex <= boundaryIndex
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


/**
 * 修复「检查点合成消息被误当作回写基线」造成的节点候选损坏（压缩功能早期实现的 bug）。
 *
 * 症状：`generateText` 曾把「发送窗口」（含检查点合成消息，且已 drop 掉边界前节点）当作回写
 * 基线，`updateCurrentMessages` 按索引把窗口消息逐条**追加到各节点候选末尾** —— 于是每个节点
 * 多出若干条「后文消息的副本」，`selectIndex` 也被改指向副本，界面表现为：展开压缩历史时只多出
 * 一条消息、部分消息在展开/收起两个状态下重复出现。
 *
 * 修复策略（保守、幂等、非破坏性判断）：
 * - 同一 message id 出现在**多个节点** = 追加副本（正常的分支/编辑历史各自 id 不同，不会跨节点重复）
 * - 对这类受损节点只保留**首个候选**（追加永远发生在末尾，故首个候选必为该节点原始消息）
 * - 未被污染的节点（无跨节点重复）完全不动，编辑/重生成历史得以保留
 *
 * 注：根因已在 `GenerationHandler`（发送上下文与回写基线分离）修复，本函数只用于自愈
 * 已受影响的既有数据；加载时执行，随下次保存落库。
 */
internal fun List<MessageNode>.repairCorruptedCandidates(): List<MessageNode> {
    val idCounts = HashMap<Uuid, Int>()
    forEach { node ->
        node.messages.forEach { message -> idCounts[message.id] = (idCounts[message.id] ?: 0) + 1 }
    }
    return map { node ->
        if (node.messages.size <= 1) return@map node
        val hasCrossNodeDuplicate = node.messages.any { (idCounts[it.id] ?: 0) > 1 }
        if (!hasCrossNodeDuplicate) {
            node
        } else {
            node.copy(messages = listOf(node.messages.first()), selectIndex = 0)
        }
    }
}

/** 指纹用 SHA-256（仅取前 32 hex 字符，足够区分且节省存储） */
private fun String.sha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(this.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(32)
}
