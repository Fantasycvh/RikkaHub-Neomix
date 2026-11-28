package app.androidrik.rikkahub.data.model

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.ai.ui.isEmptyUIMessage
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.uuid.Uuid

/**
 * 树形分支（设计稿 `docs/design/in-progress/design-conversation-tree-branching.md`）。
 *
 * **P1 不变量**：树元数据（[MessageNode.parentId] / [Conversation.activeLeafNodeId]）是**权威**——
 * 激活路径 = root→activeLeaf 的树遍历（[pathNodes]）；`messageNodes` 是全量节点集（含 off-path
 * 的兄弟子树），列表序 = 全局创建序（node_index），不再是路径序。
 * 兼容回退：`activeLeafNodeId == null`（旧数据/轻量投影）⇒ 列表即线性链；遍历失败（父链断裂/环）
 * ⇒ 回退全量列表，绝不抛异常拖垮整个会话（与 [MessageNode.safeSelectIndex] 同纪律）。
 */

/**
 * 激活路径节点：root→activeLeaf 遍历。压缩/重生成/渲染等「沿历史前缀」的计算一律经它寻址，
 * 不再直接摸 messageNodes（P0 = 列表本身；P1 翻转为树遍历）。
 *
 * 入口先 [healMissingParents]：内存态可能存在未补父的存量构造（遗留/测试/反序列化）——
 * 否则叶子推进后 pathToLeaf 会在首个 null 父处截断成后缀。稳态（父链完整）时 heal 原样返回，
 * 零开销。**安全前提**：根 = 首个创建节点（分支只在非根节点间发生，见 mergeGenerationMessages
 * 与 editMessage 的根退化规则）⇒ null 父只可能是遗留线性链的未回填行。
 */
fun Conversation.pathNodes(): List<MessageNode> = pathIndex.activePath()

/**
 * [Conversation.pathNodes] 的实例级索引：父表、heal 结果与激活路径各只算一次。
 *
 * 生命周期跟随 [Conversation] 实例；任何变更都经 `copy` 生成新实例，故无需显式失效。
 * 线程安全交给 `lazy(PUBLICATION)`：并发首访可能重复构建，但结果一致且无共享可变状态。
 */
internal class ConversationPathIndex(
    private val rawNodes: List<MessageNode>,
    private val activeLeafNodeId: Uuid?,
    private val summaries: List<NodeSummary> = emptyList(),
) {
    private val healedNodes: List<MessageNode> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        rawNodes.healMissingParents()
    }

    private val path: List<MessageNode>? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val leafId = activeLeafNodeId ?: return@lazy null
        // 稀疏模式：路径由摘要决定（正文可能有缺口），返回已加载的路径节点子序列。
        // 这也避免「父链在未加载边界处断开 ⇒ 回退全量已加载节点」把旁支混进路径。
        if (summaries.isNotEmpty()) {
            val byId = HashMap<Uuid, MessageNode>(healedNodes.size)
            healedNodes.forEach { byId[it.id] = it }
            val known = summaries.mapTo(HashSet()) { it.id }
            // 摘要只覆盖打开时的快照；本轮新生成/写入的尾部节点不在摘要里，按创建序追加。
            val extras = healedNodes.filter { it.id !in known }
            return@lazy (summaryPathIds(summaries, leafId).mapNotNull { byId[it] } + extras)
                .ifEmpty { null }
        }
        val nodes = healedNodes
        val byId = HashMap<Uuid, MessageNode>(nodes.size)
        nodes.forEach { byId[it.id] = it }
        val visited = HashSet<Uuid>()
        val out = ArrayList<MessageNode>()
        var current = byId[leafId] ?: return@lazy null
        while (true) {
            if (!visited.add(current.id)) return@lazy null // 环防御
            out.add(current)
            val parentId = current.parentId ?: break
            current = byId[parentId] ?: return@lazy null // 父链断裂
        }
        out.reverse()
        out
    }

    fun activePath(): List<MessageNode> {
        if (activeLeafNodeId == null) return rawNodes
        return path ?: healedNodes
    }
}

