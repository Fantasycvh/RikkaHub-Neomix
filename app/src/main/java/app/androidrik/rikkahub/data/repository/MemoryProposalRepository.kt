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

    /** 最近被用户**拒绝**的建议（整理提示据此避免原地重提） */
    suspend fun recentlyRejected(
        memoryId: String,
        limit: Int = MemoryProposalRepository.REFUSED_FEEDBACK_LIMIT,
    ): List<MemoryConsolidationProposal>

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

        /**
         * 喂给整理提示的「已拒绝」条数上限。
         *
         * 取 20 而不是全部：这是**提示**不是日志，20 条足够覆盖近期的反复；
         * 全量灌进去会与库快照抢预算，而库快照本身已经只有 4000 token（见 §5.6）。
         */
        const val REFUSED_FEEDBACK_LIMIT = 20
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

    override suspend fun recentlyRejected(memoryId: String, limit: Int): List<MemoryConsolidationProposal> =
        proposalDAO.getRecentlyRejected(memoryId, limit).map { it.toProposal() }

    /**
     * 应用 [ids] 指向的建议。
     *
     * ## 安全不变式（2026-10-16 真机后**收窄**，第一版写错了）
     *
     * 第一版写的是「同一 batch 原子 / 整批同生共死」，实现成了「按勾选过滤后再分组」——
     * 于是**只在勾选集合内原子**，用户拆开勾选时半成品照样发生（真机已发生：新旧两份
     * 「全部历史对话总纲」并存）。重新想过之后，**笼统的整批强制也不是对的规则**：
     *
     * - `create` / `edit` 单独应用**不丢内容**，最多留下一条可回收的重复（可随时再删）⇒ **放行**；
     * - `delete` 单独应用（同批还有没被应用的成员）**会丢内容**——那条 delete 的语义是
     *   「内容已搬到别处，这条可以退休了」，而「搬到别处」正是同批那条 `edit`；
     *   edit 没应用就删 = 内容凭空消失 ⇒ **拒绝这一条**。
     *
     * 所以规则是**逐条判、不是逐批判**：遇到前提不成立的 `delete` 就**跳过它**，
     * 其余照常应用。跳过的那条保持 `pending`（**不消耗决策**：用户没说要放弃它，
     * 只是这次勾选不足以安全执行），计入 [MemoryProposalApplyResult.blocked]，
     * 由界面说明「这条删除要连同同批的改写一起勾选」。
     *
     * 这正好让「采纳改写、先留着旧的那条」成为**受支持的操作**（真机上用户想做的就是这个），
     * 同时把「只删不改」这条会丢内容的路径堵死。
     */
    override suspend fun apply(memoryId: String, ids: Set<Long>): MemoryProposalApplyResult {
        if (ids.isEmpty()) return MemoryProposalApplyResult()
        // 全量 pending：判断「同批成员是否都被勾选」必须看**没被勾选的**那些，所以不能先过滤
        val allPending = proposalDAO.getPending(memoryId)
        val rows = allPending.filter { it.id in ids }
        if (rows.isEmpty()) return MemoryProposalApplyResult()

        // 目标是否还在：一次取全库（≤200 条），避免逐条查询
        val existingIds = memoryRepository.getMemoriesOfAssistant(memoryId).mapTo(mutableSetOf()) { it.id }

        var applied = 0
        var stale = 0
        var blocked = 0
        rows.groupBy { it.batchId }.forEach { (batchId, batch) ->
            val missing = batch.any { it.action != "create" && it.targetId !in existingIds }
            if (missing) {
                // 目标已经不在（或早被改过）⇒ 这一批的前提整体不成立，不应用并留痕
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

            // 「同批是否还有没被勾选的成员」：用**全量 pending 里同 batch 的行数**与本次勾选数比
            val siblingsUnselected = allPending.count { it.batchId == batchId } > batch.size
            val hasDelete = batch.any { it.action == "delete" }
            if (siblingsUnselected && hasDelete) {
                // 只应用了半批，而半批里有 delete ⇒ delete 会丢内容。
                // **跳过 delete**，同批的 create/edit 照常应用（它们单独应用是安全的）。
                batch.filter { it.action != "delete" }.forEach { row -> applyRow(memoryId, row) }
                applied += batch.count { it.action != "delete" }
                blocked += batch.count { it.action == "delete" }
                return@forEach
            }

            batch.forEach { row -> applyRow(memoryId, row) }
            applied += batch.size
        }
        proposalDAO.pruneDecided(memoryId, DECIDED_AUDIT_KEEP)
        return MemoryProposalApplyResult(applied = applied, stale = stale, blocked = blocked)
    }

    /** 落一条建议，并标成已应用（[apply] 的两条路径共用） */
    private suspend fun applyRow(memoryId: String, row: MemoryProposalEntity) {
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

            "delete" -> memoryRepository.deleteMemory(requireNotNull(row.targetId))
            else -> Unit
        }
        proposalDAO.updateOne(row.id, MemoryProposalEntity.DECISION_APPLIED, now())
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

/** [MemoryProposalRepository.apply] 的结果（界面文案靠它）。 */
data class MemoryProposalApplyResult(
    val applied: Int = 0,
    /** 目标已不在 ⇒ 判陈旧、不应用（与用户主动拒绝区分开） */
    val stale: Int = 0,
    /**
     * 这次**没被执行**的 `delete`：同批还有成员没被勾选，单独删会丢内容。
     *
     * 与 [stale] 分开是因为**处理方式不同**：stale 是「这条没用了」，blocked 是
     * 「你这次勾得不够，把同批的改写一起勾上就能删」。保持 `pending` 不消耗决策。
     */
    val blocked: Int = 0,
) {
    val isEmpty: Boolean get() = applied == 0 && stale == 0 && blocked == 0
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
