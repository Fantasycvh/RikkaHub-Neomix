package app.androidrik.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.TranslationHistoryEntity
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `translation_history` 的 SQL 在**真机 Room/SQLite** 上验证。
 *
 * 这里只断言 DAO 暴露的行为：倒序 + `LIMIT`、REPLACE 主键、单条删除、清空、按时间裁剪。
 * 仓储层「写完立即裁剪到 100」的编排在 JVM `TranslationHistoryRepositoryTest` 里测。
 */
@RunWith(AndroidJUnit4::class)
class TranslationHistoryDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: TranslationHistoryDAO

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.translationHistoryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun flowLatestOrdersByCreateAtDescendingAndAppliesLimit() = runBlocking {
        insert("oldest", createAt = 1_000L)
        insert("newest", createAt = 3_000L)
        insert("middle", createAt = 2_000L)

        // 排序只由 SQL 的 ORDER BY 决定，DAO 不参与。
        val all = dao.flowLatest(10).first()
        assertEquals(listOf("newest", "middle", "oldest"), all.map { it.id })

        // 只回传最近的 N 条，而不是全表读回再在外层截断。
        assertEquals(listOf("newest", "middle"), dao.flowLatest(2).first().map { it.id })
        assertTrue(dao.flowLatest(0).first().isEmpty())
    }

    @Test
    fun insertStoresEveryColumnAndRoundTripsTextFields() = runBlocking {
        dao.insert(
            TranslationHistoryEntity(
                id = "entry-1",
                sourceText = "Hello world",
                translatedText = "你好，世界",
                targetLanguage = "zh-CN",
                modelId = "9f8e7d6c-0000-4000-8000-000000000045",
                createAt = 1_700_000_000_123L,
            ),
        )

        val entry = dao.flowLatest(10).first().single()
        assertEquals("entry-1", entry.id)
        assertEquals("Hello world", entry.sourceText)
        assertEquals("你好，世界", entry.translatedText)
        assertEquals("zh-CN", entry.targetLanguage)
        assertEquals("9f8e7d6c-0000-4000-8000-000000000045", entry.modelId)
        assertEquals(1_700_000_000_123L, entry.createAt)
    }

    @Test
    fun insertWithSameIdReplacesInsteadOfDuplicating() = runBlocking {
        insert("entry-1", createAt = 1_000L)
        insert("entry-1", createAt = 2_000L, translatedText = "rewritten")

        val rows = dao.flowLatest(10).first()
        assertEquals("REPLACE 不能让同一主键变成两行", 1, rows.size)
        assertEquals("rewritten", rows.single().translatedText)
        assertEquals(2_000L, rows.single().createAt)
    }

    @Test
    fun deleteByIdRemovesOnlyThatRow() = runBlocking {
        insert("a", createAt = 1_000L)
        insert("b", createAt = 2_000L)

        dao.deleteById("a")

        assertEquals(listOf("b"), dao.flowLatest(10).first().map { it.id })
        // 删不存在的 id 是 no-op，不抛异常（UI 连点两下删除时会发生）。
        dao.deleteById("missing")
        assertEquals(listOf("b"), dao.flowLatest(10).first().map { it.id })
    }

    @Test
    fun deleteAllClearsTheTable() = runBlocking {
        insert("a", createAt = 1_000L)
        insert("b", createAt = 2_000L)

        dao.deleteAll()

        assertTrue(dao.flowLatest(10).first().isEmpty())
    }

    @Test
    fun trimToKeepsOnlyTheNewestRows() = runBlocking {
        insert("oldest", createAt = 1_000L)
        insert("second", createAt = 2_000L)
        insert("third", createAt = 3_000L)
        insert("fourth", createAt = 4_000L)
        insert("newest", createAt = 5_000L)

        dao.trimTo(3)
        assertEquals(listOf("newest", "fourth", "third"), dao.flowLatest(10).first().map { it.id })

        // 上限比现有条数大时不能误删。
        dao.trimTo(10)
        assertEquals(3, dao.flowLatest(10).first().size)

        // keep = 0 是「全删」而不是「删 0 条」。
        dao.trimTo(0)
        assertTrue(dao.flowLatest(10).first().isEmpty())
    }

    private suspend fun insert(
        id: String,
        createAt: Long,
        sourceText: String = "source-$id",
        translatedText: String = "translated-$id",
        targetLanguage: String = "zh-CN",
        modelId: String = "model-1",
    ) {
        dao.insert(
            TranslationHistoryEntity(
                id = id,
                sourceText = sourceText,
                translatedText = translatedText,
                targetLanguage = targetLanguage,
                modelId = modelId,
                createAt = createAt,
            ),
        )
    }
}
