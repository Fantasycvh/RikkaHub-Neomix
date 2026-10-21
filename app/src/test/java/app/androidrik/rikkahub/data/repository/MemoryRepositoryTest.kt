package app.androidrik.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryHistoryDAO
import app.androidrik.rikkahub.data.db.dao.MemoryTrashDAO
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryHistoryEntity
import app.androidrik.rikkahub.data.db.entity.MemoryTrashEntity
import app.androidrik.rikkahub.data.model.MemoryKind
import app.androidrik.rikkahub.data.model.MemoryTaskStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆对外 opaque id 的行为（详见 [app.androidrik.rikkahub.data.db.entity.MemoryEntity.publicId]）。
 *
 * 用内存 fake DAO 验证语义，不依赖 Android/Room：
 * - 新建分配 8 位十六进制 token，且不随删除而产生「编号空洞」观感
 * - edit/delete 用 token 定位
 * - 兼容历史消息里的旧自增编号引用（纯数字回退）
 */
class MemoryRepositoryTest {

    /** 最小内存 DAO：行为对齐 Room 实现（自增主键 + public_id 查询）。 */
    private class FakeMemoryDAO : MemoryDAO {
        private val rows = mutableListOf<MemoryEntity>()
        private var nextId = 1
        var beforeFieldWrite: (suspend () -> Unit)? = null

        private suspend fun beforeWrite() {
            val hook = beforeFieldWrite
            beforeFieldWrite = null
            hook?.invoke()
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

        override suspend fun publicIdExists(publicId: String): Boolean =
            rows.any { it.publicId == publicId }

        override suspend fun insertMemory(memory: MemoryEntity): Long {
            val id = if (memory.id == 0) nextId++ else memory.id.also { nextId = maxOf(nextId, it + 1) }
            check(rows.none { it.id == id })
            rows.add(memory.copy(id = id))
            return id.toLong()
        }

        override suspend fun insertMemories(memories: List<MemoryEntity>) {
            memories.forEach { rows.add(it.copy(id = nextId++)) }
        }

        override suspend fun updateMemory(memory: MemoryEntity) {
            error("Repository must use field-level writes, not whole-row updates")
        }

        override suspend fun updateContentFields(
            id: Int,
            assistantId: String,
            content: String,
            importance: Float?,
            description: String?,
            now: Long,
            expectedRevision: Long?,
            kind: String?, taskStatus: String?, dueAt: Long?,
        ): Int {
            beforeWrite()
            val i = rows.indexOfFirst {
                it.id == id && it.assistantId == assistantId &&
                    (expectedRevision == null || it.revision == expectedRevision)
            }
            if (i < 0) return 0
            rows[i] = rows[i].copy(
                kind = kind ?: rows[i].kind, taskStatus = taskStatus ?: rows[i].taskStatus, dueAt = dueAt ?: rows[i].dueAt,
                revision = rows[i].revision + 1,
                content = content,
                importance = importance ?: rows[i].importance,
                description = description ?: rows[i].description,
                updatedAt = now,
            )
            return 1
        }

        override suspend fun updateTaskStatus(id: Int, assistantId: String, status: String, expectedRevision: Long): Int {
            beforeWrite()
            val i = rows.indexOfFirst { it.id == id && it.assistantId == assistantId && it.kind == "task" && it.revision == expectedRevision }
            if (i < 0) return 0
            rows[i] = rows[i].copy(taskStatus = status, revision = rows[i].revision + 1)
            return 1
        }

        override suspend fun updateImportanceFields(
            id: Int, assistantId: String, importance: Float, now: Long, expectedRevision: Long?,
        ): Int {
            beforeWrite()
            val i = rows.indexOfFirst {
                it.id == id && it.assistantId == assistantId &&
                    (expectedRevision == null || it.revision == expectedRevision)
            }
            if (i < 0) return 0
            rows[i] = rows[i].copy(importance = importance, updatedAt = now, revision = rows[i].revision + 1)
            return 1
        }

        override suspend fun updateAnchor(
            id: Int, assistantId: String, isAnchor: Boolean, expectedRevision: Long?,
        ): Int {
            beforeWrite()
            val i = rows.indexOfFirst {
                it.id == id && it.assistantId == assistantId && it.isAnchor != isAnchor &&
                    (expectedRevision == null || it.revision == expectedRevision)
            }
            if (i < 0) return 0
            rows[i] = rows[i].copy(isAnchor = isAnchor, revision = rows[i].revision + 1)
            return 1
        }

        override suspend fun backfillDescription(
            id: Int, assistantId: String, description: String, expectedRevision: Long?,
        ): Int {
            beforeWrite()
            val i = rows.indexOfFirst {
                it.id == id && it.assistantId == assistantId && it.description.isEmpty() &&
                    (expectedRevision == null || it.revision == expectedRevision)
            }
            if (i < 0) return 0
            rows[i] = rows[i].copy(description = description, revision = rows[i].revision + 1)
            return 1
        }

        override suspend fun replaceBusinessFields(
            id: Int, assistantId: String, content: String, importance: Float, description: String,
            isAnchor: Boolean, now: Long, expectedRevision: Long,
            kind: String, taskStatus: String, dueAt: Long,
        ): Int {
            beforeWrite()
            val i = rows.indexOfFirst {
                it.id == id && it.assistantId == assistantId && it.revision == expectedRevision
            }
            if (i < 0) return 0
            rows[i] = rows[i].copy(
                content = content, importance = importance, description = description, isAnchor = isAnchor,
                kind = kind, taskStatus = taskStatus, dueAt = dueAt,
                updatedAt = now, revision = rows[i].revision + 1,
            )
            return 1
        }

        override suspend fun recordReadInStore(id: Int, assistantId: String, now: Long): Int {
            val i = rows.indexOfFirst { it.id == id && it.assistantId == assistantId }
            if (i < 0) return 0
            rows[i] = rows[i].copy(
                readCount = rows[i].readCount + 1, lastReadAt = maxOf(rows[i].lastReadAt, now),
                chatReadCount = rows[i].chatReadCount + 1, lastChatReadAt = maxOf(rows[i].lastChatReadAt, now),
            )
            return 1
        }

        override suspend fun recordConsolidationReadInStore(id: Int, assistantId: String, now: Long): Int {
            val i = rows.indexOfFirst { it.id == id && it.assistantId == assistantId }
            if (i < 0) return 0
            rows[i] = rows[i].copy(
                consolidationReadCount = rows[i].consolidationReadCount + 1,
                lastConsolidationReadAt = maxOf(rows[i].lastConsolidationReadAt, now),
            )
            return 1
        }

        override suspend fun deleteMemoryInStore(id: Int, assistantId: String, expectedRevision: Long?): Int {
            val removed = rows.removeAll {
                it.id == id && it.assistantId == assistantId &&
                    (expectedRevision == null || it.revision == expectedRevision)
            }
            return if (removed) 1 else 0
        }

        override suspend fun deleteMemory(id: Int) {
            rows.removeAll { it.id == id }
        }

        override suspend fun deleteMemoryByPublicId(publicId: String) {
            rows.removeAll { it.publicId == publicId }
        }

        override suspend fun deleteMemoriesOfAssistant(assistantId: String) {
            rows.removeAll { it.assistantId == assistantId }
        }

        // 与 Room 实现同语义：SQL 自增（不是「读出来 +1 写回」），且不动 updated_at
        override suspend fun recordRead(id: Int, now: Long) {
            rows.firstOrNull { it.id == id }?.let { recordReadInStore(id, it.assistantId, now) }
        }
    }

