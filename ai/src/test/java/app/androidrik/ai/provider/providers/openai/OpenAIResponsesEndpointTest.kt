package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.ReasoningOverride
import app.androidrik.ai.provider.CustomHeader
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.OPENAI_CODEX_BASE_URL
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.util.KeyRoulette
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI 兼容提供商在新 `ResponsesEndpoint` API 下的接线（P4a 重构回归）。
 *
 * 锁三件事：
 * - API Key 模式：URL 仍是 `baseUrl + responsesPath`、走默认能力；
 * - Codex 端点（baseUrl 指向官方 Codex 后端）：`developer` 角色、不吃采样参数、需要 SSE 容错；
 * - 请求头顺序语义不变：refer 头与自定义头都在、鉴权是最后写的那一个（自定义 Authorization 被盖掉）。
 */
class OpenAIResponsesEndpointTest {
    private val authenticator = OpenAIRequestAuthenticator(KeyRoulette.default())

    private fun params() = TextGenerationParams(model = Model(modelId = "gpt-4o"))

    private fun endpoint(setting: ProviderSetting.OpenAI) = setting.toResponsesEndpoint(params(), authenticator)

    @Test
    fun `api key endpoint keeps base url and default capabilities`() {
        val setting = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1",
            responsesPath = "/responses",
        )

        val endpoint = endpoint(setting)

        assertEquals("https://api.openai.com/v1/responses", endpoint.url)
        assertEquals(ResponseProviderCapabilities(), endpoint.capabilities)
        assertFalse(endpoint.forceEventStream)
        assertEquals(setting.proxy, endpoint.proxy)
    }

    @Test
    fun `codex base url endpoint uses codex capabilities and force event stream`() {
        val setting = ProviderSetting.OpenAI(baseUrl = OPENAI_CODEX_BASE_URL)

        val endpoint = endpoint(setting)

        assertEquals("$OPENAI_CODEX_BASE_URL/responses", endpoint.url)
        assertEquals("developer", endpoint.capabilities.systemMessageRole)
        assertFalse(endpoint.capabilities.supportsSamplingParams)
        assertTrue(endpoint.capabilities.alwaysIncludeEncryptedContent)
        assertTrue(endpoint.forceEventStream)
    }

    @Test
    fun `request headers keep refer headers custom headers and bearer auth`() = runBlocking {
        val setting = ProviderSetting.OpenAI(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-test",
            customHeaders = listOf(CustomHeader("X-Extra", "1")),
        )

        val builder = Request.Builder().url(endpoint(setting).url).get()
        endpoint(setting).configureRequest.invoke(builder)
        val request = builder.build()

        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertEquals("1", request.header("X-Extra"))
        // configureReferHeaders 对 openrouter 的两个头
        assertEquals("RikkaHub", request.header("X-Title"))
        assertEquals("https://rikka-ai.com", request.header("HTTP-Referer"))
    }

    @Test
    fun `provider reasoning override is carried by the endpoint`() {
        val override = ReasoningOverride(effort = "provider-pro")
        val setting = ProviderSetting.OpenAI(reasoningOverride = override)

        assertEquals(override, endpoint(setting).reasoningOverride)
    }
}
