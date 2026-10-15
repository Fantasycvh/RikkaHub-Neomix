package app.androidrik.rikkahub.data.db.dao

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // CRLF 归一化：文件在 autocrlf=true 的机器上是 CRLF，而子句截取用
        // substringBefore("\n\n") 分界——不归一化会一路吃穿到文件末尾的 sort_order
        .replace("\r\n", "\n")

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
        // - frozen_anchors：**只**由 updateFrozenAnchors / updateFrozenAnchorsOfAssistant 写
        //   （L1 锚点快照；理由同 sort_order —— 它在「会话开始冻结」与「记忆页固化」两处
        //   写库，而那两处之后调用方手里的 Conversation 可能仍是旧副本，放进这里会被写回 null）
        val exempt = setOf("nodes", "sort_order", "frozen_anchors")

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
            .filterNot {
                it.contains("\$CONVERSATION_ORDER_BY") ||
                    it.contains("\$CONVERSATION_ORDER_BY_TIME") ||
                    it.contains(allowMarker)
            }
        assertTrue(
            "发现绕过单点排序常量的查询：$violations" +
                "（加自定义顺序时漏改这类旁路会让不同界面顺序不一致；确属辅助查询请加 $allowMarker 标注）",
            violations.isEmpty(),
        )
    }

    @Test
    fun `time-ordered clause never follows the custom order`() {
        val source = daoSource()
        // ①语义：这条子句不得出现 sort_order，否则「按时间」会随拖拽漂移
        val clause = source.substringAfter("const val CONVERSATION_ORDER_BY_TIME =").substringBefore("\n\n")
        val clauseCode = clause.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        assertTrue(
            "CONVERSATION_ORDER_BY_TIME 不得引用 sort_order（AI 最近对话 / 搜索结果必须与自定义顺序无关）：$clauseCode",
            !clauseCode.contains("sort_order"),
        )
        // ②用途：只允许 AI「最近对话」与搜索这几条查询用它
        // （`@Query` 注解与 `fun` 声明分处两行，所以按行号向后看几行取函数名）
        val allowed = setOf(
            "getRecentConversationsOfAssistant",
            "searchConversations",
            "searchConversationsPaging",
            "searchConversationsOfAssistant",
            "searchConversationsOfAssistantPaging",
        )
        val lines = codeLines(source)
        val users = lines.indices
            .filter { lines[it].contains("\$CONVERSATION_ORDER_BY_TIME") }
            .map { i ->
                (i..minOf(i + 3, lines.lastIndex))
                    .firstNotNullOfOrNull { j ->
                        Regex("""fun (\w+)\(""").find(lines[j])?.groupValues?.get(1)
                    }
            }
        assertEquals(
            "按时间排序的查询只能是 AI 最近对话 / 搜索：$users",
            allowed,
            users.toSet(),
        )
        assertEquals("每条按时间查询各一处，不多不少", allowed.size, users.size)
    }

    @Test
    fun `sort_order is written only by the dedicated statements`() {
        val writers = codeLines(daoSource()).filter { it.contains("SET sort_order") }
        assertTrue(
            "sort_order 的写入点必须唯一（updateSortOrder / clearSortOrder），实际发现：$writers",
            writers.size == 2,
        )
    }

    /**
     * L1 锚点快照的写入点护栏（同 `sort_order` 那条）。
     *
     * 若 `frozen_anchors` 出现在 [ConversationDAO.updateMetadata] 的列清单里，一次普通保存
     * （用调用方手里的旧 `Conversation`）就能把刚冻好的值写回 `null` ⇒ 锚点每轮重算、
     * L1 的缓存收益归零，而且是**静默**的。
     *
     * **只数 `SET frozen_anchors` 抓不到这个形态**（`updateMetadata` 的 SET 后面跟的是
     * `assistant_id`，字面量对不上）——2026-10-16 用注入式反证实测：把该列加回列清单，
     * 只数写入点的版本**依然全绿**。所以这里显式断言列清单里没有它。
     */
    @Test
    fun `frozen_anchors is written only by the dedicated statements`() {
        val source = daoSource()
        // ① 通用元信息保存的列清单里不得出现它（决定性断言）
        val metadataSql = source
            .substringAfter("UPDATE conversationentity SET", "")
            .substringBefore("WHERE id = :id")
        assertTrue("没能定位 updateMetadata 的 SQL（护栏失效，需修测试）", metadataSql.isNotBlank())
        assertFalse(
            "updateMetadata 不得写 frozen_anchors：调用方手里的旧 Conversation 会把刚冻好的值" +
                "写回 null，锚点每轮重算、L1 的缓存收益归零（同 sort_order 的理由）",
            metadataSql.contains("frozen_anchors"),
        )
        // ② 专用写入点恰好两个：单会话冻结 + 按助手批量刷新
        val writers = codeLines(source).filter { it.contains("SET frozen_anchors") }
        assertEquals(
            "frozen_anchors 的写入点只能是 updateFrozenAnchors / updateFrozenAnchorsOfAssistant，" +
                "实际发现：$writers",
            2,
            writers.size,
        )
    }
}