    private class FakeHistoryDAO : MemoryHistoryDAO {
        val rows = mutableListOf<MemoryHistoryEntity>()
        var failInsert = false
        private var nextId = 1L
        override suspend fun insert(event: MemoryHistoryEntity): Long {
            if (failInsert) error("history failed")
            val id = nextId++
            rows.add(event.copy(id = id))
            return id
        }
        override suspend fun list(storeId: String, memoryId: Int): List<MemoryHistoryEntity> =
            rows.filter { it.assistantId == storeId && it.memoryId == memoryId }.sortedByDescending { it.id }
        override suspend fun getRevision(storeId: String, memoryId: Int, revision: Long): MemoryHistoryEntity? =
            list(storeId, memoryId).firstOrNull { it.revision == revision }
        override suspend fun prune(storeId: String, memoryId: Int, keep: Int) {
            val retained = list(storeId, memoryId).take(keep).map { it.id }.toSet()
            rows.removeAll { it.assistantId == storeId && it.memoryId == memoryId && it.id !in retained }
        }
    }

    private class FakeTrashDAO : MemoryTrashDAO {
        val rows = mutableListOf<MemoryTrashEntity>()
        override suspend fun insert(tombstone: MemoryTrashEntity) {
            check(rows.none { it.memoryId == tombstone.memoryId })
            rows.add(tombstone)
        }
        override suspend fun list(storeId: String): List<MemoryTrashEntity> =
            rows.filter { it.assistantId == storeId }.sortedByDescending { it.deletedAt }
        override suspend fun get(storeId: String, memoryId: Int): MemoryTrashEntity? =
            rows.firstOrNull { it.assistantId == storeId && it.memoryId == memoryId }
        override suspend fun publicIdExists(publicId: String): Boolean = rows.any { it.publicId == publicId }
        override suspend fun delete(storeId: String, memoryId: Int, revision: Long): Int =
            if (rows.removeAll {
                it.assistantId == storeId && it.memoryId == memoryId && it.revision == revision
            }) 1 else 0
    }

    private val dao = FakeMemoryDAO()
    private val history = FakeHistoryDAO()
    private val trash = FakeTrashDAO()
    private val repo = MemoryRepository(dao, DirectMemoryTransactionRunner, history, trash)

    @Test
    fun `scoped access rejects foreign assistant and global records including legacy ids`() = runBlocking {
        repo.addMemory("a", "own record")
        val foreign = repo.addMemory("b", "private record")
        val global = repo.addMemory(MemoryRepository.GLOBAL_MEMORY_ID, "global record")
        for (target in listOf(foreign.id, "2", global.id, "3")) {
            assertNull(repo.getMemoryInStore("a", target))
            assertNull(repo.readMemoryInStore("a", target, now = 99L))
            assertFalse(repo.deleteMemoryInStore("a", target))
            assertTrue(runCatching { repo.updateContentInStore("a", target, "unauthorized") }.isFailure)
        }
        assertEquals("private record", repo.getMemoryById(foreign.id)?.content)
        assertEquals("global record", repo.getMemoryById(global.id)?.content)
        assertEquals(0, repo.getMemoryById(foreign.id)?.readCount)
        assertEquals(0, repo.getMemoryById(global.id)?.readCount)
        assertNull(repo.getMemoryInStore(MemoryRepository.GLOBAL_MEMORY_ID, foreign.id))
    }

    @Test
    fun `scoped legacy references still read edit and delete within their own store`() = runBlocking {
        val created = repo.addMemory("a", "legacy content", importance = 0.8f, description = "topic", now = 1L)
        assertEquals(created.id, repo.getMemoryInStore("a", "1")?.id)
        assertEquals(1, repo.readMemoryInStore("a", "1", now = 2L)?.readCount)
        val edited = repo.updateContentInStore("a", "1", "edited", now = 3L)
        assertEquals("edited", edited.content)
        assertEquals(0.8f, edited.importance, 0.001f)
        assertEquals("topic", edited.description)
        assertEquals(1, edited.readCount)
        assertTrue(repo.deleteMemoryInStore("a", "1"))
        assertNull(repo.getMemoryById(created.id))
    }

    @Test
    fun `foreign numeric public token does not fall back to an own numeric primary key`() = runBlocking {
        repo.addMemory("a", "own record")
        dao.insertMemory(MemoryEntity(assistantId = "b", content = "foreign token", publicId = "1"))
        assertNull(repo.getMemoryInStore("a", "1"))
        assertFalse(repo.deleteMemoryInStore("a", "1"))
        assertEquals("own record", repo.getMemoriesOfAssistant("a").single().content)
    }

    @Test
    fun `content edit preserves telemetry anchor and unspecified fields written after lookup`() = runBlocking {
        val memory = repo.addMemory("a", "original", importance = 0.6f)
        // Deliberately interleave at the SQL boundary. This proves field semantics, not Room isolation.
        dao.beforeFieldWrite = { dao.recordRead(1, 50L) }
        val edited = repo.updateContentInStore("a", memory.id, "edited", now = 70L)
        assertEquals(1, edited.readCount)
        assertEquals(50L, edited.lastReadAt)
        assertFalse(edited.isAnchor)
        assertEquals(0.6f, edited.importance, 0.001f)
        assertEquals("", edited.description)
        assertEquals(70L, edited.updatedAt)
        assertEquals(2L, edited.revision)
    }

