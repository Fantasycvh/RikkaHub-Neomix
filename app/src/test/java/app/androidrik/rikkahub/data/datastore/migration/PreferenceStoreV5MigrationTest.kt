package app.androidrik.rikkahub.data.datastore.migration

import androidx.datastore.preferences.core.mutablePreferencesOf
import app.androidrik.ai.provider.ProviderProxy
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.rikkahub.data.datastore.SettingsStore
import app.androidrik.rikkahub.utils.JsonInstant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * V5 迁移：旧「OpenAI ChatGPT 订阅」（`codexAccount`）→ 账号类 [ProviderSetting.ChatGPT]。
 *
 * 契约（见 [PreferenceStoreV5Migration] kdoc）：
 * 1. 只迁仍在订阅态的旧 provider，`id`/`models`/通用字段与密文令牌**原样无损**；
 * 2. 显式切回 API Key 的 provider 不动（否则会删掉一份仍可用的 API Key 配置）；
 * 3. 未登录/令牌为空 ⇒ `credential = null`（账号类的「未登录」语义），不造假登录态；
 * 4. 幂等；畸形 JSON 原样返回。
 */
class PreferenceStoreV5MigrationTest {

    private val providerId = "8f7c3a1e-2b4d-4c5f-9a6b-0d1e2f3a4b5c"
    private val modelId = "3c9e5b2f-6a1d-4e7c-8f0b-2d4a6c8e0f1a"
    private val accessCiphertext = "v1:AAECAwQFBgcICQoLDA0ODw=="
    private val refreshCiphertext = "v1:EBESExQVFhcYGRobHB0eHw=="

    private val modelJson = """{"modelId":"gpt-6.1-sol","displayName":"GPT-6.1-Sol","id":"$modelId"}"""

    private fun codexAccountJson(
        accountId: String = "acct-1",
        email: String? = "user@example.com",
        planType: String? = "plus",
        expiresAt: Long = 1_700_000_000_000L,
        accessTokenEncrypted: String = accessCiphertext,
        refreshTokenEncrypted: String = refreshCiphertext,
    ): String {
        val fields = mutableListOf(
            "\"accountId\":\"$accountId\"",
            "\"expiresAt\":$expiresAt",
            "\"accessTokenEncrypted\":\"$accessTokenEncrypted\"",
            "\"refreshTokenEncrypted\":\"$refreshTokenEncrypted\"",
        )
        email?.let { fields += "\"email\":\"$it\"" }
        planType?.let { fields += "\"planType\":\"$it\"" }
        return "{${fields.joinToString(",")}}"
    }

    /** 一条旧订阅态 OpenAI provider 的 JSON 对象（不含数组括号），附带通用字段以验证保留。 */
    private fun legacyProviderObject(
        id: String = providerId,
        enabled: Boolean = true,
        name: String = "我的 ChatGPT 订阅",
        authType: String? = "chatgpt_subscription",
        apiKey: String? = "sk-old-api-key",
        codexAccount: String? = codexAccountJson(),
    ): String {
        val fields = mutableListOf(
            "\"type\":\"openai\"",
            "\"id\":\"$id\"",
            "\"enabled\":$enabled",
            "\"name\":\"$name\"",
            "\"models\":[$modelJson]",
            "\"proxy\":{\"type\":\"http\",\"address\":\"127.0.0.1\",\"port\":7890}",
            "\"customHeaders\":[{\"name\":\"X-Test\",\"value\":\"v\"}]",
            "\"retryOption\":{\"enabled\":true,\"delaysSeconds\":[1,2],\"keepPartialOnFailure\":false}",
            "\"reasoningOverride\":{\"effort\":\"high\"}",
            "\"baseUrl\":\"https://api.openai.com/v1\"",
            "\"quotaQuery\":{\"enabled\":false}",
        )
        if (apiKey != null) fields += "\"apiKey\":\"$apiKey\""
        if (authType != null) fields += "\"authType\":\"$authType\""
        if (codexAccount != null) fields += "\"codexAccount\":$codexAccount"
        return "{${fields.joinToString(",")}}"
    }

    private fun legacyProviderJson(
        id: String = providerId,
        enabled: Boolean = true,
        name: String = "我的 ChatGPT 订阅",
        authType: String? = "chatgpt_subscription",
        apiKey: String? = "sk-old-api-key",
        codexAccount: String? = codexAccountJson(),
    ): String = "[${legacyProviderObject(id, enabled, name, authType, apiKey, codexAccount)}]"

    private fun migratedJson(raw: String): List<JsonObject> =
        JsonInstant.parseToJsonElement(migrateProvidersCodexAccountToChatGPT(raw))
            .jsonArray.map { it.jsonObject }

    private fun migratedProviders(raw: String): List<ProviderSetting> =
        JsonInstant.decodeFromString(migrateProvidersCodexAccountToChatGPT(raw))

    // ---- 核心：订阅态 provider → ChatGPT 账号 ----

