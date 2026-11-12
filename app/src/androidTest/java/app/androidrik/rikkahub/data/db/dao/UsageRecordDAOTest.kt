package app.androidrik.rikkahub.data.db.dao

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.androidrik.rikkahub.data.db.AppDatabase
import app.androidrik.rikkahub.data.db.entity.UsageRecordEntity
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * `usage_record` 的聚合 SQL 在**真机 Room/SQLite** 上验证。
 *
 * 分桶断言依赖 `strftime(..., 'localtime')`，所以所有插入时刻都用**本地时间中午**：
 * 东八区凌晨的 UTC 日期会差一天，中午插入可以让「本地日/月」在任何常见时区都稳定命中。
 * 聚合全部在 SQLite 侧完成，这里只断言 DAO 暴露的结果。
 */
@RunWith(AndroidJUnit4::class)
class UsageRecordDAOTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: UsageRecordDAO

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        ).openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        dao = database.usageRecordDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getTotalsSumsEverythingAndRespectsSince() = runBlocking {
        // 空表也必须返回一行 0（COALESCE），不能让统计页拿到 null。
        val empty = dao.getTotals(0)
        assertEquals(0L, empty.promptTokens)
        assertEquals(0L, empty.completionTokens)
        assertEquals(0L, empty.cachedTokens)
        assertEquals(0L, empty.totalTokens)

        insert("2026-08-10T12:00:00", prompt = 10, completion = 1, cached = 2)
        insert("2026-08-12T12:00:00", prompt = 20, completion = 3, cached = 4)

        val all = dao.getTotals(0)
        assertEquals(30L, all.promptTokens)
        assertEquals(4L, all.completionTokens)
        assertEquals(6L, all.cachedTokens)
        assertEquals(34L, all.totalTokens)

        val sinceAug11 = dao.getTotals(epochMillis("2026-08-11T00:00:00"))
        assertEquals(20L, sinceAug11.promptTokens)
        assertEquals(3L, sinceAug11.completionTokens)
        assertEquals(4L, sinceAug11.cachedTokens)
        assertEquals(23L, sinceAug11.totalTokens)
    }

    @Test
    fun getTotalsByBucketGroupsByLocalDayAndMonth() = runBlocking {
        insert("2026-07-31T12:00:00", prompt = 5, completion = 1)
        insert("2026-08-01T12:00:00", prompt = 10, completion = 2)
        insert("2026-08-01T13:00:00", prompt = 20, completion = 3)
        insert("2026-08-12T12:00:00", prompt = 1, completion = 1, cached = 9)

        val days = dao.getTotalsByBucket("%Y-%m-%d", 0)
        assertEquals(
            listOf("2026-07-31", "2026-08-01", "2026-08-12"),
            days.map { it.bucket },
        )
        assertEquals(listOf(5L, 30L, 1L), days.map { it.promptTokens })
        assertEquals(listOf(1L, 5L, 1L), days.map { it.completionTokens })
        assertEquals(9L, days.last().cachedTokens)

        val months = dao.getTotalsByBucket("%Y-%m", 0)
        assertEquals(listOf("2026-07", "2026-08"), months.map { it.bucket })
        assertEquals(listOf(5L, 31L), months.map { it.promptTokens })
        assertEquals(listOf(1L, 6L), months.map { it.completionTokens })

        // since 过滤：只剩 8 月的那几条
        val filtered = dao.getTotalsByBucket("%Y-%m-%d", epochMillis("2026-08-01T00:00:00"))
        assertEquals(listOf("2026-08-01", "2026-08-12"), filtered.map { it.bucket })
    }

    @Test
    fun getRequestCountPerDayCountsOnlyTheRequestedSource() = runBlocking {
        insert("2026-08-11T23:00:00", source = "CHAT")
        insert("2026-08-12T00:30:00", source = "CHAT")
        insert("2026-08-12T23:59:00", source = "CHAT")
        insert("2026-08-12T12:00:00", source = "TITLE")
        insert("2026-08-12T12:00:00", source = "COMPRESSION")
        insert("2026-08-13T08:00:00", source = "CHAT")

        val allChat = dao.getRequestCountPerDay("CHAT", 0).associate { it.day to it.count }
        assertEquals(
            mapOf("2026-08-11" to 1, "2026-08-12" to 2, "2026-08-13" to 1),
            allChat,
        )

        val sinceAug12 = dao.getRequestCountPerDay("CHAT", epochMillis("2026-08-12T00:00:00"))
            .associate { it.day to it.count }
        assertEquals(mapOf("2026-08-12" to 2, "2026-08-13" to 1), sinceAug12)

        val title = dao.getRequestCountPerDay("TITLE", 0).associate { it.day to it.count }
        assertEquals(mapOf("2026-08-12" to 1), title)
    }

    @Test
    fun getTotalsByModelMergesByIdKeepsLegacyNameOnlyRowsAndSortsByVolume() = runBlocking {
        // 同一 model_id 的两条流水要合并；名称快照取任意非空值
        insert(
            "2026-08-10T12:00:00", modelId = "model-a", modelName = "Model A",
            providerName = "Provider X", prompt = 10, completion = 1, cached = 2,
        )
        insert(
            "2026-08-10T12:05:00", modelId = "model-a", modelName = "Model A",
            providerName = "Provider X", prompt = 5, completion = 2, cached = 1,
        )
        // 只有名称、没有 id 的历史回填行（迁移的旧记录可能这样；provider 快照也可能为空）
        insert(
            "2026-08-10T12:10:00", modelId = null, modelName = "Legacy Model",
            providerId = null, providerName = null, prompt = 50, completion = 0,
        )
        insert(
            "2026-08-12T12:00:00", modelId = "model-b", modelName = "Model B",
            providerName = "Provider Y", prompt = 100, completion = 2,
        )

        val rows = dao.getTotalsByModel(0)
        assertEquals(listOf("model-b", null, "model-a"), rows.map { it.modelId })
        assertEquals(listOf("Model B", "Legacy Model", "Model A"), rows.map { it.modelName })

        val modelA = rows.single { it.modelId == "model-a" }
        assertEquals(15L, modelA.promptTokens)
        assertEquals(3L, modelA.completionTokens)
        assertEquals(3L, modelA.cachedTokens)
        assertEquals("Provider X", modelA.providerName)

        val legacy = rows.single { it.modelId == null }
        assertEquals("Legacy Model", legacy.modelName)
        assertNull(legacy.providerName)
        assertEquals(50L, legacy.promptTokens)

        // since 过滤后只剩 8 月 12 日的 model-b
        val filtered = dao.getTotalsByModel(epochMillis("2026-08-12T00:00:00"))
        assertEquals(listOf("model-b"), filtered.map { it.modelId })
        assertEquals(102L, filtered.single().let { it.promptTokens + it.completionTokens })
    }

    @Test
    fun getTotalsBySourceGroupsEverySourceAndSortsByVolume() = runBlocking {
        insert("2026-08-10T12:00:00", source = "CHAT", prompt = 10, completion = 1)
        insert("2026-08-10T12:05:00", source = "CHAT", prompt = 5, completion = 2, cached = 3)
        insert("2026-08-10T12:10:00", source = "TITLE", prompt = 100, completion = 0)
        insert("2026-08-10T12:15:00", source = "COMPRESSION", prompt = 50, completion = 10)
        insert("2026-08-10T12:20:00", source = "SUBAGENT", prompt = 1, completion = 2)

        val rows = dao.getTotalsBySource(0)
        assertEquals(
            listOf("TITLE", "COMPRESSION", "CHAT", "SUBAGENT"),
            rows.map { it.source },
        )
        val chat = rows.single { it.source == "CHAT" }
        assertEquals(15L, chat.promptTokens)
        assertEquals(3L, chat.completionTokens)
        assertEquals(3L, chat.cachedTokens)
        assertEquals(100L, rows.single { it.source == "TITLE" }.promptTokens)
        assertEquals(2L, rows.single { it.source == "SUBAGENT" }.completionTokens)

        // 8 个来源里没有流水的不能凭空出现
        assertEquals(4, rows.size)
    }

    @Test
    fun getTotalsRespectsUntilUpperBound() = runBlocking {
        insert("2026-08-10T12:00:00", prompt = 10, completion = 1)
        insert("2026-08-12T12:00:00", prompt = 20, completion = 3)

        val untilAug11 = dao.getTotals(until = epochMillis("2026-08-11T00:00:00"))
        assertEquals(10L, untilAug11.promptTokens)
        assertEquals(1L, untilAug11.completionTokens)

        // 半开区间：until = 次日零点，完整含住当天
        val onlyAug12 = dao.getTotals(
            since = epochMillis("2026-08-12T00:00:00"),
            until = epochMillis("2026-08-13T00:00:00"),
        )
        assertEquals(23L, onlyAug12.totalTokens)
    }

    @Test
    fun getTotalsFiltersByProviderGroupKey() = runBlocking {
        insert("2026-08-10T12:00:00", providerId = "p-1", providerName = "Provider One", prompt = 10)
        insert("2026-08-10T12:05:00", providerId = "p-2", providerName = "Provider Two", prompt = 20)
        // 回填行：没有 id，只有名称快照——自成一个分组，按名称键命中
        insert("2026-08-10T12:10:00", providerId = null, providerName = "Provider One", prompt = 40)
        // 无 id 也无名称的行：任何提供商筛选都筛不到，只在「全部」里可见
        insert("2026-08-10T12:15:00", providerId = null, providerName = null, prompt = 80)

        assertEquals(10L, dao.getTotals(providerKey = "p-1").promptTokens)
        assertEquals(20L, dao.getTotals(providerKey = "p-2").promptTokens)
        assertEquals(40L, dao.getTotals(providerKey = "Provider One").promptTokens)
        assertEquals(150L, dao.getTotals().promptTokens)
    }

    @Test
    fun getTotalsFiltersByModelGroupKey() = runBlocking {
        insert("2026-08-10T12:00:00", modelId = "model-a", modelName = "Model A", prompt = 10)
        insert("2026-08-10T12:05:00", modelId = "model-b", modelName = "Model B", prompt = 20)
        insert("2026-08-10T12:10:00", modelId = null, modelName = "Model A", prompt = 40)

        assertEquals(10L, dao.getTotals(modelKey = "model-a").promptTokens)
        assertEquals(20L, dao.getTotals(modelKey = "model-b").promptTokens)
        assertEquals(40L, dao.getTotals(modelKey = "Model A").promptTokens)
    }

    @Test
    fun getTotalsAndBucketsStackProviderModelAndTimeFilters() = runBlocking {
        insert("2026-08-10T12:00:00", providerId = "p-1", modelId = "model-a", prompt = 10)
        insert("2026-08-10T12:05:00", providerId = "p-2", modelId = "model-a", prompt = 20)
        insert("2026-08-12T12:00:00", providerId = "p-1", modelId = "model-a", prompt = 40)
        insert("2026-08-12T12:05:00", providerId = "p-1", modelId = "model-b", prompt = 80)

        val combined = dao.getTotals(
            since = epochMillis("2026-08-11T00:00:00"),
            providerKey = "p-1",
            modelKey = "model-a",
        )
        assertEquals(40L, combined.promptTokens)

        val buckets = dao.getTotalsByBucket(
            "%Y-%m-%d",
            since = epochMillis("2026-08-11T00:00:00"),
            modelKey = "model-a",
        )
        // p-2 的 8-10 与 model-b 的 8-12 都被过滤掉
        assertEquals(listOf("2026-08-12"), buckets.map { it.bucket })
        assertEquals(40L, buckets.single().promptTokens)
    }

    @Test
    fun getDistinctOptionsGroupByIdOrSnapshotAndFollowRange() = runBlocking {
        insert(
            "2026-08-10T12:00:00",
            providerId = "p-1", providerName = "Provider One",
            modelId = "model-a", modelName = "Model A",
        )
        // 同一提供商改名：仍是一个组，名称取任意非空快照
        insert(
            "2026-08-10T12:05:00",
            providerId = "p-1", providerName = "Provider One Renamed",
            modelId = "model-a", modelName = "Model A",
        )
        // 回填组：靠 COALESCE(id, name) 成组
        insert(
            "2026-08-10T12:10:00",
            providerId = null, providerName = "Legacy Provider",
            modelId = null, modelName = "Legacy Model",
        )
        // 完全无标识的行不进选项
        insert("2026-08-10T12:15:00", providerId = null, providerName = null)
        insert(
            "2026-08-12T12:00:00",
            providerId = "p-2", providerName = "Provider Two",
            modelId = "model-b", modelName = "Model B",
        )

        val providers = dao.getDistinctProviders(0)
        assertEquals(setOf("p-1", null, "p-2"), providers.map { it.id }.toSet())
        assertEquals(3, providers.size)
        assertTrue(
            providers.single { it.id == "p-1" }.name.let { it == "Provider One" || it == "Provider One Renamed" },
        )

        val models = dao.getDistinctModels(0)
        assertEquals(setOf("model-a", null, "model-b"), models.map { it.id }.toSet())
        assertEquals("Legacy Model", models.single { it.id == null }.name)

        // 选项随时间区间联动：只剩 8-12 的 p-2 / model-b
        val sinceAug12 = epochMillis("2026-08-12T00:00:00")
        assertEquals(listOf("p-2"), dao.getDistinctProviders(sinceAug12).map { it.id })
        assertEquals(listOf("model-b"), dao.getDistinctModels(sinceAug12).map { it.id })

        // until 上界同样生效
        val untilAug11 = dao.getDistinctProviders(0, epochMillis("2026-08-11T00:00:00"))
        assertEquals(setOf("p-1", null), untilAug11.map { it.id }.toSet())
    }

    private suspend fun insert(
        localDateTime: String,
        source: String = "CHAT",
        providerId: String? = "provider-1",
        providerName: String? = "Provider One",
        modelId: String? = null,
        modelName: String? = null,
        assistantId: String? = null,
        conversationId: String? = null,
        prompt: Int = 10,
        completion: Int = 1,
        cached: Int = 0,
    ) {
        dao.insert(
            UsageRecordEntity(
                createdAt = epochMillis(localDateTime),
                source = source,
                providerId = providerId,
                providerName = providerName,
                modelId = modelId,
                modelName = modelName,
                assistantId = assistantId,
                conversationId = conversationId,
                promptTokens = prompt,
                completionTokens = completion,
                cachedTokens = cached,
            ),
        )
    }

    /** 测试里的时间一律按设备本地时区解释（与 `strftime(..., 'localtime')` 同一口径）。 */
    private fun epochMillis(localDateTime: String): Long =
        LocalDateTime.parse(localDateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
