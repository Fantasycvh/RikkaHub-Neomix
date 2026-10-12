package app.androidrik.rikkahub.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import app.androidrik.rikkahub.data.db.dao.MemoryDAO
import app.androidrik.rikkahub.data.db.entity.MemoryEntity
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
            val id = nextId++
            rows.add(memory.copy(id = id))
            return id.toLong()
        }

        override suspend fun insertMemories(memories: List<MemoryEntity>) {
            memories.forEach { rows.add(it.copy(id = nextId++)) }
        }

        override suspend fun updateMemory(memory: MemoryEntity) {
            val i = rows.indexOfFirst { it.id == memory.id }
            if (i >= 0) rows[i] = memory
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
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(readCount = rows[i].readCount + 1, lastReadAt = now)
        }
    }

    private val dao = FakeMemoryDAO()
    private val repo = MemoryRepository(dao)

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
    fun `readMemory returns null and records nothing for an unknown id`() = runBlocking {
        assertNull(repo.readMemory("deadbeef", now = 2_000L))
    }

    @Test
    fun `copyMemories carries the description`() = runBlocking {
        repo.addMemory("src", "content", description = "topic")
        repo.copyMemories("src", "dst")
        assertEquals("topic", repo.getMemoriesOfAssistant("dst").single().description)
    }
}
