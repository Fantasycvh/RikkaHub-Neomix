package app.androidrik.rikkahub.data.db.dao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 会话表的两条**结构护栏**（源码扫描 + Room 导出的 schema），对应 2026-10-04 两次真实事故的形态：
 * 「同一约束散落多处 ⇒ 改一处漏一处」。
 *
 * 1. [updateMetadata] 的目标列清单必须覆盖会话表的**每一列**（除白名单）——全行 `@Update`
 *    换成目标列 UPDATE 才引入这个风险，而换它又是为了不让保存覆盖 `sort_order`。
 * 2. 列表排序必须全部走单点常量 `CONVERSATION_ORDER_BY`——此前 13 处各自硬编码
 *    `ORDER BY is_pinned DESC, update_at DESC`，加自定义顺序时漏改任何一处都会让不同界面顺序不一致。
 * 3. `sort_order` 的写入点必须唯一。
 *
 * 列清单取自 **Room 导出的 schema JSON**（`app/schemas/…/NN.json`）而不是反射：
 * `@ColumnInfo` 是 BINARY 保留期，运行时反射取不到（只能拿到 Kotlin 字段名，曾因此误报）。
 */
class ConversationSchemaGuardsTest {

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 ${candidates.first()}（测试工作目录=${File(".").absolutePath}）")

    private fun daoSource(): String = locate(
        "src/main/java/app/androidrik/rikkahub/data/db/dao/ConversationDAO.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/db/dao/ConversationDAO.kt",
    ).readText()

    /** 当前（最高版本）schema JSON 里会话表的列名。 */
    private fun conversationColumns(): Set<String> {
        val schemaDir = locate(
            "schemas/app.androidrik.rikkahub.data.db.AppDatabase",
            "app/schemas/app.androidrik.rikkahub.data.db.AppDatabase",
        )
        val latest = schemaDir.listFiles { f -> f.name.endsWith(".json") }
            ?.maxByOrNull { it.name.removeSuffix(".json").toIntOrNull() ?: 0 }
            ?: error("schemas 目录里没有 json")
        val db = Json.parseToJsonElement(latest.readText()).jsonObject["database"]!!.jsonObject
        val entity = db["entities"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tableName"]!!.jsonPrimitive.content.equals("ConversationEntity", ignoreCase = true) }
        return entity["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
            .toSet()
    }

    /** 源码里非注释、非 KDoc 的行（护栏只应作用于真实声明）。 */
    private fun codeLines(source: String): List<String> = source.lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }

    @Test
    fun `schema really contains the custom-order columns at the latest version`() {
        val columns = conversationColumns()
        assertTrue("会话表缺少 sort_order（自定义顺序的位置值）", "sort_order" in columns)
        assertTrue("会话表缺少 last_message_at（行内「最近活跃」）", "last_message_at" in columns)
    }

    @Test
    fun `updateMetadata covers every conversation column except the documented exemptions`() {
        val columns = conversationColumns()
        // 白名单（各有明确理由，改动时必须同步这里的注释）：
        // - nodes：实体列已废弃（消息在 message_node 表），保存路径恒写 "[]"
        // - sort_order：**只**由 updateSortOrder / clearSortOrder 写（设计稿 §三.1）
        val exempt = setOf("nodes", "sort_order")

        val sql = daoSource()
            .substringAfter("UPDATE conversationentity SET", "")
            .substringBefore("WHERE id = :id")
        assertTrue("没能定位 updateMetadata 的 SQL（护栏失效，需修测试）", sql.isNotBlank())

        val missing = (columns - exempt).filterNot { sql.contains(it) }
        assertTrue(
            "updateMetadata 缺少列 $missing —— 实体新增列必须一并加进目标列 UPDATE，" +
                "否则该列会在每次保存时静默丢失",
            missing.isEmpty(),
        )
    }

    @Test
    fun `all list ordering goes through the single-point clause`() {
        // 允许显式标注的例外（当前只有"按位置值升序读现状"的辅助查询，它不是列表排序）
        val allowMarker = "guard-allow: order-helper"
        val violations = codeLines(daoSource())
            .filter { it.contains("ORDER BY") }
            .filterNot { it.contains("\$CONVERSATION_ORDER_BY") || it.contains(allowMarker) }
        assertTrue(
            "发现绕过单点排序常量的查询：$violations" +
                "（加自定义顺序时漏改这类旁路会让不同界面顺序不一致；确属辅助查询请加 $allowMarker 标注）",
            violations.isEmpty(),
        )
    }

    @Test
    fun `sort_order is written only by the dedicated statements`() {
        val writers = codeLines(daoSource()).filter { it.contains("SET sort_order") }
        assertTrue(
            "sort_order 的写入点必须唯一（updateSortOrder / clearSortOrder），实际发现：$writers",
            writers.size == 2,
        )
    }
}