    @Test
    fun `subscription provider becomes chatgpt account with ciphertext carried over as is`() {
        val provider = migratedProviders(legacyProviderJson()).single()

        assertTrue("旧订阅必须迁成账号类 ChatGPT", provider is ProviderSetting.ChatGPT)
        provider as ProviderSetting.ChatGPT
        assertEquals(Uuid.parse(providerId), provider.id)
        assertTrue("enabled 原样保留", provider.enabled)
        assertEquals("我的 ChatGPT 订阅", provider.name)
        assertEquals(listOf(Uuid.parse(modelId)), provider.models.map { it.id })
        assertEquals("gpt-6.1-sol", provider.models.single().modelId)
        assertTrue("proxy 原样保留", provider.proxy is ProviderProxy.Http)

        val credential = provider.credential
        assertEquals("access token 密文必须原样搬运", accessCiphertext, credential?.accessToken)
        assertEquals("refresh token 密文必须原样搬运", refreshCiphertext, credential?.refreshToken)
        assertEquals(1_700_000_000_000L, credential?.expiresAt)
        assertEquals("acct-1", credential?.accountId)
        assertEquals("user@example.com", credential?.email)
        assertEquals("plus", credential?.plan)
    }

    @Test
    fun `account fields are preserved and openai only fields are dropped`() {
        val provider = migratedJson(legacyProviderJson()).single()
        assertEquals("chatgpt", provider["type"]!!.jsonPrimitive.content)

        assertEquals("127.0.0.1", provider["proxy"]!!.jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals(
            "X-Test",
            provider["customHeaders"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
        assertEquals(2, provider["retryOption"]!!.jsonObject["delaysSeconds"]!!.jsonArray.size)
        assertEquals(
            "high",
            provider["reasoningOverride"]!!.jsonObject["effort"]!!.jsonPrimitive.content,
        )

        // 旧 OpenAI 专有字段与旧账号字段都不能混进新 provider
        listOf("codexAccount", "authType", "apiKey", "baseUrl", "quotaQuery").forEach { key ->
            assertFalse("新 provider 不应带旧字段 $key", key in provider)
        }
        // 密文原样落盘（不解密再加密）
        val credential = provider["credential"]!!.jsonObject
        assertEquals(accessCiphertext, credential["accessToken"]!!.jsonPrimitive.content)
        assertEquals(refreshCiphertext, credential["refreshToken"]!!.jsonPrimitive.content)
        assertEquals("acct-1", credential["accountId"]!!.jsonPrimitive.content)
        assertEquals("plus", credential["plan"]!!.jsonPrimitive.content)
        assertEquals("user@example.com", credential["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `subscription provider without codex account becomes logged out chatgpt`() {
        val provider = migratedProviders(legacyProviderJson(codexAccount = null)).single()

        assertTrue(provider is ProviderSetting.ChatGPT)
        provider as ProviderSetting.ChatGPT
        assertNull("没有 codexAccount = 未登录，不能造假登录态", provider.credential)
        assertEquals(
            "登录态丢失也不能丢模型（否则历史对话的模型选择失效）",
            listOf(Uuid.parse(modelId)),
            provider.models.map { it.id },
        )
    }

    @Test
    fun `blank token ciphertexts are treated as logged out`() {
        val provider = migratedProviders(
            legacyProviderJson(
                codexAccount = codexAccountJson(accessTokenEncrypted = "", refreshTokenEncrypted = ""),
            )
        ).single()

        assertTrue(provider is ProviderSetting.ChatGPT)
        assertNull((provider as ProviderSetting.ChatGPT).credential)
    }

    @Test
    fun `blank account id becomes null while tokens survive`() {
        val provider = migratedProviders(
            legacyProviderJson(codexAccount = codexAccountJson(accountId = ""))
        ).single() as ProviderSetting.ChatGPT

        val credential = provider.credential!!
        assertNull("空 accountId 要收敛成 null（与 OAuthCredential 语义一致）", credential.accountId)
        assertEquals(accessCiphertext, credential.accessToken)
        assertEquals("user@example.com", credential.email)
    }

    @Test
    fun `blank email and plan become null`() {
        val provider = migratedProviders(
            legacyProviderJson(codexAccount = codexAccountJson(email = "", planType = ""))
        ).single() as ProviderSetting.ChatGPT

        assertNull(provider.credential?.email)
        assertNull(provider.credential?.plan)
        assertEquals(accessCiphertext, provider.credential?.accessToken)
    }

    // ---- 边界：不转换的形态 ----

    @Test
    fun `explicit api key provider keeps old fields even with stale codex account`() {
        // 旧界面「切回 API Key」只改 authType、会遗留 codexAccount；请求链路已按 API Key 走，
        // 把它迁成账号类等于删掉一份仍能用的 API Key 配置
        val provider = migratedProviders(
            legacyProviderJson(authType = "api_key", apiKey = "sk-live")
        ).single()

        assertTrue(provider is ProviderSetting.OpenAI)
        provider as ProviderSetting.OpenAI
        // P5 删除旧字段后：旧 codexAccount/authType 由 ignoreUnknownKeys 忽略，配置仍可用
        assertEquals("sk-live", provider.apiKey)
        assertEquals("旧 codexAccount 不属于任何账号类 provider", 1, migratedProviders(legacyProviderJson(authType = "api_key", apiKey = "sk-live")).size)
    }

    @Test
    fun `provider without auth type and without codex account is untouched`() {
        val provider = migratedProviders(
            legacyProviderJson(authType = null, codexAccount = null)
        ).single()

        assertTrue("普通 API Key provider 不动", provider is ProviderSetting.OpenAI)
    }

    @Test
    fun `provider without auth type but with codex account is converted`() {
        // authType 字段出现之前形态的数据：有订阅账号即迁移
        val provider = migratedProviders(legacyProviderJson(authType = null)).single()

        assertTrue(provider is ProviderSetting.ChatGPT)
        assertEquals(accessCiphertext, (provider as ProviderSetting.ChatGPT).credential?.accessToken)
    }

    @Test
    fun `non openai providers are untouched`() {
        val raw = """
            [{"type":"claude","id":"$providerId","name":"Claude","apiKey":"sk-ant"},
             {"type":"chatgpt","id":"$modelId","name":"已有账号","credential":{"accessToken":"v1:abc"}}]
        """.trimIndent()

        val providers = migratedProviders(raw)
        assertTrue(providers[0] is ProviderSetting.Claude)
        val chatgpt = providers[1] as ProviderSetting.ChatGPT
        assertEquals("已有账号", chatgpt.name)
        assertEquals("v1:abc", chatgpt.credential?.accessToken)
    }

    @Test
    fun `mixed provider list only converts legacy subscription and keeps order`() {
        val apiKeyProviderId = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        val raw = "[${legacyProviderObject(
            id = apiKeyProviderId,
            authType = "api_key",
            apiKey = "sk-live",
        )}," +
            "${legacyProviderObject()}," +
            "{\"type\":\"claude\",\"id\":\"$modelId\",\"name\":\"Claude\"}]"

        val providers = migratedProviders(raw)
        assertEquals(3, providers.size)
        assertTrue(providers[0] is ProviderSetting.OpenAI)
        assertTrue(providers[1] is ProviderSetting.ChatGPT)
        assertTrue(providers[2] is ProviderSetting.Claude)
        assertEquals(Uuid.parse(apiKeyProviderId), providers[0].id)
        assertEquals(Uuid.parse(providerId), providers[1].id)
    }

    // ---- 幂等 / 畸形输入 / DataStore 版本门 ----

    @Test
    fun `already migrated data is idempotent`() {
        val once = migrateProvidersCodexAccountToChatGPT(legacyProviderJson())
        val twice = migrateProvidersCodexAccountToChatGPT(once)
        assertEquals(once, twice)
        assertTrue(migratedProviders(twice).single() is ProviderSetting.ChatGPT)
    }

    @Test
    fun `invalid json is returned unchanged`() {
        val broken = "[{"
        assertEquals(broken, migrateProvidersCodexAccountToChatGPT(broken))
    }

    @Test
    fun `migration bumps data version from 4 to 5 and converts providers`() = runBlocking {
        val before = mutablePreferencesOf(
            SettingsStore.VERSION to 4,
            SettingsStore.PROVIDERS to legacyProviderJson(),
        )
        val migration = PreferenceStoreV5Migration()

        assertTrue("data_version 4 必须迁移", migration.shouldMigrate(before))

        val after = migration.migrate(before)
        assertEquals(5, after[SettingsStore.VERSION])
        val providers = JsonInstant.decodeFromString<List<ProviderSetting>>(
            after[SettingsStore.PROVIDERS]!!
        )
        assertTrue(providers.single() is ProviderSetting.ChatGPT)
    }

    @Test
    fun `migrated data is not migrated again at data version 5`() = runBlocking {
        val prefs = mutablePreferencesOf(SettingsStore.VERSION to 5)
        assertFalse(PreferenceStoreV5Migration().shouldMigrate(prefs))
    }

    @Test
    fun `missing providers key only bumps version`() = runBlocking {
        val before = mutablePreferencesOf(SettingsStore.VERSION to 4)
        val after = PreferenceStoreV5Migration().migrate(before)
        assertEquals(5, after[SettingsStore.VERSION])
        assertNull(after[SettingsStore.PROVIDERS])
    }

    @Test
    fun `credential json keys match oauth credential schema`() {
        // 钉住字段名：拼错会在上面的 decode 里被 ignoreUnknownKeys 静默丢掉
        val credentialKeys = migratedJson(legacyProviderJson()).single()["credential"]!!
            .jsonObject.keys
        assertEquals(
            setOf("accessToken", "refreshToken", "expiresAt", "accountId", "email", "plan"),
            credentialKeys,
        )
    }
}