/**
 * 兜底自愈：只给 **parentId == null 的非首行** 补列表前驱，**绝不覆写显式 parentId**。
 *
 * 为什么不是整链派生（旧 asLinearChain 语义）：P1 起 parentId 权威，分支节点的显式父与列表前驱
 * 必然不同，整链派生会把树掰回线性。补 null 是安全的——P0 惰性回填只写「变更行」，存量未变更行
 * 保持 null，而它们的真实父恰好就是列表前驱（P0 列表序 == 路径序；P1 新节点一律显式带父，
 * 且创建序更大、排在存量之后，不会插进存量前缀中间）。**前提**：根 = 首个创建节点、
 * 根无兄弟（分支只在非根节点间发生——根处「空路径」不可表达，编辑/重生成退化为候选追加）；
 * 否则非首行的 null 父将无法与「兄弟根」区分。
 *
 * 加载与保存边界各调一次（保存侧防御内存构造漏父）。
 */
fun List<MessageNode>.healMissingParents(): List<MessageNode> {
    var changed = false
    val healed = mapIndexed { index, node ->
        if (index == 0 || node.parentId != null) {
            node
        } else {
            changed = true
            node.copy(parentId = getOrNull(index - 1)?.id)
        }
    }
    return if (changed) healed else this
}

/**
 * [healMissingParents] 的 Conversation 版：无变化返回 this（保存热路径免分配）。
 */
fun Conversation.withHealedTreeMeta(): Conversation {
    val healed = messageNodes.healMissingParents()
    return if (healed === messageNodes) this else copy(messageNodes = healed)
}

/**
 * root→[leafId] 的路径（树遍历）。
 * 叶子缺失 / 父链断裂 / 环 ⇒ 返回 null——调用方回退全量，绝不抛异常拖垮整个会话。
 */
fun List<MessageNode>.pathToLeaf(leafId: Uuid): List<MessageNode>? {
    val byId = HashMap<Uuid, MessageNode>(size)
    forEach { byId[it.id] = it }
    val path = ArrayList<MessageNode>()
    val visited = HashSet<Uuid>()
    var current = byId[leafId] ?: return null
    while (true) {
        if (!visited.add(current.id)) return null // 环防御
        path.add(current)
        val parentId = current.parentId ?: break
        current = byId[parentId] ?: return null // 父链断裂
    }
    path.reverse()
    return path
}

/** 节点 id → 子节点列表（一次建索引，供剪枝/分支落点复用）。 */
private fun List<MessageNode>.childrenByParent(): Map<Uuid?, List<MessageNode>> =
    groupBy { it.parentId }

/**
 * [rootId] 子树内全部节点 id（含自身）。调用方保证 rootId 存在。
 */
fun List<MessageNode>.subtreeIdsOf(rootId: Uuid): Set<Uuid> {
    val childrenOf = childrenByParent()
    val ids = HashSet<Uuid>()
    val queue = ArrayDeque<Uuid>()
    queue.add(rootId)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!ids.add(id)) continue
        childrenOf[id]?.forEach { queue.add(it.id) }
    }
    return ids
}

/**
 * [rootId] 子树的「最近更新叶子」：分支切换的落点（设计稿 §五.1——无额外状态，
 * 沿后代当前消息的 createdAt 取 max）。子树为空树/环防御失败时返回 null。
 */
fun List<MessageNode>.latestLeafIdOfSubtree(rootId: Uuid): Uuid? {
    val ids = subtreeIdsOf(rootId)
    val childrenOf = childrenByParent()
    val leaves = filter { it.id in ids && childrenOf[it.id].orEmpty().none { child -> child.id in ids } }
    return leaves.maxByOrNull { it.recencyMillis() }?.id
}

/** 全体节点中「最近更新的叶子」（剪枝把激活叶子连根剪掉且无父可退时的落点）。 */
fun List<MessageNode>.latestLeafId(): Uuid? {
    val childrenOf = childrenByParent()
    return filter { childrenOf[it.id].orEmpty().isEmpty() }
        .maxByOrNull { it.recencyMillis() }?.id
}

/** 子树/森林的「最近更新」度量：节点内各消息 createdAt 的最大值（缺省排最后）。 */
private fun MessageNode.recencyMillis(): Long =
    messages.maxOfOrNull {
        it.createdAt.toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds()
    } ?: Long.MIN_VALUE

