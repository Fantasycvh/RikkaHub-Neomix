package app.androidrik.rikkahub.data.repository

import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.db.dao.MemoryProposalDAO
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity
import kotlin.uuid.Uuid

/**
 * 建议存储的**窄接口**：`MemoryConsolidator` 依赖它而不是具体类。
 *
 * 为什么值得多一个类型：①调度器契约要求「worker 之外不认识存储形态」，而 `MemoryConsolidator`
 * 构造时拿到具体类就把 Room 拖进了它的依赖面；②单测里那些只关心「什么时候整理」的用例
 * 不必为了一条落库路径去造 Room DAO 的假实现。
 */
interface MemoryProposalStore {
    /** 换掉 [memoryId] 的待决策建议（空列表 = 什么都不做） */
    suspend fun savePending(memoryId: String, proposals: List<MemoryConsolidationProposal>)

    /** 待决策的建议 */
    suspend fun pending(memoryId: String): List<MemoryConsolidationProposal>

    /** 有建议待决策的记忆库 */
    suspend fun memoryIdsWithPending(): List<String>

    /** 应用建议（按 batch 原子；目标缺失判 stale 且不应用） */
    suspend fun apply(memoryId: String, ids: Set<Long>): MemoryProposalApplyResult

    /** 拒绝建议 */
    suspend fun reject(memoryId: String, ids: Set<Long>): Int
}

/**
 * 记忆整理**建议**的落库、恢复与应用（`docs/design/design-memory-retrieval-and-consolidation.md` §5）。
 *
 * ## 为什么建这个类（修的是两条并存的问题）
 *
 * 1. **建议只活在进程内**：落库前它们住在 `MemoryConsolidationStatus` 的 `MutableStateFlow` 里，
 *    进程一重启就没了；
 * 2. **全仓没有任何应用路径**。而 `memoryModifyApproval` **默认 true** ⇒ `proposeOnly` 是
 *    **默认行为** ⇒ 默认配置下自动整理会持续烧模型调用，产出永远无法采纳。
 *
 * ## 应用安全：逐条校验 + 按批原子
 *
 * 从「产出建议」到「用户点采纳」之间，记忆库可能已被编辑、或被另一次整理改过。所以：
 *
 * - **目标不存在** ⇒ 整批判 `stale`、**不应用**（`edit` 的 `updateContent` 对缺失 id 会抛，
 *   不校验就是一次崩溃而不是一条提示）；
 * - **同一 batch 原子**：一次合并是 `edit` + `delete` 两条，只应用其中一条会得到
 *   「内容改对了但旧的没删」或反过来的半成品。任一条前提不成立 ⇒ 整批不动；
 * - **不静默跳过**：判 `stale` 是**留痕**的（`decision='stale'` + `decided_at`），
 *   界面据此说明「这条已经不能安全应用了」，与用户主动拒绝区分开。
 *
 * **不做「内容未被改写」的前置校验**：那需要把提案时的目标 `updated_at`（或内容哈希）也存下来，
 * 而现有工具回执里拿不到它（`proposalSink` 只收到模型给的参数）。替代方案是**二次确认界面直接
 * 并排显示「目标当前内容」与「将被写成的内容」**——让用户当那道前置条件。这比存一个会随整理
 * 自相矛盾的时间戳更省也更准（设计稿 §6 已把这条记为待拍板项，此处按「界面承担」落地）。
 */
