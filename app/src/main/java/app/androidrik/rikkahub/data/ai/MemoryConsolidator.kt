package app.androidrik.rikkahub.data.ai

import android.util.Log
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.MemoryConsolidationRepository
import app.androidrik.rikkahub.data.repository.MemoryProposalStore
import app.androidrik.rikkahub.data.repository.MemoryTransactionRunner
import app.androidrik.rikkahub.utils.effectiveContextLength
import app.androidrik.rikkahub.data.repository.MemoryProposalApplyResult
import kotlin.uuid.Uuid

/**
 * 做梦（V3）的 worker：实现 [MemoryConsolidationWorker] 契约。
 *
 * 四条数据面直接委托给 [MemoryConsolidationRepository]（门控/基线/粗筛+精算都在那儿），
 * 这里只做 [consolidate] 这一件「需要模型」的事，以及围绕它的决策：
 *
 * 1. **切轮次**（[buildConsolidationTranscript]）：按 token 预算装新轮次，装不下的不推进水位；
 * 2. **给库现状**：复用聊天注入的两层披露（换更小预算）+ 一张校准表（重要度 vs 被读次数）；
 * 3. **模型**：优先全局「快速模型」（`Settings.fastModelId`，它本来就是给标题/建议这类后台任务用的），
 *    没配再回落到助手的聊天模型；
 * 4. **审批降级**：库的任一参与助手开着写入审批 ⇒ 整轮**只提案不改库**（见设计稿 §7.2）；
 * 5. **水位只推进到真正带进模型的位置**，失败一律不推进（内容不丢，下一轮对话或手动会再试）。
 *
 * 三条刻意的取舍：
 * - **没模型可用时不发请求、但记成一次失败**：记成「没可做的事」会让调度器每 2 分钟重扫同一批内容
 *   （空转）；记成失败会换来调度器的退避（`failed` ⇒ 不再自动重试，直到下一轮对话结束或手动触发），
 *   而**水位没推进 ⇒ 内容不丢**。这是对 §7.3「离线跳过」的实现修正，已就地更新设计稿；
 * - **自动 ≤16 步 / 手动 ≤32 步**：后台跑飞比对话里更难看，而用户显式手动的整理允许更彻底；
 * - **幂等**：重复调用时新轮次已为空 ⇒ transcript 为空且非手动 ⇒ 直接返回 null（**不发请求**）。
 */
