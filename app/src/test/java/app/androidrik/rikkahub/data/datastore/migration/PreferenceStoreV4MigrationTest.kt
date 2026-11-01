package app.androidrik.rikkahub.data.datastore.migration

import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V4 迁移：旧 `balanceOption` → `quotaQuery`，要求字段语义一一对应、旧键删除、其余配置不动。
 */
class PreferenceStoreV4MigrationTest {
    private fun providers(json: String): List<JsonObject> =
        JsonInstant.parseToJsonElement(migrateProvidersBalanceToQuota(json)).jsonArray.map { it.jsonObject }

    @Test
    fun `single endpoint balance is mapped to url and result path`() {
        val json = """
            [{"type":"openai","id":"a","name":"A","balanceOption":{"enabled":true,"apiPath":"/credits","resultPath":"data.total_usage"}}]
        """.trimIndent()

        val provider = providers(json).single()
        assertFalse("旧字段必须删除", "balanceOption" in provider)
        val quota = provider["quotaQuery"]!!.jsonObject
        assertTrue(quota["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("{{baseUrl}}/credits", quota["url"]!!.jsonPrimitive.content)
        assertEquals("data.total_usage", quota["resultPath"]!!.jsonPrimitive.content)
        assertFalse("单接口模式不应写 sources", "sources" in quota)
    }

    @Test
    fun `multi endpoint balance keeps sources and expression losslessly`() {
        val json = """
            [{"type":"openai","id":"a","name":"A","balanceOption":{"enabled":true,"apiPath":"/ignored",
              "resultPath":"data.total_usage",
              "sources":[
                {"name":"quota","apiPath":"/api/user/self","resultPath":"data.quota"},
                {"name":"usage","apiPath":"/api/user/self","resultPath":"data.used_quota"}
              ],
              "expression":"quota - usage"}}]
        """.trimIndent()

        val quota = providers(json).single()["quotaQuery"]!!.jsonObject
        val sources = quota["sources"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("quota", "usage"), sources.map { it["name"]!!.jsonPrimitive.content })
        assertEquals("/api/user/self", sources[0]["apiPath"]!!.jsonPrimitive.content)
        assertEquals("data.used_quota", sources[1]["resultPath"]!!.jsonPrimitive.content)
        assertEquals("quota - usage", quota["expression"]!!.jsonPrimitive.content)
    }

    @Test
    fun `disabled balance still migrates flag and keeps other provider fields`() {
        val json = """
            [{"type":"claude","id":"b","name":"B","enabled":false,"apiKey":"sk","balanceOption":{"enabled":false,"apiPath":"/credits","resultPath":"balance"}}]
        """.trimIndent()

        val provider = providers(json).single()
        val quota = provider["quotaQuery"]!!.jsonObject
        assertFalse(quota["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("balance", quota["resultPath"]!!.jsonPrimitive.content)
        // 其余字段原样保留
        assertEquals("claude", provider["type"]!!.jsonPrimitive.content)
        assertEquals("sk", provider["apiKey"]!!.jsonPrimitive.content)
        assertFalse(provider["enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `absolute api path is kept as is instead of prefixing base url`() {
        val json = """
            [{"type":"openai","id":"a","name":"A","balanceOption":{"enabled":true,"apiPath":"https://relay.example.com/v1/usage","resultPath":"remaining"}}]
        """.trimIndent()

        val quota = providers(json).single()["quotaQuery"]!!.jsonObject
        assertEquals("https://relay.example.com/v1/usage", quota["url"]!!.jsonPrimitive.content)
        assertEquals("remaining", quota["resultPath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `providers without balanceOption are untouched`() {
        val json = """[{"type":"openai","id":"a","name":"A"}]"""
        val provider = providers(json).single()
        assertFalse("quotaQuery" in provider)
        assertFalse("balanceOption" in provider)
    }

    @Test
    fun `already migrated data is idempotent`() {
        val once = migrateProvidersBalanceToQuota(
            """[{"type":"openai","id":"a","name":"A","balanceOption":{"enabled":true,"apiPath":"/x","resultPath":"y"}}]"""
        )
        val twice = migrateProvidersBalanceToQuota(once)
        assertEquals(once, twice)
        val quota = providers(twice).single()["quotaQuery"]!!.jsonObject
        assertEquals("{{baseUrl}}/x", quota["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid json is returned unchanged`() {
        val broken = "[{"
        assertEquals(broken, migrateProvidersBalanceToQuota(broken))
    }
}
