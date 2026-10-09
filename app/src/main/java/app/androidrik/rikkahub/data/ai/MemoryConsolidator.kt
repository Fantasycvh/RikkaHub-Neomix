package app.androidrik.rikkahub.data.ai

import android.util.Log
import app.androidrik.rikkahub.data.datastore.Settings
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.data.model.Conversation
import app.androidrik.rikkahub.data.repository.MemoryConsolidationRepository
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
        val baselines = conversations.associate { it.id to repository.baselineOf(it) }
        val transcript = buildConsolidationTranscript(conversations, baselines)

        if (transcript.text.isBlank() && !fullReview) {
            // 有轮次但没有可整理的**正文**（例如整轮只有思考/工具结果）：把水位推过去。
            // 不推进的话它会永远算「待整理」——每 2 分钟被扫一次却什么也做不了。
            transcript.consumed.forEach { repository.markConsolidated(it.conversationId, it.watermark) }
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
        Log.i(
            TAG,
            "consolidate($memoryId): ${conversations.size} conversation(s), " +
                "${library.size} record(s), remaining=${transcript.remainingTurns}, fullReview=$fullReview",
        )
        val outcome = agent.run(
            MemoryConsolidationRequest(
                settings = settings,
                model = model,
                memoryId = memoryId,
                libraryPrompt = buildMemoryPrompt(library, DEFAULT_LIBRARY_TOKENS, now()),
                calibrationTable = buildMemoryCalibrationTable(library),
                transcript = transcript.text,
                maxSteps = if (fullReview) MAX_MANUAL_STEPS else MAX_AUTO_STEPS,
                // 审批开着（任一相关开关）⇒ 整轮只提案：后台没有用户可等，而"直接拒绝"会丢掉
                // 模型原本想改的内容（见 docs/design-memory-consolidation.md §7.2）
                proposeOnly = participants.any { it.memoryModifyApproval || it.memoryCreateApproval },
            )
        )

        // 只推进**真正带进模型**的水位；agent 抛异常时根本走不到这里（内容不丢）
        transcript.consumed.forEach { repository.markConsolidated(it.conversationId, it.watermark) }
        return MemoryConsolidationRun(
            memoryId = memoryId,
            finishedAt = now(),
            conversationIds = conversationIds,
            fullReview = fullReview,
            changedMemories = outcome.appliedChanges,
            proposals = outcome.proposals,
        )
    }
}