    @Test
    fun `importance anchor and description backfill reject concurrent business revisions`() = runBlocking {
        val memory = repo.addMemory("a", "original", now = 1L)
        dao.beforeFieldWrite = {
            dao.recordRead(1, 2L)
            dao.updateAnchor(1, "a", true)
            dao.updateContentFields(1, "a", "newer content", null, null, 3L)
        }
        val scoreFailure = runCatching { repo.updateImportance(memory.id, 0.95f, now = 4L) }.exceptionOrNull()
        assertTrue(scoreFailure is MemoryRevisionConflictException)
        val scored = repo.getMemoryById(memory.id)!!
        assertEquals("newer content", scored.content)
        assertEquals(1, scored.readCount)
        assertTrue(scored.isAnchor)
        assertEquals(0.6f, scored.importance, 0.001f)

        dao.beforeFieldWrite = {
            dao.recordRead(1, 5L)
            dao.updateContentFields(1, "a", "newest content", null, null, 6L)
        }
        assertTrue(runCatching { repo.setAnchor(memory.id, false) }.exceptionOrNull()
            is MemoryRevisionConflictException)
        val unanchored = repo.getMemoryById(memory.id)!!
        assertEquals("newest content", unanchored.content)
        assertEquals(6L, unanchored.updatedAt)
        assertEquals(2, unanchored.readCount)
        assertTrue(unanchored.isAnchor)

        dao.beforeFieldWrite = {
            dao.recordRead(1, 7L)
            dao.backfillDescription(1, "a", "user supplied topic")
        }
        assertTrue(runCatching {
            repo.addMemory("a", "newest content", description = "model topic", now = 8L)
        }.exceptionOrNull() is MemoryRevisionConflictException)
        val duplicate = repo.getMemoryById(memory.id)!!
        assertEquals("user supplied topic", duplicate.description)
        assertEquals(3, duplicate.readCount)
        assertTrue(duplicate.isAnchor)
        assertEquals(6L, duplicate.updatedAt)
    }

    @Test
    fun `add deduplication insertion eviction and bulk copy stay inside one transaction boundary`() = runBlocking {
        // This probe verifies placement only; actual rollback and concurrency require real Room tests.
        var active = false
        var runs = 0
        val transactions = object : MemoryTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T {
                check(!active)
                active = true
                runs++
                return try { block() } finally { active = false }
            }
        }
        repeat(MemoryRepository.MAX_MEMORY_COUNT) { index ->
            dao.insertMemory(MemoryEntity(
                assistantId = "dst", content = "seed $index", publicId = "seed$index", updatedAt = 10L,
            ))
        }
        dao.insertMemory(MemoryEntity(assistantId = "src", content = "source", publicId = "source"))
        var deletions = 0
        var batches = 0
        val guardedDAO = object : MemoryDAO by dao {
            override suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity> {
                check(active) { "lookup outside transaction" }
                return dao.getMemoriesOfAssistant(assistantId)
            }

            override suspend fun publicIdExists(publicId: String): Boolean {
                check(active) { "public id check outside transaction" }
                return dao.publicIdExists(publicId)
            }

            override suspend fun insertMemory(memory: MemoryEntity): Long {
                check(active) { "insert outside transaction" }
                return dao.insertMemory(memory)
            }

            override suspend fun insertMemories(memories: List<MemoryEntity>) {
                check(active) { "bulk insert outside transaction" }
                batches++
                dao.insertMemories(memories)
            }

            override suspend fun deleteMemoryInStore(
                id: Int, assistantId: String, expectedRevision: Long?,
            ): Int {
                check(active) { "eviction outside transaction" }
                deletions++
                return dao.deleteMemoryInStore(id, assistantId, expectedRevision)
            }
        }
        val guarded = MemoryRepository(guardedDAO, transactions, history, trash)
        val created = guarded.addMemory("dst", "new record", now = 10L)
        assertEquals(1, runs)
        assertEquals(1, deletions)
        assertEquals(created.id, guarded.addMemory("dst", "new record", now = 10L).id)
        assertEquals(2, runs)
        assertEquals(1, guarded.copyMemories("src", "dst", now = 10L))
        assertEquals(3, runs)
        assertEquals(0, batches) // Inserts now capture each numeric id for its CREATE history.
        assertEquals(2, deletions)
        assertEquals(2, trash.rows.size)
        assertEquals(MemoryRepository.MAX_MEMORY_COUNT, dao.getMemoriesOfAssistant("dst").size)
    }

    // ---- searchMemories：把注入预算之外的记录变成可取的 ----
    // 背景（真机度量，peer-apps-benchmark §11.3 E）：50 条库 / 8000 token 注入预算下
    // 全文 7 + 清单 20，**23 条完全不出现**；而换清单层判据不改覆盖度 ⇒ 只能靠按需检索。

    @Test
    fun `search finds a record that injection would not be able to show`() = runBlocking {
        repo.addMemory("asst", "用户喜欢手冲咖啡，早上喝")
        repo.addMemory("asst", "TikZ 渲染器已切换为完整 LaTeX 引擎", description = "TikZ 渲染")
        val hits = repo.searchMemories("asst", "TikZ")
        assertEquals(1, hits.size)
        assertTrue(hits.first().content.contains("LaTeX"))
    }

    @Test
    fun `search matches on the description too`() = runBlocking {
        // 清单行只给 description；检索必须也看它，否则「按清单里的主题词去查」会查不到
        repo.addMemory("asst", "引擎已从 WASM 切换", description = "TikZ 渲染器状态")
        assertEquals(1, repo.searchMemories("asst", "TikZ").size)
    }

    @Test
    fun `search is scoped per assistant`() = runBlocking {
        repo.addMemory("a", "TikZ 渲染")
        repo.addMemory("b", "TikZ 渲染")
        assertEquals(1, repo.searchMemories("a", "TikZ").size)
    }

    @Test
    fun `search returns nothing for an unmatched or empty query`() = runBlocking {
        repo.addMemory("asst", "用户喜欢手冲咖啡")
        assertTrue(repo.searchMemories("asst", "量子力学").isEmpty())
        assertTrue("空查询不得退化成「列出全部」", repo.searchMemories("asst", "   ").isEmpty())
        assertTrue(repo.searchMemories("asst", "咖啡", limit = 0).isEmpty())
    }

