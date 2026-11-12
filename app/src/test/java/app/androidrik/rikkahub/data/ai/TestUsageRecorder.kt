package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.db.dao.DayCount
import app.androidrik.rikkahub.data.db.dao.ModelUsageRow
import app.androidrik.rikkahub.data.db.dao.SourceUsage
import app.androidrik.rikkahub.data.db.dao.UsageBucket
import app.androidrik.rikkahub.data.db.dao.UsageOptionRow
import app.androidrik.rikkahub.data.db.dao.UsageRecordDAO
import app.androidrik.rikkahub.data.db.dao.UsageTotals
import app.androidrik.rikkahub.data.db.entity.UsageRecordEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job

/**
 * 测试用的空用量记录器：不碰数据库，写流水直接丢弃。
 *
 * 构造 `GenerationHandler` 需要一个 [UsageRecorder]（它会在每次 provider 请求后往
 * `usage_record` 写一行），而这些测试考的是生成循环本身——用真的 Adb 写库既慢又会因为
 * Room 未初始化而炸。
 */
internal fun noopUsageRecorder(): UsageRecorder = UsageRecorder(
    dao = NoopUsageRecordDao,
    scope = CoroutineScope(Dispatchers.Unconfined + Job()),
)

private object NoopUsageRecordDao : UsageRecordDAO {
    override suspend fun insert(record: UsageRecordEntity) = Unit

    override suspend fun getTotals(
        since: Long,
        until: Long?,
        providerKey: String?,
        modelKey: String?,
    ): UsageTotals = UsageTotals()

    override suspend fun getTotalsByBucket(
        format: String,
        since: Long,
        until: Long?,
        providerKey: String?,
        modelKey: String?,
    ): List<UsageBucket> = emptyList()

    override suspend fun getRequestCountPerDay(source: String, since: Long): List<DayCount> = emptyList()

    override suspend fun getTotalsByModel(
        since: Long,
        until: Long?,
        providerKey: String?,
        modelKey: String?,
    ): List<ModelUsageRow> = emptyList()

    override suspend fun getTotalsBySource(
        since: Long,
        until: Long?,
        providerKey: String?,
        modelKey: String?,
    ): List<SourceUsage> = emptyList()

    override suspend fun getDistinctProviders(since: Long, until: Long?): List<UsageOptionRow> = emptyList()

    override suspend fun getDistinctModels(since: Long, until: Long?): List<UsageOptionRow> = emptyList()
}