/**
 * 生成回写的 **id 制合并**（P0 起替代索引对齐回写）。
 *
 * 规则（id + 位置混合，缺一不可；P1 起位置以**激活路径**为坐标系——生成基线取自路径，
 * 位置 i 的语义 =「路径上第 i 个回合」）：
 * 1. message.id 命中已有节点 → 就地替换该候选（selectIndex 不动）——修掉索引制
 *    「id 错位 ⇒ 往位置节点追加副本」的损坏形态（见 [repairCorruptedCandidates]）；
 * 2. id 未知且**路径位置 i 有节点** → 追加为该节点候选（selectIndex = 末位）——
 *    **THIS_ONLY 重生成依赖此条**：新回复落成中间目标节点的候选；
 * 3. id 未知且位置超出路径 → **新建节点**，父 = 上一条消息落点的节点（链式），
 *    并把 activeLeafNodeId 推进到末个新节点——发送/续写/压缩后新回复都经此条
 *    挂到激活叶子之下；scope B 的「兄弟分支」是调用方先把叶子收回到分支点，
 *    让这里自然开出新子树。
 */
fun Conversation.mergeGenerationMessages(messages: List<UIMessage>): Conversation {
    if (messages.isEmpty()) return this
    // 入口先自愈：内存态可能存在未补父的存量构造（遗留/反序列化）——否则新建节点推进
    // activeLeafNodeId 后，pathToLeaf 会在首个 null 父处截断，路径只剩后缀。
    // 无 null（稳态）时 heal 原样返回，零开销。
    val healedNodes = messageNodes.healMissingParents()
    val conversation = if (healedNodes === messageNodes) this else copy(messageNodes = healedNodes)
    val path = conversation.pathNodes()
    val listIndexByNodeId = HashMap<Uuid, Int>()
    val ownerByMessageId = HashMap<Uuid, Int>()
    healedNodes.forEachIndexed { index, node ->
        listIndexByNodeId[node.id] = index
        node.messages.forEach { message -> ownerByMessageId.getOrPut(message.id) { index } }
    }
    var newNodes: MutableList<MessageNode>? = null
    var lastLandedNodeId: Uuid? = null
    var newLeafNodeId: Uuid? = null
    messages.forEachIndexed { index, message ->
        val workingNodes = newNodes ?: healedNodes
        val ownerIndex = ownerByMessageId[message.id]
        val targetListIndex: Int
        val targetNode: MessageNode
        if (ownerIndex != null) {
            // 规则 1：id 命中，就地替换候选（工作副本优先——同轮内可能已改过该节点）
            targetListIndex = ownerIndex
            targetNode = workingNodes[ownerIndex]
        } else {
            val pathNode = path.getOrNull(index)
            if (pathNode != null) {
                // 规则 2：路径位置命中，候选追加
                targetListIndex = listIndexByNodeId.getValue(pathNode.id)
                targetNode = workingNodes[targetListIndex]
            } else {
                // 规则 3：超出路径，新建节点链在上一落点之下
                val parent = lastLandedNodeId ?: path.lastOrNull()?.id
                val newNode = message.toMessageNode().copy(parentId = parent)
                val list = newNodes ?: healedNodes.toMutableList().also { newNodes = it }
                list.add(newNode)
                ownerByMessageId[message.id] = list.lastIndex
                listIndexByNodeId[newNode.id] = list.lastIndex
                lastLandedNodeId = newNode.id
                newLeafNodeId = newNode.id
                return@forEachIndexed
            }
        }
        val candidateIndex = targetNode.messages.indexOfFirst { it.id == message.id }
        if (candidateIndex >= 0 && targetNode.messages[candidateIndex] == message) {
            lastLandedNodeId = targetNode.id
            return@forEachIndexed
        }
        val candidates = targetNode.messages.toMutableList()
        if (candidateIndex >= 0) candidates[candidateIndex] = message else candidates.add(message)
        val newNode = targetNode.copy(
            messages = candidates,
            selectIndex = if (candidateIndex >= 0) targetNode.selectIndex else candidates.lastIndex,
        )
        val list = newNodes ?: healedNodes.toMutableList().also { newNodes = it }
        list[targetListIndex] = newNode
        ownerByMessageId[message.id] = targetListIndex
        lastLandedNodeId = newNode.id
    }
    if (newNodes == null) return conversation
    return conversation.copy(
        messageNodes = newNodes!!,
        activeLeafNodeId = newLeafNodeId ?: activeLeafNodeId,
    )
}

