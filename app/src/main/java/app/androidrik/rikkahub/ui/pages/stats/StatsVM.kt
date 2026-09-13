package app.androidrik.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.androidrik.rikkahub.data.db.dao.ConversationDAO
import app.androidrik.rikkahub.data.db.dao.MessageNodeDAO
import app.androidrik.rikkahub.data.db.dao.ModelTokenStats
import app.androidrik.rikkahub.data.db.dao.TokenStatsScope
import app.androidrik.rikkahub.data.db.dao.getMessageCountPerDay
import app.androidrik.rikkahub.data.db.dao.getDayTokenStats
import app.androidrik.rikkahub.data.db.dao.getModelTokenStats
import app.androidrik.rikkahub.data.db.dao.getTokenStats
import app.androidrik.ai.provider.Model
import app.androidrik.rikkahub.data.datastore.SettingsStore
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt
import kotlin.uuid.Uuid

/** 热力图的度量维度。 */
enum class HeatmapMetric { MESSAGES, TOKENS }

/** 单个模型的统计（已解析展示名）。 */
data class ModelUsage(
    val label: String,
    val providerName: String?,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
) {
    val total: Long get() = promptTokens + completionTokens
}

data class AppStats(
    val isLoading: Boolean = true,
    val scope: TokenStatsScope = TokenStatsScope.ALL_BRANCHES,
    val heatmapMetric: HeatmapMetric = HeatmapMetric.MESSAGES,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val tokensPerDay: Map<LocalDate, Int> = emptyMap(),
    val modelUsage: List<ModelUsage> = emptyList(),
    val launchCount: Int = 0,
) {
    /** 缓存命中率（cached / prompt，按用量加权）。prompt 为 0 时无意义，返回 null。 */
    val cacheHitRate: Float?
        get() = if (totalPromptTokens > 0) {
            (totalCachedTokens.toDouble() / totalPromptTokens).toFloat()
        } else {
            null
        }
}

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats: StateFlow<AppStats> = _stats.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    fun setScope(scope: TokenStatsScope) {
        if (_stats.value.scope == scope) return
        _stats.update { it.copy(isLoading = true, scope = scope) }
        viewModelScope.launch { loadStats() }
    }

    fun setHeatmapMetric(metric: HeatmapMetric) {
        _stats.update { it.copy(heatmapMetric = metric) }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()
        val scope = _stats.value.scope

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 基于消息的 createdAt 统计每日活跃量，SQLite 侧 GROUP BY，返回 ≤371 行
        val (conversationsPerDay, tokensPerDay) = withContext(Dispatchers.IO) {
            val perDay = messageNodeDAO
                .getMessageCountPerDay(scope, startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
            // token 量级远超 Int，这里按"千 token"折算以便复用同一套分档热力图
            val perDayTokens = messageNodeDAO
                .getDayTokenStats(scope, startDate)
                .mapNotNull { entry ->
                    val date = runCatching { LocalDate.parse(entry.day) }.getOrNull()
                    val thousands = ((entry.promptTokens + entry.completionTokens) / 1000L)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                    date?.let { it to thousands }
                }
                .toMap()
            perDay to perDayTokens
        }

        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats(scope)

        val modelUsage = withContext(Dispatchers.IO) {
            resolveModelUsage(
                rows = messageNodeDAO.getModelTokenStats(scope),
                models = settingsStore.settingsFlow.value.providers.flatMap { it.models },
            )
        }

        val launchCount = settingsStore.settingsFlow.value.launchCount

        _stats.update {
            it.copy(
                isLoading = false,
                totalConversations = totalConversations,
                totalMessages = tokenStats.totalMessages,
                totalPromptTokens = tokenStats.promptTokens,
                totalCompletionTokens = tokenStats.completionTokens,
                totalCachedTokens = tokenStats.cachedTokens,
                conversationsPerDay = conversationsPerDay,
                tokensPerDay = tokensPerDay,
                modelUsage = modelUsage,
                launchCount = launchCount,
            )
        }
    }
}

/**
 * 把 SQL 侧的分组元组解析为展示名并合并同名行。
 *
 * 展示名优先级：消息上的名称快照（当时叫什么）→ 按 modelId 查当前设置 → 「已删除模型」。
 * 合并键用展示名 + 提供商名——同一模型被删除后重建会得到新 UUID，按 id 分组会裂成两行。
 */
internal fun resolveModelUsage(
    rows: List<ModelTokenStats>,
    models: List<Model>,
): List<ModelUsage> {
    val modelsById = models.associateBy { it.id }
    return rows
        .map { row ->
            val bySnapshot = row.modelName?.takeIf { it.isNotBlank() }
            val byLookup = row.modelId
                ?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() }
                ?.let { uuid -> modelsById[uuid]?.displayName }
                ?.takeIf { it.isNotBlank() }
            ModelUsage(
                label = bySnapshot ?: byLookup ?: DELETED_MODEL_LABEL,
                providerName = row.providerName?.takeIf { it.isNotBlank() },
                promptTokens = row.promptTokens,
                completionTokens = row.completionTokens,
                cachedTokens = row.cachedTokens,
            )
        }
        .groupBy { it.label to it.providerName }
        .map { (_, group) ->
            ModelUsage(
                label = group.first().label,
                providerName = group.first().providerName,
                promptTokens = group.sumOf { it.promptTokens },
                completionTokens = group.sumOf { it.completionTokens },
                cachedTokens = group.sumOf { it.cachedTokens },
            )
        }
        .sortedByDescending { it.total }
}

internal const val DELETED_MODEL_LABEL = "已删除模型"

/** 百分比整数展示，用于占比条与命中率。 */
internal fun Float.asPercent(): Int = (this * 100).roundToInt()