    @Test
    fun `search ranks more matches first and honours the limit`() = runBlocking {
        repo.addMemory("asst", "咖啡 咖啡 咖啡", description = "咖啡")
        repo.addMemory("asst", "咖啡只是顺带一提")
        val hits = repo.searchMemories("asst", "咖啡", limit = 2)
        assertEquals(2, hits.size)
        assertTrue("命中更全的排前面", hits.first().content.startsWith("咖啡 咖啡"))
        assertEquals(1, repo.searchMemories("asst", "咖啡", limit = 1).size)
    }

    @Test
    fun `search does not record a read`() = runBlocking {
        // 与 readMemory 的口径区分：检索返回 N 条，不代表 N 条都被用了
        val m = repo.addMemory("asst", "TikZ 渲染")
        repo.searchMemories("asst", "TikZ")
        val after = repo.getMemoriesOfAssistant("asst").first { it.id == m.id }
        assertEquals(0, after.readCount)
        assertEquals(0L, after.lastReadAt)
    }

    @Test
    fun `addMemory assigns an 8-char hex public token not a sequence number`() = runBlocking {
        val a = repo.addMemory("asst", "first")
        val b = repo.addMemory("asst", "second")

        // 8 位小写十六进制
        assertTrue("token not 8 chars: ${a.id}", a.id.length == 8)
        assertTrue("token not hex: ${a.id}", a.id.all { it in "0123456789abcdef" })
        // 关键：新 token 不是「上一条 + 1」这种序号（否则仍会泄漏条数/出现空洞）
        assertNotEquals("should not be sequential-ish", "00000001", a.id)
        assertNotEquals("tokens must differ", a.id, b.id)
    }

    @Test
    fun `addMemory is idempotent for identical content (tool loop re-create guard)`() = runBlocking {
        // 回归：一轮生成里注入模型的记忆列表是快照，工具循环中新建的记忆当轮不可见 →
        // 模型会对同一事实反复 create。同内容应复用而非新增（实测一次可建 6 条）。
        val first = repo.addMemory("asst", "User's favorite color is teal.")
        val again = repo.addMemory("asst", "User's favorite color is teal.")

        assertEquals("should reuse the existing record", first.id, again.id)
        assertEquals(1, repo.getMemoriesOfAssistant("asst").size)
    }

    @Test
    fun `addMemory deduplicates ignoring surrounding whitespace`() = runBlocking {
        val first = repo.addMemory("asst", "prefers brief replies")
        val again = repo.addMemory("asst", "  prefers brief replies  ")

        assertEquals(first.id, again.id)
        assertEquals(1, repo.getMemoriesOfAssistant("asst").size)
        // 落库内容已 trim
        assertEquals("prefers brief replies", repo.getMemoriesOfAssistant("asst").first().content)
    }

    @Test
    fun `addMemory still adds genuinely different content`() = runBlocking {
        repo.addMemory("asst", "likes tea")
        repo.addMemory("asst", "likes coffee")
        assertEquals(2, repo.getMemoriesOfAssistant("asst").size)
    }

    @Test
    fun `deduplication is scoped per assistant`() = runBlocking {
        // 不同助手可以有相同内容，不应互相去重
        val a = repo.addMemory("a", "same text")
        val b = repo.addMemory("b", "same text")
        assertNotEquals(a.id, b.id)
        assertEquals(1, repo.getMemoriesOfAssistant("a").size)
        assertEquals(1, repo.getMemoriesOfAssistant("b").size)
    }

    @Test
    fun `edit and delete address the record by public token`() = runBlocking {
        val m = repo.addMemory("asst", "original")

        val updated = repo.updateContent(m.id, "changed")
        assertEquals("changed", updated.content)
        assertEquals(m.id, updated.id)

        assertEquals("changed", repo.getMemoryById(m.id)?.content)

        repo.deleteMemory(m.id)
        assertNull(repo.getMemoryById(m.id))
    }

    @Test
    fun `legacy numeric reference still resolves via auto-increment id`() = runBlocking {
        // 历史消息里可能存着旧的自增编号（如 "1"）；token 查不到时应回退按 id 查
        val first = repo.addMemory("asst", "legacy-target")

        // 模拟旧引用：用自增主键的十进制字符串
        val legacyRef = "1"
        assertNotEquals(first.id, legacyRef)
        assertEquals("legacy-target", repo.getMemoryById(legacyRef)?.content)

        repo.deleteMemory(legacyRef)
        assertNull(repo.getMemoryById(first.id))
    }

    @Test
    fun `deleting an unknown token is a no-op`() = runBlocking {
        repo.addMemory("asst", "keep me")
        repo.deleteMemory("deadbeef") // 不存在，不应抛
        repo.deleteMemory("not-a-token") // 非数字非 token，也不应抛
        assertEquals(1, repo.getMemoriesOfAssistant("asst").size)
    }

    @Test
    fun `memories are isolated per assistant`() = runBlocking {
        repo.addMemory("a", "x")
        repo.addMemory("b", "y")
        assertEquals(1, repo.getMemoriesOfAssistant("a").size)
        assertEquals("x", repo.getMemoriesOfAssistant("a").first().content)
    }

    // ---- importance / updatedAt（打分校准的输入，见 docs/design/design-memory-scoring.md） ----

    @Test
    fun `addMemory stores provided importance`() = runBlocking {
        val m = repo.addMemory("asst", "identity fact", importance = 0.95f)
        assertEquals(0.95f, m.importance, 0.001f)
        assertEquals(0.95f, repo.getMemoryById(m.id)!!.importance, 0.001f)
    }

    @Test
    fun `addMemory falls back to default importance when absent or out of range`() = runBlocking {
        // 模型不填 → 用默认（普通偏好档）；越界 → 钳制
        val missing = repo.addMemory("asst", "no importance")
        assertEquals(MemoryEntity.DEFAULT_IMPORTANCE, missing.importance, 0.001f)

        val tooBig = repo.addMemory("asst", "too big", importance = 5f)
        assertEquals(1f, tooBig.importance, 0.001f)

        val negative = repo.addMemory("asst", "negative", importance = -2f)
        assertEquals(0f, negative.importance, 0.001f)
    }

