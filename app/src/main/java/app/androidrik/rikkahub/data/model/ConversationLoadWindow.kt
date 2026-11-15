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
    val ordered = summaries.sortedBy { it.nodeIndex }
    val path = activeLeafNodeId
        ?.let { leaf -> walkToRoot(leaf, byId) }
        ?: ordered.map { it.id }
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
