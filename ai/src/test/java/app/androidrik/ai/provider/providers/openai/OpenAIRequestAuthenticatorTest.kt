package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.util.KeyRoulette
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * API Key 鉴权（旧 ChatGPT 订阅的设备码链路已在 P5 删除，订阅鉴权走 `providers/AccountAuth.kt`）。
 */
class OpenAIRequestAuthenticatorTest {

    @Test
    fun `API Key 模式设置 Bearer 头`() {
        val setting = ProviderSetting.OpenAI(apiKey = "sk-test")
        val builder = Request.Builder().url("https://api.openai.com/v1/responses").get()
        OpenAIRequestAuthenticator(KeyRoulette.default()).authenticateApiKey(builder, setting)
        assertEquals("Bearer sk-test", builder.build().header("Authorization"))
        assertNull(builder.build().header("ChatGPT-Account-ID"))
    }

    @Test
    fun `多 key 设置按顺序取第一个`() {
        val setting = ProviderSetting.OpenAI(
            apiKeys = listOf(
                app.androidrik.ai.provider.ApiKeyEntry(key = "sk-first"),
                app.androidrik.ai.provider.ApiKeyEntry(key = "sk-second"),
            ),
        )
        val builder = Request.Builder().url("https://api.openai.com/v1/responses").get()
        OpenAIRequestAuthenticator(KeyRoulette.default()).authenticateApiKey(builder, setting)
        assertEquals("Bearer sk-first", builder.build().header("Authorization"))
    }
}
