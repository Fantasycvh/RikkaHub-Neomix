package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.OPENAI_CODEX_BASE_URL
import app.androidrik.ai.provider.OpenAIAuthType
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.util.KeyRoulette
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenAIRequestAuthenticatorTest {

    private val codexUrl = "$OPENAI_CODEX_BASE_URL/responses"

    private fun subscriptionSetting() = ProviderSetting.OpenAI(
        authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
    )

    private fun tokenProvider(token: String = "access-token", accountId: String = "acct-1") =
        OpenAICodexTokenProvider { CodexToken(accessToken = token, accountId = accountId) }

    private suspend fun authHeaders(
        setting: ProviderSetting.OpenAI,
        url: String,
        provider: OpenAICodexTokenProvider? = tokenProvider(),
    ): Request {
        val builder = Request.Builder().url(url).get()
        OpenAIRequestAuthenticator(KeyRoulette.default(), provider).authenticate(builder, setting, url)
        return builder.build()
    }

    @Test
    fun `订阅模式带 Bearer 令牌 账号头与 originator`() = kotlinx.coroutines.runBlocking {
        val request = authHeaders(subscriptionSetting(), codexUrl)
        assertEquals("Bearer access-token", request.header("Authorization"))
        assertEquals("acct-1", request.header("ChatGPT-Account-ID"))
        assertEquals("codex_cli_rs", request.header("originator"))
    }

    @Test
    fun `订阅凭据拒绝发往非 Codex 端点`() = kotlinx.coroutines.runBlocking {
        try {
            authHeaders(subscriptionSetting(), "https://evil.example.com/v1/responses")
            fail("应当拒绝非官方端点")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("官方 Codex 端点"))
        }
    }

    @Test
    fun `未注入令牌提供者时给出可读错误`() = kotlinx.coroutines.runBlocking {
        try {
            authHeaders(subscriptionSetting(), codexUrl, provider = null)
            fail("缺少令牌提供者应报错")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("订阅登录未初始化"))
        }
    }

    @Test
    fun `API Key 模式沿用 KeyRoulette 行为`() = kotlinx.coroutines.runBlocking {
        val setting = ProviderSetting.OpenAI(apiKey = "sk-test", apiKeys = listOf())
        // 请求级自定义头走 applyCustomHeaders，不在这里断言
        val request = authHeaders(setting, "https://api.openai.com/v1/responses", provider = null)
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertEquals(null, request.header("ChatGPT-Account-ID"))
    }

    @Test
    fun `API Key 模式设置 Bearer 头`() {
        val setting = ProviderSetting.OpenAI(apiKey = "sk-test")
        val builder = Request.Builder().url("https://api.openai.com/v1/responses").get()
        OpenAIRequestAuthenticator(KeyRoulette.default(), null).authenticateApiKey(builder, setting)
        assertEquals("Bearer sk-test", builder.build().header("Authorization"))
    }
}
