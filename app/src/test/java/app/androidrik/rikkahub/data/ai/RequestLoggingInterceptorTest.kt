package app.androidrik.rikkahub.data.ai

import app.androidrik.common.android.LogEntry
import app.androidrik.common.android.Logging
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 请求日志拦截器的**真实调用链**测试：真实 [OkHttpClient] → [RequestLoggingInterceptor] →
 * `Logging` 内存 ring（不发网络请求，用终止拦截器返回构造的 [Response]）。
 *
 * 覆盖两条不能退化的约束：
 * 1. AI-4：凭据级 query（Vertex `?key=`）与凭据头在 **ring 里**就已脱敏（不是只在落盘格式化时）；
 * 2. 记录日志不能改变请求语义：one-shot / duplex 请求体不能被日志提前 `writeTo` 消费，
 *    真正“发出去”的 body 必须完整。
 */
class RequestLoggingInterceptorTest {

    @Before
    fun setUp() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)
    }

    @After
    fun tearDown() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(false)
    }

    // ---- helpers -------------------------------------------------------------

    /** 真实 client：日志拦截器在链首，[terminal] 负责短路返回响应（等价于网络层不参与）。 */
    private fun clientWith(terminal: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(RequestLoggingInterceptor())
            .addInterceptor(terminal)
            .build()

    private fun onlyRequestLog(): LogEntry.RequestLog {
        val logs = Logging.getRecentLogs()
        assertEquals("expected exactly one request log, got: $logs", 1, logs.size)
        return logs.single() as LogEntry.RequestLog
    }

    /** 可计数的请求体：用于证明日志没有提前把它消费掉。 */
    private open class CountingBody(
        private val payload: String,
        private val oneShot: Boolean = false,
        private val duplex: Boolean = false,
    ) : RequestBody() {
        var writes = 0
            private set

        override fun contentType() = "application/json".toMediaType()

        override fun writeTo(sink: BufferedSink) {
            writes++
            sink.writeUtf8(payload)
        }

        override fun isOneShot(): Boolean = oneShot

        override fun isDuplex(): Boolean = duplex
    }

    // ---- AI-4：凭据不落内存 ring --------------------------------------------

    @Test
    fun `vertex api key in url query never reaches the log ring`() {
        val vertexKey = "FAKE_VERTEX_KEY_ABC123"
        val url = "https://example.com/v1beta/models/gemini:streamGenerateContent?alt=sse&key=$vertexKey"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer FAKE_BEARER_TOKEN")
            .header("x-goog-api-key", "FAKE_GOOGLE_HEADER_KEY")
            .header("Content-Type", "application/json")
            .post("""{"prompt":"hi","max_tokens":16}""".toRequestBody("application/json".toMediaType()))
            .build()

        val terminal = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Set-Cookie", "session=FAKE_SESSION")
                .body("""{"ok":true}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        clientWith(terminal).newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }

        val entry = onlyRequestLog()
        assertFalse("url must not carry the vertex key: ${entry.url}", entry.url.contains(vertexKey))
        assertTrue("redaction marker expected: ${entry.url}", entry.url.contains("key=[redacted]"))
        assertTrue("other query params must stay", entry.url.contains("alt=sse"))

        // 凭据头在 ring 里就已脱敏
        assertEquals("[redacted]", entry.requestHeaders["Authorization"])
        assertEquals("[redacted]", entry.requestHeaders["x-goog-api-key"])
        // 响应头同样
        assertEquals("[redacted]", entry.responseHeaders["Set-Cookie"])
        // 非敏感头与普通 JSON 正文保持原样
        assertEquals("application/json", entry.requestHeaders["Content-Type"])
        assertEquals("""{"prompt":"hi","max_tokens":16}""", entry.requestBody)
        assertEquals(200, entry.responseCode)
        assertTrue("duration must be recorded", entry.durationMs != null)

        // 内存 ring 是“可分享日志文件”的数据源，格式化后更不能再出现凭据
        val formatted = formatLogForTest(entry)
        assertFalse(formatted.contains(vertexKey))
        assertFalse(formatted.contains("FAKE_BEARER_TOKEN"))
        assertTrue(formatted.contains("max_tokens"))
    }

    // ---- one-shot / duplex 请求体不能被日志消费 ------------------------------

    @Test
    fun `one shot request body is not consumed by logging`() {
        val payload = """{"prompt":"one-shot-body"}"""
        val body = CountingBody(payload, oneShot = true)
        var sentBody: String? = null

        val terminal = Interceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            sentBody = buffer.readUtf8()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"ok":true}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val request = Request.Builder()
            .url("https://example.com/v1/chat/completions")
            .post(body)
            .build()

        clientWith(terminal).newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }

        // 日志没写、真正发送写了一次，且内容完整
        assertEquals("logging must not write the one-shot body", 1, body.writes)
        assertEquals(payload, sentBody)
        assertEquals("[body not captured: one-shot request]", onlyRequestLog().requestBody)
    }

    @Test
    fun `duplex request body is not consumed by logging`() {
        val payload = """{"prompt":"duplex-body"}"""
        val body = CountingBody(payload, duplex = true)
        var sentBody: String? = null

        val terminal = Interceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            sentBody = buffer.readUtf8()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"ok":true}""".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val request = Request.Builder()
            .url("https://example.com/v1/chat/completions")
            .post(body)
            .build()

        clientWith(terminal).newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }

        assertEquals("logging must not write the duplex body", 1, body.writes)
        assertEquals(payload, sentBody)
        assertEquals("[body not captured: duplex request]", onlyRequestLog().requestBody)
    }

    @Test
    fun `logging disabled neither consumes the body nor records anything`() {
        Logging.setRequestLoggingEnabled(false)
        val payload = """{"prompt":"no-logging"}"""
        val body = CountingBody(payload, oneShot = true)

        val terminal = Interceptor { chain ->
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val request = Request.Builder()
            .url("https://example.com/v1/chat/completions")
            .post(body)
            .build()

        clientWith(terminal).newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }

        assertEquals(1, body.writes)
        assertTrue("logging disabled must not record", Logging.getRecentLogs().isEmpty())
    }

    // ---- 失败路径：错误文本里的凭据同样脱敏，原异常原样抛出 -------------------

    @Test
    fun `failed request logs sanitized error and rethrows the original failure`() {
        val vertexKey = "FAKE_ERROR_KEY_999"
        val url = "https://example.com/v1?sse=1&key=$vertexKey"
        val terminal = Interceptor { chain ->
            throw IOException("connect failed for ${chain.request().url}")
        }
        val request = Request.Builder().url(url).get().build()

        try {
            clientWith(terminal).newCall(request).execute()
            fail("the interceptor must rethrow the network failure")
        } catch (e: IOException) {
            // 请求语义不变：异常照常抛给调用方（并保留原始信息）
            assertTrue(e.message.orEmpty().contains(url))
        }

        val entry = onlyRequestLog()
        assertFalse("error text must not leak the key", entry.error.orEmpty().contains(vertexKey))
        assertTrue(entry.error.orEmpty().contains("connect failed"))
        assertTrue("url must be redacted in the ring too", entry.url.contains("key=[redacted]"))
        assertNull(entry.responseCode)
    }

    /**
     * 复刻落盘格式化的行为（`formatLogEntry` 是 common 的 `internal`，app 测试拿不到）。
     *
     * 只验证「ring 里的数据本身已脱敏」这一个可观察面：如果 ring 干净，落盘也不会脏；
     * 真正的格式化回归由 common 模块的 LogSanitizationTest / LogFormatTest 覆盖。
     */
    private fun formatLogForTest(entry: LogEntry.RequestLog): String = buildString {
        append(entry.method).append(' ').append(entry.url).append('\n')
        entry.requestHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append('\n') }
        entry.requestBody?.let { append("[request body]\n").append(it).append('\n') }
        entry.responseHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append('\n') }
        entry.error?.let { append("error: ").append(it).append('\n') }
    }
}