    @Test
    fun `updateContent refreshes updatedAt so edited memories are not treated as stale`() = runBlocking {
        // 回归护栏：updatedAt 是时效衰减基准，不刷新就等于「改了没用」——
        // 刚维护过的记忆仍按旧时间被算成陈旧，最后被淘汰。
        val m = repo.addMemory("asst", "original", now = 1_000L)
        assertEquals(1_000L, m.updatedAt)

        val updated = repo.updateContent(m.id, "changed", now = 9_999L)
        assertEquals(9_999L, updated.updatedAt)
        assertEquals(9_999L, repo.getMemoryById(m.id)!!.updatedAt)
    }

    @Test
    fun `updateContent keeps importance when not provided`() = runBlocking {
        // 模型只说改内容时，不该顺手把重要度重置掉
        val m = repo.addMemory("asst", "content", importance = 0.9f, now = 0L)
        val updated = repo.updateContent(m.id, "new content", importance = null, now = 1L)
        assertEquals(0.9f, updated.importance, 0.001f)
    }

    @Test
    fun `updateContent applies explicit importance override`() = runBlocking {
        val m = repo.addMemory("asst", "content", importance = 0.9f, now = 0L)
        val updated = repo.updateContent(m.id, "content", importance = 0.3f, now = 1L)
        assertEquals(0.3f, updated.importance, 0.001f)
    }

    @Test
    fun `updateImportance changes only the score input`() = runBlocking {
        val m = repo.addMemory("asst", "content", importance = 0.5f, now = 0L)
        val updated = repo.updateImportance(m.id, 0.8f, now = 7L)!!
        assertEquals(0.8f, updated.importance, 0.001f)
        assertEquals("content", updated.content)
        assertEquals(7L, updated.updatedAt)
    }

    @Test
    fun `updateImportance on unknown id returns null`() = runBlocking {
        assertNull(repo.updateImportance("deadbeef", 0.5f))
    }

    @Test
    fun `overflow eviction drops lowest score not merely oldest`() = runBlocking {
        // 与入选口径一致：淘汰按分数（否则「重要但久未改动」的记忆会被新写入的次要记忆挤掉）。
        // 造 200 条：第 1 条是高重要度老记忆，其余是低重要度新记忆。
        val now = 1_000_000L
        repo.addMemory("asst", "important identity", importance = 1.0f, now = now)
        repeat(199) { i ->
            // 内容不能重复（幂等去重会合并），故带序号
            repo.addMemory("asst", "minor note $i", importance = 0.1f, now = now)
        }
        assertEquals(MemoryRepository.MAX_MEMORY_COUNT, repo.getMemoriesOfAssistant("asst").size)

        // 再加一条触发淘汰
        repo.addMemory("asst", "one more", importance = 0.1f, now = now)

        val remaining = repo.getMemoriesOfAssistant("asst")
        assertEquals(MemoryRepository.MAX_MEMORY_COUNT, remaining.size)
        // 高分那条必须还在（旧实现按「最旧」淘汰会把它删掉，因为它是最早插入的）
        assertTrue(
            "high-importance memory was evicted by low-importance newcomers",
            remaining.any { it.content == "important identity" },
        )
    }

    @Test
    fun `a new low score memory that evicts itself fails instead of reporting success`() = runBlocking {
        val now = 1_000_000L
        repeat(MemoryRepository.MAX_MEMORY_COUNT) { index ->
            repo.addMemory("asst", "important memory $index", importance = 1f, now = now)
        }

        val failure = runCatching {
            repo.addMemory("asst", "cannot fit", importance = 0f, now = now)
        }.exceptionOrNull()

        assertTrue("self-eviction must not return a successful memory", failure is IllegalStateException)
        assertTrue("failure must explain capacity rejection", failure?.message.orEmpty().contains("capacity"))
        // DirectMemoryTransactionRunner does not establish rollback; the real Room test covers that.
    }

    // ---- copyMemories（复制助手时整批搬运记忆，上游 282b85f2） ----

    @Test
    fun `copyMemories 搬运内容与重要度，并给出全新的对外 id 与写入时刻`() = runBlocking {
        repo.addMemory("src", "likes tea", importance = 0.9f, now = 1_000L)
        repo.addMemory("src", "works in the morning", importance = 0.3f, now = 1_000L)

        val copied = repo.copyMemories("src", "dst", now = 5_000L)

        assertEquals(2, copied)
        val dstMemories = repo.getMemoriesOfAssistant("dst")
        assertEquals(listOf("likes tea", "works in the morning"), dstMemories.map { it.content })
        assertEquals(listOf(0.9f, 0.3f), dstMemories.map { it.importance })
        // updatedAt 取当下：沿用旧时间会让复制来的记忆一上来就被算成陈旧而沉底
        assertTrue("updatedAt 应为复制时刻", dstMemories.all { it.updatedAt == 5_000L })
        // 对外 opaque id 必须全新：两个助手共用同一个 token 会让审批/删除指到别人那条
        val srcIds = repo.getMemoriesOfAssistant("src").map { it.id }.toSet()
        assertTrue("copied ids must differ from source", dstMemories.none { it.id in srcIds })
    }

    @Test
    fun `copyMemories 后删副本的一条不会动到源助手`() = runBlocking {
        repo.addMemory("src", "shared fact")
        repo.copyMemories("src", "dst")

        val copiedId = repo.getMemoriesOfAssistant("dst").single().id
        repo.deleteMemory(copiedId)

        assertEquals(0, repo.getMemoriesOfAssistant("dst").size)
        assertEquals("源助手必须原样保留", 1, repo.getMemoriesOfAssistant("src").size)
    }

    @Test
    fun `copyMemories 对空源或自身复制是 no-op`() = runBlocking {
        assertEquals(0, repo.copyMemories("empty", "dst"))

        repo.addMemory("src", "only one")
        assertEquals(0, repo.copyMemories("src", "src"))
        assertEquals(1, repo.getMemoriesOfAssistant("src").size)
        assertEquals(0, repo.getMemoriesOfAssistant("dst").size)
    }

    @Test
    fun `copyMemories 同样受 200 条上限约束`() = runBlocking {
        // 源侧本身不会超过上限，但复制实现必须复用同一条裁剪路径（同一不变式只有一处实现）
        val now = 1_000_000L
        repeat(MemoryRepository.MAX_MEMORY_COUNT) { i ->
            repo.addMemory("src", "note $i", importance = 0.5f, now = now)
        }
        repo.copyMemories("src", "dst", now = now)
        assertEquals(MemoryRepository.MAX_MEMORY_COUNT, repo.getMemoriesOfAssistant("dst").size)
    }

