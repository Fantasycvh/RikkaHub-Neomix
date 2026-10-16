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
    private val conversationLoader: suspend (Uuid) -> Conversation?,
    private val limitPerRun: Int = DEFAULT_LIMIT_PER_RUN,
    /** 判定「自动整理间隔」用的时钟；测试注入固定时刻，生产用真实时间 */
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /**
         * 一次整理最多带走多少个会话（契约里「一次能处理的数量」由这里定）。
         *
         * 取 8 的理由：整理本身是一次多步模型调用，一次带太多会话会让单次运行又长又贵，
         * 而没处理完的部分下次扫库还会被捞出来（调度器每次整理后都会重扫）。
         */
        const val DEFAULT_LIMIT_PER_RUN = 8

        /**
         * **自动**整理同一记忆库的最小间隔。
         *
         * 为什么必须有：`onTurnFinished` 的规则是「一轮结束 + 空闲 2 分钟」就整理——不设间隔的话，
         * 活跃会话会变成**每 2 分钟一次后台模型调用**（一轮对话就够触发）。6 小时把上限压到
         * 每库每天 ≤4 次，同时保证「今天聊的，今晚就被整理」。
         *
         * **只约束自动路径**：`pendingOf` / `memoryIdsWithUpdates` / 自动的 `findPending` 判它，
         * 手动整理的 `findPending(manual = true)` 不判（用户显式点击即授权）。见设计稿 §7.3。
         */
        const val MIN_AUTO_INTERVAL_MS = 6L * 60 * 60 * 1000
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
        if (!autoIntervalElapsed(listOf(assistant))) return null
        return pendingOfIgnoringInterval(conversation, assistant)
    }

    /**
     * 门控的第二层（**不含**自动频次上限）。
     *
     * 为什么拆开：上限只约束**自动**路径，而手动整理（`organize`）走的是 `findPending(manual = true)`
     * → 也会调用本函数。把上限留在公共点会让「手动整理」被静默挡住——正是契约里明确要避免的那件事。
     */
    private suspend fun pendingOfIgnoringInterval(
        conversation: Conversation,
        assistant: Assistant,
        /**
         * 手动整理：**丢掉「开关开启时刻」那一半基线**，只保留每会话的 `consolidated_at`。
         *
         * 语义是「把手动整理定义为『所有还没整理过的轮次』，不论多老」——这正是「存量历史整理」
         * 的诉求（详见 `design-memory-retrieval-and-consolidation.md` §4）。进度行仍然生效，
         * 所以不会把已整理过的轮次再灌一遍。
         */
        ignoreSince: Boolean = false,
    ): PendingMemoryConversation? {
        if (!assistant.memoryConsolidationEnabled()) return null
        if (conversation.memoryDisabled) return null
        val baseline = if (ignoreSince) {
            baselineOf(conversation, assistant, ignoreSince = true)
        } else {
            baselineOf(conversation, assistant)
        }
        val turns = unconsolidatedTurns(conversation, baseline)
        return if (turns > 0) {
            PendingMemoryConversation(conversation.id, assistant.memoryId(), turns)
        } else {
            null
        }
    }

    /**
     * 按 id 载入完整会话（worker 要把新轮次拼成正文）。
     *
     * 暴露在仓库里而不是让 worker 直接依赖 `ConversationRepository`：worker 的策略要能用 JVM 单测
     * 钉死，而 `ConversationRepository` 是个构造很重的具体类、在单测里造不出来。
     */
    suspend fun loadConversation(id: Uuid): Conversation? = conversationLoader(id)

    /**
     * 该会话的整理基线：`max(助手开关的开启时刻, 该会话的整理进度)`（见 `memoryConsolidationBaseline`）。
     *
     * 公开给 worker：它要把水位推进到「最后一条带进模型的消息时间」，得先知道从哪儿算起。
     */
    suspend fun baselineOf(conversation: Conversation): Long {
        val assistant = assistants().firstOrNull { it.id == conversation.assistantId } ?: return 0L
        return baselineOf(conversation, assistant)
    }

    private suspend fun baselineOf(
        conversation: Conversation,
        assistant: Assistant,
        /** 见 [pendingOfIgnoringInterval]：手动整理丢掉「开启时刻」那一半，只留进度行。 */
        ignoreSince: Boolean = false,
    ): Long =
        memoryConsolidationBaseline(
            consolidationSince = if (ignoreSince) 0L else assistant.memoryConsolidationSince,
            consolidatedAt = consolidationDAO.getConsolidatedAt(conversation.id.toString()) ?: 0L,
        )

    /**
     * 可能有新内容的记忆库（**粗筛**：只按会话的末尾消息时间，不保证真有新轮次）。
     *
     * 应用启动补漏用：进程被杀前没整理完的会话靠它捞回来。
     */
    suspend fun memoryIdsWithUpdates(): List<String> =
        participantsByStore()
            .filter { (_, participants) ->
                if (!autoIntervalElapsed(participants)) return@filter false
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
    suspend fun findPending(memoryId: String, manual: Boolean = false): List<PendingMemoryConversation> {
        val assistants = assistants()
        val participants = assistants.filter { it.memoryConsolidationEnabled() && it.memoryId() == memoryId }
        if (participants.isEmpty()) return emptyList()
        // 自动路径服从频次上限；手动整理（用户点了「整理一下」）不判
        if (!manual && !autoIntervalElapsed(participants)) return emptyList()
        // **手动整理丢掉「开启时刻」基线**（只留每会话进度行）⇒ 「所有还没整理过的轮次，不论多老」。
        // 自动路径仍用完整基线（否则刚拨开开关就会把几百个历史会话一次性灌进队列——那正是
        // 基线存在的理由，见 memoryConsolidationBaseline 的 kdoc）。
        val baseline = if (manual) 0L else storeBaseline(participants)
        val byId = assistants.associateBy { it.id }
        return participants
            .flatMap { assistant ->
                consolidationDAO.getCandidateConversationIds(assistant.id.toString(), baseline, limitPerRun)
            }
            .distinct()
            .mapNotNull { id ->
                val conversation = Uuid.parseOrNull(id)?.let { conversationLoader(it) } ?: return@mapNotNull null
                val assistant = byId[conversation.assistantId] ?: return@mapNotNull null
                pendingOfIgnoringInterval(conversation, assistant, ignoreSince = manual)
            }
            .take(limitPerRun)
    }

    /**
     * 把 [conversation] 现有内容都算作已整理（分叉时用）。
     */
    suspend fun markConsolidated(conversation: Conversation, now: Long = System.currentTimeMillis()) =
        markConsolidated(conversation.id, now)

    /**
     * 把会话的进度推进到 [watermark]（epoch millis）。
     *
     * 整理 agent 用的是这个重载：它推进的是「最后一条**真正带进模型**的消息时间」，而不是 `now`——
     * 用 `now` 会把没装进这一轮预算、或期间刚产生的新消息一起标记成整理过了（静默漏掉）。
     */
    suspend fun markConsolidated(conversationId: Uuid, watermark: Long) {
        consolidationDAO.upsertProgress(
            MemoryConsolidationEntity(conversationId = conversationId.toString(), consolidatedAt = watermark),
        )
    }

    /**
     * 自动路径的频次上限是否已过（按库算：参与助手里最近一次整理完成的时间）。
     *
     * 从没整理过 ⇒ 允许（第一次总要跑）；否则要隔 [MIN_AUTO_INTERVAL_MS]。
     */
    private suspend fun autoIntervalElapsed(participants: List<Assistant>): Boolean {
        val last = participants.maxOf { consolidationDAO.getLastConsolidatedAt(it.id.toString()) ?: 0L }
        return last <= 0L || now() - last >= MIN_AUTO_INTERVAL_MS
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
