package app.androidrik.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.dao.FavoriteDAO
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import app.androidrik.rikkahub.data.db.dao.MessageNodeDiffRow
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity
import app.androidrik.rikkahub.data.ai.prompts.filterOutCheckpointMessages
import app.androidrik.rikkahub.data.model.MessageNode
import app.androidrik.rikkahub.data.model.healMissingParents
import app.androidrik.rikkahub.data.model.repairCorruptedCandidates
import app.androidrik.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * 列表加载耗时埋点（诊断会话打开加载耗时，供判断是否需要窗口化）。
 * 埋点日志沿用原 TAG「ConversationRepository」——诊断脚本与台账按此 grep，改值会断线。
 */
private const val TAG = "ConversationRepository"

/** 签名分隔符（控制字符 U+0001，JSON 与 UUID 中不会出现，避免拼接歧义） */
private const val SEP = "\u0001"

/**
 * Legacy repair needs structural evidence, not just a text prefix (ordinary messages can quote it).
 * Nodes containing upstream checkpoints are authoritative, including their unselected alternatives.
 */
internal fun List<MessageNode>.repairLegacyCheckpointCandidates(): List<MessageNode> {
    val nodeCounts = flatMap { node -> node.messages.map { it.id }.distinct() }
        .groupingBy { it }.eachCount()
    val cleaned = map { node ->
        if (node.messages.any { it.isContextCheckpoint } ||
            node.messages.none { (nodeCounts[it.id] ?: 0) > 1 }
        ) return@map node
        val messages = node.messages.filterOutCheckpointMessages()
        // Never turn a node into an empty shell on the strength of a heuristic.
        if (messages.isEmpty() || messages.size == node.messages.size) return@map node
        val selectedId = node.messages.getOrNull(node.selectIndex)?.id
        node.copy(
            messages = messages,
            selectIndex = messages.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: messages.lastIndex,
        )
    }
    val repaired = cleaned.repairCorruptedCandidates()
    return cleaned.mapIndexed { index, node ->
        if (node.messages.any { it.isContextCheckpoint }) node else repaired[index]
    }
}

/**
 * 消息节点的分页加载与增量保存（批次 9 从 [ConversationRepository] 拆出）。
 *
 * 加载侧带**页隔离恢复**：读失败先收窄到单行隔离坏行，最坏只丢一行（旧实现整页跳过会
 * 经增量保存放大成不可逆丢数据，见 [loadMessageNodes] 内注释）。
 * 保存侧按 **id 键 signature map diff**（树形分支 P0），常规场景只写 1 行（见 [saveMessageNodes] 内注释）。
 */
