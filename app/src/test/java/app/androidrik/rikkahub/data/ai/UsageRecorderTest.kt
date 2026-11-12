package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.TokenUsage
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.Provider
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.TextGenerationResult
import app.androidrik.ai.ui.StreamChunk
import app.androidrik.ai.ui.UIMessage
import app.androidrik.rikkahub.data.db.dao.DayCount
import app.androidrik.rikkahub.data.db.dao.ModelUsageRow
import app.androidrik.rikkahub.data.db.dao.SourceUsage
import app.androidrik.rikkahub.data.db.dao.UsageBucket
import app.androidrik.rikkahub.data.db.dao.UsageOptionRow
import app.androidrik.rikkahub.data.db.dao.UsageRecordDAO
import app.androidrik.rikkahub.data.db.dao.UsageTotals
import app.androidrik.rikkahub.data.db.entity.UsageRecordEntity
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [UsageRecorder] 的纯 JVM 测试：不起 Room、不起 Robolectric。
 *
 * [UsageRecordDAO] 在这里是手写内存实现（它是 interface，DAO 的 SQL 语义由 androidTest 的
 * `UsageRecordDAOTest` 在真机上验证）；`android.util.Log` 走 `unitTests.isReturnDefaultValues`。
 */
@OptIn(ExperimentalUuidApi::class)
class UsageRecorderTest {
    private val dao = FakeUsageRecordDAO()
    private val setting = ProviderSetting.OpenAI()
    private val model = Model(modelId = "gpt-test", displayName = "Test Model")
    private val params = TextGenerationParams(model = model)
    private val assistantId = Uuid.random()
    private val conversationId = Uuid.random()
    private val tag = UsageTag(
        source = UsageSource.CHAT,
        assistantId = assistantId,
        conversationId = conversationId,
    )

    private fun TestScope.recorder(dao: UsageRecordDAO = this@UsageRecorderTest.dao) =
        UsageRecorder(dao, this)

    // ---- 新接口夹具（旧 `chunk(usage)` 的等价物） ----

