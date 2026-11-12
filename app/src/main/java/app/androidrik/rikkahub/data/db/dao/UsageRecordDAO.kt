package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.androidrik.rikkahub.data.db.entity.UsageRecordEntity

/**
 * 用量流水表的读接口。
 *
 * 所有聚合都在 SQLite 侧完成（`SUM` + `GROUP BY` + `strftime` 分桶），只回传 ≤ 桶数/条目数的行，
 * 不把流水本身装进内存——表会随时间无限长，逐行读回再在 Kotlin 里求和是不可接受的。
 *
 * 区间聚合查询统一接受可选的筛选参数（`until` 上界半开、provider/model 三元组为 null 表示不筛选）；
 * provider/model 的兜底匹配照顾 43→44 回填行（id 为 NULL、只有名称快照）：
 * `id 命中 OR (id IS NULL AND 名称快照命中)`。
 */
@Dao
interface UsageRecordDAO {
    @Insert
    suspend fun insert(record: UsageRecordEntity)

    /** 区间内（`since <= created_at < until`，until 为 NULL 不设上界）的输入/输出/缓存合计 */
    @Query(
        "SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens, " +
            "COALESCE(SUM(completion_tokens), 0) AS completionTokens, " +
            "COALESCE(SUM(cached_tokens), 0) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since AND (:until IS NULL OR created_at < :until) " +
            "AND (:providerId IS NULL OR provider_id = :providerId " +
            "OR (provider_id IS NULL AND provider_name = :providerName)) " +
            "AND (:modelId IS NULL OR model_id = :modelId " +
            "OR (model_id IS NULL AND model_name = :modelName))"
    )
    suspend fun getTotals(
        since: Long = 0,
        until: Long? = null,
        providerId: String? = null,
        providerName: String? = null,
        modelId: String? = null,
        modelName: String? = null,
    ): UsageTotals

    /**
     * 按本地时间分桶汇总。
     *
     * [format] 是 strftime 格式：按天 `%Y-%m-%d`、按月 `%Y-%m`。
     * `created_at / 1000` 是毫秒转秒（SQLite 的 'unixepoch' 只吃秒），`'localtime'` 保证
     * 「今天」是用户眼里的今天，而不是 UTC 的今天（东八区凌晨会差一整天）。
     */
    @Query(
        "SELECT strftime(:format, created_at / 1000, 'unixepoch', 'localtime') AS bucket, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since AND (:until IS NULL OR created_at < :until) " +
            "AND (:providerId IS NULL OR provider_id = :providerId " +
            "OR (provider_id IS NULL AND provider_name = :providerName)) " +
            "AND (:modelId IS NULL OR model_id = :modelId " +
            "OR (model_id IS NULL AND model_name = :modelName)) " +
            "GROUP BY bucket ORDER BY bucket"
    )
    suspend fun getTotalsByBucket(
        format: String,
        since: Long,
        until: Long? = null,
        providerId: String? = null,
        providerName: String? = null,
        modelId: String? = null,
        modelName: String? = null,
    ): List<UsageBucket>

    /**
     * 每天（本地日期 `yyyy-MM-dd`）某类来源的**请求次数**，热力图用。
     *
     * 用请求数而不是消息数：一次多步工具调用会发多个请求，这正是「今天用了多少」的真实刻度；
     * 且它只走 `created_at` 索引，不需要碰消息 JSON。
     */
    @Query(
        "SELECT strftime('%Y-%m-%d', created_at / 1000, 'unixepoch', 'localtime') AS day, " +
            "COUNT(*) AS count " +
            "FROM usage_record WHERE source = :source AND created_at >= :since GROUP BY day"
    )
    suspend fun getRequestCountPerDay(source: String, since: Long): List<DayCount>

    /**
     * 按模型汇总。
     *
     * 分组键是 `model_id`（回填的旧记录可能只有名称快照没有 id，用名称兜底）；
     * 名称取任意一条非空快照——同一模型的历史快照可能因改名而不同，展示侧按当前设置解析最新名。
     */
    @Query(
        "SELECT model_id AS modelId, MAX(model_name) AS modelName, MAX(provider_name) AS providerName, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since AND (:until IS NULL OR created_at < :until) " +
            "AND (:providerId IS NULL OR provider_id = :providerId " +
            "OR (provider_id IS NULL AND provider_name = :providerName)) " +
            "AND (:modelId IS NULL OR model_id = :modelId " +
            "OR (model_id IS NULL AND model_name = :modelName)) " +
            "GROUP BY COALESCE(model_id, model_name) " +
            "ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsByModel(
        since: Long,
        until: Long? = null,
        providerId: String? = null,
        providerName: String? = null,
        modelId: String? = null,
        modelName: String? = null,
    ): List<ModelUsageRow>

    /** 按来源（对话/压缩/标题/建议/翻译/记忆整理/OCR/子代理）汇总 */
    @Query(
        "SELECT source AS source, SUM(prompt_tokens) AS promptTokens, " +
            "SUM(completion_tokens) AS completionTokens, SUM(cached_tokens) AS cachedTokens " +
            "FROM usage_record WHERE created_at >= :since AND (:until IS NULL OR created_at < :until) " +
            "AND (:providerId IS NULL OR provider_id = :providerId " +
            "OR (provider_id IS NULL AND provider_name = :providerName)) " +
            "AND (:modelId IS NULL OR model_id = :modelId " +
            "OR (model_id IS NULL AND model_name = :modelName)) " +
            "GROUP BY source ORDER BY SUM(prompt_tokens + completion_tokens) DESC"
    )
    suspend fun getTotalsBySource(
        since: Long,
        until: Long? = null,
        providerId: String? = null,
        providerName: String? = null,
        modelId: String? = null,
        modelName: String? = null,
    ): List<SourceUsage>

    /**
     * 筛选下拉的选项：区间内出现过的提供商/模型（随 `since/until` 联动，选了必有数据）。
     *
     * 按 `COALESCE(id, 名称快照)` 分组——回填行没有 id，也能作为一个选项被筛中；
     * 名称取组内任意非空快照。展示名由调用方按当前设置解析（排行逻辑同款）。
     */
    @Query(
        "SELECT provider_id AS id, MAX(provider_name) AS name FROM usage_record " +
            "WHERE created_at >= :since AND (:until IS NULL OR created_at < :until) " +
            "AND (provider_id IS NOT NULL OR provider_name IS NOT NULL) " +
            "GROUP BY COALESCE(provider_id, provider_name)"
    )
    suspend fun getDistinctProviders(since: Long, until: Long? = null): List<UsageOptionRow>

    @Query(
        "SELECT model_id AS id, MAX(model_name) AS name FROM usage_record " +
            "WHERE created_at >= :since AND (:until IS NULL OR created_at < :until) " +
            "AND (model_id IS NOT NULL OR model_name IS NOT NULL) " +
            "GROUP BY COALESCE(model_id, model_name)"
    )
    suspend fun getDistinctModels(since: Long, until: Long? = null): List<UsageOptionRow>
}

data class UsageTotals(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

data class DayCount(
    val day: String,
    val count: Int,
)

data class UsageBucket(
    val bucket: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

data class ModelUsageRow(
    val modelId: String?,
    val modelName: String?,
    val providerName: String?,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

data class SourceUsage(
    val source: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
)

/** 筛选选项的原始行：`id` 优先匹配，回填行（id 为 NULL）靠名称快照。 */
data class UsageOptionRow(
    val id: String?,
    val name: String?,
)
