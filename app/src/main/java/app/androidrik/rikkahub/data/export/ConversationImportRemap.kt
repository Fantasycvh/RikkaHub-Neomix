package app.androidrik.rikkahub.data.export

import app.androidrik.ai.ui.UIMessageAnnotation
import app.androidrik.rikkahub.data.model.CompressionSummary
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * 导入一个导出的会话时，把它的 id **全部换新**并重映射内部引用。
 *
 * 为什么必须换：导入语义是「**新建**而非覆盖」（`docs/design/design-conversation-import-export.md` 决策 1）——
 * 沿用原 id 会覆盖本机同 id 会话（数据丢失）。换新 id 后同一文件可反复导入（每次得到新副本）。
 *
 * 换新的对象（依据见设计稿 §三）：
 * - `Conversation.id`（主键）、`MessageNode.id`（主键，且 FTS 表存 `node_id`）、
 *   `UIMessage.id`（**FTS 表也存 `message_id`**，不换会在两次导入后产生重复）
 * - **`toolCallId` 等批内引用不换**：它们在节点/消息内自洽，整批一起搬即保持自洽
 *
 * 同时按新节点序列**重映射并重算压缩检查点**（方案 A，见 [remapCompressionSummaries]）。
 *
 * **源环境引用一律清空**（`folderId` / `workspaceCwd`）——它们指向**源助手/源设备**的资源，
 * 目标助手不一定存在对应物：
 * - `folderId`：文件夹是**按助手归属**的（`FolderEntity.assistantId`），而抽屉默认视图查的是
 *   `folder_id = ''`（`ConversationDAO.getUnfiledConversationsOfAssistantPaging`）。悬空的
 *   `folderId` 会让会话**在默认视图看不到、文件夹 chip 也不存在** → 抽屉里彻底消失（只有
 *   「历史」页还找得到）。清空 = 落入「未归类」，即导入新会话的自然归属。
 * - `workspaceCwd`：绝对路径在源工作区内。目标助手的 workspace 里若无此路径，
 *   `WorkspaceManager.executeCommand` 的 `require(workingDir.exists())` 会抛错。
 *
 * `modeInjectionIds` / `lorebookIds` 则**不必处理**：注入是按 id 过滤匹配的
 * （`modeInjections.filter { it.enabled && effectiveIds.contains(it.id) }`），悬空 id 只是匹配不到，
 * 无副作用（与助手侧 `PreferencesStore` 的清理同效）。
 *
 * @param targetAssistantId 目标助手（由用户选择；导出文件里的 `assistantId` 一律丢弃——
 *   跨设备必然失效）。
 * @return 可直接落库的新会话。
 */
fun Conversation.remapForImport(targetAssistantId: Uuid): Conversation {
    val nodeIdMap = mutableMapOf<Uuid, Uuid>()
    // #1906：引用指向 messageId，而这里**每个消息 id 都要换新** ⇒ 必须先建全量映射
    // 再改写引用。两遍扫描是必要的：引用可以指向「后面才出现」的消息（前向引用），
    // 单遍边生成边查表会漏掉这一类。映射覆盖**全部候选**消息（不只是 selectIndex 选中的），
    // 因为被引用的消息可能正躺在某个未选中的分支里。
    val messageIdMap = mutableMapOf<Uuid, Uuid>()
    messageNodes.forEach { node ->
        node.messages.forEach { message ->
            messageIdMap[message.id] = Uuid.random()
        }
    }
    val newNodeNodes = messageNodes.map { node ->
        val newId = Uuid.random()
        nodeIdMap[node.id] = newId
        node.copy(
            id = newId,
            // 消息 id 也要换（FTS 存 message_id）；toolCallId 等批内引用保持原样即自洽
            messages = node.messages.map { message ->
                message.copy(
                    id = messageIdMap.getValue(message.id),
                    annotations = message.annotations.remapQuotes(messageIdMap),
                )
            },
        )
    }
    val remapped = copy(
        id = Uuid.random(),
        assistantId = targetAssistantId,
        messageNodes = newNodeNodes,
        // 源环境的归属/路径不继承（见上：悬空的 folderId 会让会话在抽屉里消失）
        folderId = null,
        workspaceCwd = null,
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
 * #1906：把引用注解里的 `messageId` 换到本次导入的新 id 上。
 *
 * 引用只在**所属会话内**解释，所以整会话导入后必须重映射，否则所有引用立刻指向不存在的 id。
 * （fork 复制走的是另一条路：`forkConversationAtMessage` 保留 message id，无需处理。）
 *
 * **查不到就保留原 id**：引用目标可能已被删除/被压缩截断（导出时就不在文件里），
 * 这类悬空引用不影响展示与发送——快照文本才是发送依据。改写快照内容反而会造假数据。
 */
private fun List<UIMessageAnnotation>.remapQuotes(messageIdMap: Map<Uuid, Uuid>): List<UIMessageAnnotation> {
    if (none { it is UIMessageAnnotation.QuotedMessage }) return this
    return map { annotation ->
        if (annotation !is UIMessageAnnotation.QuotedMessage) {
            annotation
        } else {
            annotation.copy(messageId = messageIdMap[annotation.messageId] ?: annotation.messageId)
        }
    }
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
