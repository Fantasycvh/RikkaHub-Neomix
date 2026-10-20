package app.androidrik.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.androidrik.rikkahub.data.ai.UsageSource
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.data.datastore.findModelById
import app.androidrik.rikkahub.data.db.dao.ConversationDAO
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import app.androidrik.rikkahub.data.db.dao.SourceUsage
import app.androidrik.rikkahub.data.db.dao.UsageBucket
import app.androidrik.rikkahub.data.db.dao.UsageRecordDAO
import app.androidrik.rikkahub.data.db.dao.UsageTotals
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.uuid.Uuid

/** 热力图的度量维度：请求次数 / token 消耗。都用 `usage_record` 流水。 */
enum class HeatmapMetric { REQUESTS, TOKENS }

/**
 * 卡片区（全量、与时间范围无关）的统计。
 *
 * 热力图按「最近 52 周」固定窗口查询，所以它跟用量区的时间范围筛选互不影响；
 * 两种度量都提前查好，切换时只改 [heatmapMetric]，不再重查数据库。
 */
data class AppStats(
    val isLoading: Boolean = true,
    val heatmapMetric: HeatmapMetric = HeatmapMetric.REQUESTS,
    val totalConversations: Int = 0,
    /** 节点数（不是消息条数）：一个节点可能带多个分支消息，按行数算是唯一确定的刻度。 */
    val totalMessages: Int = 0,
    val launchCount: Int = 0,
    /** 每天（本地日期）的聊天请求次数，`source = CHAT`。 */
    val requestsPerDay: Map<LocalDate, Int> = emptyMap(),
    /** 每天（本地日期）的 token 合计（输入 + 输出），全部来源。 */
    val tokensPerDay: Map<LocalDate, Long> = emptyMap(),
)

/** 用量统计的时间范围；[days] 为空表示全部，此时趋势按月分桶。 */
enum class UsageRange(val days: Int?) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    ALL(null),
}