    // ---- V2：主题词与读取遥测 ----

    @Test
    fun `description is stored and returned, and truncated at the max length`() = runBlocking {
        val long = "x".repeat(MemoryRepository.MAX_DESCRIPTION_LENGTH + 50)
        val created = repo.addMemory("a", "content", description = "  topics about food  ")
        assertEquals("topics about food", created.description)
        assertEquals("topics about food", repo.getMemoriesOfAssistant("a").single().description)

        val truncated = repo.addMemory("a", "another", description = long)
        assertEquals(MemoryRepository.MAX_DESCRIPTION_LENGTH, truncated.description.length)
    }

    @Test
    fun `description is optional and defaults to empty`() = runBlocking {
        val created = repo.addMemory("a", "no description here")
        assertEquals("", created.description)
    }

    @Test
    fun `updateContent keeps the old description when none is passed`() = runBlocking {
        val created = repo.addMemory("a", "content", description = "old topic")
        val updated = repo.updateContent(created.id, "new content")
        assertEquals("old topic", updated.description)
        assertEquals("new content", updated.content)

        val replaced = repo.updateContent(created.id, "newer", description = "new topic")
        assertEquals("new topic", replaced.description)
    }

    @Test
    fun `duplicate create backfills a missing description instead of dropping it`() = runBlocking {
        // 工具循环里模型重发同一内容但补了主题词：复用记录时顺手补上，不能白丢
        val first = repo.addMemory("a", "same content")
        assertEquals("", first.description)
        val second = repo.addMemory("a", "same content", description = "backfilled")
        assertEquals(first.id, second.id)
        assertEquals("backfilled", second.description)
    }

    @Test
    fun `readMemory bumps the read telemetry and leaves updated_at alone`() = runBlocking {
        val created = repo.addMemory("a", "content", now = 1_000L)
        assertEquals(0, created.readCount)

        val read1 = repo.readMemory(created.id, now = 2_000L)!!
        assertEquals(1, read1.readCount)
        assertEquals(2_000L, read1.lastReadAt)
        // 读取不是修改：updated_at 不动（否则时效衰减被平白刷新，等于「读一次就变新」）
        assertEquals(1_000L, read1.updatedAt)

        val read2 = repo.readMemory(created.id, now = 3_000L)!!
        assertEquals(2, read2.readCount)
        assertEquals(3_000L, read2.lastReadAt)
    }

    @Test
    fun `out of order scoped reads increment both counts without moving last read backwards`() = runBlocking {
        val created = repo.addMemory("a", "content", now = 1L)
        repo.readMemoryInStore("a", created.id, now = 20L)
        val olderRequest = repo.readMemoryInStore("a", created.id, now = 10L)!!

        assertEquals(2, olderRequest.readCount)
        assertEquals(20L, olderRequest.lastReadAt)
        assertEquals(1L, olderRequest.updatedAt)
        val stored = repo.getMemoryInStore("a", created.id)!!
        assertEquals(2, stored.readCount)
        assertEquals(20L, stored.lastReadAt)
    }

    @Test
    fun `legacy dao reads also keep the greatest request time`() = runBlocking {
        val created = repo.addMemory("a", "content", now = 1L)
        dao.recordRead(1, now = 20L)
        dao.recordRead(1, now = 10L)
        val stored = repo.getMemoryById(created.id)!!
        assertEquals(2, stored.readCount)
        assertEquals(20L, stored.lastReadAt)
        assertEquals(1L, stored.updatedAt)
    }

    @Test
    fun `readMemory returns null and records nothing for an unknown id`() = runBlocking {
        assertNull(repo.readMemory("deadbeef", now = 2_000L))
    }

    @Test
    fun `business writes advance revisions while telemetry and idempotent writes do not`() = runBlocking {
        val created = repo.addMemory("a", "fact", now = 1L)
        assertEquals(1L, created.revision)
        assertEquals(1L, repo.readMemoryInStore("a", created.id, now = 2L)!!.revision)
        assertEquals(1L, repo.addMemory("a", "fact").revision)
        val edited = repo.updateContentInStore("a", created.id, "edited", expectedRevision = 1L)
        assertEquals(2L, edited.revision)
        assertEquals(3L, repo.updateImportance(created.id, 0.8f, expectedRevision = 2L)!!.revision)
        repo.setAnchor(created.id, true, expectedRevision = 3L)
        assertEquals(4L, repo.getMemoryInStore("a", created.id)!!.revision)
        repo.setAnchor(created.id, true, expectedRevision = 4L)
        assertEquals(4L, repo.getMemoryInStore("a", created.id)!!.revision)
        val backfilled = repo.addMemory("a", "edited", description = "topic")
        assertEquals(5L, backfilled.revision)
        assertEquals(setOf("CREATE", "UPDATE", "IMPORTANCE", "ANCHOR", "BACKFILL"),
            repo.listHistory("a", created.id).map { it.event.operation }.toSet())
    }

    @Test
    fun `stale and missing model versions fail closed without mutation or audit changes`() = runBlocking {
        val created = repo.addMemory("a", "original", actor = "CHAT", reason = "tool create")
        val edited = repo.updateContentInStore(
            "a", created.id, "new", expectedRevision = 1L, actor = "CHAT", reason = "tool edit",
        )
        assertEquals(2L, edited.revision)
        val historyCount = history.rows.size
        assertTrue(runCatching {
            repo.updateContentInStore("a", created.id, "stale", expectedRevision = 1L, actor = "CHAT")
        }.exceptionOrNull() is MemoryRevisionConflictException)
        assertTrue(runCatching {
            repo.deleteMemoryInStore("a", created.id, expectedRevision = 1L, actor = "CONSOLIDATOR")
        }.exceptionOrNull() is MemoryRevisionConflictException)
        assertTrue(runCatching {
            repo.updateContentInStore("a", created.id, "no version", actor = "CHAT")
        }.exceptionOrNull() is MemoryRevisionConflictException)
        assertEquals("new", repo.getMemoryInStore("a", created.id)!!.content)
        assertEquals(historyCount, history.rows.size)
        assertTrue(trash.rows.isEmpty())
        assertEquals("CHAT", history.rows.first().actor)
        assertEquals("tool create", history.rows.first().reason)
    }

