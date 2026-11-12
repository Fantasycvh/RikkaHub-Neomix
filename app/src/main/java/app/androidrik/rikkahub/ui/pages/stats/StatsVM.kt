package app.androidrik.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
import java.time.temporal.ChronoUnit
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

/** 用量统计的时间范围；[days] 为空表示全部（自定义档用 [StatsFilter.customStart/customEnd]）。 */
enum class UsageRange(val days: Int?) {
    WEEK(7),
    MONTH(30),
    QUARTER(90),
    ALL(null),
    CUSTOM(null),
}

/**
 * 筛选键：与 DISTINCT 选项查询的 `COALESCE(id, 名称快照)` 分组一致；[groupKey] 即 DAO 过滤参数。
 * 回填行（id 为 NULL、只有名称快照）自成一个组，按名称键命中。
 */
data class ProviderKey(val id: String?, val name: String?) {
    val groupKey: String? get() = id ?: name
}

data class ModelKey(val id: String?, val name: String?) {
    val groupKey: String? get() = id ?: name
}

/**
 * 「用量」区的筛选：时段 / 提供商 / 模型三个维度**叠加生效（AND）**，只作用于用量区
 * （热力图与总览卡片沿用各自的固定口径，不参与筛选）。
 *
 * [customStart]/[customEnd] 是含端点的闭区间，仅 [UsageRange.CUSTOM] 档读取。
 */
data class StatsFilter(
    val range: UsageRange = UsageRange.MONTH,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
    val provider: ProviderKey? = null,
    val model: ModelKey? = null,
)

/** 筛选下拉的一项；[label] 为空表示名称快照也缺失，页面显示「未知」。 */
interface UsageFilterOption<K> {
    val key: K
    val label: String?
}

data class ProviderFilterOption(
    override val key: ProviderKey,
    override val label: String?,
) : UsageFilterOption<ProviderKey>

data class ModelFilterOption(
    override val key: ModelKey,
    override val label: String?,
) : UsageFilterOption<ModelKey>

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
 * [filter] 一起带出来（而不是只看 VM 的筛选状态）：切换筛选时旧数据会保留到新查询返回，
 * 页面必须能区分「这份数据属于哪组筛选」，否则图表标题/点选状态会错位。
 * 选项列表随时间区间联动（只列区间内出现过的提供商/模型），保证选了必有数据。
 */
