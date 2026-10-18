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

    /** 短事务应用本次选择；create/edit 可部分采纳，delete 保留同批依赖校验。 */
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
 * ## 存储事务与产品采纳规则是两个层次
 *
 * - **真实短事务**：保存、拒绝、应用分别把读取、记忆写入、决策与审计裁剪放在同一事务中，
 *   异常时整体回滚。共享 runner 的嵌套调用加入外层事务，便于整理器组合持久化；不在事务里运行 LLM。
 * - **产品允许部分采纳**：`create` / `edit` 可以单独采纳；`delete` 仍要求同批待决策成员
 *   一起勾选，且同批不能有 rejected/stale 成员，否则保持 pending 并计入 blocked。
 *   已 applied 的同批成员不阻止删除；这不是「整个 batch 必须同生共死」。
 * - **作用域与陈旧校验**：目标必须仍在本记忆库中；本次选中的同批成员有目标缺失或跨库时，
 *   将该组选中成员记为 `stale`，不应用。未选中成员不因此消耗决策。
 * - **不静默跳过**：`stale` 留痕（`decision='stale'` + `decided_at`），与用户主动拒绝区分开。
 *
 * **不做「内容未被改写」的前置校验**：那需要把提案时的目标 `updated_at`（或内容哈希）也存下来，
 * 而现有工具回执里拿不到它（`proposalSink` 只收到模型给的参数）。替代方案是**二次确认界面直接
 * 并排显示「目标当前内容」与「将被写成的内容」**——让用户当那道前置条件。这比存一个会随整理
 * 自相矛盾的时间戳更省也更准（设计稿 §6 已把这条记为待拍板项，此处按「界面承担」落地）。
 */