/**
 * 流式增量合并（按 `message.id` 就地更新，跳过全树扫描与索引重排）。
 *
 * 仅用于已建好候选的增量快照；新建候选节点仍须经 [mergeGenerationMessages] 全量落位。
 */
fun Conversation.mergeGenerationDelta(deltaMessages: List<UIMessage>): Conversation {
    if (deltaMessages.isEmpty()) return this
    if (deltaMessages.size == 1) {
        return mergeSingleDeltaMessage(deltaMessages[0])
    }
    var updated = this
    for (message in deltaMessages) {
        updated = updated.mergeSingleDeltaMessage(message)
    }
    return updated
}

private fun Conversation.mergeSingleDeltaMessage(message: UIMessage): Conversation {
    val lastNode = messageNodes.lastOrNull()
    if (lastNode != null) {
        val lastCandidateIdx = lastNode.messages.indexOfFirst { it.id == message.id }
        if (lastCandidateIdx >= 0) {
            if (lastNode.messages[lastCandidateIdx] == message) return this
            val candidates = lastNode.messages.toMutableList()
            candidates[lastCandidateIdx] = message
            val updatedNodes = messageNodes.toMutableList()
            updatedNodes[updatedNodes.lastIndex] = lastNode.copy(messages = candidates)
            return copy(messageNodes = updatedNodes)
        }
    }
    // 非末节点更新（如 THIS_ONLY 中间节点重生成候选已落位后的流式追加）：倒序搜索
    for (nodeIndex in messageNodes.indices.reversed()) {
        val node = messageNodes[nodeIndex]
        val candidateIdx = node.messages.indexOfFirst { it.id == message.id }
        if (candidateIdx >= 0) {
            if (node.messages[candidateIdx] == message) return this
            val candidates = node.messages.toMutableList()
            candidates[candidateIdx] = message
            val updatedNodes = messageNodes.toMutableList()
            updatedNodes[nodeIndex] = node.copy(messages = candidates)
            return copy(messageNodes = updatedNodes)
        }
    }
    // 防御兜底：未命中已知 id 时追加到当前激活叶子之后
    val parent = activeLeafNodeId ?: pathNodes().lastOrNull()?.id
    val newNode = message.toMessageNode().copy(parentId = parent)
    return copy(
        messageNodes = messageNodes + newNode,
        activeLeafNodeId = newNode.id,
    )
}

fun Conversation.applyGenerationChunk(chunk: app.androidrik.rikkahub.data.ai.GenerationChunk.Messages): Conversation =
    if (chunk.isDelta) mergeGenerationDelta(chunk.messages) else mergeGenerationMessages(chunk.messages)

/** [markGeneratingNodeIncomplete] 的 id 寻址版（流式检查点调用点用它）。 */
fun Conversation.markGeneratingNodeIncompleteById(nodeId: Uuid): Conversation {
    val index = messageNodes.indexOfFirst { it.id == nodeId }
    if (index < 0) return this
    return markGeneratingNodeIncomplete(index)
}

/**
 * #2317 治本（B）：摘掉「整轮零输出」的空候选（空回复补救重试后模型仍未产出任何可见内容）。
 *
 * 为什么不留着：`messagesForRequest` 不过滤空消息——空候选会被后续轮次当作上下文原样发出
 * （部分 provider 直接报错）；UI 上它也是一条「隐形回复」（体验层已用占位文案兜底，
 * 这里负责不让它进数据库、不进上下文）。空候选的典型来源：候选先建、内容后流（见
 * [mergeGenerationMessages] 规则 2/3），模型零输出时就留下 selected 的空消息。
 *
 * 守卫（全部满足才摘）：
 * - 只动 [replyMessageId] 落点的节点（本轮生成的落点，id 寻址不依赖列表下标）；
 * - 该候选是 ASSISTANT 且 parts 为空（isEmptyUIMessage——工具调用/非空 reasoning 都不算空）；
 * - 不带 PartialFailure 草稿标记（半截失败草稿有自己的恢复入口与横幅，不能摘）；
 * - 整节点摘除时该节点必须是叶子（无任何子节点），并把 activeLeafNodeId 收回到父节点。
 *
 * 返回 null = 无需改动（调用方沿用原会话）。
 */