class MemoryConsolidator(
    private val repository: MemoryConsolidationRepository,
    /** 该记忆库当前的记录（做库现状与校准表用）。生产接 `MemoryRepository::getMemoriesOfAssistant` */
    private val memories: suspend (String) -> List<AssistantMemory>,
    private val agent: MemoryConsolidationAgent,
    private val settings: suspend () -> Settings,
    /**
     * 建议的落库与应用。**只做委托**：调度器不认识存储形态（契约第 1 条），
     * 而校验/按批原子/审计留痕都在 [MemoryProposalRepository] 里。
     */
    private val proposals: MemoryProposalStore,
    private val transactions: MemoryTransactionRunner,
    private val now: () -> Long = System::currentTimeMillis,
) : MemoryConsolidationWorker {

    companion object {
        private const val TAG = "MemoryConsolidator"

        /** 自动整理的步数上限（后台任务，成本优先） */
        const val MAX_AUTO_STEPS = 16

        /** 手动整理的步数上限（用户显式要求，允许更彻底；上游同样分两档） */
        const val MAX_MANUAL_STEPS = 32
    }

    override suspend fun pendingOf(conversation: Conversation): PendingMemoryConversation? =
        repository.pendingOf(conversation)

    override suspend fun memoryIdsWithUpdates(): List<String> = repository.memoryIdsWithUpdates()

    override suspend fun findPending(memoryId: String, manual: Boolean): List<PendingMemoryConversation> =
        repository.findPending(memoryId, manual)

    // ---- 建议的落库与应用：只做委托（存储侧知识不进调度器，见接口契约第 1 条） ----

    override suspend fun pendingProposals(memoryId: String): List<MemoryConsolidationProposal> =
        proposals.pending(memoryId)

    override suspend fun memoryIdsWithProposals(): List<String> = proposals.memoryIdsWithPending()

    override suspend fun applyProposals(memoryId: String, ids: Set<Long>): MemoryProposalApplyResult =
        proposals.apply(memoryId, ids)

    override suspend fun rejectProposals(memoryId: String, ids: Set<Long>): Int =
        proposals.reject(memoryId, ids)

    override suspend fun markConsolidated(conversation: Conversation) = repository.markConsolidated(conversation)

    override suspend fun consolidate(
        memoryId: String,
        conversationIds: Set<Uuid>,
        fullReview: Boolean,
    ): MemoryConsolidationRun? {
        val settings = settings()
        val participants = settings.assistants.filter { it.memoryId() == memoryId }
        if (participants.isEmpty()) return null
        if (!fullReview && conversationIds.isEmpty()) return null

        val conversations = conversationIds.mapNotNull { id -> repository.loadConversation(id) }
        val baselines = conversations.associate { it.id to repository.baselineOf(it, manual = fullReview) }
        val transcript = buildConsolidationTranscript(conversations, baselines)

        if (transcript.text.isBlank() && !fullReview) {
            // 有轮次但没有可整理的**正文**（例如整轮只有思考/工具结果）：把水位推过去。
            // 不推进的话它会永远算「待整理」——每 2 分钟被扫一次却什么也做不了。
            transactions.run {
                transcript.consumed.forEach { repository.markConsolidated(it.conversationId, it.watermark) }
            }
            return null
        }

        val model = settings.findModelById(settings.fastModelId)
            ?: settings.findModelById(participants.first().chatModelId)
        if (model == null) {
            Log.w(TAG, "consolidate($memoryId): no model available (fastModelId / chatModelId unset)")
            return MemoryConsolidationRun(
                memoryId = memoryId,
                finishedAt = now(),
                conversationIds = conversationIds,
                fullReview = fullReview,
                error = "no model available for consolidation",
            )
        }

        val library = memories(memoryId)
        // **库快照按页下发**（`docs/design/.../design-memory-retrieval-and-consolidation.md` §7）。
        // 真机库 49 条 ≈58,489 token，而快照预算是 4K（手动一页 12K）⇒ 一次看不完；
        // 分页 + 游标是「全量复核」唯一的做法：每轮推进一页，走完一轮从头再来。
        // 手动复核的页预算**按模型窗口放开**（用户 2026-10-16 定：上限 512K、输入占 60%），
        // 于是「全量」通常一轮走完；自动路径仍是 4K（它的本职是处理新轮次）。
        val libraryBudget = if (fullReview) {
            manualLibraryBudget(model.effectiveContextLength())
        } else {
            DEFAULT_LIBRARY_TOKENS
        }
        val page = pageOfLibrary(
            memories = library,
            cursor = repository.reviewCursor(memoryId),
            budgetTokens = libraryBudget,
        )
        Log.i(
            TAG,
            "consolidate($memoryId): ${conversations.size} conversation(s), " +
                "${library.size} record(s), page=${page.from}-${page.to}/${page.total} " +
                "last=${page.isLast} budget=$libraryBudget remaining=${transcript.remainingTurns} fullReview=$fullReview",
        )
        // 共享记忆库（useGlobalMemory）时，改类纪律不能由某一个助手替其他人放宽：
        // 只有**真正参与整理**的助手（开启且记忆开）都显式开启，才允许调整类型。
        // 不能拿全部同库助手做 all：未开整理/已停用的助手不参与本次整理，其开关也永远看不到，
        // 否则用户在当前助手打开开关会被一个无法操作的隐藏状态静默否决。
        val enabledParticipants = participants.filter { it.memoryConsolidationEnabled() }
        val allowKindChange = enabledParticipants.isNotEmpty() &&
            enabledParticipants.all { it.memoryConsolidationReclassify }
        val outcome = agent.run(
            MemoryConsolidationRequest(
                settings = settings,
                model = model,
                memoryId = memoryId,
                libraryPrompt = buildLibraryPagePrompt(page),
                initialRevisions = page.records.associate { it.id to it.revision },
                // 用户拒绝过的改动：不喂进去，模型下一轮会原地重提同一条
                // （真机实测：同一条 `delete` 被两轮整理重复提出）
                refusedChanges = refusedChangeLines(proposals.recentlyRejected(memoryId)),
                // **校准表只列本页**：它以前列全库（含读不到正文的 id）⇒ 模型「知道 id 却看不到内容」
                // ⇒ 盲改（真机实证：同一条 edit 被逐字节重复提出）。看不到的记录就**不该出现在它面前**；
                // 确实要看，走 `memory_tool action=search` / `read`（那两条路本来就在）。
                calibrationTable = buildMemoryCalibrationTable(page.records),
                transcript = transcript.text,
                maxSteps = if (fullReview) MAX_MANUAL_STEPS else MAX_AUTO_STEPS,
                // 审批开着（任一相关开关）⇒ 整轮只提案：后台没有用户可等，而"直接拒绝"会丢掉
                // 模型原本想改的内容（见 docs/design/design-memory-consolidation.md §7.2）
                proposeOnly = participants.any { it.memoryModifyApproval || it.memoryCreateApproval },
                allowKindChange = allowKindChange,
            )
        )

        // The model runs outside the transaction. In proposal mode, its suggestions and the
        // progress that consumes their evidence must become durable together, or not at all.
        // Direct-write mode already commits tool mutations during agent.run; those changes cannot
        // be rolled back here if generation or this final commit fails (not run-level exactly-once). Nested proposal writes join
        // this same Room transaction; failures propagate to the scheduler (no in-memory fallback).
        val persistedProposals = transactions.run {
            proposals.savePending(memoryId, outcome.proposals)
            transcript.consumed.forEach { repository.markConsolidated(it.conversationId, it.watermark) }
            // Automatic extraction must not consume the user-owned full-library review cursor.
            if (fullReview) {
                repository.setReviewCursor(memoryId, page.nextCursor)
            }
            if (outcome.proposals.isEmpty()) emptyList() else proposals.pending(memoryId)
        }
        return MemoryConsolidationRun(
            memoryId = memoryId,
            finishedAt = now(),
            conversationIds = conversationIds,
            fullReview = fullReview,
            changedMemories = outcome.appliedChanges,
            proposals = persistedProposals,
            libraryReviewed = page.to,
            libraryTotal = page.total,
            libraryPageDone = page.isLast,
        )
    }
}
