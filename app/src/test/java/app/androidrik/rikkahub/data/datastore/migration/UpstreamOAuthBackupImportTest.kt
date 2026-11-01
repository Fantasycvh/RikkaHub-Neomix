package app.androidrik.rikkahub.data.datastore.migration

import app.androidrik.ai.provider.OAuthCredential
import app.androidrik.ai.provider.ProviderProxy
import app.androidrik.rikkahub.data.ai.account.CredentialCipher
import app.androidrik.rikkahub.data.ai.account.decrypted
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游 RikkaHub 备份里的 OAuth 账号 provider（chatgpt / grok）能不能被本仓导入：
 *
 * - P3 起 `ProviderSetting.Account` 与上游同名同 `@SerialName`（`chatgpt` / `grok`）、
 *   `OAuthCredential` 字段名一致（accessToken/refreshToken/expiresAt/accountId/email/plan）；
 * - 上游凭据是**明文**（本仓落盘才加密），本仓读取时「无 `v1:` 前缀视为明文」所以能直接用；
 * - 上游 JSON 没有本仓额外字段（proxy/retryOption/reasoningOverride）与未来新字段，
 *   靠 `ignoreUnknownKeys` + 默认值兼容。
 *
 * 这里直接走恢复链路用的两个入口：[SettingsJsonMigrator.migrate]（包名/版本迁移）→
 * [SettingsCompatDecoder.decode]（未知类型逐条降级）。
 */
class UpstreamOAuthBackupImportTest {

    /** 上游 `Settings` 里 providers 的 JSON 形态（上游无 proxy/retryOption/reasoningOverride）。 */
    private fun upstreamSettingsJson(providers: String) = """
        {"providers":$providers}
    """.trimIndent()

    private fun decode(providers: String): SettingsDecodeResult {
        val migrated = SettingsJsonMigrator.migrate(
            settingsJson = upstreamSettingsJson(providers),
            currentApplicationId = "app.androidrik.rikkahub.debug",
        )
        return SettingsCompatDecoder.decode(JsonInstant, migrated)
    }

    @Test
    fun `upstream chatgpt account provider imports with plaintext credential`() {
        val result = decode(
            """
            [{"type":"chatgpt","id":"e0e1b2c3-4d5e-4f60-8a9b-0c1d2e3f4a5b",
              "name":"ChatGPT","enabled":true,"models":[],"customHeaders":[],
              "credential":{"accessToken":"plain-access","refreshToken":"plain-refresh",
                            "expiresAt":1750000000000,"accountId":"acct-1",
                            "email":"user@example.com","plan":"plus"}}]
            """.trimIndent()
        )

        assertTrue(result.droppedEntries.isEmpty())
        val provider = result.settings.providers.single() as ProviderSetting.ChatGPT
        assertEquals("ChatGPT", provider.name)
        assertEquals("e0e1b2c3-4d5e-4f60-8a9b-0c1d2e3f4a5b", provider.id.toString())
        val credential = provider.credential!!
        assertEquals("plain-access", credential.accessToken)
        assertEquals("plain-refresh", credential.refreshToken)
        assertEquals(1750000000000L, credential.expiresAt)
        assertEquals("acct-1", credential.accountId)
        assertEquals("user@example.com", credential.email)
        assertEquals("plus", credential.plan)
        // 本仓额外字段由默认值补齐
        assertEquals(ProviderProxy.Inherit, provider.proxy)
        assertEquals(null, provider.retryOption)
        assertEquals(null, provider.reasoningOverride)
    }

    @Test
    fun `upstream grok account provider imports`() {
        val result = decode(
            """
            [{"type":"grok","id":"f0e1d2c3-4b5a-4697-8879-0a1b2c3d4e5f",
              "name":"Grok","enabled":true,"models":[{"modelId":"grok-4.1","displayName":"Grok 4.1"}],
              "credential":{"accessToken":"plain-grok","refreshToken":null,"expiresAt":0}}]
            """.trimIndent()
        )

        assertTrue(result.droppedEntries.isEmpty())
        val provider = result.settings.providers.single() as ProviderSetting.Grok
        assertEquals("grok-4.1", provider.models.single().modelId)
        assertEquals("plain-grok", provider.credential?.accessToken)
    }

    @Test
    fun `unknown future provider type is dropped but others survive`() {
        val result = decode(
            """
            [{"type":"chatgpt","id":"e0e1b2c3-4d5e-4f60-8a9b-0c1d2e3f4a5b","name":"ChatGPT",
              "credential":{"accessToken":"plain-access"}},
             {"type":"future-provider","id":"a0e1b2c3-4d5e-4f60-8a9b-0c1d2e3f4a5b","name":"Future"}]
            """.trimIndent()
        )

        assertEquals(1, result.settings.providers.size)
        assertTrue(result.settings.providers.single() is ProviderSetting.ChatGPT)
        assertEquals(1, result.droppedEntries.size)
        assertTrue(result.droppedEntries.single().contains("future-provider"))
    }

    @Test
    fun `upstream plaintext credential stays usable after read`() {
        // 本仓读取凭据时：带 v1: 前缀的才解密，其余当明文（上游备份形态）——identity 密码器等价
        val plaintext = OAuthCredential(
            accessToken = "plain-access",
            refreshToken = "plain-refresh",
        )
        val cipher = object : CredentialCipher {
            override fun encrypt(plaintext: String): String = plaintext
            override fun decrypt(ciphertext: String): String = ciphertext
        }
        val decrypted = plaintext.decrypted(cipher)
        assertEquals("plain-access", decrypted.accessToken)
        assertEquals("plain-refresh", decrypted.refreshToken)
        assertNotNull(decrypted)
    }
}
