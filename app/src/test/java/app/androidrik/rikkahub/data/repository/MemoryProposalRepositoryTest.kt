package app.androidrik.rikkahub.data.repository

import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryProposalDAO
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 整理建议的**落库 / 恢复 / 应用**（`docs/design/design-memory-retrieval-and-consolidation.md` §5）。
 *
 * 产品规则使用 DirectMemoryTransactionRunner；事务契约使用可重入、带快照回滚的内存 fake。
 * fake 仅验证调用边界与失败传播，不证明 Room/SQLite 原子性；真实故障回滚需 instrumentation 覆盖。
 *
 * 这套用例对应的是本轮修掉的两条并存问题之一：落库前建议只活在进程内、**且全仓没有应用路径**，
 * 而 `memoryModifyApproval` 默认 true ⇒ `proposeOnly` 是默认行为 ⇒ 默认配置下自动整理白烧模型调用。
 */
class MemoryProposalRepositoryTest {

    /** 最小内存建议表：语义对齐 DAO 的 SQL（pending 过滤、替换、改判、保留上限） */
    private class FakeProposalDAO : MemoryProposalDAO {
        val rows = mutableListOf<MemoryProposalEntity>()
        private var nextId = 1L
        var afterInsert: (() -> Unit)? = null
        var afterDecision: (() -> Unit)? = null
        var afterPrune: (() -> Unit)? = null
        var afterGetPending: (suspend () -> Unit)? = null
        var afterDeletePending: (suspend () -> Unit)? = null

        fun rollbackPoint(): () -> Unit {
            val saved = rows.toList()
            val savedNextId = nextId
            return {
                rows.clear()
                rows.addAll(saved)
                nextId = savedNextId
            }
        }

        override suspend fun getPending(memoryId: String): List<MemoryProposalEntity> {
            val pending = rows.filter {
                it.memoryId == memoryId && it.decision == MemoryProposalEntity.DECISION_PENDING
            }.sortedWith(compareBy({ it.createdAt }, { it.id }))
            afterGetPending?.invoke()
            return pending
        }