class MemoryProposalRepository(
    private val proposalDAO: MemoryProposalDAO,
    private val memoryRepository: MemoryRepository,
    private val transactions: MemoryTransactionRunner,
    private val now: () -> Long = System::currentTimeMillis,
) : MemoryProposalStore {
    companion object {
        /** 每库可裁剪审计上限；仍有 pending 的同批已决策成员另作必要守卫证据保留。 */
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
    ) = transactions.run {
        if (proposals.isEmpty()) return@run
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
        proposalDAO.pruneDecided(memoryId, DECIDED_AUDIT_KEEP)
    }

    /** 某记忆库待决策的建议（进程重启后把界面恢复出来的唯一来源） */
    override suspend fun pending(memoryId: String): List<MemoryConsolidationProposal> =
        proposalDAO.getPending(memoryId).map { it.toProposal() }

    /** 有建议待决策的记忆库（启动时逐库恢复用；空库不返回） */
    override suspend fun memoryIdsWithPending(): List<String> = proposalDAO.memoryIdsWithPending()

    override suspend fun recentlyRejected(memoryId: String, limit: Int): List<MemoryConsolidationProposal> =
        proposalDAO.getRecentlyRejected(memoryId, limit).map { it.toProposal() }

    /**
     * 应用 [ids] 指向的建议；读取、校验、写入、决策与裁剪共享一个短事务。
     *
     * 产品规则独立于事务原子性：`create` / `edit` 可单独采纳，允许暂留重复；
     * `delete` 若仍有同批待决策成员未选中，或同批有 rejected/stale 成员，则保持 pending 并计入 blocked。
     * 先前已应用的同批改写不再 pending，后续可单独采纳剩余删除。
     *
     * 本次选中的同批成员若有目标缺失或跨库，则这些选中成员一起记为 stale；
     * 这不是强制采纳整个 batch。存储异常或本次写入的记录被后续容量淘汰时，回滚整个 apply 调用；
     * 用户显式采纳的删除不属于容量淘汰，不要求被明确删除的记录继续存活。
     */
    override suspend fun apply(memoryId: String, ids: Set<Long>): MemoryProposalApplyResult = transactions.run {
        if (ids.isEmpty()) return@run MemoryProposalApplyResult()
        // 全量 pending：判断「同批成员是否都被勾选」必须看**没被勾选的**那些，所以不能先过滤
        val allPending = proposalDAO.getPending(memoryId)
        val rows = allPending.filter { it.id in ids }
        if (rows.isEmpty()) return@run MemoryProposalApplyResult()

        var applied = 0
        var stale = 0
        var blocked = 0
        var blockedByDecision = 0
        val writtenIds = mutableSetOf<String>()
        val explicitlyDeletedIds = mutableSetOf<String>()
        suspend fun applyTrackedRow(row: MemoryProposalEntity) {
            val canonicalId = applyRow(memoryId, row) ?: return
            when (row.action) {
                "create", "edit" -> writtenIds += canonicalId
                "delete" -> explicitlyDeletedIds += canonicalId
            }
        }
        rows.groupBy { it.batchId }.forEach { (batchId, batch) ->
            val missing = batch.any { row ->
                row.action != "create" && (row.targetId == null ||
                    memoryRepository.getMemoryInStore(memoryId, row.targetId) == null)
            }
            if (missing) {
                // 目标缺失或不属于本库 ⇒ 本次选中的同批成员不应用并留痕；不检测内容是否改过。
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
            val failedSibling = hasDelete && proposalDAO.getBatch(memoryId, batchId).any {
                it.decision == MemoryProposalEntity.DECISION_REJECTED ||
                    it.decision == MemoryProposalEntity.DECISION_STALE
            }
            if ((siblingsUnselected || failedSibling) && hasDelete) {
                // 同批仍有未选中的待办，或其搬运前提已被拒绝/判陈旧 ⇒ 保守阻止 delete。
                // **跳过 delete**，同批的 create/edit 照常应用（它们单独应用是安全的）。
                batch.filter { it.action != "delete" }.forEach { row -> applyTrackedRow(row) }
                applied += batch.count { it.action != "delete" }
                val blockedDeletes = batch.count { it.action == "delete" }
                blocked += blockedDeletes
                if (failedSibling) blockedByDecision += blockedDeletes
                return@forEach
            }

            batch.forEach { row -> applyTrackedRow(row) }
            applied += batch.size
        }
        // Later creates can evict an earlier create/edit even when each addMemory itself survived.
        // Compare canonical IDs, not proposal aliases (legacy numeric targets may refer to the same row).
        check((writtenIds - explicitlyDeletedIds).all { memoryRepository.getMemoryInStore(memoryId, it) != null }) {
            "Memory capacity conflict: proposal write was evicted during apply"
        }
        proposalDAO.pruneDecided(memoryId, DECIDED_AUDIT_KEEP)
        MemoryProposalApplyResult(
            applied = applied, stale = stale, blocked = blocked, blockedByDecision = blockedByDecision,
        )
    }

    /** 落一条建议、标成已应用并返回受影响的 canonical id（不是提案中的旧数字别名）。 */
    private suspend fun applyRow(memoryId: String, row: MemoryProposalEntity): String? {
        val canonicalId = when (row.action) {
            "create" -> memoryRepository.addMemory(
                memoryId,
                row.content,
                row.importance,
                row.description,
            ).id

            "edit" -> memoryRepository.updateContentInStore(
                memoryId,
                requireNotNull(row.targetId),
                row.content,
                row.importance,
                row.description,
            ).id

            "delete" -> {
                val targetId = requireNotNull(row.targetId)
                val canonicalId = memoryRepository.getMemoryInStore(memoryId, targetId)?.id
                val deleted = memoryRepository.deleteMemoryInStore(memoryId, targetId)
                if (deleted) canonicalId else null
            }
            else -> null
        }
        proposalDAO.updateOne(row.id, MemoryProposalEntity.DECISION_APPLIED, now())
        return canonicalId
    }

    /** 拒绝 [ids] 指向的建议（用户显式说不要）。不做存在性校验——拒绝不需要目标成立。 */
    override suspend fun reject(memoryId: String, ids: Set<Long>): Int = transactions.run {
        if (ids.isEmpty()) return@run 0
        val rows = proposalDAO.getPending(memoryId).filter { it.id in ids }
        if (rows.isEmpty()) return@run 0
        proposalDAO.updateDecision(rows.map { it.id }, MemoryProposalEntity.DECISION_REJECTED, now())
        proposalDAO.pruneDecided(memoryId, DECIDED_AUDIT_KEEP)
        rows.size
    }
}

/** [MemoryProposalRepository.apply] 的结果（界面文案靠它）。 */
data class MemoryProposalApplyResult(
    val applied: Int = 0,
    /** 目标已不在 ⇒ 判陈旧、不应用（与用户主动拒绝区分开） */
    val stale: Int = 0,
    /**
     * 这次**没被执行**的 `delete`：同批有未选中的待办或 rejected/stale 成员，不能安全删除。
     *
     * 与 [stale] 分开是因为**处理方式不同**：stale 是「这条没用了」，blocked 是
     * 「同批搬运前提未满足，本次不能删」。保持 `pending` 不消耗决策。
     */
    val blocked: Int = 0,
    /** [blocked] 的子集：同批有 rejected/stale 成员，不能仅靠补选修复，需要重新整理。 */
    val blockedByDecision: Int = 0,
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