/** 趋势图的一个点：按天时是当天，按月时是当月 1 号。 */
data class UsagePoint(
    val date: LocalDate,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/** 模型排行的一项；[name] 为空表示模型已删除且没有名称快照，页面显示「未知模型」。 */
data class ModelUsageItem(
    val name: String?,
    val providerName: String?,
    val totalTokens: Long,
)

/**
 * 「用量」区的全部数据。
 *
 * [range] 一起带出来（而不是只看 VM 的 range flow）：切换范围时旧数据会保留到新查询返回，
 * 页面必须能区分「这份数据属于哪个范围」，否则图表标题/点选状态会错位。
 */
data class UsageStats(
    val range: UsageRange,
    val totals: UsageTotals,
    val points: List<UsagePoint>,
    val byMonth: Boolean,
    val models: List<ModelUsageItem>,
    val sources: List<SourceUsage>,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val usageRecordDAO: UsageRecordDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats: StateFlow<AppStats> = _stats.asStateFlow()

    // 切换范围时先保留旧数据，查完再整体替换，避免用量区闪成加载态
    private val _usage = MutableStateFlow<UsageStats?>(null)
    val usage: StateFlow<UsageStats?> = _usage.asStateFlow()

    private val _range = MutableStateFlow(UsageRange.MONTH)
    val range: StateFlow<UsageRange> = _range.asStateFlow()

    private var usageJob: Job? = null

    init {
        viewModelScope.launch { loadStats() }
        loadUsage(_range.value)
    }

    fun setRange(range: UsageRange) {
        if (range == _range.value) return
        _range.value = range
        loadUsage(range)
    }

    fun setHeatmapMetric(metric: HeatmapMetric) {
        _stats.update { it.copy(heatmapMetric = metric) }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()
        // 热力图固定展示最近 53 周（52 周前的那一周 + 本周），起点取当周周日
        val startSunday = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
        val since = startSunday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 请求次数只数 CHAT：一次工具调用循环会发多个请求，正是「今天用了多少」的真实刻度
        val requestsPerDay = usageRecordDAO
            .getRequestCountPerDay(UsageSource.CHAT.name, since)
            .mapNotNull { entry ->
                runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
            }
            .toMap()

        // token 热力图取全部来源（标题、翻译、压缩这些不落到消息里的消耗也算）
        val tokensPerDay = usageRecordDAO
            .getTotalsByBucket(DAY_BUCKET_FORMAT, since)
            .mapNotNull { bucket ->
                runCatching { LocalDate.parse(bucket.bucket) }.getOrNull()
                    ?.let { date -> date to bucket.promptTokens + bucket.completionTokens }
            }
            .toMap()

        val totalConversations = conversationDAO.countAll()
        val totalMessages = messageNodeDAO.countAll()
        val launchCount = settingsStore.settingsFlow.value.launchCount

        _stats.update {
            it.copy(
                isLoading = false,
                totalConversations = totalConversations,
                totalMessages = totalMessages,
                launchCount = launchCount,
                requestsPerDay = requestsPerDay,
                tokensPerDay = tokensPerDay,
            )
        }
    }

    private fun loadUsage(range: UsageRange) {
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val startDate = range.days?.let { today.minusDays(it - 1L) }
            val since = startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L

            // 流水独立于消息/会话存在：删掉的会话、被丢弃的分支仍然是真实花费
            val totals = usageRecordDAO.getTotals(since)
            val byMonth = range.days == null
            val buckets = usageRecordDAO.getTotalsByBucket(
                format = if (byMonth) MONTH_BUCKET_FORMAT else DAY_BUCKET_FORMAT,
                since = since,
            )
            val points = if (byMonth) {
                monthlyPoints(buckets, today)
            } else {
                dailyPoints(buckets, startDate ?: today, today)
            }

            // 模型展示名优先按 id 查当前设置，查不到再用流水里的名称快照（模型可能已被删除）
            val settings = settingsStore.settingsFlow.first { !it.init }
            val models = usageRecordDAO.getTotalsByModel(since)
                .map { row ->
                    val model = row.modelId
                        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                        ?.let { settings.findModelById(it) }
                    ModelUsageItem(
                        name = model?.displayName?.takeIf { it.isNotBlank() }
                            ?: row.modelName?.takeIf { it.isNotBlank() },
                        providerName = row.providerName?.takeIf { it.isNotBlank() },
                        totalTokens = row.promptTokens + row.completionTokens,
                    )
                }
                .sortedByDescending { it.totalTokens }

            val sources = usageRecordDAO.getTotalsBySource(since)

            _usage.value = UsageStats(
                range = range,
                totals = totals,
                points = points,
                byMonth = byMonth,
                models = models,
                sources = sources,
            )
        }
    }

    /** 按天补 0：没有用量的日子也要有点位，柱子才能按日期均匀排开。 */
    private fun dailyPoints(
        buckets: List<UsageBucket>,
        start: LocalDate,
        today: LocalDate,
    ): List<UsagePoint> {
        val byDate = buckets.associateBy { it.bucket }
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { date -> byDate[date.toString()].toPoint(date) }
            .toList()
    }

    /** 全部范围按月：从最早有用量的月份排到本月，中间缺的月份补 0。 */
    private fun monthlyPoints(buckets: List<UsageBucket>, today: LocalDate): List<UsagePoint> {
        val byMonth = buckets
            .mapNotNull { bucket ->
                runCatching { YearMonth.parse(bucket.bucket) to bucket }.getOrNull()
            }
            .toMap()
        val first = byMonth.keys.minOrNull() ?: return emptyList()
        val current = YearMonth.from(today)
        return generateSequence(first) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(current) }
            .map { month -> byMonth[month].toPoint(month.atDay(1)) }
            .toList()
    }

    private fun UsageBucket?.toPoint(date: LocalDate) = UsagePoint(
        date = date,
        promptTokens = this?.promptTokens ?: 0,
        completionTokens = this?.completionTokens ?: 0,
        cachedTokens = this?.cachedTokens ?: 0,
    )

    private companion object {
        const val DAY_BUCKET_FORMAT = "%Y-%m-%d"
        const val MONTH_BUCKET_FORMAT = "%Y-%m"
    }
}