        override suspend fun getBatch(memoryId: String, batchId: String): List<MemoryProposalEntity> =
            rows.filter { it.memoryId == memoryId && it.batchId == batchId }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))

        override suspend fun getRecentlyRejected(
            memoryId: String,
            limit: Int,
        ): List<MemoryProposalEntity> =
            rows.filter { it.memoryId == memoryId && it.decision == MemoryProposalEntity.DECISION_REJECTED }
                .sortedWith(compareByDescending<MemoryProposalEntity> { it.decidedAt }.thenByDescending { it.id })
                .take(limit)

        override suspend fun memoryIdsWithPending(): List<String> =
            rows.filter { it.decision == MemoryProposalEntity.DECISION_PENDING }
                .map { it.memoryId }
                .distinct()

        override suspend fun insertAll(proposals: List<MemoryProposalEntity>) {
            proposals.forEach { rows += it.copy(id = nextId++) }
            afterInsert?.invoke()
        }

        override suspend fun deletePending(memoryId: String) {
            rows.removeAll {
                it.memoryId == memoryId && it.decision == MemoryProposalEntity.DECISION_PENDING
            }
            afterDeletePending?.invoke()
        }

        override suspend fun updateDecision(ids: List<Long>, decision: String, now: Long) {
            rows.replaceAll { if (it.id in ids) it.copy(decision = decision, decidedAt = now) else it }
            afterDecision?.invoke()
        }

        override suspend fun updateOne(id: Long, decision: String, now: Long) {
            rows.replaceAll { if (it.id == id) it.copy(decision = decision, decidedAt = now) else it }
            afterDecision?.invoke()
        }

        override suspend fun pruneDecided(memoryId: String, keep: Int) {
            val pendingBatches = rows.filter {
                it.memoryId == memoryId && it.decision == MemoryProposalEntity.DECISION_PENDING
            }.map { it.batchId }.toSet()
            val decided = rows.filter {
                it.memoryId == memoryId && it.decision != MemoryProposalEntity.DECISION_PENDING &&
                    it.batchId !in pendingBatches
            }
            val drop = decided.sortedWith(compareByDescending<MemoryProposalEntity> { it.decidedAt }
                .thenByDescending { it.id }).drop(keep).map { it.id }.toSet()
            rows.removeAll { it.id in drop }
            afterPrune?.invoke()
        }
    }

    private class FakeMemoryDAO : MemoryDAO {
        val rows = mutableListOf<MemoryEntity>()
        private var nextId = 1
        var afterDeleteMemory: ((MemoryEntity) -> Unit)? = null

        fun rollbackPoint(): () -> Unit {
            val saved = rows.toList()
            val savedNextId = nextId
            return {
                rows.clear()
                rows.addAll(saved)
                nextId = savedNextId
            }
        }

        override fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>> =
            MutableStateFlow(rows.filter { it.assistantId == assistantId })

        override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> =
            rows.filter { it.assistantId == assistantId }

        override fun getAllMemoriesFlow(): Flow<List<MemoryEntity>> = MutableStateFlow(rows.toList())

        override suspend fun getAllMemories(): List<MemoryEntity> = rows.toList()

        override suspend fun getMemoryById(id: Int): MemoryEntity? = rows.find { it.id == id }

        override suspend fun getMemoryByPublicId(publicId: String): MemoryEntity? =
            rows.find { it.publicId == publicId }

        override suspend fun publicIdExists(publicId: String): Boolean = rows.any { it.publicId == publicId }

        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val id = nextId++
            rows += memory.copy(id = id)
            return id.toLong()
        }

        override suspend fun insertMemories(memories: List<MemoryEntity>) {
            memories.forEach { insertMemory(it) }
        }

        override suspend fun updateMemory(memory: MemoryEntity) {
            rows.replaceAll { if (it.id == memory.id) memory else it }
        }

        private fun updateInStore(
            id: Int,
            assistantId: String,
            accepts: (MemoryEntity) -> Boolean = { true },
            update: (MemoryEntity) -> MemoryEntity,
        ): Int {
            val index = rows.indexOfFirst { it.id == id && it.assistantId == assistantId && accepts(it) }
            if (index < 0) return 0
            rows[index] = update(rows[index])
            return 1
        }

        override suspend fun updateContentFields(
            id: Int,
            assistantId: String,
            content: String,
            importance: Float?,
            description: String?,
            now: Long,
        ): Int = updateInStore(id, assistantId) {
            it.copy(
                content = content, importance = importance ?: it.importance,
                description = description ?: it.description, updatedAt = now,
            )
        }

        override suspend fun updateImportanceFields(
            id: Int, assistantId: String, importance: Float, now: Long,
        ): Int = updateInStore(id, assistantId) { it.copy(importance = importance, updatedAt = now) }

        override suspend fun updateAnchor(id: Int, assistantId: String, isAnchor: Boolean): Int =
            updateInStore(id, assistantId, accepts = { it.isAnchor != isAnchor }) { it.copy(isAnchor = isAnchor) }

        override suspend fun backfillDescription(id: Int, assistantId: String, description: String): Int =
            updateInStore(id, assistantId, accepts = { it.description.isEmpty() }) {
                it.copy(description = description)
            }

        override suspend fun recordReadInStore(id: Int, assistantId: String, now: Long): Int =
            updateInStore(id, assistantId) {
                it.copy(readCount = it.readCount + 1, lastReadAt = maxOf(it.lastReadAt, now))
            }

        override suspend fun deleteMemoryInStore(id: Int, assistantId: String): Int {
            val removed = rows.removeAll { it.id == id && it.assistantId == assistantId }
            return if (removed) 1 else 0
        }

        override suspend fun recordRead(id: Int, now: Long) = Unit

        override suspend fun deleteMemory(id: Int) {
            val removed = rows.filter { it.id == id }
            rows.removeAll { it.id == id }
            removed.forEach { afterDeleteMemory?.invoke(it) }
        }

        override suspend fun deleteMemoryByPublicId(publicId: String) {
            rows.removeAll { it.publicId == publicId }
        }

        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            rows.removeAll { it.assistantId == assistantId }
        }
    }

    /** Contract fake only: serializes writers and restores both tables on failure, not a Room substitute. */
    private class SnapshotTransactions(
        private val proposals: FakeProposalDAO,
        private val memories: FakeMemoryDAO,
    ) : MemoryTransactionRunner {
        private class Active(val runner: SnapshotTransactions) : AbstractCoroutineContextElement(Key) {
            companion object Key : CoroutineContext.Key<Active>
        }

        private val mutex = Mutex()
        var commits = 0
            private set
        var rollbacks = 0
            private set

        override suspend fun <T> run(block: suspend () -> T): T {
            if (currentCoroutineContext()[Active]?.runner === this) return block()
            return mutex.withLock {
                val restoreProposals = proposals.rollbackPoint()
                val restoreMemories = memories.rollbackPoint()
                try {
                    withContext(Active(this)) { block() }.also { commits++ }
                } catch (failure: Throwable) {
                    restoreProposals()
                    restoreMemories()
                    rollbacks++
                    throw failure
                }
            }
        }
    }

    private class TransactionFixture {
        val proposals = FakeProposalDAO()
        val memories = FakeMemoryDAO()
        val transactions = SnapshotTransactions(proposals, memories)
        val memoryRepository = MemoryRepository(memories, transactions)
        val repository = MemoryProposalRepository(proposals, memoryRepository, transactions, now = { 1_000L })
    }

    private class InjectedFailure : IllegalStateException("injected DAO failure")

    private suspend fun expectInjectedFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("The DAO failure must propagate to the transaction owner")
        } catch (_: InjectedFailure) {
            // Expected: repositories must not swallow persistence failures and commit partial work.
        }
    }

    private val proposalDAO = FakeProposalDAO()
    private val memoryRepository = MemoryRepository(FakeMemoryDAO(), DirectMemoryTransactionRunner)
    private var clock = 1_000L
    private val repository = MemoryProposalRepository(
        proposalDAO, memoryRepository, DirectMemoryTransactionRunner, now = { clock++ },
    )

    private suspend fun seedMemory(content: String, assistantId: String = "store"): String =
        memoryRepository.addMemory(assistantId, content).id

    private fun proposal(
        action: String,
        targetId: String? = null,
        content: String = "",
        batchId: String = "b1",
    ) = MemoryConsolidationProposal(
        action = action,
        targetId = targetId,
        content = content,
        batchId = batchId,
    )

    // ---- 落库与恢复 ----

    @Test
    fun `pending survives what used to be process death`() = runBlocking {
        // 落库的意义就是这条：以前建议只活在 MutableStateFlow 里，重启即失
        repository.savePending("store", listOf(proposal("create", content = "likes tea")))
        val restored = repository.pending("store")
        assertEquals(1, restored.size)
        assertTrue("恢复出来的建议必须带 id（界面按 id 勾选与提交）", restored.first().id > 0L)
        assertEquals(listOf("store"), repository.memoryIdsWithPending())
    }

    @Test
    fun `saving a new batch replaces the pending set`() = runBlocking {
        repository.savePending("store", listOf(proposal("create", content = "first", batchId = "b1")))
        repository.savePending("store", listOf(proposal("create", content = "second", batchId = "b2")))
        val pending = repository.pending("store")
        assertEquals("待决策集合代表「当前待办」，叠上几轮会出现互相矛盾的重复改动", 1, pending.size)
        assertEquals("second", pending.first().content)
    }

    @Test
    fun `saving an empty list does not wipe pending suggestions`() = runBlocking {
        repository.savePending("store", listOf(proposal("create", content = "keep me")))
        repository.savePending("store", emptyList())
        assertEquals("空跑不该抹掉待办（与状态的黏语义同源）", 1, repository.pending("store").size)
    }

    // ---- 应用 ----

    @Test
    fun `apply creates edits and deletes for real`() = runBlocking {
        val target = seedMemory("old content")
        val toDelete = seedMemory("remove me")
        repository.savePending(
            "store",
            listOf(
                proposal("create", content = "brand new"),
                proposal("edit", targetId = target, content = "new content", batchId = "b1"),
                proposal("delete", targetId = toDelete, batchId = "b2"),
            ),
        )
        val ids = repository.pending("store").map { it.id }.toSet()
        val result = repository.apply("store", ids)

        assertEquals(3, result.applied)
        assertEquals(0, result.stale)
        val contents = memoryRepository.getMemoriesOfAssistant("store").map { it.content }
        assertTrue("create 要真的建", contents.contains("brand new"))
        assertTrue("edit 要真的改", contents.contains("new content"))
        assertTrue("delete 要真的删", contents.none { it == "remove me" })
        assertTrue("应用后待决策集合清空", repository.pending("store").isEmpty())
    }

    @Test
    fun `selected batch members with a missing target are marked stale together`() = runBlocking {
        // 仅本次选中的同批成员共同校验目标；一个缺失则这组选中成员都判 stale。
        // 不意味着用户必须采纳整个 batch（create/edit 单独采纳另有用例）。
        val gone = seedMemory("will vanish")
        repository.savePending(
            "store",
            listOf(
                proposal("edit", targetId = gone, content = "edited", batchId = "batch-x"),
                proposal("delete", targetId = "ffffffff", batchId = "batch-x"),
            ),
        )
        val ids = repository.pending("store").map { it.id }.toSet()
        val result = repository.apply("store", ids)

        assertEquals("本次选中的同批成员不应用", 0, result.applied)
        assertEquals("本次选中的同批成员判 stale", 2, result.stale)
        val contents = memoryRepository.getMemoriesOfAssistant("store").map { it.content }
        assertTrue("目标还在、内容不该被改", contents.contains("will vanish"))
        assertTrue("也不该凭空删掉不存在的东西", contents.size == 1)
    }

    // ---- 收窄后的安全不变式（2026-10-16 真机后改正） ----

    @Test
    fun `applying only the delete of a batch deletes nothing`() = runBlocking {
        // **这是装机暴露的洞**：一次「合并」是 edit + delete。单独执行那条 delete，
        // 而它要搬运的内容还在同批那条没被应用的 edit 里 ⇒ 内容凭空消失。
        val keep = seedMemory("the surviving copy")
        val retire = seedMemory("the older duplicate")
        repository.savePending(
            "store",
            listOf(
                proposal("edit", targetId = keep, content = "merged content", batchId = "merge"),
                proposal("delete", targetId = retire, batchId = "merge"),
            ),
        )
        val deleteRow = repository.pending("store").first { it.action == "delete" }

        val result = repository.apply("store", setOf(deleteRow.id))

        assertEquals("一条都不该被执行", 0, result.applied)
        assertEquals("那条 delete 计入 blocked", 1, result.blocked)
        assertEquals("普通未勾选阻挡不属于已失败决策", 0, result.blockedByDecision)
        val contents = memoryRepository.getMemoriesOfAssistant("store").map { it.content }
        assertTrue("目标绝不能被删", contents.contains("the older duplicate"))
        assertTrue("edit 也没被单独偷偷应用", contents.contains("the surviving copy"))
        assertTrue(
            "保持 pending（不消耗决策）——用户把同批的改写一起勾上就能删",
            repository.pending("store").any { it.id == deleteRow.id },
        )
    }

    @Test
    fun `applying the whole batch performs the merge`() = runBlocking {
        val keep = seedMemory("the surviving copy")
        val retire = seedMemory("the older duplicate")
        repository.savePending(
            "store",
            listOf(
                proposal("edit", targetId = keep, content = "merged content", batchId = "merge"),
                proposal("delete", targetId = retire, batchId = "merge"),
            ),
        )
        val ids = repository.pending("store").map { it.id }.toSet()
        val result = repository.apply("store", ids)

        assertEquals(2, result.applied)
        assertEquals(0, result.blocked)
        val contents = memoryRepository.getMemoriesOfAssistant("store").map { it.content }
        assertTrue("改写生效", contents.contains("merged content"))
        assertTrue("旧的退休了", contents.none { it == "the older duplicate" })
    }

    @Test
    fun `applying only the edit of a batch is allowed`() = runBlocking {
        // 真机上用户想做的正是这个：**采纳改写、先留着旧的那条**（可随时再删）。
        // edit 单独应用不丢内容，所以必须放行——否则用户会被迫连带删除。
        val keep = seedMemory("the surviving copy")
        val retire = seedMemory("the older duplicate")
        repository.savePending(
            "store",
            listOf(
                proposal("edit", targetId = keep, content = "merged content", batchId = "merge"),
                proposal("delete", targetId = retire, batchId = "merge"),
            ),
        )
        val editRow = repository.pending("store").first { it.action == "edit" }
        val result = repository.apply("store", setOf(editRow.id))

        assertEquals(1, result.applied)
        assertEquals(0, result.blocked)
        val contents = memoryRepository.getMemoriesOfAssistant("store").map { it.content }
        assertTrue("改写生效", contents.contains("merged content"))
        assertTrue("旧的那条还在（用户的意图）", contents.contains("the older duplicate"))
    }

    @Test
    fun `stale is recorded separately from rejected`() = runBlocking {
        // 用户没有拒绝它，是前提不成立了 —— 界面文案完全不同，混在一起会让用户以为自己点过拒绝
        repository.savePending("store", listOf(proposal("edit", targetId = "deadbeef", content = "x")))
        val ids = repository.pending("store").map { it.id }.toSet()
        repository.apply("store", ids)
        assertEquals(
            MemoryProposalEntity.DECISION_STALE,
            proposalDAO.rows.single().decision,
        )
        assertTrue("判过就必须留痕（decided_at 落值）", proposalDAO.rows.single().decidedAt > 0L)
    }

    @Test
    fun `apply of an id that is not pending is a no-op`() = runBlocking {
        repository.savePending("store", listOf(proposal("create", content = "x")))
        assertTrue(repository.apply("store", setOf(9999L)).isEmpty)
        assertEquals("什么都没动", 1, repository.pending("store").size)
    }

    // ---- 拒绝 ----

    @Test
    fun `reject marks the row without touching the memory library`() = runBlocking {
        val target = seedMemory("keep me")
        repository.savePending("store", listOf(proposal("delete", targetId = target)))
        val ids = repository.pending("store").map { it.id }.toSet()
        assertEquals(1, repository.reject("store", ids))

        assertEquals(1, memoryRepository.getMemoriesOfAssistant("store").size)
        assertEquals(MemoryProposalEntity.DECISION_REJECTED, proposalDAO.rows.single().decision)
        assertTrue(repository.pending("store").isEmpty())
    }

    @Test
    fun `cross-store edit and delete targets are stale including legacy numeric ids`() = runBlocking {
        val target = memoryRepository.addMemory("other-store", "foreign content")
        for (action in listOf("edit", "delete")) {
            for (reference in listOf(target.id, target.sortKey.toString())) {
                repository.savePending("store", listOf(proposal(action, targetId = reference, content = "tampered")))
                val selected = repository.pending("store").single().id
                val result = repository.apply("store", setOf(selected))
                assertEquals(MemoryProposalApplyResult(stale = 1), result)
                assertEquals("foreign content", memoryRepository.getMemoryInStore("other-store", target.id)?.content)
                assertEquals(
                    MemoryProposalEntity.DECISION_STALE,
                    proposalDAO.rows.single { it.id == selected }.decision,
                )
            }
        }
        assertTrue(memoryRepository.getMemoriesOfAssistant("store").isEmpty())
    }

    @Test
    fun `same-store legacy numeric target can be edited and then deleted`() = runBlocking {
        val target = memoryRepository.addMemory("store", "old")
        val legacyId = target.sortKey.toString()
        repository.savePending("store", listOf(proposal("edit", targetId = legacyId, content = "changed")))
        assertEquals(1, repository.apply("store", repository.pending("store").map { it.id }.toSet()).applied)
        assertEquals("changed", memoryRepository.getMemoryInStore("store", target.id)?.content)
        repository.savePending("store", listOf(proposal("delete", targetId = legacyId)))
        assertEquals(1, repository.apply("store", repository.pending("store").map { it.id }.toSet()).applied)
        assertTrue(memoryRepository.getMemoriesOfAssistant("store").isEmpty())
    }

    @Test
    fun `create may be adopted before its batch delete`() = runBlocking {
        val retired = seedMemory("keep until replacement accepted")
        repository.savePending("store", listOf(
            proposal("create", content = "replacement"),
            proposal("delete", targetId = retired),
        ))
        val create = repository.pending("store").single { it.action == "create" }
        assertEquals(MemoryProposalApplyResult(applied = 1), repository.apply("store", setOf(create.id)))
        assertEquals(2, memoryRepository.getMemoriesOfAssistant("store").size)
        val delete = repository.pending("store").single()
        assertEquals("delete", delete.action)
        assertEquals(MemoryProposalApplyResult(applied = 1), repository.apply("store", setOf(delete.id)))
        assertEquals("replacement", memoryRepository.getMemoriesOfAssistant("store").single().content)
    }

    @Test
    fun `rejected same-batch edit blocks delete but still allows create`() = runBlocking {
        val target = seedMemory("source must remain")
        repository.savePending("store", listOf(
            proposal("edit", targetId = target, content = "unaccepted rewrite"),
            proposal("create", content = "independent new content"),
            proposal("delete", targetId = target),
        ))
        val pending = repository.pending("store")
        val edit = pending.single { it.action == "edit" }
        repository.reject("store", setOf(edit.id))
        val remaining = repository.pending("store").map { it.id }.toSet()
        assertEquals(
            MemoryProposalApplyResult(applied = 1, blocked = 1, blockedByDecision = 1),
            repository.apply("store", remaining),
        )
        assertEquals("source must remain", memoryRepository.getMemoryInStore("store", target)?.content)
        assertEquals("delete", repository.pending("store").single().action)
        assertTrue(memoryRepository.getMemoriesOfAssistant("store").any { it.content == "independent new content" })
    }

    @Test
    fun `stale same-batch edit blocks later delete`() = runBlocking {
        val source = seedMemory("do not delete")
        repository.savePending("store", listOf(
            proposal("edit", targetId = "missing-id", content = "never written"),
            proposal("delete", targetId = source),
        ))
        val edit = repository.pending("store").single { it.action == "edit" }
        assertEquals(MemoryProposalApplyResult(stale = 1), repository.apply("store", setOf(edit.id)))
        val delete = repository.pending("store").single()
        assertEquals(
            MemoryProposalApplyResult(blocked = 1, blockedByDecision = 1),
            repository.apply("store", setOf(delete.id)),
        )
        assertEquals("do not delete", memoryRepository.getMemoryInStore("store", source)?.content)
        assertEquals(delete.id, repository.pending("store").single().id)
    }

    @Test
    fun `applied same-batch edit does not block later delete`() = runBlocking {
        val keep = seedMemory("replacement")
        val source = seedMemory("old source")
        repository.savePending("store", listOf(
            proposal("edit", targetId = keep, content = "merged"),
            proposal("delete", targetId = source),
        ))
        val edit = repository.pending("store").single { it.action == "edit" }
        assertEquals(MemoryProposalApplyResult(applied = 1), repository.apply("store", setOf(edit.id)))
        val delete = repository.pending("store").single()
        assertEquals(MemoryProposalApplyResult(applied = 1), repository.apply("store", setOf(delete.id)))
        assertEquals("merged", memoryRepository.getMemoriesOfAssistant("store").single().content)
    }

    @Test
    fun `pruning preserves failed sibling evidence until its batch has no pending`() = runBlocking {
        val source = seedMemory("protected source")
        repository.savePending("store", listOf(
            proposal("edit", targetId = source, content = "rejected rewrite", batchId = "protected"),
            proposal("delete", targetId = source, batchId = "protected"),
        ))
        val edit = repository.pending("store").single { it.action == "edit" }
        val delete = repository.pending("store").single { it.action == "delete" }
        repository.reject("store", setOf(edit.id))
        val refused = proposalDAO.rows.single { it.id == edit.id }
        proposalDAO.insertAll(List(MemoryProposalRepository.DECIDED_AUDIT_KEEP + 5) { index ->
            refused.copy(id = 0, batchId = "audit-$index", decidedAt = 10_000L + index)
        })
        assertEquals(
            MemoryProposalApplyResult(blocked = 1, blockedByDecision = 1),
            repository.apply("store", setOf(delete.id)),
        )
        assertEquals(refused, proposalDAO.getBatch("store", "protected").single { it.id == edit.id })
        assertEquals(
            MemoryProposalRepository.DECIDED_AUDIT_KEEP + 1,
            proposalDAO.rows.count { it.decision != MemoryProposalEntity.DECISION_PENDING },
        )
        assertEquals(
            MemoryProposalApplyResult(blocked = 1, blockedByDecision = 1),
            repository.apply("store", setOf(delete.id)),
        )
        assertEquals("protected source", memoryRepository.getMemoryInStore("store", source)?.content)

        repository.reject("store", setOf(delete.id))
        assertTrue(proposalDAO.getBatch("store", "protected").isEmpty())
        assertEquals(MemoryProposalRepository.DECIDED_AUDIT_KEEP, proposalDAO.rows.size)
    }

    @Test
    fun `failed sibling in a different store does not block the same batch id`() = runBlocking {
        val source = seedMemory("delete in this store")
        repository.savePending("other", listOf(proposal("create", content = "refused elsewhere", batchId = "shared")))
        repository.reject("other", repository.pending("other").map { it.id }.toSet())
        repository.savePending("store", listOf(proposal("delete", targetId = source, batchId = "shared")))
        val ids = repository.pending("store").map { it.id }.toSet()
        assertEquals(MemoryProposalApplyResult(applied = 1), repository.apply("store", ids))
        assertTrue(memoryRepository.getMemoriesOfAssistant("store").isEmpty())
    }

    private suspend fun TransactionFixture.seedCapacityLibrary() {
        val at = System.currentTimeMillis()
        repeat(MemoryRepository.MAX_MEMORY_COUNT) { index ->
            memories.insertMemory(MemoryEntity(
                assistantId = "store",
                publicId = "a" + index.toString(16).padStart(7, '0'),
                content = "original-$index",
                importance = if (index == 0) 0f else 1f,
                isAnchor = index != 0,
                updatedAt = at,
            ))
        }
    }

    private suspend fun expectCapacityFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Evicting this apply's surviving writes must abort the transaction")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("capacity"))
        }
    }

    @Test
    fun `later create evicting earlier create rolls back even after deleting source`() = runBlocking {
        val f = TransactionFixture()
        f.seedCapacityLibrary()
        val source = f.memories.rows.last()
        val evicted = mutableListOf<String>()
        f.memories.afterDeleteMemory = { evicted += it.content }
        f.repository.savePending("store", listOf(
            proposal("create", content = "earlier replacement").copy(importance = 0.1f),
            proposal("create", content = "later replacement").copy(importance = 0.9f),
            proposal("delete", targetId = source.publicId),
        ))
        val beforeMemories = f.memories.rows.toList()
        val beforeProposals = f.proposals.rows.toList()
        var decisions = 0
        f.proposals.afterDecision = { decisions++ }
        expectCapacityFailure { f.repository.apply("store", beforeProposals.map { it.id }.toSet()) }
        assertTrue(
            "First create must have survived until the later create evicted it",
            evicted.contains("earlier replacement"),
        )
        assertEquals(3, decisions)
        assertEquals(beforeMemories, f.memories.rows)
        assertEquals(beforeProposals, f.proposals.rows)
        assertEquals(1, f.transactions.rollbacks)
    }

    @Test
    fun `create evicting an earlier edit target rolls back its canonical write`() = runBlocking {
        val f = TransactionFixture()
        f.seedCapacityLibrary()
        val target = f.memories.rows.first()
        f.repository.savePending("store", listOf(
            proposal("edit", targetId = target.id.toString(), content = "edited replacement").copy(importance = 0.1f),
            proposal("create", content = "higher score replacement").copy(importance = 0.9f),
        ))
        val beforeMemories = f.memories.rows.toList()
        val beforeProposals = f.proposals.rows.toList()
        val evicted = mutableListOf<String>()
        f.memories.afterDeleteMemory = { evicted += it.content }
        expectCapacityFailure { f.repository.apply("store", beforeProposals.map { it.id }.toSet()) }
        assertTrue(evicted.contains("edited replacement"))
        assertEquals(beforeMemories, f.memories.rows)
        assertEquals(beforeProposals, f.proposals.rows)
    }

    @Test
    fun `explicit legacy delete of edited canonical id is not a capacity conflict`() = runBlocking {
        val f = TransactionFixture()
        val target = f.memoryRepository.addMemory("store", "original")
        f.repository.savePending("store", listOf(
            proposal("edit", targetId = target.id, content = "changed"),
            proposal("delete", targetId = target.sortKey.toString()),
        ))
        val ids = f.repository.pending("store").map { it.id }.toSet()
        assertEquals(MemoryProposalApplyResult(applied = 2), f.repository.apply("store", ids))
        assertTrue(f.memories.rows.isEmpty())
        assertTrue(f.repository.pending("store").isEmpty())
    }

    // ---- 事务调用契约（内存模拟，不证明 Room 原子性） ----

    @Test
    fun `failed replacement restores previous pending proposals`() = runBlocking {
        val f = TransactionFixture()
        f.repository.savePending("store", listOf(proposal("create", content = "previous")))
        val previous = f.proposals.rows.toList()
        f.proposals.afterInsert = { throw InjectedFailure() }
        expectInjectedFailure {
            f.repository.savePending("store", listOf(proposal("create", content = "replacement")))
        }
        assertEquals(previous, f.proposals.rows)
        assertEquals(1, f.transactions.rollbacks)
        f.proposals.afterInsert = null
        f.repository.savePending("store", listOf(proposal("create", content = "retry")))
        assertEquals(previous.single().id + 1L, f.repository.pending("store").single().id)
    }

    @Test
    fun `late apply failure rolls back memory edits deletes and decisions across batches`() = runBlocking {
        val f = TransactionFixture()
        val keep = f.memoryRepository.addMemory("store", "original")
        val retire = f.memoryRepository.addMemory("store", "duplicate")
        f.repository.savePending("store", listOf(
            proposal("edit", targetId = keep.id, content = "merged", batchId = "first"),
            proposal("delete", targetId = retire.id, batchId = "second"),
        ))
        val memoriesBefore = f.memories.rows.toList()
        val proposalsBefore = f.proposals.rows.toList()
        var decisions = 0
        f.proposals.afterDecision = { if (++decisions == 2) throw InjectedFailure() }
        expectInjectedFailure { f.repository.apply("store", proposalsBefore.map { it.id }.toSet()) }
        assertEquals(2, decisions)
        assertEquals(memoriesBefore, f.memories.rows)
        assertEquals(proposalsBefore, f.proposals.rows)
        assertEquals(1, f.transactions.rollbacks)
    }

    @Test
    fun `apply pruning failure also rolls back newly created memory`() = runBlocking {
        val f = TransactionFixture()
        f.repository.savePending("store", listOf(proposal("create", content = "new")))
        val before = f.proposals.rows.toList()
        f.proposals.afterPrune = { throw InjectedFailure() }
        expectInjectedFailure { f.repository.apply("store", before.map { it.id }.toSet()) }
        assertEquals(before, f.proposals.rows)
        assertTrue(f.memories.rows.isEmpty())
        assertEquals(1, f.transactions.rollbacks)
    }

    @Test
    fun `reject pruning failure restores decisions and pruned audit rows`() = runBlocking {
        val f = TransactionFixture()
        f.repository.savePending("store", listOf(proposal("create", content = "pending")))
        val pendingId = f.proposals.rows.single().id
        repeat(MemoryProposalRepository.DECIDED_AUDIT_KEEP + 1) { index ->
            f.proposals.rows += f.proposals.rows.first().copy(
                id = 1_000L + index,
                decision = MemoryProposalEntity.DECISION_REJECTED,
                decidedAt = index.toLong(),
            )
        }
        val before = f.proposals.rows.toList()
        f.proposals.afterPrune = { throw InjectedFailure() }
        expectInjectedFailure { f.repository.reject("store", setOf(pendingId)) }
        assertEquals(before, f.proposals.rows)
        assertTrue(f.memories.rows.isEmpty())
        assertEquals(1, f.transactions.rollbacks)
    }

    @Test
    fun `outer transaction failure rolls back nested save and apply`() = runBlocking {
        val f = TransactionFixture()
        expectInjectedFailure {
            f.transactions.run {
                f.repository.savePending("store", listOf(proposal("create", content = "never committed")))
                val ids = f.repository.pending("store").map { it.id }.toSet()
                assertEquals(1, f.repository.apply("store", ids).applied)
                throw InjectedFailure()
            }
        }
        assertTrue(f.proposals.rows.isEmpty())
        assertTrue(f.memories.rows.isEmpty())
        assertEquals(0, f.transactions.commits)
        assertEquals(1, f.transactions.rollbacks)
    }

    private class TransactionPause {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()

        suspend fun pause() {
            entered.complete(Unit)
            resume.await()
        }
    }

    private fun FakeProposalDAO.pauseNextPendingRead(): TransactionPause = TransactionPause().also { pause ->
        afterGetPending = {
            afterGetPending = null
            pause.pause()
        }
    }

    @Test
    fun `apply holds the transaction until decision and excludes reject and save`() = runBlocking {
        val f = TransactionFixture()
        f.repository.savePending("store", listOf(proposal("create", content = "accepted")))
        val ids = f.repository.pending("store").map { it.id }.toSet()
        val pause = f.proposals.pauseNextPendingRead()
        val apply = async(start = CoroutineStart.UNDISPATCHED) { f.repository.apply("store", ids) }
        pause.entered.await()
        val reject = async(start = CoroutineStart.UNDISPATCHED) { f.repository.reject("store", ids) }
        val save = async(start = CoroutineStart.UNDISPATCHED) {
            f.repository.savePending("store", listOf(proposal("create", content = "next")))
        }
        assertFalse(reject.isCompleted)
        assertFalse(save.isCompleted)
        assertEquals(MemoryProposalEntity.DECISION_PENDING, f.proposals.rows.single().decision)
        assertTrue(f.memories.rows.isEmpty())
        pause.resume.complete(Unit)
        assertEquals(MemoryProposalApplyResult(applied = 1), apply.await())
        assertEquals(0, reject.await())
        save.await()
        assertEquals("accepted", f.memories.rows.single().content)
        assertEquals("next", f.repository.pending("store").single().content)
        assertEquals(1, f.proposals.rows.count { it.decision == MemoryProposalEntity.DECISION_APPLIED })
        assertEquals(4, f.transactions.commits) // save + apply (including nested memory write) + reject + save
    }

    @Test
    fun `reject excludes apply and replacement save until its decision commits`() = runBlocking {
        val f = TransactionFixture()
        f.repository.savePending("store", listOf(proposal("create", content = "rejected")))
        val ids = f.repository.pending("store").map { it.id }.toSet()
        val pause = f.proposals.pauseNextPendingRead()
        val reject = async(start = CoroutineStart.UNDISPATCHED) { f.repository.reject("store", ids) }
        pause.entered.await()
        val apply = async(start = CoroutineStart.UNDISPATCHED) { f.repository.apply("store", ids) }
        val save = async(start = CoroutineStart.UNDISPATCHED) {
            f.repository.savePending("store", listOf(proposal("create", content = "next")))
        }
        assertFalse(apply.isCompleted)
        assertFalse(save.isCompleted)
        assertEquals(MemoryProposalEntity.DECISION_PENDING, f.proposals.rows.single().decision)
        pause.resume.complete(Unit)
        assertEquals(1, reject.await())
        assertTrue(apply.await().isEmpty)
        save.await()
        assertTrue(f.memories.rows.isEmpty())
        assertEquals("next", f.repository.pending("store").single().content)
        assertEquals(1, f.proposals.rows.count { it.decision == MemoryProposalEntity.DECISION_REJECTED })
    }

    @Test
    fun `replacement save excludes apply and reject between delete and insert`() = runBlocking {
        val f = TransactionFixture()
        f.repository.savePending("store", listOf(proposal("create", content = "previous")))
        val ids = f.repository.pending("store").map { it.id }.toSet()
        val pause = TransactionPause()
        f.proposals.afterDeletePending = {
            f.proposals.afterDeletePending = null
            pause.pause()
        }
        val save = async(start = CoroutineStart.UNDISPATCHED) {
            f.repository.savePending("store", listOf(proposal("create", content = "replacement")))
        }
        pause.entered.await()
        val apply = async(start = CoroutineStart.UNDISPATCHED) { f.repository.apply("store", ids) }
        val reject = async(start = CoroutineStart.UNDISPATCHED) { f.repository.reject("store", ids) }
        assertFalse(apply.isCompleted)
        assertFalse(reject.isCompleted)
        pause.resume.complete(Unit)
        save.await()
        assertTrue(apply.await().isEmpty)
        assertEquals(0, reject.await())
        assertTrue(f.memories.rows.isEmpty())
        assertEquals("replacement", f.repository.pending("store").single().content)
        assertEquals(1, f.proposals.rows.size)
    }

    // ---- 审计与上限 ----

    @Test
    fun `decided rows are kept as an audit trail but capped`() = runBlocking {
        // 「删了没有账」是本轮要修的另一件事 ⇒ 已决策的行必须留；但也必须封顶，
        // 否则建议表是又一个只增不减的表。
        repeat(MemoryProposalRepository.DECIDED_AUDIT_KEEP + 5) { i ->
            repository.savePending("store", listOf(proposal("create", content = "n$i", batchId = "b$i")))
            val ids = repository.pending("store").map { it.id }.toSet()
            repository.apply("store", ids)
        }
        val decided = proposalDAO.rows.count { it.decision != MemoryProposalEntity.DECISION_PENDING }
        assertTrue("审计上限要生效（实际 $decided）", decided <= MemoryProposalRepository.DECIDED_AUDIT_KEEP)
        assertTrue("但确实留了账", decided > 0)
    }

    @Test
    fun `pending is scoped per memory store`() = runBlocking {
        repository.savePending("a", listOf(proposal("create", content = "for a")))
        repository.savePending("b", listOf(proposal("create", content = "for b")))
        assertEquals(1, repository.pending("a").size)
        assertEquals("for b", repository.pending("b").single().content)
        assertEquals(setOf("a", "b"), repository.memoryIdsWithPending().toSet())
    }
}
