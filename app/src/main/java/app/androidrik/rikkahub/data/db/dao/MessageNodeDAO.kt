package app.androidrik.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.androidrik.rikkahub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    @Query("SELECT * FROM message_node WHERE conversation_id = :conversationId ORDER BY node_index ASC")
    suspend fun getNodesOfConversation(conversationId: String): List<MessageNodeEntity>

    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: MessageNodeEntity)

    @Update
    suspend fun update(node: MessageNodeEntity)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("DELETE FROM message_node WHERE id = :nodeId")
    suspend fun deleteById(nodeId: String)

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>

    @RawQuery
    suspend fun getModelTokenStatsRaw(query: SupportSQLiteQuery): List<ModelTokenStats>

    @RawQuery
    suspend fun getDayTokenStatsRaw(query: SupportSQLiteQuery): List<DayTokenStats>
    // 队列附件清理：判断某 file:// URL 是否仍被任何消息 JSON 引用（保守保留所有分支出现的 URL）。
    @Query("SELECT EXISTS(SELECT 1 FROM message_node WHERE instr(messages, :encodedFileUrl) > 0)")
    suspend fun hasFileReference(encodedFileUrl: String): Boolean

}

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class MessageDayCount(val day: String, val count: Int)

/** 单个模型的 token 聚合（分组键为 SQL 侧原始元组，展示名由 VM 侧解析）。 */
data class ModelTokenStats(
    val modelId: String?,
    val modelName: String?,
    val providerName: String?,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

/** 单日 token 聚合。 */
data class DayTokenStats(
    val day: String,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

/**
 * Token 统计口径。
 *
 * - [ALL_BRANCHES]：节点的全部分支（含重新生成/编辑后被丢弃的版本）= 真实花费账本
 * - [SELECTED_BRANCH]：每节点只取选中分支 = 屏幕上当前可见的对话
 */
enum class TokenStatsScope {
    ALL_BRANCHES,
    SELECTED_BRANCH;

    internal val filter: String
        get() = when (this) {
            ALL_BRANCHES -> ""
            SELECTED_BRANCH -> "j.key = mn.select_index"
        }
}


// 在 json_each() 的参数内校验 JSON，避免损坏行导致整个统计查询失败。
// 使用 CASE 而非依赖 WHERE 条件的求值顺序，无效 JSON 按空数组处理。
private const val VALID_MESSAGES_JSON = "CASE WHEN json_valid(mn.messages) THEN mn.messages ELSE '[]' END"

private const val FROM_MESSAGES_JSON = " FROM message_node mn, json_each($VALID_MESSAGES_JSON) j"

/** 组合口径过滤与额外条件，全部为空时不生成 WHERE（避免多余子句）。 */
internal fun whereClause(scope: TokenStatsScope, vararg extra: String): String {
    val conditions = buildList {
        if (scope.filter.isNotEmpty()) add(scope.filter)
        addAll(extra.filter { it.isNotEmpty() })
    }
    return if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
}

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
internal fun tokenStatsQuery(scope: TokenStatsScope) = SimpleSQLiteQuery(
    "SELECT COUNT(*) AS totalMessages, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens" +
        FROM_MESSAGES_JSON +
        whereClause(scope)
)

suspend fun MessageNodeDAO.getTokenStats(scope: TokenStatsScope): MessageTokenStats =
    getTokenStatsRaw(tokenStatsQuery(scope))

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(
    scope: TokenStatsScope,
    startDate: String,
): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count" +
                FROM_MESSAGES_JSON +
                whereClause(
                    scope,
                    "json_extract(j.value, '$.role') = 'user'",
                    "json_extract(j.value, '$.createdAt') >= ?",
                ) +
                " GROUP BY day",
            arrayOf(startDate)
        )
    )

/**
 * 按模型聚合 token。
 *
 * 分组键用 SQL 侧原始元组（modelId + 两个名称快照）：名称解析需要 settings，SQL 侧做不到；
 * 同一展示名的多行（如按 id 与按快照）由 VM 侧合并。
 */
suspend fun MessageNodeDAO.getModelTokenStats(scope: TokenStatsScope): List<ModelTokenStats> =
    getModelTokenStatsRaw(
        SimpleSQLiteQuery(
            "SELECT json_extract(j.value, '$.modelId') AS modelId, " +
                "json_extract(j.value, '$.modelNameSnapshot') AS modelName, " +
                "json_extract(j.value, '$.providerNameSnapshot') AS providerName, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens" +
                FROM_MESSAGES_JSON +
                whereClause(scope, "json_extract(j.value, '$.usage') IS NOT NULL") +
                " GROUP BY modelId, modelName, providerName"
        )
    )

suspend fun MessageNodeDAO.getDayTokenStats(scope: TokenStatsScope, startDate: String): List<DayTokenStats> =
    getDayTokenStatsRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
                "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens" +
                FROM_MESSAGES_JSON +
                whereClause(
                    scope,
                    "json_extract(j.value, '$.usage') IS NOT NULL",
                    "substr(json_extract(j.value, '$.createdAt'), 1, 10) >= ?",
                ) +
                " GROUP BY day",
            arrayOf(startDate)
        )
    )