    @Test
    fun `SQL CAS rejects a changed business row even when preflight version matched`() = runBlocking {
        val created = repo.addMemory("a", "original")
        dao.beforeFieldWrite = { dao.updateAnchor(created.sortKey, "a", true) }
        assertTrue(runCatching {
            repo.updateContentInStore("a", created.id, "stale write", expectedRevision = 1L)
        }.exceptionOrNull() is MemoryRevisionConflictException)
        val current = repo.getMemoryInStore("a", created.id)!!
        assertEquals("original", current.content)
        assertEquals(2L, current.revision)
        assertTrue(current.isAnchor)
        assertEquals(1, history.rows.size)
    }

    @Test
    fun `atomic user save changes content and anchor once and retains telemetry`() = runBlocking {
        val created = repo.addMemory("a", "original", now = 1L)
        repo.readMemoryInStore("a", created.id, now = 2L)
        val saved = repo.updateMemoryInStore(
            "a", created.id, "new", 0.9f, "topic", true, expectedRevision = 1L, now = 3L,
        )
        assertEquals(2L, saved.revision)
        assertEquals(1, saved.readCount)
        assertEquals(2L, saved.lastReadAt)
        assertTrue(saved.isAnchor)
        assertEquals(2, history.rows.size)
        assertEquals("original", repo.listHistory("a", created.id).first().snapshot.content)
        val anchoredCreate = repo.createMemoryInStore("a", "another", isAnchor = true)
        assertTrue(anchoredCreate.isAnchor)
        assertEquals(2L, anchoredCreate.revision)
    }

    @Test
    fun `history restores old business state as a new revision without rewinding read telemetry`() = runBlocking {
        val created = repo.addMemory("a", "original", importance = 0.3f, now = 1L)
        repo.updateMemoryInStore("a", created.id, "changed", 0.9f, "new", true, 1L, now = 2L)
        repo.readMemoryInStore("a", created.id, now = 10L)
        repo.readMemoryInStore("a", created.id, now = 20L)
        val restored = repo.restoreRevision("a", created.id, targetRevision = 1L, expectedRevision = 2L, now = 30L)
        assertEquals(3L, restored.revision)
        assertEquals("original", restored.content)
        assertEquals(0.3f, restored.importance, 0.001f)
        assertFalse(restored.isAnchor)
        assertEquals(2, restored.readCount)
        assertEquals(20L, restored.lastReadAt)
        assertEquals(30L, restored.updatedAt)
        assertEquals("RESTORE_REVISION", repo.listHistory("a", created.id).first().event.operation)
        assertEquals("changed", repo.listHistory("a", created.id).first().snapshot.content)
        assertTrue(runCatching {
            repo.restoreRevision("a", created.id, 1L, expectedRevision = 2L)
        }.exceptionOrNull() is MemoryRevisionConflictException)
    }

    @Test
    fun `history retains only the newest twenty events per original numeric record`() = runBlocking {
        val created = repo.addMemory("a", "v1")
        repeat(25) { index -> repo.updateContentInStore("a", created.id, "v${index + 2}") }
        val entries = repo.listHistory("a", created.id)
        assertEquals(MemoryRepository.MAX_HISTORY_COUNT, entries.size)
        assertEquals(25L, entries.first().snapshot.revision)
        assertEquals(6L, entries.last().snapshot.revision)
        val before = repo.getMemoryInStore("a", created.id)!!
        assertTrue(runCatching {
            repo.restoreRevision("a", created.id, targetRevision = 1L, expectedRevision = before.revision)
        }.isFailure)
        assertEquals(before, repo.getMemoryInStore("a", created.id))
    }

    @Test
    fun `trash restores stable numeric and public ids with monotonically increasing versions`() = runBlocking {
        val created = repo.addMemory("a", "original", now = 1L)
        repo.readMemoryInStore("a", created.id, now = 2L)
        repo.deleteMemoryInStore("a", created.id, expectedRevision = 1L, actor = "CHAT", reason = "forget")
        val deleted = repo.listTrash("a").single()
        assertEquals(2L, deleted.tombstone.revision)
        assertEquals(1L, deleted.snapshot.revision)
        assertEquals("CHAT", deleted.tombstone.actor)
        assertEquals("forget", deleted.tombstone.reason)
        assertNull(repo.getMemoryInStore("a", created.id))
        assertTrue(runCatching {
            repo.restoreTrash("a", created.sortKey, expectedRevision = 1L)
        }.exceptionOrNull() is MemoryRevisionConflictException)
        val restored = repo.restoreTrash("a", created.sortKey, expectedRevision = 2L, now = 3L)
        assertEquals(created.sortKey, restored.sortKey)
        assertEquals(created.id, restored.id)
        assertEquals(3L, restored.revision)
        assertEquals(1, restored.readCount)
        assertTrue(repo.listTrash("a").isEmpty())
        repo.deleteMemoryInStore("a", created.id, expectedRevision = 3L)
        assertEquals(4L, repo.listTrash("a").single().tombstone.revision)
        assertEquals(5L, repo.restoreTrash("a", created.sortKey, expectedRevision = 4L).revision)
        assertEquals(setOf("CREATE", "DELETE", "RESTORE_TRASH"),
            repo.listHistory("a", created.id).map { it.event.operation }.toSet())
    }

    @Test
    fun `history and trash operations reject cross store identifiers including legacy numeric ids`() = runBlocking {
        val created = repo.addMemory("a", "private")
        repo.updateContentInStore("a", created.id, "new")
        assertTrue(repo.listHistory("b", created.id).isEmpty())
        assertTrue(repo.listHistory("b", created.sortKey.toString()).isEmpty())
        assertTrue(runCatching { repo.restoreRevision("b", created.id, 1L, 2L) }.isFailure)
        repo.deleteMemoryInStore("a", created.id, expectedRevision = 2L)
        assertTrue(repo.listTrash("b").isEmpty())
        assertTrue(repo.listHistory("b", created.id).isEmpty())
        assertTrue(runCatching { repo.restoreTrash("b", created.sortKey, 3L) }.isFailure)
        assertEquals(1, repo.listTrash("a").size)
    }

