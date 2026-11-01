package app.androidrik.rikkahub.data.datastore.migration

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonMigratorTest {

    @Test
    fun `旧包名 Avatar 完整类名迁移到新包名`() {
        val input = """{"displaySetting":{"userAvatar":{"type":"me.rerere.rikkahub.data.model.Avatar.Dummy"}}}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("app.androidrik.rikkahub.data.model.Avatar.Dummy"))
        assertFalse(result.contains("me.rerere."))
    }

    @Test
    fun `旧包名 mcpServers 完整类名经包名迁移后转短名`() {
        val input = """{"mcpServers":[{"type":"me.rerere.rikkahub.data.mcp.McpServerConfig.SseTransportServer"}]}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("\"sse\""))
        assertFalse(result.contains("me.rerere."))
    }

    @Test
    fun `旧包名 assistants UIMessagePart 完整类名转短名`() {
        val input = """{"assistants":[{"presetMessages":[{"parts":[{"type":"me.rerere.ai.ui.UIMessagePart.Text"}]}]}]}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("\"text\""))
        assertFalse(result.contains("me.rerere."))
    }

    @Test
    fun `私有开发版包名的多态类名迁移到当前命名空间`() {
        val input = """{"displaySetting":{"userAvatar":{"type":"app.fantasycvh.rikkahub.data.model.Avatar.Dummy"}}}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("app.androidrik.rikkahub.data.model.Avatar.Dummy"))
        assertFalse(result.contains("app.fantasycvh."))
    }

    @Test
    fun `私有开发版包名的文件路径迁移到当前 applicationId`() {
        val input = """{
          "a":"file:///data/user/0/app.fantasycvh.rikkahub/files/upload/a.jpg",
          "b":"/data/data/app.fantasycvh.rikkahub/files/workspaces/w/x.txt",
          "c":"file:///data/user/0/app.fantasycvh.rikkahub.debug/files/upload/b.jpg"
        }"""
        val result = SettingsJsonMigrator.migrate(input, "app.androidrik.rikkahub")
        assertFalse(result.contains("fantasycvh"))
        assertTrue(result.contains("file:///data/user/0/app.androidrik.rikkahub/files/upload/a.jpg"))
        assertTrue(result.contains("file:///data/user/0/app.androidrik.rikkahub/files/workspaces/w/x.txt"))
        assertTrue(result.contains("file:///data/user/0/app.androidrik.rikkahub/files/upload/b.jpg"))
    }

    @Test
    fun `新包名备份不受影响`() {
        val input = """{"displaySetting":{"userAvatar":{"type":"app.androidrik.rikkahub.data.model.Avatar.Dummy"}}}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("app.androidrik.rikkahub.data.model.Avatar.Dummy"))
        assertFalse(result.contains("me.rerere."))
    }

    @Test
    fun `V4 旧备份里的 rikkahub 搜索提供方被剔除且选中索引左移`() {
        val input = "{\"searchServices\":[" +
            "{\"type\":\"rikkahub\",\"id\":\"2f0d3a58-4b7c-4c1e-9a5f-3d0f4e6a7b81\"}," +
            "{\"type\":\"tavily\",\"id\":\"9c1b2a3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d\"}]," +
            "\"searchServiceSelected\":1}"
        val result = SettingsJsonMigrator.migrate(input)
        // 子类已从 sealed 层删除 ⇒ 旧备份若留着它，decodeFromString<Settings> 会整份失败
        assertFalse(result.contains("rikkahub"))
        assertTrue(result.contains("\"tavily\""))
        // 选中项原为 tavily（旧索引 1），剔除前一项后必须左移到 0，否则会切到别的提供方
        assertTrue(result.contains("\"searchServiceSelected\":0"))
    }

    @Test
    fun `V4 只含 rikkahub 的旧备份剔除后为空数组并 clamp 索引`() {
        val input = "{\"searchServices\":[{\"type\":\"rikkahub\"}],\"searchServiceSelected\":3}"
        val result = SettingsJsonMigrator.migrate(input)
        assertFalse(result.contains("rikkahub"))
        assertTrue(result.contains("\"searchServices\":[]"))
        assertTrue(result.contains("\"searchServiceSelected\":0"))
    }

    @Test
    fun `V4 不含 rikkahub 的备份保持原样`() {
        val input = "{\"searchServices\":[{\"type\":\"tavily\"," +
            "\"id\":\"9c1b2a3d-4e5f-4a6b-8c7d-0e1f2a3b4c5d\"}],\"searchServiceSelected\":0}"
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("\"tavily\""))
        assertTrue(result.contains("\"searchServiceSelected\":0"))
    }

    @Test
    fun `备份恢复把 providers 的 balanceOption 迁移成 quotaQuery`() {
        val input = "{\"providers\":[{\"type\":\"openai\",\"id\":\"a\",\"name\":\"A\"," +
            "\"balanceOption\":{\"enabled\":true,\"apiPath\":\"/credits\",\"resultPath\":\"data.total_usage\"}}]}"
        val result = SettingsJsonMigrator.migrate(input)
        assertFalse(result.contains("balanceOption"))
        assertTrue(result.contains("\"quotaQuery\""))
        assertTrue(result.contains("{{baseUrl}}/credits"))
        assertTrue(result.contains("data.total_usage"))
    }

    @Test
    fun `备份里没有 providers 时不报错且原样返回`() {
        val input = """{"launchCount":7}"""
        val result = SettingsJsonMigrator.migrate(input)
        assertTrue(result.contains("\"launchCount\":7"))
    }

    // ---- V7：旧订阅 codexAccount → ChatGPT 账号（与 DataStore V5 等价） ----

    private val providerId = "8f7c3a1e-2b4d-4c5f-9a6b-0d1e2f3a4b5c"
    private val modelId = "3c9e5b2f-6a1d-4e7c-8f0b-2d4a6c8e0f1a"

    private fun legacySubscriptionBackup(authType: String = "chatgpt_subscription"): String =
        "{\"providers\":[{" +
            "\"type\":\"openai\",\"id\":\"$providerId\",\"name\":\"我的订阅\"," +
            "\"models\":[{\"modelId\":\"gpt-6.1-sol\",\"displayName\":\"GPT-6.1-Sol\",\"id\":\"$modelId\"}]," +
            "\"baseUrl\":\"https://api.openai.com/v1\",\"apiKey\":\"sk-old\"," +
            "\"authType\":\"$authType\"," +
            "\"codexAccount\":{\"accountId\":\"acct-1\",\"email\":\"user@example.com\",\"planType\":\"plus\"," +
            "\"expiresAt\":1700000000000,\"accessTokenEncrypted\":\"v1:access-cipher\"," +
            "\"refreshTokenEncrypted\":\"v1:refresh-cipher\"}}]}"

    @Test
    fun `V7 旧备份的订阅 provider 迁移成 ChatGPT 账号且密文原样保留`() {
        val result = SettingsJsonMigrator.migrate(legacySubscriptionBackup())

        assertFalse("旧账号字段不能留（它已不属于任何 provider）", result.contains("codexAccount"))
        assertFalse(result.contains("chatgpt_subscription"))
        assertTrue("必须改写成账号类 type", result.contains("\"type\":\"chatgpt\""))
        // id / 模型 / 密文令牌都不能变：对话历史按 modelId 找回模型，账号层按 v1: 密文解密
        assertTrue(result.contains(providerId))
        assertTrue(result.contains("\"gpt-6.1-sol\""))
        assertTrue(result.contains("v1:access-cipher"))
        assertTrue(result.contains("v1:refresh-cipher"))
        assertTrue(result.contains("\"accountId\":\"acct-1\""))
        assertTrue(result.contains("\"plan\":\"plus\""))
    }

    @Test
    fun `V7 备份恢复后的订阅 provider 能被账号层解码（不再按旧 OpenAI 落回设置）`() {
        val migrated = SettingsJsonMigrator.migrate(legacySubscriptionBackup())
        val decoded = SettingsCompatDecoder.decode(JsonInstant, migrated)

        assertTrue(decoded.droppedEntries.isEmpty())
        val provider = decoded.settings.providers.single()
        assertTrue(provider is ProviderSetting.ChatGPT)
        provider as ProviderSetting.ChatGPT
        assertEquals(providerId, provider.id.toString())
        assertEquals(listOf(modelId), provider.models.map { it.id.toString() })
        assertEquals("v1:access-cipher", provider.credential?.accessToken)
        assertEquals("v1:refresh-cipher", provider.credential?.refreshToken)
        assertEquals("acct-1", provider.credential?.accountId)
        assertEquals("plus", provider.credential?.plan)
    }

    @Test
    fun `V7 备份里已切回 API Key 的 provider 不被迁移`() {
        val result = SettingsJsonMigrator.migrate(legacySubscriptionBackup(authType = "api_key"))

        assertTrue("显式 API Key 模式保留原 provider", result.contains("\"type\":\"openai\""))
        assertTrue(result.contains("\"authType\":\"api_key\""))
        assertTrue("旧账号字段保留（P5 再决定去留）", result.contains("codexAccount"))
        assertFalse(result.contains("\"type\":\"chatgpt\""))
    }

    @Test
    fun `V7 备份迁移幂等`() {
        val once = SettingsJsonMigrator.migrate(legacySubscriptionBackup())
        val twice = SettingsJsonMigrator.migrate(once)
        assertEquals(once, twice)
    }

    @Test
    fun `V7 没有 providers 的备份不受影响`() {
        val result = SettingsJsonMigrator.migrate("""{"launchCount":3}""")
        assertTrue(result.contains("\"launchCount\":3"))
        assertFalse(result.contains("chatgpt"))
    }

    @Test
    fun `V7 与 V6 叠加：旧余额配置与旧订阅账号同时迁移`() {
        val input = "{\"providers\":[{" +
            "\"type\":\"openai\",\"id\":\"$providerId\",\"name\":\"订阅\"," +
            "\"authType\":\"chatgpt_subscription\"," +
            "\"balanceOption\":{\"enabled\":true,\"apiPath\":\"/credits\",\"resultPath\":\"data.x\"}," +
            "\"codexAccount\":{\"accountId\":\"acct-1\",\"expiresAt\":0," +
            "\"accessTokenEncrypted\":\"v1:access-cipher\",\"refreshTokenEncrypted\":\"v1:refresh-cipher\"}}]}"

        val result = SettingsJsonMigrator.migrate(input)
        assertFalse("V6 先删 balanceOption", result.contains("balanceOption"))
        assertTrue(result.contains("\"type\":\"chatgpt\""))
        // 账号类没有 quotaQuery：V6 中间产物不能残留在最终 JSON 里
        assertFalse(result.contains("quotaQuery"))
        assertTrue(result.contains("v1:access-cipher"))
    }

    @Test
    fun `V7 未登录的旧订阅 provider 迁移后 credential 为空`() {
        val result = SettingsJsonMigrator.migrate(
            "{\"providers\":[{\"type\":\"openai\",\"id\":\"$providerId\"," +
                "\"authType\":\"chatgpt_subscription\"}]}"
        )
        val provider = SettingsCompatDecoder.decode(JsonInstant, result).settings.providers.single()
        assertTrue(provider is ProviderSetting.ChatGPT)
        assertNull((provider as ProviderSetting.ChatGPT).credential)
    }
}
