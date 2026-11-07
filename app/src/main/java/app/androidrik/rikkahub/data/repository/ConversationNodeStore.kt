package app.androidrik.rikkahub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.dao.FavoriteDAO
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity
import app.androidrik.rikkahub.data.ai.prompts.filterOutCheckpointMessages
import app.androidrik.rikkahub.data.model.MessageNode
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
 * 保存侧按「公共内容前缀」diff，常规场景只写 1 行（见 [saveMessageNodes] 内注释）。
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
            // 自愈早期压缩 bug 造成的跨节点候选副本（详见 repairCorruptedCandidates）
            nodes.repairLegacyCheckpointCandidates()
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
     * 增量保存消息节点：只写「与库中公共前缀之后」的部分，并清理已不存在的尾部节点。
     *
     * **为何**：原实现每次保存都 `deleteByConversation` **删除全表再按下标重插**——
     * 5000 节点的会话发一条消息就重写 5000 行；且该写法意味着
     * 「内存列表 ≡ 全量」是写入侧依赖的不变量（窗口化前置障碍）。
     *
     * 算法：求「库中签名序列」与「内存签名序列」的公共**内容**前缀
     * （签名 = id + select_index + messages，盖住「编辑后候选变但 id 不变」）
     * -> 删除库中前缀之后的旧行 -> 插入内存中前缀之后的新行。
     * 常见场景（尾部追加/修改最后一条）即只写 1 行。
     *
     * `node_index` 用**绝对位置**（common + i）而非分片内下标，为将来窗口化留余地。
     */
    internal suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val existing = messageNodeDAO.getNodeSignaturesOfConversation(conversationId)
        val incoming = nodes.map { node ->
            node.id.toString() + SEP + node.selectIndex + SEP + JsonInstant.encodeToString(node.messages)
        }
        val common = commonPrefixLength(existing, incoming)

        // 库中前缀之后的旧行已不存在于内存 -> 删除（含 node_index = common）
        if (common < existing.size) {
            messageNodeDAO.deleteFromIndex(conversationId, common)
        }
        if (common >= nodes.size) return
        val entities = nodes.drop(common).mapIndexed { offset, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = common + offset,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }

    /** 两个签名序列的公共前缀长度（签名 = id + select_index + messages） */
    private fun commonPrefixLength(a: List<String>, b: List<String>): Int {
        val max = minOf(a.size, b.size)
        var i = 0
        while (i < max && a[i] == b[i]) i++
        return i
    }
}
