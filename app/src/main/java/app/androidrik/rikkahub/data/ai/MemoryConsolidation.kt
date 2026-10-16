package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.model.Assistant
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.MemoryRepository
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * 做梦（整理，V3）的**纯策略**：只回答三个问题——「这个助手要不要自动整理」「从哪一刻之后算新内容」
 * 「这个会话有多少轮新内容」。带数据库的部分在 `MemoryConsolidationRepository`。
 *
 * 为什么单独成文件、为什么是纯函数：这三条判据决定的是**会自动花钱的后台行为**，必须能被逐条钉死；
 * 散在仓库层与 UI 的 `if` 里的话，「为什么它没整理」会变成一个要看三处才能回答的问题
 * （见 `docs/design/design-memory-consolidation.md` §7）。
 */

/** 一个助手的记忆库 id：开了「使用全局记忆」时是共享库，否则是助手自己一份。 */
internal fun Assistant.memoryId(): String =
    if (useGlobalMemory) MemoryRepository.GLOBAL_MEMORY_ID else id.toString()

/**
 * 自动整理是否开启。**三道闸缺一不可**：
 *
 * - `enabled`：停用的助手不该在后台被整理（上游 #2130 的阻塞④正是「停用助手照样被整理」）；
 * - `enableMemory`：没有记忆可整理（做梦的上游是记忆，没开记忆就无从谈起）；
 * - `memoryConsolidationSince > 0`：**助手级开关本身**（默认关，见设计稿 §7.1）。
 *
 * 隐身的**对话**不在这里判：那是会话级的事，由调用方查 `Conversation.memoryDisabled`
 * （`pendingOf` 里那一道）。
 */
internal fun Assistant.memoryConsolidationEnabled(): Boolean =
    enabled && enableMemory && memoryConsolidationSince > 0L

/**
 * 整理基线：**只对这一刻之后的新内容负责**。
 *
 * 取「开关开启时刻」与「该会话上次整理完成时刻」的**较大者**，两者都不可省：
 * - 只看进度行 ⇒ 刚拨开开关就会把几百个历史会话一次性灌进整理队列（用户没要求的后台花费）；
 * - 只看开启时刻 ⇒ 已整理过的会话会被反复整理同一批轮次（进度行就白记了）。
 *
 * **想要历史内容被整理**，两条路：①把基线选成 [MEMORY_CONSOLIDATION_ALL_HISTORY]（「从最早开始」）；
 * ②走**手动整理**——`findPending(manual = true)` 会把「开启时刻」那一半丢掉
 * （只保留每会话的 `consolidated_at` 进度），即「把手动整理定义为『所有还没整理过的轮次』，
 * 不论多老」。见 `docs/design/design-memory-retrieval-and-consolidation.md` §4。
 */
internal fun memoryConsolidationBaseline(consolidationSince: Long, consolidatedAt: Long): Long =
    maxOf(consolidationSince, consolidatedAt)

/**
 * 「整理全部历史」的哨兵基线：**早于一切真实消息，但非 0**。
 *
 * 为什么非 0：`memoryConsolidationSince > 0L` 同时是「开关是否拨开」的判据
 * （见 [memoryConsolidationEnabled]）——用 0 表示"全部历史"会与"关闭"撞车。
 * 取 1（epoch + 1ms）：真实消息的时间戳不可能早于它，而 SQL 的
 * `last_message_at > MAX(consolidated_at, 1)` 与轮次计数的 `> 1` 都能自然命中全部内容。
 *
 * **不新增字段**：`memoryConsolidationSince` 仍是唯一的 `Long`（0 = 关 / >0 = 基线），
 * 于是没有 DB 迁移、也没有第二套真相。
 */
const val MEMORY_CONSOLIDATION_ALL_HISTORY = 1L

/** 当前基线是否等于「全部历史」哨兵（UI 用它决定选中哪一档）。 */
internal fun Assistant.memoryConsolidationCoversAllHistory(): Boolean =
    memoryConsolidationSince == MEMORY_CONSOLIDATION_ALL_HISTORY

/**
 * 会话里**基线之后**的轮数（一个 `MessageNode` = 一轮）。
 *
 * 数的是「节点里有没有基线之后的消息」，而不是节点自身的时间：本仓的 `MessageNode` **没有时间戳**
 * （只有 id / 消息 / 选中项），时间在消息上。
 *
 * 用 `any` 而不是 `currentMessage`：后者在候选损坏时会抛异常（见 `NodeCandidateRepairTest` 那套修复），
 * 而做梦是后台路径——不该因为一条脏数据把整轮整理打掉。
 */
internal fun unconsolidatedTurns(conversation: Conversation, baseline: Long): Int {
    val zone = TimeZone.currentSystemDefault()
    return conversation.messageNodes.count { node ->
        node.messages.any { it.createdAt.toInstant(zone).toEpochMilliseconds() > baseline }
    }
}
