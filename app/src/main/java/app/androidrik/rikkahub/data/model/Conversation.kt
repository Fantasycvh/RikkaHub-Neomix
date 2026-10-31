package app.androidrik.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.finishReasoning
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.util.InstantSerializer
import app.androidrik.rikkahub.data.ai.prompts.buildCompactionCheckpointText
import app.androidrik.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import java.time.Instant
import kotlin.uuid.Uuid

/** Only selected messages count, matching the message-queue approval gate. */
internal val Conversation.pendingToolCalls: List<UIMessagePart.Tool>
    get() = currentMessages.flatMap { message ->
        message.parts.filterIsInstance<UIMessagePart.Tool>().filter { it.isPending }
    }

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    /**
     * 压缩检查点列表（末行 = 最新边界 = active）。从 compression_checkpoint 表按需加载注入，
     * 不存入 conversations 表（与 messageNodes 同模式——独立表管理，避免污染元数据列）。
     * 默认空 = 未压缩。设计见 [CompressionSummary] 与 `docs/design/design-auto-compaction.md` §4.4。
     */
    val compressionSummaries: List<CompressionSummary> = emptyList(),
    val upstreamConfig: UpstreamConversationConfig? = null,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    /**
     * **末尾消息的 `createdAt`**（列表行内「最近活跃」显示用；见
     * `docs/design/design-conversation-manual-order.md` §一.3）。
     *
     * 与 [updateAt] 的区别：后者会被改标题/置顶/重新生成等**非消息动作**刷新，
     * 用它当"最近活跃"会撒谎。旧数据为 null，UI 回退 [updateAt]。
     */
    @Serializable(with = InstantSerializer::class)
    val lastMessageAt: Instant? = null,
    val customSystemPrompt: String? = null,
    /**
     * **会话长期目标**（定时任务 P2，purrcat A4.2；见 `docs/design/design-scheduled-ai-tasks.md` §四）。
     *
     * 目标是**会话的属性**而非任务表字段——多个任务可以共享同一份；`withGoal = true` 的定时任务
     * 注入时把它拼在 prompt 头部，使多次自唤醒围绕同一目标而非孤立 prompt
     * （这是「主动 agent」与「定时 prompt」的分界）。用户在聊天页编辑；
     * 持久化在 conversations 表的 `goal` 列（DB v51→52，`""` = 未设置）。
     */
    val goal: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    /**
     * **本对话不使用记忆**（隐身对话；见 `docs/design/design-memory-discipline.md` §4.4）。
     *
     * 语义（V2 批次）：**不注入记忆（含清单层）+ 不提供记忆工具**——开关要真能拦住「这次对话
     * 碰我的记忆库」，否则工具仍可逐条读回来、开关等于装饰。「不整理」留给 V3 的做梦框架。
     *
     * 默认 false = 现行为；缺字段反序列化也是 false ⇒ 无需数据回填。
     */
    val memoryDisabled: Boolean = false,
    /**
     * **冻结的锚点集合**（L1）：会话开始时一次 + 用户手动「固化记忆」时重写。
     *
     * 存的是**锚点 id（逗号分隔）**，不是锚点文本 —— 文本按当前内容实时渲染。理由：记忆库
     * 有两条**自动**删除路径（做梦整理 `MemoryConsolidationGenerationAgent`、200 条上限淘汰
     * `MemoryRepository.enforceMaxCount`），存文本会让「已经删掉的记忆」永远留在 system 里
     * （既忘不掉也改不动），而这两条路径都不可能去逐个刷新会话。存 id 则「删除即消失、
     * 改写即生效」，而 system 的**字节稳定性只由集合决定**：集合不变时逐字节不变，
     * 前缀缓存照常命中。
     *
     * 三态：`null` = 未冻结（实时锚点，存量会话的默认）；`""` = 冻结且当时无锚点 ⇒ 不产生块。
     * 见 `docs/design/design-memory-injection-tiering.md` §5。
     */
    val frozenAnchors: String? = null,
    @Transient
    val newConversation: Boolean = false
) {
    /**
     * [frozenAnchors] 的解码视图：`null` = 未冻结，空集 = 冻结且当时无锚点。
     *
     * 计算属性（无 backing field）⇒ 不参与序列化。
     */
    val frozenAnchorIds: Set<String>?
        get() = frozenAnchors?.let { raw ->
            if (raw.isEmpty()) emptySet() else raw.split(',').filter { it.isNotBlank() }.toSet()
        }

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
            return messageNodes.map { node -> node.messages[node.safeSelectIndex] }
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

    // ---- 压缩检查点（设计见 docs/design/design-auto-compaction.md §4.1 三边界对齐）----

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
     * 压缩发送窗口的**可重算**描述：只固化「检查点消息 + 边界下标」，不固化消息列表。
     *
     * **为什么不直接给窗口列表**：一个回合内工具调用会让 live messages 增长（新增 assistant
     * 消息及其工具结果）。若沿用回合初的窗口快照，模型在下一步就**看不到自己刚发出的 tool call
     * 的结果** → 会重复调用同一工具（实测 memory_tool create 被重复执行、search_web 同一 query
     * 跑两次）。改为每步对 live messages 重算即可让工具结果自然进入上下文。
     *
     * @param checkpointMessage native 合成消息，或 upstream 原始已持久化检查点（不改正文/角色/标记）
     * @param boundaryIndex native 最后排除的节点，或 upstream 原始检查点所在节点；其后为原文
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
        val importedIndex = messageNodes.indexOfLast { node ->
            node.messages.getOrNull(node.selectIndex)?.isContextCheckpoint == true
        }
        val checkpoint = activeCompression()
        val boundaryIndex = checkpoint?.boundaryNodeId?.let { boundaryId ->
            messageNodes.indexOfFirst { it.id.toString() == boundaryId }
        } ?: -1
        // Native boundaries name the last excluded node; an upstream boundary is the
        // original retained checkpoint itself. Only a strictly newer native boundary wins.
        if (importedIndex >= 0 && importedIndex >= boundaryIndex) {
            return CompressionWindow(messageNodes[importedIndex].currentMessage, importedIndex)
        }
        if (checkpoint == null || boundaryIndex < 0) return null
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
        val window = compressionWindow() ?: return messageNodes
        return messageNodes.drop(window.boundaryIndex + 1)
    }

    /** Display-only projection; imported checkpoints remain authoritative persisted message nodes. */
    fun displayCompression(): CompressionSummary? {
        val window = compressionWindow() ?: return null
        if (!window.checkpointMessage.isContextCheckpoint) return activeCompression()
        val message = window.checkpointMessage
        return CompressionSummary(
            conversationId = id,
            content = message.toText(),
            messageCount = messageNodes.take(window.boundaryIndex)
                .count { !it.currentMessage.isContextCheckpoint },
            boundaryNodeId = messageNodes[window.boundaryIndex].id.toString(),
            // 检查点自身的生成时间：ContextLength 的 usage 陈旧判定与原生检查点同口径。
            createdAt = java.time.Instant.ofEpochMilli(
                message.createdAt.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
            ),
        )
    }

    /**
     * 判断某节点位置是否落在压缩边界内（含边界节点本身）。
     *
     * 用途：编辑/删除边界内的历史消息后，摘要与实际历史不一致（摘要反映的是压缩时刻的内容），
     * 调用方据此丢弃检查点回退全量发送，避免 AI 的上下文与用户所见对不上。
     * 无有效检查点时恒为 false。
     */
    fun isWithinCompressionBoundary(nodeIndex: Int): Boolean {
        val boundaryIndex = compressionWindow()?.boundaryIndex ?: return false
        return nodeIndex <= boundaryIndex
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
    /**
     * 越界防御的选中下标。`select_index` 落库无约束，历史脏数据（崩溃写入/旧版本 bug/导入）
     * 可能让它越界——直接抛异常会让 [Conversation.currentMessages]、token 估算、压缩等
     * 所有读路径一起失败，且用户无法自救（修复入口本身要先读会话）。夹取到合法区间，
     * 宁可选中非预期分支也不要整个会话打不开。
     */
    val safeSelectIndex: Int
        get() = if (messages.isEmpty()) 0 else selectIndex.coerceIn(0, messages.lastIndex)

    val currentMessage get() = if (messages.isEmpty()) {
        throw IllegalStateException("MessageNode has no messages: selectIndex=$selectIndex")
    } else {
        messages[safeSelectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

/**
 * 把**流式中途**的节点标记为「未完成」，用于落库半截内容（流式检查点 / 用户停止）。
 *
 * 解决两个只有「进程被杀后重开」才会暴露的问题（曾实测：强杀后重开，半截内容在，但既没有
 * 「不完整」提示也没法接着写）：
 * 1. `finishIncomplete` 原本只在流**正常收尾**时写入，进程被杀时那一行永远不会执行 ⇒
 *    恢复出来的半截消息看起来像一条正常完成的回复。标记后横幅会提示并提供「继续生成」。
 * 2. 未结束的推理块 `finishedAt == null` ⇒ 思考卡片按「进行中」渲染（计时器每 50ms 自增），
 *    在一条已死的消息上永远走表。用 [finishReasoning] 收尾。
 *
 * **只标记当前选中的候选**（`selectIndex`）：同一节点的其它候选是历史版本，可能本已正常完成，
 * 一并标记会伪造「它也被截断了」。
 *
 * 调用方约定：只用于**写库的那份副本**。内存态不要带上这个标记——内存态由流收尾的
 * `markLastFinish` 写真实结束状态，若内存也标脏，正常结束前 UI 就会弹横幅。
 */
fun Conversation.markGeneratingNodeIncomplete(nodeIndex: Int): Conversation {
    val node = messageNodes.getOrNull(nodeIndex) ?: return this
    val activeIndex = node.selectIndex
    val active = node.messages.getOrNull(activeIndex) ?: return this
    if (active.role != MessageRole.ASSISTANT) return this
    val markedNode = node.copy(
        messages = node.messages.toMutableList().also {
            it[activeIndex] = active.finishReasoning().copy(finishIncomplete = true)
        }
    )
    return copy(messageNodes = messageNodes.toMutableList().also { it[nodeIndex] = markedNode })
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(        messages = listOf(this),
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
