package app.androidrik.rikkahub.data.repository

import app.androidrik.rikkahub.data.db.dao.TranslationHistoryDAO
import app.androidrik.rikkahub.data.db.entity.TranslationHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * [TranslationHistoryRepository] 的纯 JVM 测试：不起 Room、不起 Robolectric。
 *
 * [TranslationHistoryDAO] 是 interface，这里用手写内存实现；DAO 的 SQL 语义（倒序、LIMIT、
 * REPLACE、裁剪）由 androidTest 的 `TranslationHistoryDAOTest` 在真机上验证。
 *
 * 时钟通过构造参数注入，所以 `createAt` 是确定的，不需要依赖当前系统时间。
 */
class TranslationHistoryRepositoryTest {
    private val dao = FakeTranslationHistoryDAO()
    private var clock = 1_700_000_000_000L
    private val repository = TranslationHistoryRepository(dao) { clock }

    @Test
    fun `add writes every field with an injected timestamp and a fresh uuid id`() = runTest {
        clock = 1_700_000_000_123L

        repository.add(
            sourceText = "Hello world",
            translatedText = "你好，世界",
            targetLanguage = "zh-CN",
            modelId = "9f8e7d6c-0000-4000-8000-000000000045",
        )

        val entry = dao.inserted.single()
        assertEquals("Hello world", entry.sourceText)
        assertEquals("你好，世界", entry.translatedText)
        assertEquals("zh-CN", entry.targetLanguage)
        assertEquals("9f8e7d6c-0000-4000-8000-000000000045", entry.modelId)
        assertEquals("createAt 必须用注入的时钟", 1_700_000_000_123L, entry.createAt)

        // 主键是随机 UUID 的字符串形式（java.util.UUID 解析失败会直接抛异常）；
        // 用 toString() 反解一次，确保存的是标准小写连字符格式（备份/导入按文本列处理）。
        val parsed = UUID.fromString(entry.id)
        assertEquals(entry.id, parsed.toString())
        assertEquals(36, entry.id.length)
    }

    @Test
    fun `each add gets its own uuid instead of reusing the previous one`() = runTest {
        repeat(5) { index ->
            clock = 1_700_000_000_000L + index
            repository.add("source-$index", "translated-$index", "en", "model-1")
        }

        val ids = dao.inserted.map { it.id }
        assertEquals("5 条记录应有 5 个不同的主键", 5, ids.toSet().size)
        assertEquals(
            "时间戳应逐条递增",
            listOf(0L, 1L, 2L, 3L, 4L),
            dao.inserted.map { it.createAt - 1_700_000_000_000L },
        )
    }

    @Test
    fun `add trims to the hard limit after every insert`() = runTest {
        val total = TranslationHistoryRepository.MAX_HISTORY_COUNT + 1

        repeat(total) { index ->
            clock = 1_700_000_000_000L + index
            repository.add("source-$index", "translated-$index", "zh-CN", "model-1")
        }

        // 每次 add 都写一行、都裁剪一次（不依赖调用方记得清理）。
        assertEquals(total, dao.inserted.size)
        assertEquals(List(total) { TranslationHistoryRepository.MAX_HISTORY_COUNT }, dao.trimmedTo)
        assertEquals(100, TranslationHistoryRepository.MAX_HISTORY_COUNT)

        // 裁剪后只剩最新的 100 条：最旧的那条被删掉，最新的还在。
        val remaining = dao.rows.value
        assertEquals(TranslationHistoryRepository.MAX_HISTORY_COUNT, remaining.size)
        assertEquals("source-${total - 1}", remaining.first().sourceText)
        assertEquals("source-1", remaining.last().sourceText)
        assertTrue(remaining.none { it.sourceText == "source-0" })
    }

    @Test
    fun `history exposes the dao flow and asks it for at most the limit`() = runTest {
        dao.seed(
            entry("c", createAt = 3_000L),
            entry("b", createAt = 2_000L),
            entry("a", createAt = 1_000L),
        )

        val emitted = repository.history().first()

        assertEquals("仓储不重排、不截断，DAO 给什么就是什么", listOf("c", "b", "a"), emitted.map { it.id })
        assertEquals(TranslationHistoryRepository.MAX_HISTORY_COUNT, dao.requestedLimit)
    }

    @Test
    fun `delete forwards the id to the dao`() = runTest {
        dao.seed(entry("keep", createAt = 1_000L), entry("remove", createAt = 2_000L))

        repository.delete("remove")

        assertEquals(listOf("remove"), dao.deletedIds)
        assertEquals(listOf("keep"), repository.history().first().map { it.id })
    }

    @Test
    fun `clear removes every row`() = runTest {
        dao.seed(entry("a", createAt = 1_000L), entry("b", createAt = 2_000L))

        repository.clear()

        assertEquals(1, dao.clearCount)
        assertTrue(repository.history().first().isEmpty())
    }

    private fun entry(id: String, createAt: Long): TranslationHistoryEntity = TranslationHistoryEntity(
        id = id,
        sourceText = "source-$id",
        translatedText = "translated-$id",
        targetLanguage = "zh-CN",
        modelId = "model-1",
        createAt = createAt,
    )

    /**
     * 内存版 DAO：只实现「观测 + 写入 + 删除 + 裁剪」的**语义**，SQL 细节交给真机测试。
     *
     * `flowLatest` 刻意不排序——排序是 DAO 的 SQL 契约（`ORDER BY create_at DESC`），
     * 仓储层只负责透传；这样「history 不重排」这条也能被测出来。
     */
    private class FakeTranslationHistoryDAO : TranslationHistoryDAO {
        val rows = MutableStateFlow<List<TranslationHistoryEntity>>(emptyList())
        val inserted = mutableListOf<TranslationHistoryEntity>()
        val trimmedTo = mutableListOf<Int>()
        val deletedIds = mutableListOf<String>()
        var clearCount = 0
            private set
        var requestedLimit: Int? = null
            private set

        fun seed(vararg entries: TranslationHistoryEntity) {
            rows.value = entries.toList()
        }

        override fun flowLatest(limit: Int): Flow<List<TranslationHistoryEntity>> {
            requestedLimit = limit
            return rows.map { it.take(limit) }
        }

        override suspend fun insert(entity: TranslationHistoryEntity) {
            inserted += entity
            rows.update { current -> current.filterNot { it.id == entity.id } + entity }
        }

        override suspend fun deleteById(id: String) {
            deletedIds += id
            rows.update { current -> current.filterNot { it.id == id } }
        }

        override suspend fun deleteAll() {
            clearCount++
            rows.value = emptyList()
        }

        override suspend fun trimTo(keep: Int) {
            trimmedTo += keep
            rows.update { current -> current.sortedByDescending { it.createAt }.take(keep) }
        }
    }
}
