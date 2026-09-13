package app.androidrik.rikkahub.data.db.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * token 统计 SQL 的口径拼接。
 *
 * 两个口径语义不同且都有用：
 * - ALL_BRANCHES：节点全部分支（含被丢弃的重生成/编辑版本）= 真实花费
 * - SELECTED_BRANCH：每节点取选中分支（j.key = mn.select_index）= 屏幕可见的对话
 */
class TokenStatsQueryTest {

    private fun sqlOf(scope: TokenStatsScope): String = tokenStatsQuery(scope).sql

    @Test
    fun `all branches scope has no branch filter`() {
        val sql = sqlOf(TokenStatsScope.ALL_BRANCHES)
        assertFalse("全分支口径不应带 select_index 过滤", sql.contains("select_index"))
        assertFalse("无任何条件时不应生成 WHERE", sql.contains("WHERE"))
    }

    @Test
    fun `selected branch scope filters by select index`() {
        val sql = sqlOf(TokenStatsScope.SELECTED_BRANCH)
        assertTrue(sql.contains("j.key = mn.select_index"))
        assertTrue(sql.contains("WHERE"))
    }

    @Test
    fun `selected branch scope is always valid when extra conditions exist`() {
        val clause = whereClause(
            TokenStatsScope.SELECTED_BRANCH,
            "json_extract(j.value, '$.usage') IS NOT NULL",
            "substr(json_extract(j.value, '$.createdAt'), 1, 10) >= ?",
        )
        assertTrue(clause.startsWith(" WHERE "))
        // 3 个条件 -> 2 个 AND 连接符
        assertEquals(2, Regex(" AND ").findAll(clause).count())
        assertTrue(clause.contains("j.key = mn.select_index"))
        assertTrue(clause.contains("IS NOT NULL"))
        assertTrue(clause.contains(">= ?"))
    }

    @Test
    fun `empty extra conditions are dropped`() {
        assertEquals(" WHERE j.key = mn.select_index", whereClause(TokenStatsScope.SELECTED_BRANCH, ""))
        assertEquals("", whereClause(TokenStatsScope.ALL_BRANCHES, ""))
    }

    @Test
    fun `invalid json is guarded in every query`() {
        listOf(TokenStatsScope.ALL_BRANCHES, TokenStatsScope.SELECTED_BRANCH).forEach { scope ->
            val sql = sqlOf(scope)
            assertTrue(
                "损坏行必须按空数组处理，避免整条统计查询失败：$scope",
                sql.contains("json_valid(mn.messages)"),
            )
        }
    }
}
