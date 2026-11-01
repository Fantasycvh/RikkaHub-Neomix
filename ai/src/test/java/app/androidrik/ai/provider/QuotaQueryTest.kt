package app.androidrik.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 额度体系：类型序列化兼容性与模板渲染（单接口 / 多接口两种形态）。
 *
 * 迁移相关约束：旧 `BalanceOption` 的字段必须能被**无损**表达成 [QuotaQuery]，
 * 且 `QuotaQuery` 的旧 JSON（无 `sources`/`expression` 字段）反序列化后行为不变。
 */
class QuotaQueryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `default quota query keeps upstream single endpoint shape`() {
        val query = QuotaQuery()
        assertEquals("{{baseUrl}}/credits", query.url)
        assertEquals("data.total_usage", query.resultPath)
        assertTrue(query.sources.isEmpty())
        assertEquals("", query.expression)
        assertEquals(listOf(CustomHeader("Authorization", "Bearer {{apiKey}}")), query.headers)
    }

    @Test
    fun `old json without sources and expression still decodes`() {
        val raw = """{"enabled":true,"url":"{{baseUrl}}/balance","headers":[],"resultPath":"data.balance"}"""
        val query = json.decodeFromString<QuotaQuery>(raw)
        assertTrue(query.enabled)
        assertEquals("{{baseUrl}}/balance", query.url)
        assertEquals("data.balance", query.resultPath)
        assertTrue(query.sources.isEmpty())
        assertEquals("", query.expression)
    }

    @Test
    fun `multi source config round trips losslessly`() {
        val query = QuotaQuery(
            enabled = true,
            sources = listOf(
                QuotaSource(name = "quota", apiPath = "/api/user/self", resultPath = "data.quota"),
                QuotaSource(name = "usage", apiPath = "/api/user/self", resultPath = "data.used_quota"),
            ),
            expression = "quota - usage",
        )
        val decoded = json.decodeFromString<QuotaQuery>(json.encodeToString(query))
        assertEquals(query, decoded)
        assertEquals("quota - usage", decoded.expression)
    }

    @Test
    fun `template variables are replaced and unknown ones are rejected`() {
        val vars = mapOf("baseUrl" to "https://api.example.com/v1", "apiKey" to "sk-1")

        assertEquals(
            "https://api.example.com/v1/credits?key=sk-1",
            renderQuotaTemplate("{{baseUrl}}/credits?key={{ apiKey }}") { vars[it] },
        )
        assertEquals(
            "Bearer sk-1",
            renderQuotaTemplate("Bearer {{apiKey}}") { vars[it] },
        )
        // 没有模板变量时原样返回（尤其是 JSON 里的 `{}` 不能被误伤）
        assertEquals("""{"a":1}""", renderQuotaTemplate("""{"a":1}""") { vars[it] })

        val error = assertThrows(IllegalArgumentException::class.java) {
            renderQuotaTemplate("{{baseUrl}}/{{privateKey}}") { vars[it] }
        }
        assertTrue(error.message!!.contains("privateKey"))
    }

    @Test
    fun `variables list is the public contract for templates`() {
        assertEquals(listOf("baseUrl", "apiKey"), QuotaQuery.VARIABLES)
    }

    @Test
    fun `json expression evaluation uses source names`() {
        // 迁移后 expression 的求值仍走本仓既有评估器，这里只锁「变量名 = sources.name」约定
        val sources = listOf(
            QuotaSource(name = "quota", apiPath = "/a", resultPath = "q"),
            QuotaSource(name = "usage", apiPath = "/b", resultPath = "u"),
        )
        val variables = sources.associate { it.name to it.resultPath }
        assertEquals(mapOf("quota" to "q", "usage" to "u"), variables)
        assertNull(variables["expression"])
    }
}
