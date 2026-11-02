package app.androidrik.ai.util

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.providers.openai.ChatCompletionsAPI
import app.androidrik.ai.ui.UIMessage
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 请求级会话语义头（上游 #2186 `configureSessionHeaders`）：
 * `X-Session-ID` 带会话 id，opencode 网关额外带 `x-opencode-session`；没有 sessionId 时逐字节不变。
 */
class SessionHeadersTest {

    private fun build(url: String, sessionId: String?): Request =
        Request.Builder()
            .url(url)
            .configureSessionHeaders(url, sessionId)
            .build()

    @Test
    fun `有 sessionId 时带 X-Session-ID`() {
        val request = build("https://api.openai.com/v1/chat/completions", "conv-1")
        assertEquals(listOf("conv-1"), request.headers.values("X-Session-ID"))
        assertNull("非 opencode 网关不加私有头", request.headers["x-opencode-session"])
    }

    @Test
    fun `没有 sessionId 时不加任何会话头`() {
        val request = build("https://api.openai.com/v1/chat/completions", null)
        assertNull(request.headers["X-Session-ID"])
        assertNull(request.headers["x-opencode-session"])
    }

    @Test
    fun `opencode 网关额外带私有会话头`() {
        val request = build("https://opencode.ai/v1/chat/completions", "conv-2")
        assertEquals(listOf("conv-2"), request.headers.values("X-Session-ID"))
        assertEquals(listOf("conv-2"), request.headers.values("x-opencode-session"))
    }

    @Test
    fun `真实请求链路上 ChatCompletions 会发出 X-Session-ID`() {
        val server = MockWebServer()
        server.enqueue(MockResponse(code = 401, body = """{"error":{"message":"stop here"}}"""))
        server.start()
        try {
            val provider = ProviderSetting.OpenAI(
                name = "session-test",
                apiKey = "sk-test",
                baseUrl = server.url("/v1").toString().trimEnd('/'),
            )
            val params = TextGenerationParams(
                model = Model(modelId = "gpt-4o"),
                sessionId = "conv-42",
            )
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            runCatching {
                runBlocking { api.generateText(provider, listOf(UIMessage.user("hi")), params) }
            }

            val recorded = server.takeRequest()
            assertEquals(listOf("conv-42"), recorded.headers.values("X-Session-ID"))
        } finally {
            server.close()
        }
    }
}
