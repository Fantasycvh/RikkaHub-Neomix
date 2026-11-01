package app.androidrik.ai.provider.providers.openai

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Codex SSE Content-Type 补头拦截器：官方后端偶发缺 `text/event-stream`，
 * 不补头时 okhttp `EventSources` 会直接报 `Invalid content-type`。
 */
class CodexSseContentTypeInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(alwaysMatch: Boolean): OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(CodexSseContentTypeInterceptor { alwaysMatch })
        .build()

    private fun request(path: String = "/backend-api/codex/responses") =
        Request.Builder().url(server.url(path)).build()

    private fun sseResponse(contentType: String? = null): MockResponse = MockResponse(
        code = 200,
        headers = Headers.Builder()
            .apply { contentType?.let { add("Content-Type", it) } }
            .build(),
        body = "data: {\"type\":\"response.completed\"}\n\n",
    )

    @Test
    fun `缺失 Content-Type 时补成 text-event-stream`() {
        server.enqueue(sseResponse())

        client(alwaysMatch = true).newCall(request()).execute().use { response ->
            assertEquals("text/event-stream", response.body!!.contentType().toString())
            assertEquals(
                "data: {\"type\":\"response.completed\"}\n\n",
                response.body!!.string(),
            )
        }
    }

    @Test
    fun `已有 Content-Type 时原样放行`() {
        server.enqueue(sseResponse("text/event-stream"))

        client(alwaysMatch = true).newCall(request()).execute().use { response ->
            assertEquals("text/event-stream", response.body!!.contentType().toString())
        }
    }

    @Test
    fun `非 Codex 端点不改变 Content-Type`() {
        server.enqueue(sseResponse())

        client(alwaysMatch = false).newCall(request("/v1/models")).execute().use { response ->
            assertNull(response.body!!.contentType())
        }
    }
}
