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
}