fun Conversation.withoutEmptyGenerationCandidate(replyMessageId: Uuid): Conversation? {
    val nodeIndex = messageNodes.indexOfFirst { node -> node.messages.any { it.id == replyMessageId } }
    if (nodeIndex < 0) return null
    val node = messageNodes[nodeIndex]
    val candidateIndex = node.messages.indexOfFirst { it.id == replyMessageId }
    val candidate = node.messages[candidateIndex]
    if (candidate.role != MessageRole.ASSISTANT) return null
    if (!candidate.parts.isEmptyUIMessage()) return null
    if (candidate.annotations.any { it is UIMessageAnnotation.PartialFailure }) return null

    return if (node.messages.size > 1) {
        // 规则 2 落点（重生成/THIS_ONLY）：节点还有别的候选，只摘空候选，选中位就近回落
        val remaining = node.messages.filterIndexed { index, _ -> index != candidateIndex }
        val replacement = node.copy(
            messages = remaining,
            selectIndex = candidateIndex.coerceIn(0, remaining.lastIndex),
        )
        copy(messageNodes = messageNodes.mapIndexed { i, n -> if (i == nodeIndex) replacement else n })
    } else {
        // 规则 3 落点（新节点）：无子节点才整节点摘除（刚生成的叶子天然满足，防御 off-path 子树）
        if (messageNodes.any { it.parentId == node.id }) return null
        copy(
            messageNodes = messageNodes.filterIndexed { i, _ -> i != nodeIndex },
            activeLeafNodeId = if (activeLeafNodeId == node.id) node.parentId else activeLeafNodeId,
        )
    }
}

/**
 * 「包含全部版本」导出顺序（P3 §八）：树**前序遍历**（兄弟子树按创建序），节点内候选按
 * 存储序展开。与旧实现的 `messageNodes.flatMap { it.messages }`（创建序平铺、分支叙事断裂）
 * 不同，输出保持「主干 → 分支 1 整棵 → 分支 2 整棵」的可读结构。含环防御。
 */
fun Conversation.messagesOfAllVersionsInTreeOrder(): List<UIMessage> {
    val nodes = messageNodes.healMissingParents()
    val childrenOf = nodes.groupBy { it.parentId }
    val out = ArrayList<UIMessage>()
    val visited = HashSet<Uuid>()
    fun dfs(parentId: Uuid?) {
        childrenOf[parentId]?.forEach { node ->
            if (!visited.add(node.id)) return@forEach
            out.addAll(node.messages)
            dfs(node.id)
        }
    }
    dfs(null)
    return out
}

/**
 * 树内分叉（P2 §7.3）：在 [messageId] 所在节点处**开新时间线**——新建兄弟节点承载消息副本
 * （id 重生成、内容/注解/模型归属原样），激活叶子切过去；旧节点与其下游原样保留。
 * 根角落：根无兄弟（heal 不变量），退化为候选追加（数据不丢，同 scope B 的根退化规则）。
 * 目标消息不存在时原样返回。
 */
fun Conversation.withBranchForkAt(messageId: Uuid): Conversation {
    val targetNode = messageNodes.firstOrNull { node ->
        node.messages.any { it.id == messageId }
    } ?: return this
    val source = targetNode.messages.find { it.id == messageId } ?: targetNode.currentMessage
    val copied = source.copy(id = Uuid.random())

    if (targetNode.parentId == null) {
        return copy(
            messageNodes = messageNodes.map { node ->
                if (node.id == targetNode.id) {
                    node.copy(messages = node.messages + copied, selectIndex = node.messages.size)
                } else {
                    node
                }
            },
        )
    }

    val branchNode = MessageNode(
        id = Uuid.random(),
        parentId = targetNode.parentId,
        messages = listOf(copied),
        selectIndex = 0,
    )
    return copy(
        messageNodes = messageNodes + branchNode,
        activeLeafNodeId = branchNode.id,
    )
}