internal class ConversationNodeStore(
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
) {
    internal suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val startNs = System.nanoTime()
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var offset = 0
            var pageSize = 64
            // 读失败（单节点 messages 超出 cursor 窗口等）：先收窄到单行把坏行隔离出来，
            // 单行仍失败才跳过该行。旧实现整页跳过，内存序列缺一整页，而增量保存按
            // 「内存 ≡ 全量」求公共前缀删除重插 ⇒ 该位置起的库中行（含未读出的好节点）
            // 全被删掉，不可逆丢数据。收窄后最坏只丢一行（该行本就无法渲染）。
            fun onUnreadablePage(e: Exception) {
                e.printStackTrace()
                if (pageSize > 1) {
                    pageSize = 1
                } else {
                    Log.w(TAG, "loadMessageNodes: skip unreadable node row at offset=$offset (conv=$conversationId)")
                    offset += 1
                }
            }
            while (true) {
                val page = try {
                    messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset)
                } catch (e: CancellationException) {
                    // ⚠️ 必须排在 IllegalStateException **之前**：CancellationException 是它的子类，
                    // 顺序反了就会被下面那个 catch 吞掉，于是协程取消后继续往下读，
                    // **在本事务里无限循环**（事务永不释放、消息加载永不返回）。
                    // 吸收上游 923ad3d91（Fixes #2151），此处保持同样语义。
                    throw e
                } catch (e: IllegalStateException) {
                    onUnreadablePage(e)
                    continue
                } catch (e: SQLiteBlobTooBigException) {
                    onUnreadablePage(e)
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val rawMessages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            parentId = entity.parentId?.let { runCatching { Uuid.parse(it) }.getOrNull() },
                            messages = rawMessages,
                            selectIndex = entity.selectIndex,
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
                if (pageSize == 1) {
                    // 单行模式读到成功页，恢复批量（坏行会在下一次批量读取中再次被收窄隔离）
                    pageSize = 64
                }
            }
            // 自愈早期压缩 bug 造成的跨节点候选副本（详见 repairCorruptedCandidates）；
            // healMissingParents：给 P0 惰性回填未触达的 null 父行按创建序补前驱
            // （P1 起 parentId 权威，显式父一律保留不覆写——树形分支不能被掰回线性）
            nodes.repairLegacyCheckpointCandidates().healMissingParents()
        }.also { loaded ->
            // 耗时埋点：打开会话、列表加载都经过这里。
            // 触发窗口化的阈值（2026-09-20 实测修正，design-auto-compaction 附录 A §5.2）：
            // 驱动因素是**字节量而非节点数**——1024 节点/334KB 只要 232ms，而节点更少（912）
            // 但 16MB 的要 1706ms。故判据 = 单会话 >1MB 字节量、或此日志 >300ms。
            // 字节量可直接查：SELECT SUM(LENGTH(messages)) FROM message_node GROUP BY conversation_id
            val ms = (System.nanoTime() - startNs) / 1_000_000
            Log.i(TAG, "loadMessageNodes: ${loaded.size} nodes in ${ms}ms (conv=$conversationId)")
        }
    }

    /**
     * 增量保存消息节点（树形分支 P0：**id 键 signature map diff**，取代公共前缀 diff；
     * 设计稿 design-conversation-tree-branching.md §五.2）。
     *
     * **为何换**：前缀 diff 假定「内存列表 ≡ 库中全量线性序」，树形分支（P1）下不再成立。
     * id 键 diff 只依赖节点身份：变更 upsert、消失 delete、新增分配新创建序——
     * 与列表顺序解耦，P1 分支乱序存储无需再改本算法。
     *
     * `node_index` 语义 = **全局创建序**（append-only、永不复用）：存活行保持原值，
     * 新行取 max+1。加载按它排序（列表序 = 创建序 ≠ 路径序，路径由 activeLeafNodeId 决定）。
     * `parent_id` **P1 起权威**，随变更行原样写入；内存侧由 [healMissingParents] 兜底补 null
     * （P0 惰性回填未触达的存量行）。
     *
     * 常见场景（尾部追加/修改最后一条）仍只写 1 行；签名计算量与前缀版同量级。
     */
    internal suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val plan = computeNodeDiff(
            conversationId = conversationId,
            existing = messageNodeDAO.getNodeDiffRows(conversationId),
            chain = nodes.healMissingParents(),
        )
        if (plan.removedIds.isNotEmpty()) {
            messageNodeDAO.deleteByIds(conversationId, plan.removedIds)
        }
        if (plan.toWrite.isNotEmpty()) {
            messageNodeDAO.insertAll(plan.toWrite)
        }
    }
}

/** [computeNodeDiff] 的结果：待 upsert 的行 + 待删除的节点 id。 */
internal data class NodeDiffPlan(
    val toWrite: List<MessageNodeEntity>,
    val removedIds: List<String>,
)

/**
 * id 键 diff 的纯计算（JVM 单测钉住；[ConversationNodeStore.saveMessageNodes] 只做 IO）。
 *
 * 签名 = select_index + messages JSON（盖住「编辑后候选变但 id 不变」）。
 * **不比 parent_id**：它由内存树原样落库，签名不变就不重写（避免无谓行写放大）；
 * 语义变更（换父/建分支）必然伴随消息或新增节点变化，不会漏写。
 * **removedIds 语义**：调用方传的 `chain` 是**全量节点集**（含 off-path 兄弟子树），
 * 因此只有剪枝/删除真正缩小集合时才会产生删除——P1 激活路径切换不删任何节点。
 */
internal fun computeNodeDiff(
    conversationId: String,
    existing: List<MessageNodeDiffRow>,
    chain: List<MessageNode>,
): NodeDiffPlan {
    val existingById = existing.associateBy { it.id }
    val incomingIds = chain.mapTo(HashSet()) { it.id.toString() }
    val removedIds = existing.map { it.id }.filter { it !in incomingIds }
    var nextIndex = (existing.maxOfOrNull { it.nodeIndex } ?: -1) + 1
    val toWrite = ArrayList<MessageNodeEntity>()
    chain.forEach { node ->
        val id = node.id.toString()
        val messagesJson = JsonInstant.encodeToString(node.messages)
        val signature = node.selectIndex.toString() + SEP + messagesJson
        val prev = existingById[id]
        if (prev != null && prev.signature == signature) return@forEach
        toWrite += MessageNodeEntity(
            id = id,
            conversationId = conversationId,
            nodeIndex = prev?.nodeIndex ?: nextIndex++,
            messages = messagesJson,
            selectIndex = node.selectIndex,
            parentId = node.parentId?.toString(),
        )
    }
    return NodeDiffPlan(toWrite = toWrite, removedIds = removedIds)
}