data class UsageStats(
    val range: UsageRange,
    val filter: StatsFilter,
    val totals: UsageTotals,
    val points: List<UsagePoint>,
    val byMonth: Boolean,
    val models: List<ModelUsageItem>,
    val sources: List<SourceUsage>,
    val providerOptions: List<ProviderFilterOption>,
    val modelOptions: List<ModelFilterOption>,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val usageRecordDAO: UsageRecordDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats: StateFlow<AppStats> = _stats.asStateFlow()

    // 切换筛选时先保留旧数据，查完再整体替换，避免用量区闪成加载态
    private val _usage = MutableStateFlow<UsageStats?>(null)
    val usage: StateFlow<UsageStats?> = _usage.asStateFlow()

    private val _filter = MutableStateFlow(StatsFilter())
    val filter: StateFlow<StatsFilter> = _filter.asStateFlow()

    // 时段是筛选的一个投影：页面现有的 SegmentedPill 只订阅这一维
    val range: StateFlow<UsageRange> = _filter
        .map { it.range }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UsageRange.MONTH)

    private var usageJob: Job? = null

    init {
        viewModelScope.launch { loadStats() }
        // 筛选状态的唯一重查入口：collect 自身会先用当前值跑一次
        viewModelScope.launch {
            _filter.collect { loadUsage(it) }
        }
    }

    fun setRange(range: UsageRange) {
        if (range == _filter.value.range) return
        _filter.update { it.copy(range = range) }
    }

    fun setProviderFilter(provider: ProviderKey?) {
        if (provider == _filter.value.provider) return
        _filter.update { it.copy(provider = provider) }
    }

    fun setModelFilter(model: ModelKey?) {
        if (model == _filter.value.model) return
        _filter.update { it.copy(model = model) }
    }

    /** 自定义档确认入口：区间不完整（缺起止）则不接受。 */
    fun setCustomRange(start: LocalDate, end: LocalDate) {
        _filter.update { it.copy(range = UsageRange.CUSTOM, customStart = start, customEnd = end) }
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

    private fun loadUsage(filter: StatsFilter) {
        usageJob?.cancel()
        usageJob = viewModelScope.launch {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()

            // 时间窗口：预设档只有下界（没有未来流水，不需要上界）；
            // 自定义档闭区间 [start, end] 转半开 [since, until)，end+1 天零点，避开毫秒精度踩边
            val startDate = when (filter.range) {
                UsageRange.CUSTOM -> filter.customStart
                else -> filter.range.days?.let { today.minusDays(it - 1L) }
            }
            val since = startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L
            val until = if (filter.range == UsageRange.CUSTOM) {
                filter.customEnd?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            } else {
                null
            }

            // 分桶：全部按月；自定义跨度超过阈值按月（与 QUARTER 档对齐）；其余按天
            val byMonth = when (filter.range) {
                UsageRange.ALL -> true
                UsageRange.CUSTOM -> filter.customStart != null && filter.customEnd != null &&
                    ChronoUnit.DAYS.between(filter.customStart, filter.customEnd) + 1 >
                    MONTHLY_BUCKET_THRESHOLD_DAYS
                else -> false
            }

            val providerKey = filter.provider?.groupKey
            val modelKey = filter.model?.groupKey

            // 流水独立于消息/会话存在：删掉的会话、被丢弃的分支仍然是真实花费
            val totals = usageRecordDAO.getTotals(
                since = since,
                until = until,
                providerKey = providerKey,
                modelKey = modelKey,
            )
            val buckets = usageRecordDAO.getTotalsByBucket(
                format = if (byMonth) MONTH_BUCKET_FORMAT else DAY_BUCKET_FORMAT,
                since = since,
                until = until,
                providerKey = providerKey,
                modelKey = modelKey,
            )
            val points = if (byMonth) {
                monthlyPoints(buckets, today)
            } else {
                dailyPoints(buckets, startDate ?: today, today)
            }

            // 模型展示名优先按 id 查当前设置，查不到再用流水里的名称快照（模型可能已被删除）
            val settings = settingsStore.settingsFlow.first { !it.init }
            val models = usageRecordDAO.getTotalsByModel(
                since = since,
                until = until,
                providerKey = providerKey,
                modelKey = modelKey,
            )
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

            val sources = usageRecordDAO.getTotalsBySource(
                since = since,
                until = until,
                providerKey = providerKey,
                modelKey = modelKey,
            )

            // 筛选选项：区间内出现过的提供商/模型，展示名解析口径与排行一致
            // （模型按当前设置解析最新名、快照兜底；提供商直接用名称快照，排行同款）
            val providerOptions = usageRecordDAO.getDistinctProviders(since, until)
                .map { row ->
                    ProviderFilterOption(
                        key = ProviderKey(row.id, row.name),
                        label = row.name?.takeIf { it.isNotBlank() },
                    )
                }
            val modelOptions = usageRecordDAO.getDistinctModels(since, until)
                .map { row ->
                    val model = row.id
                        ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
                        ?.let { settings.findModelById(it) }
                    ModelFilterOption(
                        key = ModelKey(row.id, row.name),
                        label = model?.displayName?.takeIf { it.isNotBlank() }
                            ?: row.name?.takeIf { it.isNotBlank() },
                    )
                }

            _usage.value = UsageStats(
                range = filter.range,
                filter = filter,
                totals = totals,
                points = points,
                byMonth = byMonth,
                models = models,
                sources = sources,
                providerOptions = providerOptions,
                modelOptions = modelOptions,
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

        /** 自定义区间趋势图按月分桶的天数阈值，与 QUARTER 档对齐 */
        const val MONTHLY_BUCKET_THRESHOLD_DAYS = 90L
    }
}