    /** 非流式结果：只关心 usage（旧 `MessageChunk(choices = emptyList(), usage = ...)` 的等价物）。 */
    private fun textResult(usage: TokenUsage?) = TextGenerationResult(
        id = "chunk",
        model = "gpt-test",
        message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()),
        usage = usage,
    )

    /** 带 usage 的流事件（旧 `chunk(usage)` 的流式等价物）。 */
    private fun usageEvent(usage: TokenUsage) = StreamChunk.Usage(usage)

    /** 不带 usage 的流事件（旧 `chunk(null)` 的等价物）：包装器应原样透传、不记流水。 */
    private fun noUsageEvent(): StreamChunk = StreamChunk.TextStart("noop")

    @Test
    fun `generateText records one row with source provider model and tag attribution`() = runTest {
        val provider = FakeProvider(
            result = textResult(TokenUsage(promptTokens = 10, completionTokens = 2, cachedTokens = 4, totalTokens = 12)),
        )

        recorder().wrap(provider, tag).generateText(setting, emptyList(), params)
        testScheduler.advanceUntilIdle()

        val record = dao.records.single()
        assertEquals(UsageSource.CHAT.name, record.source)
        assertEquals(setting.id.toString(), record.providerId)
        assertEquals(setting.name, record.providerName)
        assertEquals(model.id.toString(), record.modelId)
        assertEquals("Test Model", record.modelName)
        assertEquals(assistantId.toString(), record.assistantId)
        assertEquals(conversationId.toString(), record.conversationId)
        assertEquals(10, record.promptTokens)
        assertEquals(2, record.completionTokens)
        assertEquals(4, record.cachedTokens)
        assertTrue("记录时间应为当前时刻", record.createdAt > 0L)
    }

    @Test
    fun `generateText falls back to modelId snapshot when display name is blank`() = runTest {
        val provider = FakeProvider(result = textResult(TokenUsage(promptTokens = 3, completionTokens = 1)))
        val blankNamedModel = Model(modelId = "gpt-raw", displayName = "")

        recorder().wrap(provider, tag)
            .generateText(setting, emptyList(), params.copy(model = blankNamedModel))
        testScheduler.advanceUntilIdle()

        assertEquals("gpt-raw", dao.records.single().modelName)
    }

    @Test
    fun `streamText merges usage snapshots and records exactly one row`() = runTest {
        val provider = FakeProvider(
            chunks = flow {
                emit(usageEvent(TokenUsage(promptTokens = 10)))
                emit(noUsageEvent())
                emit(usageEvent(TokenUsage(completionTokens = 5, cachedTokens = 3)))
            },
        )

        val chunks = recorder().wrap(provider, tag).streamText(setting, emptyList(), params).toList()
        testScheduler.advanceUntilIdle()

        assertEquals(3, chunks.size)
        val record = dao.records.single()
        assertEquals(10, record.promptTokens)
        assertEquals(5, record.completionTokens)
        assertEquals(3, record.cachedTokens)
        assertEquals(UsageSource.CHAT.name, record.source)
    }

    @Test
    fun `streamText that throws midway still records the usage already received`() = runTest {
        val provider = FakeProvider(
            chunks = flow {
                emit(usageEvent(TokenUsage(promptTokens = 10)))
                throw IllegalStateException("upstream exploded")
            },
        )

        val failure = runCatching {
            recorder().wrap(provider, tag).streamText(setting, emptyList(), params).toList()
        }.exceptionOrNull()
        testScheduler.advanceUntilIdle()

        assertNotNull("上游异常必须继续向上抛", failure)
        assertEquals("upstream exploded", failure?.message)
        // 只记一次，且记的是真正已经发生的部分用量
        assertEquals(1, dao.records.size)
        assertEquals(10, dao.records.single().promptTokens)
    }

    @Test
    fun `streamText cancelled midway still records the usage already received`() = runTest {
        val provider = FakeProvider(
            chunks = flow {
                emit(usageEvent(TokenUsage(promptTokens = 10)))
                awaitCancellation()
            },
        )

        val received = recorder().wrap(provider, tag)
            .streamText(setting, emptyList(), params)
            .take(1)
            .toList()
        testScheduler.advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals(1, dao.records.size)
        assertEquals(10, dao.records.single().promptTokens)
    }

    @Test
    fun `usage with no prompt and no completion tokens is not recorded`() = runTest {
        val recorder = recorder()
        // usage 为 null：上游没回
        recorder.wrap(FakeProvider(result = textResult(null)), tag).generateText(setting, emptyList(), params)
        // 只有 cache 字段：prompt/completion 都是 0
        recorder.wrap(FakeProvider(result = textResult(TokenUsage(cachedTokens = 7))), tag)
            .generateText(setting, emptyList(), params)
        recorder.wrap(FakeProvider(chunks = flow { emit(noUsageEvent()) }), tag)
            .streamText(setting, emptyList(), params).toList()
        testScheduler.advanceUntilIdle()

        assertTrue("空用量不应产生流水行", dao.records.isEmpty())
    }

    @Test
    fun `two attempts each record their own row`() = runTest {
        val recorder = recorder()
        recorder.wrap(FakeProvider(result = textResult(TokenUsage(promptTokens = 10, completionTokens = 1))), tag)
            .generateText(setting, emptyList(), params)
        recorder.wrap(FakeProvider(result = textResult(TokenUsage(promptTokens = 20, completionTokens = 2))), tag)
            .generateText(setting, emptyList(), params)
        testScheduler.advanceUntilIdle()

        assertEquals(2, dao.records.size)
        assertEquals(
            listOf(10 to 1, 20 to 2),
            dao.records.map { it.promptTokens to it.completionTokens },
        )
    }

    @Test
    fun `dao insert failure does not affect the returned result`() = runTest {
        val throwingDao = FakeUsageRecordDAO(throwOnInsert = true)

        val result = recorder(throwingDao).wrap(
            FakeProvider(result = textResult(TokenUsage(promptTokens = 10, completionTokens = 1))),
            tag,
        ).generateText(setting, emptyList(), params)
        val chunks = recorder(throwingDao).wrap(
            FakeProvider(chunks = flow { emit(usageEvent(TokenUsage(promptTokens = 2, completionTokens = 1))) }),
            tag,
        ).streamText(setting, emptyList(), params).toList()
        testScheduler.advanceUntilIdle()

        assertNotNull("generateText 的结果不能被写流水失败吞掉", result)
        assertEquals(1, chunks.size)
        assertTrue("写库失败只打日志，不能留下半成品行", throwingDao.records.isEmpty())
    }

    @Test
    fun `unrelated provider capabilities are passed through`() = runTest {
        // 包装器是 Kotlin 委托：只覆盖 generateText/streamText，其余能力必须原样透传。
        val provider = FakeProvider(result = textResult(null))
        val wrapped = recorder().wrap(provider, tag)
        assertTrue(wrapped.listModels(setting).isEmpty())
    }

    private class FakeProvider(
        private val result: TextGenerationResult? = null,
        private val chunks: Flow<StreamChunk> = emptyFlow(),
    ) : Provider<ProviderSetting.OpenAI> {
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> = emptyList()

        override suspend fun generateText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult = result ?: error("FakeProvider has no generateText result")

        override suspend fun streamText(
            providerSetting: ProviderSetting.OpenAI,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = chunks
    }

    private class FakeUsageRecordDAO(
        private val throwOnInsert: Boolean = false,
    ) : UsageRecordDAO {
        val records = mutableListOf<UsageRecordEntity>()

        override suspend fun insert(record: UsageRecordEntity) {
            if (throwOnInsert) error("database is on fire")
            records += record
        }

        override suspend fun getTotals(
            since: Long,
            until: Long?,
            providerId: String?,
            providerName: String?,
            modelId: String?,
            modelName: String?,
        ): UsageTotals = UsageTotals()

        override suspend fun getTotalsByBucket(
            format: String,
            since: Long,
            until: Long?,
            providerId: String?,
            providerName: String?,
            modelId: String?,
            modelName: String?,
        ): List<UsageBucket> = emptyList()

        override suspend fun getRequestCountPerDay(source: String, since: Long): List<DayCount> = emptyList()

        override suspend fun getTotalsByModel(
            since: Long,
            until: Long?,
            providerId: String?,
            providerName: String?,
            modelId: String?,
            modelName: String?,
        ): List<ModelUsageRow> = emptyList()

        override suspend fun getTotalsBySource(
            since: Long,
            until: Long?,
            providerId: String?,
            providerName: String?,
            modelId: String?,
            modelName: String?,
        ): List<SourceUsage> = emptyList()

        override suspend fun getDistinctProviders(since: Long, until: Long?): List<UsageOptionRow> = emptyList()

        override suspend fun getDistinctModels(since: Long, until: Long?): List<UsageOptionRow> = emptyList()
    }
}
