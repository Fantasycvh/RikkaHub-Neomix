package app.androidrik.rikkahub.data.model

import app.androidrik.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * 树形分支 P0：id/路径寻址地基（设计稿 `docs/design/in-progress/design-conversation-tree-branching.md`）。
 *
 * **P0 不变量：内存态 messageNodes 列表序 == 激活路径序**（尚不创建分支）。
 * 树元数据（[MessageNode.parentId] / [Conversation.activeLeafNodeId]）在 P0 是**派生量**——
 * 由落库边界（[withLinearTreeMeta]）按列表序填充，读路径不依赖它；P1 引入分支创建后翻转为
 * 权威（届时 [pathNodes] 切换为树遍历，本文件的纯函数已先由单测钉住）。
 */

/**
 * 激活路径节点。P0 = 列表本身（列表序 == 路径序，零开销）；
 * P1 切换为 root→activeLeaf 的树遍历（[pathToLeaf]，失败回退全量）。
 * 压缩/重生成等「沿历史前缀」的计算一律经它寻址，不再直接摸 messageNodes。
 */
fun Conversation.pathNodes(): List<MessageNode> = messageNodes

/** 按列表序填充 parentId（根 = null）。无变化时返回原列表（保存热路径免分配）。 */
fun List<MessageNode>.asLinearChain(): List<MessageNode> {
    var changed = false
    val chained = mapIndexed { index, node ->
        val parentId = getOrNull(index - 1)?.id
        if (node.parentId == parentId) node else {
            changed = true
            node.copy(parentId = parentId)
        }
    }
    return if (changed) chained else this
}

/** P0 落库边界：按列表序派生树元数据（链 + 叶子 = 末节点）。无变化返回 this。 */
fun Conversation.withLinearTreeMeta(): Conversation {
    val chained = messageNodes.asLinearChain()
    val leafId = chained.lastOrNull()?.id
    return if (chained === messageNodes && activeLeafNodeId == leafId) this
    else copy(messageNodes = chained, activeLeafNodeId = leafId)
}

/**
 * root→[leafId] 的路径（树遍历；P1 的权威读路径，P0 先单测钉住）。
 * 叶子缺失 / 父链断裂 / 环 ⇒ 返回 null——调用方回退全量，绝不抛异常拖垮整个会话
 * （与 [MessageNode.safeSelectIndex] 同一条「宁可选中非预期也不打不开」纪律）。
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

/**
 * 生成回写的 **id 制合并**（P0 起替代 [Conversation.updateCurrentMessages] 的索引对齐；
 * 旧函数保留作等价性测试基准，P1 移除）。
 *
 * 规则（id + 位置混合，缺一不可）：
 * 1. message.id 命中已有节点 → 就地替换该候选（selectIndex 不动）——修掉索引制
 *    「id 错位 ⇒ 往位置节点追加副本」的损坏形态（见 [repairCorruptedCandidates]）；
 * 2. id 未知**且位置 i 有节点** → 追加为该节点候选（selectIndex = 末位）——
 *    **THIS_ONLY 重生成依赖此条**：新回复需要落成中间目标节点的候选，纯 id 制尾部追加会破坏它；
 * 3. id 未知且位置超出尾部 → 追加新节点。
 *
 * 常规生成（基线 id 与位置一致）下与索引制逐字节等价；唯一分叉是规则 1 的跨节点 id
 * 情形（索引制 = bug，id 制 = 正确）。
 */
fun Conversation.mergeGenerationMessages(messages: List<UIMessage>): Conversation {
    if (messages.isEmpty()) return this
    val ownerByMessageId = HashMap<Uuid, Int>()
    messageNodes.forEachIndexed { index, node ->
        node.messages.forEach { message -> ownerByMessageId.getOrPut(message.id) { index } }
    }
    var newNodes: MutableList<MessageNode>? = null
    messages.forEachIndexed { index, message ->
        val targetIndex = ownerByMessageId[message.id] ?: index
        val node = messageNodes.getOrNull(targetIndex)
        val newNode = if (node == null) {
            message.toMessageNode()
        } else {
            val candidateIndex = node.messages.indexOfFirst { it.id == message.id }
            if (candidateIndex >= 0 && node.messages[candidateIndex] == message) {
                return@forEachIndexed
            }
            val candidates = node.messages.toMutableList()
            if (candidateIndex >= 0) candidates[candidateIndex] = message else candidates.add(message)
            node.copy(
                messages = candidates,
                selectIndex = if (candidateIndex >= 0) node.selectIndex else candidates.lastIndex,
            )
        }
        val updated = newNodes ?: messageNodes.toMutableList().also { newNodes = it }
        if (targetIndex > updated.lastIndex) {
            updated.add(newNode)
            ownerByMessageId[message.id] = updated.lastIndex
        } else {
            updated[targetIndex] = newNode
            ownerByMessageId[message.id] = targetIndex
        }
    }
    return newNodes?.let { copy(messageNodes = it) } ?: this
}

/** [markGeneratingNodeIncomplete] 的 id 寻址版（流式检查点调用点用它；索引版 P1 退役）。 */
fun Conversation.markGeneratingNodeIncompleteById(nodeId: Uuid): Conversation {
    val index = messageNodes.indexOfFirst { it.id == nodeId }
    if (index < 0) return this
    return markGeneratingNodeIncomplete(index)
}