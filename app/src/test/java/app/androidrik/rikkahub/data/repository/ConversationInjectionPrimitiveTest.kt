package app.androidrik.rikkahub.data.repository

import app.androidrik.rikkahub.utils.JsonInstant
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 会话级提示词注入绑定的**单列写原语**（Web 路由 `POST /api/conversations/{id}/injections` 的落点）。
 *
 * 三件事必须钉住，否则「只改目标列、不动其它字段」只是注释里的承诺：
 * 1. DAO 的 `@Query` **只** SET `mode_injection_ids` / `lorebook_ids`（多一列就会顺手动别的字段，
 *    而整对象保存正是这样把旧快照盖回去的）；
 * 2. 仓库方法只走这条单列语句，不借道 `updateMetadata` / `updateConversation`（后者会整对象写回）；
 * 3. 两条 `*_ids` 列只用**一处**编码，且读路径 `decodeFromString<Set<Uuid>>` 能解回 ——
 *    格式分叉会让另一条写路径写进去的绑定在下次加载时静默丢失。
 */
class ConversationInjectionPrimitiveTest {

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到 ${candidates.first()}（测试工作目录=${File(".").absolutePath}）")

    private fun daoSource(): String = locate(
        "src/main/java/app/androidrik/rikkahub/data/db/dao/ConversationDAO.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/db/dao/ConversationDAO.kt",
    ).readText().replace("\r\n", "\n")

    private fun repositorySource(): String = locate(
        "src/main/java/app/androidrik/rikkahub/data/repository/ConversationRepository.kt",
        "app/src/main/java/app/androidrik/rikkahub/data/repository/ConversationRepository.kt",
    ).readText().replace("\r\n", "\n")

    /** 取某 DAO 方法上的 `@Query` SQL：把按 `+` 拼接的字符串字面量按出现顺序拼回来。 */
    private fun queryOf(source: String, functionName: String): String {
        val funAt = source.indexOf("fun $functionName(")
        check(funAt >= 0) { "ConversationDAO.kt 里找不到 fun $functionName" }
        val annotationAt = source.lastIndexOf("@Query(", funAt)
        check(annotationAt >= 0) { "$functionName 上没有 @Query" }
        val raw = source.substring(annotationAt, funAt)
        return Regex("\"([^\"]*)\"").findAll(raw).joinToString("") { it.groupValues[1] }
    }

    /** 截出某个成员函数声明到下一个同级成员函数声明之间的源码。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "ConversationRepository.kt 里找不到 $signature" }
        val next = listOf("\n    suspend fun ", "\n    private fun ", "\n    internal fun ", "\n    fun ")
            .map { source.indexOf(it, start + 1) }
            .filter { it >= 0 }
            .minOrNull() ?: source.length
        return source.substring(start, next)
    }

    @Test
    fun `dao primitive writes exactly the two injection columns of one row`() {
        val sql = queryOf(daoSource(), "updateConversationInjections")

        val setClause = sql.substringAfter("UPDATE conversationentity SET ").substringBefore(" WHERE ")
        val columns = setClause.split(",").map { it.substringBefore("=").trim() }.filter { it.isNotEmpty() }

        assertEquals(
            "注入绑定原语只能写这两列（多一列就会在生成中顺手动别的字段）：$sql",
            setOf("mode_injection_ids", "lorebook_ids"),
            columns.toSet(),
        )
        assertEquals("列数必须恰好 2：$sql", 2, columns.size)
        assertEquals("只按主键定位一行（不做批量更新）：$sql", "id = :id", sql.substringAfter(" WHERE "))
    }

    @Test
    fun `repository primitive goes through the single-column statement only`() {
        val body = functionBody(repositorySource(), "suspend fun updateConversationInjections(")

        assertTrue(
            "必须调用 DAO 的单列原语：$body",
            "conversationDAO.updateConversationInjections(" in body,
        )
        assertFalse("不得借道整对象保存（updateConversation）：$body", "updateConversation(" in body)
        assertFalse("不得借道元信息整份写（updateMetadata）：$body", "updateMetadata(" in body)
        assertFalse("不得整对象写回（saveConversation）：$body", "saveConversation(" in body)
        assertTrue(
            "两条 ids 必须走共用编码（与整对象保存同格式）：$body",
            "encodeConversationInjectionIds(" in body,
        )
    }

    @Test
    fun `whole-object save and the single-column primitive share one encoding`() {
        val mapper = functionBody(repositorySource(), "fun conversationToConversationEntity(")

        assertTrue(
            "整对象保存必须用同一个编码器写 mode_injection_ids：$mapper",
            "encodeConversationInjectionIds(conversation.modeInjectionIds)" in mapper,
        )
        assertTrue(
            "整对象保存必须用同一个编码器写 lorebook_ids：$mapper",
            "encodeConversationInjectionIds(conversation.lorebookIds)" in mapper,
        )
    }

    @Test
    fun `shared encoding round-trips through the read path`() {
        val ids = setOf(Uuid.random(), Uuid.random())
        val encoded = encodeConversationInjectionIds(ids)

        assertTrue("编码形态是 JSON 数组文本：$encoded", encoded.startsWith("[") && encoded.endsWith("]"))
        ids.forEach {
            assertTrue("编码里必须含 $it 的字符串形式：$encoded", encoded.contains(it.toString()))
        }
        assertEquals(
            "读路径（conversationEntityToConversation 的 decodeFromString<Set<Uuid>>）必须能解回：$encoded",
            ids,
            JsonInstant.decodeFromString<Set<Uuid>>(encoded),
        )
        assertEquals(
            "空集合也是合法编码（不写 null）：${encodeConversationInjectionIds(emptySet())}",
            emptySet<Uuid>(),
            JsonInstant.decodeFromString<Set<Uuid>>(encodeConversationInjectionIds(emptySet())),
        )
    }
}