    @Test
    fun `quota evictions remain in durable trash rather than being permanently destroyed`() = runBlocking {
        repeat(MemoryRepository.MAX_MEMORY_COUNT + 25) { index ->
            repo.addMemory("a", "fact $index", importance = 0.6f, now = 1L, actor = "CHAT", reason = "tool")
        }
        val deleted = repo.listTrash("a")
        assertEquals(25, deleted.size)
        assertEquals(MemoryRepository.MAX_MEMORY_COUNT, repo.getMemoriesOfAssistant("a").size)
        assertTrue(deleted.all { it.tombstone.actor == "CHAT" && it.tombstone.reason == "tool" })
        assertTrue(deleted.all { it.snapshot.content.startsWith("fact ") })
        deleted.forEach {
            assertTrue(repo.listHistory("a", it.snapshot.publicId).any { event -> event.event.operation == "EVICT" })
        }
    }

    @Test
    fun `restoring into a full library refuses without evicting any live record or consuming trash`() = runBlocking {
        val created = repo.addMemory("a", "deleted")
        repo.deleteMemoryInStore("a", created.id, expectedRevision = 1L)
        repeat(MemoryRepository.MAX_MEMORY_COUNT) { index -> repo.addMemory("a", "kept $index") }
        val before = repo.getMemoriesOfAssistant("a")
        assertTrue(runCatching { repo.restoreTrash("a", created.sortKey, 2L) }.exceptionOrNull()
            ?.message.orEmpty().contains("capacity"))
        assertEquals(before, repo.getMemoriesOfAssistant("a"))
        assertEquals(1, repo.listTrash("a").size)
    }

    @Test
    fun `kind parsing safely falls back`() {
        MemoryKind.entries.forEach { assertEquals(it, MemoryKind.fromStorage(it.storage)) }
        MemoryTaskStatus.entries.forEach { assertEquals(it, MemoryTaskStatus.fromStorage(it.storage)) }
        assertEquals(MemoryKind.TOPIC, MemoryKind.fromStorage("future"))
        assertEquals(MemoryTaskStatus.OPEN, MemoryTaskStatus.fromStorage(null))
        val legacy = kotlinx.serialization.json.Json.decodeFromString<MemoryEntity>("""{"assistantId":"a","content":"legacy"}""")
        assertEquals("topic", legacy.kind)
        assertEquals("open", legacy.taskStatus)
        assertEquals(0L, legacy.dueAt)
    }

    @Test
    fun `task lifecycle keeps recency CAS and is idempotent`() = runBlocking {
        val created = repo.addMemory("a", "commitment", now = 10L, kind = MemoryKind.TASK, dueAt = 30L)
        val done = repo.setTaskStatusInStore("a", created.id, MemoryTaskStatus.DONE, created.revision, now = 20L)
        assertEquals(10L, done.updatedAt)
        assertEquals(2L, done.revision)
        assertEquals(done, repo.setTaskStatusInStore("a", done.id, MemoryTaskStatus.DONE, done.revision))
        assertTrue(runCatching { repo.setTaskStatus(done.id, MemoryTaskStatus.DONE, 1L) }.exceptionOrNull() is MemoryRevisionConflictException)
        assertTrue(runCatching { repo.setTaskStatusInStore("b", done.id, MemoryTaskStatus.OPEN, 2L) }.isFailure)
        val topic = repo.updateContentInStore("a", done.id, done.content, kind = MemoryKind.TOPIC)
        assertEquals(0L, topic.dueAt)
        assertEquals(MemoryTaskStatus.OPEN, topic.taskStatus)
        assertTrue(runCatching { repo.setTaskStatus(topic.id, MemoryTaskStatus.DONE, topic.revision) }.exceptionOrNull()?.message == "not a task")
    }

    @Test
    fun `new task commitments do not deduplicate closed or differently dated tasks`() = runBlocking {
        val first = repo.addMemory("a", "weekly report", kind = MemoryKind.TASK, dueAt = 10L)
        assertEquals(first.id, repo.addMemory("a", "weekly report", kind = MemoryKind.TASK, dueAt = 10L).id)
        val otherDate = repo.addMemory("a", "weekly report", kind = MemoryKind.TASK, dueAt = 20L)
        assertNotEquals(first.id, otherDate.id)
        repo.setTaskStatus(first.id, MemoryTaskStatus.DONE, first.revision)
        val reopenedCommitment = repo.addMemory("a", "weekly report", kind = MemoryKind.TASK, dueAt = 10L)
        assertNotEquals(first.id, reopenedCommitment.id)
    }

    @Test
    fun `task cannot anchor and UI can atomically unanchor during conversion`() = runBlocking {
        val anchored = repo.createMemoryInStore("a", "identity", isAnchor = true)
        assertTrue(runCatching { repo.updateContent(anchored.id, "identity", kind = MemoryKind.TASK) }.isFailure)
        val task = repo.updateMemoryInStore("a", anchored.id, anchored.content, anchored.importance, anchored.description,
            false, anchored.revision, kind = MemoryKind.TASK, dueAt = 10L)
        assertFalse(task.isAnchor)
        assertTrue(runCatching { repo.setAnchor(task.id, true, task.revision) }.isFailure)
    }

    @Test
    fun `copy and revision restore retain task business state`() = runBlocking {
        val created = repo.addMemory("src", "commitment", kind = MemoryKind.TASK, dueAt = 100L)
        val done = repo.setTaskStatus(created.id, MemoryTaskStatus.DONE, created.revision)
        repo.copyMemories("src", "dst")
        val copied = repo.getMemoriesOfAssistant("dst").single()
        assertEquals(MemoryKind.TASK, copied.kind)
        assertEquals(MemoryTaskStatus.DONE, copied.taskStatus)
        assertEquals(100L, copied.dueAt)
        val topic = repo.updateContent(done.id, "topic", kind = MemoryKind.TOPIC)
        val restored = repo.restoreRevision("src", topic.id, done.revision, topic.revision)
        assertEquals(MemoryKind.TASK, restored.kind)
        assertEquals(MemoryTaskStatus.DONE, restored.taskStatus)
        assertEquals(100L, restored.dueAt)
    }

    @Test
    fun `copyMemories carries the description`() = runBlocking {
        repo.addMemory("src", "content", description = "topic")
        repo.copyMemories("src", "dst")
        assertEquals("topic", repo.getMemoriesOfAssistant("dst").single().description)
    }
}
