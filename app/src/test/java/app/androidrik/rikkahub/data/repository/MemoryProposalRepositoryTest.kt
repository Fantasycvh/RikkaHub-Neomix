package app.androidrik.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import app.androidrik.rikkahub.data.ai.MemoryConsolidationProposal
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryProposalDAO
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryProposalEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整理建议的**落库 / 恢复 / 应用**（`docs/design/design-memory-retrieval-and-consolidation.md` §5）。
 *
 * 用内存 fake DAO，不依赖 Android/Room（与 [MemoryRepositoryTest] 同款）。
 *
 * 这套用例对应的是本轮修掉的两条并存问题之一：落库前建议只活在进程内、**且全仓没有应用路径**，
 * 而 `memoryModifyApproval` 默认 true ⇒ `proposeOnly` 是默认行为 ⇒ 默认配置下自动整理白烧模型调用。
 */
class MemoryProposalRepositoryTest {

    /** 最小内存建议表：语义对齐 DAO 的 SQL（pending 过滤、替换、改判、保留上限） */
    private class FakeProposalDAO : MemoryProposalDAO {
        val rows = mutableListOf<MemoryProposalEntity>()
        private var nextId = 1L

        override suspend fun getPending(memoryId: String): List<MemoryProposalEntity> =
            rows.filter { it.memoryId == memoryId && it.decision == MemoryProposalEntity.DECISION_PENDING }
                .sortedWith(compareBy({ it.createdAt }, { it.id }))

        override suspend fun memoryIdsWithPending(): List<String> =
            rows.filter { it.decision == MemoryProposalEntity.DECISION_PENDING }
                .map { it.memoryId }
                .distinct()

        override suspend fun insertAll(proposals: List<MemoryProposalEntity>) {
            proposals.forEach { rows += it.copy(id = nextId++) }
        }

        override suspend fun deletePending(memoryId: String) {
            rows.removeAll {
                it.memoryId == memoryId && it.decision == MemoryProposalEntity.DECISION_PENDING
            }
        }

        override suspend fun updateDecision(ids: List<Long>, decision: String, now: Long) {
            rows.replaceAll { if (it.id in ids) it.copy(decision = decision, decidedAt = now) else it }
        }

        override suspend fun updateOne(id: Long, decision: String, now: Long) {
            rows.replaceAll { if (it.id == id) it.copy(decision = decision, decidedAt = now) else it }
        }

        override suspend fun pruneDecided(memoryId: String, keep: Int) {
            val decided = rows.filter {
                it.memoryId == memoryId && it.decision != MemoryProposalEntity.DECISION_PENDING
            }
            val drop = decided.sortedWith(compareByDescending<MemoryProposalEntity> { it.decidedAt }
                .thenByDescending { it.id }).drop(keep).map { it.id }.toSet()
            rows.removeAll { it.id in drop }
        }
    }

    private class FakeMemoryDAO : MemoryDAO {
        val rows = mutableListOf<MemoryEntity>()
        private var nextId = 1

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

        override suspend fun recordRead(id: Int, now: Long) = Unit

        override suspend fun deleteMemory(id: Int) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteMemoryByPublicId(publicId: String) {
            rows.removeAll { it.publicId == publicId }
        }

        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            rows.removeAll { it.assistantId == assistantId }
        }
    }

    private val proposalDAO = FakeProposalDAO()
    private val memoryRepository = MemoryRepository(FakeMemoryDAO())
    private var clock = 1_000L
    private val repository = MemoryProposalRepository(proposalDAO, memoryRepository, now = { clock++ })

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
    fun `a batch with a missing target is not applied at all`() = runBlocking {
        // 一次「合并重复记忆」在工具层是 edit + delete 两条。只应用其中一条会得到半成品
        // （内容改对了但旧的没删），所以整批必须同生共死。
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

        assertEquals("整批不动", 0, result.applied)
        assertEquals("整批判 stale", 2, result.stale)
        val contents = memoryRepository.getMemoriesOfAssistant("store").map { it.content }
        assertTrue("目标还在、内容不该被改", contents.contains("will vanish"))
        assertTrue("也不该凭空删掉不存在的东西", contents.size == 1)
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
