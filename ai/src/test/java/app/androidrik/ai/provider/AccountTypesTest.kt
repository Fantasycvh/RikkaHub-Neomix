package app.androidrik.ai.provider

import app.androidrik.ai.util.json
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 账号类提供商（P3）：`ProviderSetting.Account.ChatGPT/Grok` 的序列化契约与凭据行为。
 *
 * 类型名 `chatgpt` / `grok` 是**持久化格式**，改名等于旧数据/旧备份读不出来。
 */
class AccountTypesTest {
    private val credential = OAuthCredential(
        accessToken = "access-token-1234567890",
        refreshToken = "refresh-token-0987654321",
        expiresAt = 1_700_000_000_000L,
        accountId = "acct-1",
        email = "user@example.com",
        plan = "plus",
    )

    @Test
    fun `account providers serialize with stable polymorphic type names`() {
        val chatgpt = ProviderSetting.ChatGPT(name = "ChatGPT", credential = credential)
        val grok = ProviderSetting.Grok(name = "Grok", credential = credential)

        val chatgptJson = json.encodeToString<ProviderSetting>(chatgpt)
        val grokJson = json.encodeToString<ProviderSetting>(grok)

        assertTrue(chatgptJson.contains("\"type\":\"chatgpt\""))
        assertTrue(grokJson.contains("\"type\":\"grok\""))
        // 凭据字段随设置一起序列化（落盘加密由 app 层账号管理负责）
        assertTrue(chatgptJson.contains("\"refreshtoken\"") || chatgptJson.contains("\"refreshToken\""))
        assertTrue(chatgptJson.contains("user@example.com"))
    }

    @Test
    fun `account providers round trip through polymorphic json`() {
        val providers = listOf<ProviderSetting>(
            ProviderSetting.ChatGPT(name = "ChatGPT", credential = credential),
            ProviderSetting.Grok(name = "Grok"),
        )
        val encoded = json.encodeToString(providers)
        val decoded = json.decodeFromString<List<ProviderSetting>>(encoded)

        val chatgpt = decoded[0] as ProviderSetting.ChatGPT
        assertEquals("ChatGPT", chatgpt.name)
        assertEquals(credential, chatgpt.credential)
        val grok = decoded[1] as ProviderSetting.Grok
        assertEquals("Grok", grok.name)
        assertNull(grok.credential)
    }

    @Test
    fun `legacy json without credential field decodes as signed out`() {
        val legacy = """{"type":"chatgpt","id":"${Uuid.random()}","name":"ChatGPT","models":[]}"""
        val decoded = json.decodeFromString<ProviderSetting>(legacy)
        assertEquals(ProviderSetting.ChatGPT::class, decoded::class)
        assertNull((decoded as ProviderSetting.ChatGPT).credential)
    }

    @Test
    fun `withCredential replaces and clears the credential`() {
        val signedOut = ProviderSetting.ChatGPT()
        val signedIn = signedOut.withCredential(credential) as ProviderSetting.ChatGPT
        assertEquals(credential, signedIn.credential)
        assertNull(signedIn.withCredential(null).credential)
        // id 不变，账号层按 id 写回设置
        assertEquals(signedOut.id, signedIn.id)
    }

    @Test
    fun `copyProvider keeps credential and only touches display fields`() {
        val original = ProviderSetting.Grok(name = "Grok", credential = credential)
        val renamed = original.copyProvider(name = "Grok 2") as ProviderSetting.Grok
        assertEquals("Grok 2", renamed.name)
        assertEquals(credential, renamed.credential)
        assertEquals(original.id, renamed.id)
    }

    @Test
    fun `account providers have no api keys`() {
        val account = ProviderSetting.ChatGPT(credential = credential)
        assertEquals("", account.effectiveApiKeys)
    }

    @Test
    fun `credential toString masks tokens`() {
        val text = credential.toString()
        assertFalse("不应泄露完整 access token", text.contains(credential.accessToken))
        assertTrue(text.contains("acce***7890"))
        assertTrue(text.contains("user@example.com"))
    }

    @Test
    fun `empty token is fully masked`() {
        assertTrue(OAuthCredential(accessToken = "").toString().contains("accessToken=***"))
        assertTrue(OAuthCredential(accessToken = "short").toString().contains("accessToken=***"))
    }
}