class MemoryProposalRepository(
    private val proposalDAO: MemoryProposalDAO,
    private val memoryRepository: MemoryRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : MemoryProposalStore {
    companion object {
        /** 审计行保留上限（每记忆库）：界面只看 pending，已决策的行只是账 */
        const val DECIDED_AUDIT_KEEP = 100
    }

    /**
     * 换掉 [memoryId] 的待决策建议（新一轮整理提出建议时调用）。
     *
     * 同一批建议共用一个 [MemoryConsolidationProposal.batchId]（空则现生成一个）。
     */
    override suspend fun savePending(
        memoryId: String,
        proposals: List<MemoryConsolidationProposal>,
    ) {
        if (proposals.isEmpty()) return
        val at = now()
        val batchId = proposals.firstOrNull { it.batchId.isNotBlank() }?.batchId
            ?: Uuid.random().toString()
        proposalDAO.deletePending(memoryId)
        proposalDAO.insertAll(
            proposals.map { proposal ->
                MemoryProposalEntity(
                    memoryId = memoryId,
                    batchId = proposal.batchId.ifBlank { batchId },
                    action = proposal.action,
                    targetId = proposal.targetId,
                    content = proposal.content,
                    importance = proposal.importance,
                    description = proposal.description,
                    createdAt = if (proposal.createdAt > 0L) proposal.createdAt else at,
                )
            },
        )
    }

    /** 某记忆库待决策的建议（进程重启后把界面恢复出来的唯一来源） */
    override suspend fun pending(memoryId: String): List<MemoryConsolidationProposal> =
        proposalDAO.getPending(memoryId).map { it.toProposal() }

    /** 有建议待决策的记忆库（启动时逐库恢复用；空库不返回） */
    override suspend fun memoryIdsWithPending(): List<String> = proposalDAO.memoryIdsWithPending()

    /**
     * 应用 [ids] 指向的建议，**按 batch 原子**。
     *
     * @return 实际应用/判陈旧的条数（界面据此说「应用了 N 条、M 条已过期」）
     */
    override suspend fun apply(memoryId: String, ids: Set<Long>): MemoryProposalApplyResult {
        if (ids.isEmpty()) return MemoryProposalApplyResult()
        val rows = proposalDAO.getPending(memoryId).filter { it.id in ids }
        if (rows.isEmpty()) return MemoryProposalApplyResult()

        // 目标是否还在：一次取全库（≤200 条），避免逐条查询
        val existingIds = memoryRepository.getMemoriesOfAssistant(memoryId).mapTo(mutableSetOf()) { it.id }

        var applied = 0
        var stale = 0
        rows.groupBy { it.batchId }.forEach { (_, batch) ->
            val missing = batch.any { it.action != "create" && it.targetId !in existingIds }
            if (missing) {
                // 整批不动：半成品比不应用更糟（合并语义的 edit/delete 必须同生共死）
                batch.forEach {
                    proposalDAO.updateOne(
                        it.id,
                        MemoryProposalEntity.DECISION_STALE,
                        now(),
                    )
                }
                stale += batch.size
                return@forEach
            }
            batch.forEach { row ->
                when (row.action) {
                    "create" -> memoryRepository.addMemory(
                        memoryId,
                        row.content,
                        row.importance,
                        row.description,
                    )

                    "edit" -> memoryRepository.updateContent(
                        requireNotNull(row.targetId),
                        row.content,
                        row.importance,
                        row.description,
                    )
                    // 目标在 apply 前的存在性已校验；这里再兜一层：目标若在本批内被前一条删掉，
                    // delete 是幂等 no-op（不抛），而 edit 会抛 ⇒ 上面那条 missing 校验按批全查，
                    // 所以同批「先 edit 后 delete 同一条」不会被误判
                    "delete" -> memoryRepository.deleteMemory(requireNotNull(row.targetId))
                    else -> Unit
                }
                proposalDAO.updateOne(row.id, MemoryProposalEntity.DECISION_APPLIED, now())
                applied++
            }
        }
        proposalDAO.pruneDecided(memoryId, DECIDED_AUDIT_KEEP)
        return MemoryProposalApplyResult(applied = applied, stale = stale)
    }

    /** 拒绝 [ids] 指向的建议（用户显式说不要）。不做存在性校验——拒绝不需要目标成立。 */
    override suspend fun reject(memoryId: String, ids: Set<Long>): Int {
        if (ids.isEmpty()) return 0
        val rows = proposalDAO.getPending(memoryId).filter { it.id in ids }
        if (rows.isEmpty()) return 0
        proposalDAO.updateDecision(rows.map { it.id }, MemoryProposalEntity.DECISION_REJECTED, now())
        proposalDAO.pruneDecided(memoryId, DECIDED_AUDIT_KEEP)
        return rows.size
    }
}

/** [MemoryProposalRepository.apply] 的结果（界面的两句话文案靠它）。 */
data class MemoryProposalApplyResult(
    val applied: Int = 0,
    val stale: Int = 0,
) {
    val isEmpty: Boolean get() = applied == 0 && stale == 0
}

private fun MemoryProposalEntity.toProposal() = MemoryConsolidationProposal(
    action = action,
    targetId = targetId,
    content = content,
    importance = importance,
    description = description,
    id = id,
    batchId = batchId,
    createdAt = createdAt,
)
