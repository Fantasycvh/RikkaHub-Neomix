package app.androidrik.rikkahub.data.model

import kotlin.uuid.Uuid

/**
 * 节点摘要（不含 messages 正文）。稀疏加载的路径与预算都只依赖它。
 * 见 `docs/design/in-progress/design-conversation-lazy-load.md` §三.1。
 */
data class NodeSummary(
    val id: Uuid,
    val parentId: Uuid?,
    val nodeIndex: Int,
    /** 选中候选下标。指纹摘要化（阶段 5）靠它覆盖分支切换/编辑/重新生成。 */
    val selectIndex: Int = 0,
    val messageBytes: Long,
)

/**
 * 一次稀疏加载的窗口计划。
 *
 * @property pathIds root→activeLeaf 的完整路径（摘要级，不含正文）
 * @property loadedIds 需要读取正文的节点 id（创建序升序）
 * @property omittedCount 路径上未加载正文的节点数（UI 显示「加载更早」）
 */
data class ConversationLoadWindow(
    val pathIds: List<Uuid>,
    val loadedIds: List<Uuid>,
    val omittedCount: Int,
) {
    val hasOlder: Boolean get() = omittedCount > 0
}

/**
 * 按「压缩边界 + 尾部预算」计算需要加载正文的节点集合。
 *
 * 规则：
 * 1. 有有效边界节点 ⇒ 只加载边界之后的尾巴（与 `compressionWindow()` 对齐）；
 * 2. 从路径尾部向前累计，超过 [maxNodes] 或 [maxBytes] 即停止，至少保留最后一条；
 * 3. 叶子缺失 / 父链断裂 / 环 ⇒ 回退「按创建序的全量顺序」（与 [pathNodes] 的回退一致）。
 */
fun buildConversationLoadWindow(
    summaries: List<NodeSummary>,
    activeLeafNodeId: Uuid?,
    boundaryNodeId: Uuid? = null,
    maxNodes: Int = DEFAULT_MAX_NODES,
    maxBytes: Long = DEFAULT_MAX_BYTES,
): ConversationLoadWindow {
    if (summaries.isEmpty()) return ConversationLoadWindow(emptyList(), emptyList(), 0)
    val byId = HashMap<Uuid, NodeSummary>(summaries.size)
    summaries.forEach { byId[it.id] = it }
    val path = summaryPathIds(summaries, activeLeafNodeId, byId)
    val boundaryIndex = boundaryNodeId?.let { id -> path.indexOf(id) } ?: -1
    val tailStart = (boundaryIndex + 1).coerceAtLeast(0)
    val tail = path.subList(tailStart, path.size)
    val loaded = ArrayList<Uuid>()
    var bytes = 0L
    for (index in tail.indices.reversed()) {
        val nodeId = tail[index]
        val nodeBytes = byId[nodeId]?.messageBytes ?: 0L
        val exceeds = loaded.isNotEmpty() &&
            (loaded.size >= maxNodes || bytes + nodeBytes > maxBytes)
        if (exceeds) break
        loaded.add(nodeId)
        bytes += nodeBytes
    }
    loaded.reverse()
    return ConversationLoadWindow(
        pathIds = path,
        loadedIds = loaded,
        omittedCount = path.size - loaded.size,
    )
}

/**
 * root→activeLeaf 的摘要路径（只有 id，不含正文）。
 *
 * 稀疏模式下这是**唯一权威路径**：`Conversation.pathNodes()` 只能看到已加载正文的节点，
 * 边界在路径上的判定必须用摘要，否则压缩检查点会因边界正文未加载而被误判失效。
 * 叶子缺失 / 父链断裂 / 环 ⇒ 回退「创建序全量」（与 [Conversation.pathNodes] 的回退一致）。
 */
fun summaryPathIds(
    summaries: List<NodeSummary>,
    activeLeafNodeId: Uuid?,
    byId: Map<Uuid, NodeSummary> = HashMap<Uuid, NodeSummary>(summaries.size).also { index ->
        summaries.forEach { index[it.id] = it }
    },
): List<Uuid> {
    if (summaries.isEmpty()) return emptyList()
    return activeLeafNodeId
        ?.let { leaf -> walkToRoot(leaf, byId) }
        ?: summaries.sortedBy { it.nodeIndex }.map { it.id }
}

private fun walkToRoot(leafId: Uuid, byId: Map<Uuid, NodeSummary>): List<Uuid>? {
    val visited = HashSet<Uuid>()
    val out = ArrayList<Uuid>()
    var current = byId[leafId] ?: return null
    while (true) {
        if (!visited.add(current.id)) return null
        out.add(current.id)
        val parentId = current.parentId ?: break
        current = byId[parentId] ?: return null
    }
    out.reverse()
    return out
}

const val DEFAULT_MAX_NODES = 300
const val DEFAULT_MAX_BYTES = 1_000_000L

/**
 * **压缩前缀物理清理**的删除集合（阶段 6，纯函数）：边界节点**子树之外**的全部节点。
 *
 * 边界本身与其后代保留——边界是检查点的锚（路径/指纹/折叠行都靠它），后代是未压缩原文。
 * 父链断裂/环的节点永远进不了子树，按「可删」处理；调用方还需叠加收藏/在途生成等守卫。
 */
fun prefixPruneCandidates(summaries: List<NodeSummary>, boundaryNodeId: Uuid): Set<Uuid> {
    if (summaries.isEmpty()) return emptySet()
    val children = summaries.groupBy { it.parentId }
    val keep = HashSet<Uuid>()
    val stack = ArrayDeque<Uuid>()
    stack.addLast(boundaryNodeId)
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (!keep.add(id)) continue
        children[id]?.forEach { stack.addLast(it.id) }
    }
    return summaries.asSequence().map { it.id }.filter { it !in keep }.toSet()
}
