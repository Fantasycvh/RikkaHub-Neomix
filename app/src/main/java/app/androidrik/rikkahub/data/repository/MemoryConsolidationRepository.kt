package app.androidrik.rikkahub.data.repository

import app.androidrik.rikkahub.data.ai.PendingMemoryConversation
import app.androidrik.rikkahub.data.ai.memoryConsolidationBaseline
import app.androidrik.rikkahub.data.ai.memoryConsolidationEnabled
import app.androidrik.rikkahub.data.ai.memoryId
import app.androidrik.rikkahub.data.ai.unconsolidatedTurns
import app.androidrik.rikkahub.data.db.dao.MemoryConsolidationDAO
import app.androidrik.rikkahub.data.db.entity.MemoryConsolidationEntity
import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

/**
 * 做梦（整理，V3）的**数据面**：进度表读写 + 「哪些会话有新内容要整理」的查询。
 *
 * 实现的是 `MemoryConsolidationWorker` 契约里**与模型无关的那四条**（`pendingOf` /
 * `memoryIdsWithUpdates` / `findPending` / `markConsolidated`）。真正吃模型的 `consolidate`
 * 属下一批（V3-b2），所以本类**暂时不实现那个接口**——半实现的接口比不实现更容易让人误以为
 * 「已经能整理了」。
 *
 * **本批不接线**（没有调用方）：接上就要求 `consolidate` 存在，否则调度器每 2 分钟空跑一次。
 *
 * 门控与基线的判据在 `data/ai/MemoryConsolidation.kt`（纯函数，可单测）；这里只管"查什么、怎么查"。
 */
class MemoryConsolidationRepository(
    private val consolidationDAO: MemoryConsolidationDAO,
    /** 当前设置里的助手列表：只有它知道「助手启用 / 记忆开关 / 做梦开关 / 全局记忆」 */
    private val assistants: suspend () -> List<Assistant>,
    /** 载入完整会话（数轮次要消息时间）：生产接 `ConversationRepository::getConversationById` */
    private val loadConversation: suspend (Uuid) -> Conversation?,
    private val limitPerRun: Int = DEFAULT_LIMIT_PER_RUN,
) {
    companion object {
        /**
         * 一次整理最多带走多少个会话（契约里「一次能处理的数量」由这里定）。
         *
         * 取 8 的理由：整理本身是一次多步模型调用，一次带太多会话会让单次运行又长又贵，
         * 而没处理完的部分下次扫库还会被捞出来（调度器每次整理后都会重扫）。
         */
        const val DEFAULT_LIMIT_PER_RUN = 8
    }

    /**
     * [conversation] 有多少轮还没整理；不需要整理时返回 null。
     *
     * 四道门控：助手存在 → 做梦开启（助手启用 + 开记忆 + 开关拨开）→ 对话不是隐身 →
     * 基线之后确实有新轮次。前三道是"要不要"，第四道是"有没有东西整理"——
     * 后者的失败必须返回 null 而不是 0 轮，否则调度器会把"没东西整理"记成一次成功的整理。
     */
    suspend fun pendingOf(conversation: Conversation): PendingMemoryConversation? {
        val assistant = assistants().firstOrNull { it.id == conversation.assistantId } ?: return null
        return pendingOf(conversation, assistant)
    }

    private suspend fun pendingOf(
        conversation: Conversation,
        assistant: Assistant,
    ): PendingMemoryConversation? {
        if (!assistant.memoryConsolidationEnabled()) return null
        if (conversation.memoryDisabled) return null
        val baseline = memoryConsolidationBaseline(
            consolidationSince = assistant.memoryConsolidationSince,
            consolidatedAt = consolidationDAO.getConsolidatedAt(conversation.id.toString()) ?: 0L,
        )
        val turns = unconsolidatedTurns(conversation, baseline)
        return if (turns > 0) {
            PendingMemoryConversation(conversation.id, assistant.memoryId(), turns)
        } else {
            null
        }
    }

    /**
     * 可能有新内容的记忆库（**粗筛**：只按会话的末尾消息时间，不保证真有新轮次）。
     *
     * 应用启动补漏用：进程被杀前没整理完的会话靠它捞回来。
     */
    suspend fun memoryIdsWithUpdates(): List<String> =
        participantsByStore()
            .filter { (_, participants) ->
                val baseline = storeBaseline(participants)
                participants.any { assistant ->
                    consolidationDAO.getCandidateConversationIds(assistant.id.toString(), baseline, 1)
                        .isNotEmpty()
                }
            }
            .keys
            .toList()

    /**
     * 记忆库 [memoryId] 下待整理的会话（最多 [limitPerRun] 个，新内容优先）。
     *
     * 粗筛之后**逐条精算轮数**：`last_message_at` 只能说明"可能聊了新东西"（改标题不算，
     * 但迁移/恢复旧库会让它撒谎），真正算数的是消息时间；精算为 0 的会话直接丢掉，不写进度行
     * （写了就把还没整理的内容标记成整理过了）。
     */
    suspend fun findPending(memoryId: String): List<PendingMemoryConversation> {
        val assistants = assistants()
        val participants = assistants.filter { it.memoryConsolidationEnabled() && it.memoryId() == memoryId }
        if (participants.isEmpty()) return emptyList()
        val baseline = storeBaseline(participants)
        val byId = assistants.associateBy { it.id }
        return participants
            .flatMap { assistant ->
                consolidationDAO.getCandidateConversationIds(assistant.id.toString(), baseline, limitPerRun)
            }
            .distinct()
            .mapNotNull { id ->
                val conversation = Uuid.parseOrNull(id)?.let { loadConversation(it) } ?: return@mapNotNull null
                val assistant = byId[conversation.assistantId] ?: return@mapNotNull null
                pendingOf(conversation, assistant)
            }
            .take(limitPerRun)
    }

    /**
     * 把 [conversation] 现有内容都算作已整理。
     *
     * 两个调用场景：分叉（复制过来的轮次由原对话负责整理）、以及每次整理完成后推进进度。
     */
    suspend fun markConsolidated(conversation: Conversation, now: Long = System.currentTimeMillis()) {
        consolidationDAO.upsertProgress(
            MemoryConsolidationEntity(conversationId = conversation.id.toString(), consolidatedAt = now),
        )
    }

    /**
     * 开了做梦的助手，按**记忆库**分组（同一个全局库可能被多个助手共用）。
     *
     * 分组而不是逐个助手处理：契约里整理的粒度是记忆库，一次 run 要覆盖库里所有助手的待整理会话。
     */
    private suspend fun participantsByStore(): Map<String, List<Assistant>> =
        assistants().filter { it.memoryConsolidationEnabled() }.groupBy { it.memoryId() }

    /**
     * 一个记忆库的基线取**参与助手的最晚开启时刻**。
     *
     * 为什么取最晚而不是最早：全局库被多个助手共用时，若取最早，某个助手刚拨开开关就会给**整库**
     * 倒灌历史——而它本来只需要对"自己开启之后"负责。取最晚是保守方向：宁可少整理，不许多花钱。
     */
    private fun storeBaseline(participants: List<Assistant>): Long =
        participants.maxOf { it.memoryConsolidationSince }
}
