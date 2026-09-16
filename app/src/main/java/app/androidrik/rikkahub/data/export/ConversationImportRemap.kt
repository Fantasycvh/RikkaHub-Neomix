package app.androidrik.rikkahub.data.export

import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * 导入一个导出的会话时，把它的 id **全部换新**并重映射内部引用。
 *
 * 为什么必须换：导入语义是「**新建**而非覆盖」（`docs/design-conversation-import-export.md` 决策 1）——
 * 沿用原 id 会覆盖本机同 id 会话（数据丢失）。换新 id 后同一文件可反复导入（每次得到新副本）。
 *
 * 换新的对象（依据见设计稿 §三）：
 * - `Conversation.id`（主键）、`MessageNode.id`（主键，且 FTS 表存 `node_id`）、
 *   `UIMessage.id`（**FTS 表也存 `message_id`**，不换会在两次导入后产生重复）
 * - **`toolCallId` 等批内引用不换**：它们在节点/消息内自洽，整批一起搬即保持自洽
 *
 * 同时按新节点序列**重映射并重算压缩检查点**（方案 A，见 [remapCompressionSummaries]）。
 *
 * @param targetAssistantId 目标助手（由用户选择；导出文件里的 `assistantId` 一律丢弃——
 *   跨设备必然失效）。
 * @return 可直接落库的新会话。
 */
fun Conversation.remapForImport(targetAssistantId: Uuid): Conversation {
    val nodeIdMap = mutableMapOf<Uuid, Uuid>()
    val newNodeNodes = messageNodes.map { node ->
        val newId = Uuid.random()
        nodeIdMap[node.id] = newId
        node.copy(
            id = newId,
            // 消息 id 也要换（FTS 存 message_id）；toolCallId 等批内引用保持原样即自洽
            messages = node.messages.map { it.copy(id = Uuid.random()) },
        )
    }
    val remapped = copy(
        id = Uuid.random(),
        assistantId = targetAssistantId,
        messageNodes = newNodeNodes,
    )
    return remapped.copy(
        compressionSummaries = remapCompressionSummaries(
            source = this,
            target = remapped,
            nodeIdMap = nodeIdMap,
        ),
    )
}

/**
 * 压缩检查点迁移（方案 A）：把检查点的 `boundaryNodeId` 换到新节点上，并按**新节点序列重算
 * 区域指纹**，使检查点在导入后**立即有效**（`currentMessages` 仍是「检查点 + 边界后原文」）。
 *
 * 为什么不"不带检查点"：那样长会话导入后发送全量历史，而唯一兜底
 * `limitContext(assistant.contextMessageLimit)` 的**默认值是 0 = 不限制**
 * （`limitContext` 在 `limit <= 0` 时直接返回原列表）→ 静默的上下文膨胀。
 * 见设计稿 §5.1/§5.2。
 *
 * 各字段处理（设计稿 §5.1 七点）：
 * 1. `conversationId` → 由**落库方**改为新会话 id（这里返回的 summary 仍带旧 id，落库时以新为准）；
 * 2. `boundaryNodeId` → 经 [nodeIdMap] 换成新节点 id（**漏了这步检查点会立即失效**）；
 * 3. `areaFingerprint` → 在**新节点序列**上重算（用 [Conversation.areaFingerprintFor]）；
 * 4. `messageCount` / `content` / `createdAt` → 原样（`messageCount` 仅供展示与下次压缩累计）。
 *
 * 边界节点缺失（导出文件与检查点不一致）时**丢弃该检查点**——留着会是无效行。
 */
private fun remapCompressionSummaries(
    source: Conversation,
    target: Conversation,
    nodeIdMap: Map<Uuid, Uuid>,
): List<CompressionSummary> {
    if (source.compressionSummaries.isEmpty()) return emptyList()
    val boundaryIndexByNewId = target.messageNodes.withIndex().associate { (index, node) -> node.id to index }

    return source.compressionSummaries.mapNotNull { summary ->
        val oldBoundary = summary.boundaryNodeId ?: return@mapNotNull null
        val oldBoundaryUuid = runCatching { Uuid.parse(oldBoundary) }.getOrNull()
            ?: return@mapNotNull null
        val newBoundaryUuid = nodeIdMap[oldBoundaryUuid] ?: return@mapNotNull null
        val newBoundaryIndex = boundaryIndexByNewId[newBoundaryUuid] ?: return@mapNotNull null

        summary.copy(
            conversationId = target.id,
            boundaryNodeId = newBoundaryUuid.toString(),
            // 在新节点序列上重算指纹（旧指纹是在旧 id 上算的，必然失配）
            areaFingerprint = target.areaFingerprintFor(newBoundaryIndex),
        )
    }
}
