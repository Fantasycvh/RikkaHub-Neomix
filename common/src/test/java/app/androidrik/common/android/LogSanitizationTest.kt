package app.androidrik.common.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预签名 URL 凭据脱敏（[sanitizeSensitiveText]）与日志写入入口（[Logging.addLog]）的护栏。
 *
 * 背景：S3 预签名 URL 的 `X-Amz-Signature` / `X-Amz-Credential` / `X-Amz-Security-Token`
 * 是 bearer-token 级别凭据（到期前可下载/写入对象）。它们会出现在 provider 请求体、
 * 异常消息与诊断文本里，绝不能进内存日志 ring、落盘文件或日志 UI。
 *
 * 这里全部使用**假**凭据字符串，不包含任何真实 key。
 */
class LogSanitizationTest {
    private val fakeSignature = "FAKESIGNATUREdeadbeef0123456789"
    private val fakeAccessKey = "AKIAFAKEACCESSKEY"
    private val fakeSecurityToken = "FAKE_SECURITY_TOKEN_abc123"

    private val presignedUrl =
        "https://bucket.example.com/rikkahub_uploads/a.png" +
            "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
            "&X-Amz-Credential=$fakeAccessKey%2F20261019%2Fauto%2Fs3%2Faws4_request" +
            "&X-Amz-Date=20261019T000000Z" +
            "&X-Amz-Expires=86400" +
            "&X-Amz-SignedHeaders=host" +
            "&X-Amz-Signature=$fakeSignature"

    @After
    fun tearDown() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(false)
    }

    // ---- sanitizeSensitiveText：URL / JSON / 编码形态 ----

    @Test
    fun `standard presigned url query values are redacted and other params stay`() {
        val sanitized = sanitizeSensitiveText(presignedUrl)

        assertFalse("signature must not survive", sanitized.contains(fakeSignature))
        assertFalse("access key must not survive", sanitized.contains(fakeAccessKey))
        assertTrue(sanitized.contains("X-Amz-Signature=[redacted]"))
        assertTrue(sanitized.contains("X-Amz-Credential=[redacted]"))
        // 其余参数与路径原样保留，保证日志仍可诊断
        assertTrue(sanitized.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"))
        assertTrue(sanitized.contains("X-Amz-Date=20261019T000000Z"))
        assertTrue(sanitized.contains("X-Amz-Expires=86400"))
        assertTrue(sanitized.contains("X-Amz-SignedHeaders=host"))
        assertTrue(sanitized.contains("https://bucket.example.com/rikkahub_uploads/a.png?"))
    }

    @Test
    fun `url embedded inside a json string is redacted without breaking the json`() {
        val json = """{"image":"$presignedUrl","count":1}"""

        val sanitized = sanitizeSensitiveText(json)

        assertFalse(sanitized.contains(fakeSignature))
        assertFalse(sanitized.contains(fakeAccessKey))
        assertTrue(sanitized.contains("\"image\":\"https://bucket.example.com"))
        assertTrue(sanitized.contains("X-Amz-Signature=[redacted]"))
        assertTrue("json tail preserved", sanitized.contains(",\"count\":1}"))
    }

    @Test
    fun `percent encoded nested url is redacted up to the encoded separators`() {
        val encoded = "image=" +
            "https%3A%2F%2Fbucket.example.com%2Frikkahub_uploads%2Fa.png" +
            "%3FX-Amz-Signature%3D$fakeSignature" +
            "%26X-Amz-Credential%3D$fakeAccessKey%2F20261019%2Fauto%2Fs3%2Faws4_request" +
            "%26X-Amz-Date%3D20261019T000000Z"

        val sanitized = sanitizeSensitiveText(encoded)

        assertFalse(sanitized.contains(fakeSignature))
        assertFalse(sanitized.contains(fakeAccessKey))
        assertTrue(sanitized.contains("X-Amz-Signature%3D[redacted]"))
        assertTrue(sanitized.contains("X-Amz-Credential%3D[redacted]%26X-Amz-Date%3D20261019T000000Z"))
    }

    @Test
    fun `security token and lower-cased parameter names are redacted`() {
        val text = "GET https://x?x-amz-security-token=$fakeSecurityToken&X-AMZ-SIGNATURE=$fakeSignature"

        val sanitized = sanitizeSensitiveText(text)

        assertFalse(sanitized.contains(fakeSecurityToken))
        assertFalse(sanitized.contains(fakeSignature))
        assertTrue(sanitized.contains("x-amz-security-token=[redacted]"))
        assertTrue(sanitized.contains("X-AMZ-SIGNATURE=[redacted]"))
    }

    @Test
    fun `plain diagnostics are left untouched`() {
        val text = "S3 upload failed (status=403), retrying attempt 2; key=rikkahub_uploads/abc.png"

        assertEquals(text, sanitizeSensitiveText(text))
    }

    @Test
    fun `sanitization is idempotent`() {
        val once = sanitizeSensitiveText(presignedUrl)
        assertEquals(once, sanitizeSensitiveText(once))
    }

    // ---- 日志入口：ring 与文件输出都不能泄漏 ----

    @Test
    fun `request log ring and formatted output never contain the fake signature`() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = presignedUrl,
                method = "POST",
                requestHeaders = mapOf("X-Debug-Url" to presignedUrl),
                requestBody = """{"image":"$presignedUrl"}""",
                error = "upstream rejected: $presignedUrl",
            )
        )

        val entries = Logging.getRecentLogs()
        assertEquals(1, entries.size)
        val stored = entries.single() as LogEntry.RequestLog
        // 内存 ring 里本身就不能是原始凭据（不只是落盘时脱敏）
        assertFalse("ring url leaked", stored.url.contains(fakeSignature))
        assertFalse("ring url leaked credential", stored.url.contains(fakeAccessKey))
        assertFalse("ring body leaked", stored.requestBody.orEmpty().contains(fakeSignature))
        assertFalse("ring error leaked", stored.error.orEmpty().contains(fakeSignature))
        assertTrue("ring header values must be sanitized", stored.requestHeaders.values.none { it.contains(fakeSignature) })

        val formatted = formatLogEntry(stored)
        assertFalse("formatted output leaked", formatted.contains(fakeSignature))
        assertFalse("formatted output leaked credential", formatted.contains(fakeAccessKey))
        assertTrue("redaction marker present", formatted.contains("[redacted]"))
        // 诊断上下文保留
        assertTrue(formatted.contains("X-Amz-Expires=86400"))
        assertTrue(formatted.contains("rikkahub_uploads/a.png"))
    }

    @Test
    fun `text log messages are sanitized before they enter the ring`() {
        Logging.clear()
        Logging.log(
            "Sketch",
            "open failed: $presignedUrl (decoding error, other text kept)",
        )

        val stored = Logging.getRecentLogs().single() as LogEntry.TextLog
        assertFalse(stored.message.contains(fakeSignature))
        assertFalse(stored.message.contains(fakeAccessKey))
        assertTrue("other text preserved", stored.message.contains("(decoding error, other text kept)"))
        assertFalse(formatLogEntry(stored).contains(fakeSignature))
    }

    // ---- AI-4：Vertex / 通用 query 凭据（`?key=`、`api_key=`、`token=` …）----

    private val fakeVertexKey = "FAKE_GOOGLE_VERTEX_KEY_123456"
    private val vertexUrl =
        "https://us-central1-aiplatform.googleapis.com/v1/projects/p/locations/l/" +
            "publishers/google/models/gemini-2.5-pro:streamGenerateContent?alt=sse&key=$fakeVertexKey"

    @Test
    fun `vertex api key in url query is redacted and other params stay`() {
        val sanitized = sanitizeSensitiveText(vertexUrl)

        assertFalse("vertex api key must not survive", sanitized.contains(fakeVertexKey))
        assertTrue(sanitized.contains("key=[redacted]"))
        // 其余参数与路径保留，日志仍可诊断
        assertTrue(sanitized.contains("alt=sse"))
        assertTrue(sanitized.contains("streamGenerateContent"))
    }

    @Test
    fun `generic api key and token query params are redacted`() {
        val url = "https://gateway.example.com/v1?" +
            "api_key=FAKE_API_KEY_1&api-key=FAKE_API_KEY_2&apikey=FAKE_API_KEY_3&apiKey=FAKE_API_KEY_4" +
            "&access_token=FAKE_ACCESS&refresh_token=FAKE_REFRESH&id_token=FAKE_ID" +
            "&token=FAKE_TOKEN&client_secret=FAKE_CLIENT_SECRET&password=FAKE_PASSWORD&page=2"

        val sanitized = sanitizeSensitiveText(url)

        listOf(
            "FAKE_API_KEY_1", "FAKE_API_KEY_2", "FAKE_API_KEY_3", "FAKE_API_KEY_4",
            "FAKE_ACCESS", "FAKE_REFRESH", "FAKE_ID", "FAKE_TOKEN",
            "FAKE_CLIENT_SECRET", "FAKE_PASSWORD",
        ).forEach { secret ->
            assertFalse("$secret must not survive: $sanitized", sanitized.contains(secret))
        }
        assertTrue(sanitized.contains("api_key=[redacted]"))
        assertTrue(sanitized.contains("access_token=[redacted]"))
        assertTrue(sanitized.contains("page=2"))
    }

    @Test
    fun `form encoded body credentials are redacted without mangling other values`() {
        val form = "grant_type=refresh_token&client_id=public" +
            "&client_secret=FAKE_CLIENT_SECRET&refresh_token=FAKE_REFRESH"

        val sanitized = sanitizeSensitiveText(form)

        assertFalse(sanitized.contains("FAKE_CLIENT_SECRET"))
        assertFalse(sanitized.contains("FAKE_REFRESH"))
        // 值里恰好出现 refresh_token 字样时不能误伤（参数名位置才算凭据）
        assertTrue(sanitized.contains("grant_type=refresh_token"))
        assertTrue(sanitized.contains("client_id=public"))
    }

    @Test
    fun `lookalike parameter names and diagnostic key values are left untouched`() {
        // 诊断短语里的对象 key 不是凭据（旧用例约束：普通诊断信息必须保留）
        val diagnostic = "S3 upload failed (status=403), retrying attempt 2; key=rikkahub_uploads/abc.png"
        assertEquals(diagnostic, sanitizeSensitiveText(diagnostic))

        // 短参数名的边界：monkey/mykey/hockey 不是 key，max_tokens/token_count 不是 token
        // 复合名带左边界：myapi_key / monkey_client_secret / xaccess_token 不能被误当成 api_key 等
        val lookalikes = "https://x/v1?monkey=banana&mykey=1&hockey=2&max_tokens=128&token_count=3" +
            "&keyword=abc&apikeyish=zz&myapi_key=not-a-key&monkey_client_secret=nope&xaccess_token=nope"
        assertEquals(lookalikes, sanitizeSensitiveText(lookalikes))
    }

    @Test
    fun `compound credential names are redacted even without query separators`() {
        // 复合名足够明确：诊断文本里（空格/SQL 风格 `;` 之后）也应打码
        val text = "request failed: client_secret=FAKE_CLIENT_SECRET; api_key=FAKE_API_KEY (status=401)"
        val sanitized = sanitizeSensitiveText(text)

        assertFalse(sanitized.contains("FAKE_CLIENT_SECRET"))
        assertFalse(sanitized.contains("FAKE_API_KEY"))
        assertTrue(sanitized.contains("request failed"))
        assertTrue(sanitized.contains("(status=401)"))
    }

    @Test
    fun `plain json bodies are not spoiled`() {
        val json = """{"model":"gpt","max_tokens":128,"key":"cache-key","message":"a=b"}"""
        assertEquals(json, sanitizeSensitiveText(json))
    }

    @Test
    fun `encoded nested url with generic params is redacted`() {
        val encodedCompound = "target=https%3A%2F%2Fapi.example.com%2Fv1" +
            "%3Fapi_key%3DFAKE_COMPOUND%26alt%3Dsse"
        val compoundSanitized = sanitizeSensitiveText(encodedCompound)
        assertFalse(compoundSanitized.contains("FAKE_COMPOUND"))
        assertTrue(compoundSanitized.contains("api_key%3D[redacted]%26alt%3Dsse"))

        // 短名（key=）在编码后的嵌套 URL 里也要能识别（边界是 %3F/%26，不是 lookbehind）
        val encodedShort = "target=https%3A%2F%2Fapi.example.com%2Fv1%3Fkey%3DFAKE_SHORT%26alt%3Dsse"
        val shortSanitized = sanitizeSensitiveText(encodedShort)
        assertFalse(shortSanitized.contains("FAKE_SHORT"))
        assertTrue(shortSanitized.contains("key%3D[redacted]%26alt%3Dsse"))
    }

    @Test
    fun `generic sanitization is idempotent`() {
        val once = sanitizeSensitiveText("$vertexUrl&token=FAKE_TOKEN")
        assertEquals(once, sanitizeSensitiveText(once))
    }

    @Test
    fun `ring and formatted output redact vertex key and credential headers at entry`() {
        Logging.clear()
        Logging.setRequestLoggingEnabled(true)

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = vertexUrl,
                method = "POST",
                requestHeaders = mapOf(
                    "Authorization" to "Bearer FAKE_BEARER",
                    "x-goog-api-key" to fakeVertexKey,
                    "content-type" to "application/json",
                    "X-Debug-Url" to vertexUrl,
                ),
                requestBody = """{"prompt":"hi","max_tokens":16}""",
                error = "upstream 400 for $vertexUrl",
            )
        )

        val stored = Logging.getRecentLogs().single() as LogEntry.RequestLog
        assertFalse("ring url leaked the vertex key", stored.url.contains(fakeVertexKey))
        assertTrue(stored.url.contains("key=[redacted]"))
        // 敏感头必须在 ring 里就已脱敏，不能只在格式化时处理
        assertEquals("[redacted]", stored.requestHeaders["Authorization"])
        assertEquals("[redacted]", stored.requestHeaders["x-goog-api-key"])
        // 普通头保留；普通头值里内嵌的 URL 也会被脱敏
        assertEquals("application/json", stored.requestHeaders["content-type"])
        assertFalse("debug header leaked", stored.requestHeaders["X-Debug-Url"].orEmpty().contains(fakeVertexKey))
        // 普通 JSON 正文保持原样
        assertEquals("""{"prompt":"hi","max_tokens":16}""", stored.requestBody)
        assertFalse("ring error leaked", stored.error.orEmpty().contains(fakeVertexKey))

        val formatted = formatLogEntry(stored)
        assertFalse(formatted.contains(fakeVertexKey))
        assertFalse(formatted.contains("FAKE_BEARER"))
        assertTrue(formatted.contains("max_tokens"))
    }

    @Test
    fun `expanded credential header names are redacted`() {
        val redacted = mapOf(
            "api-key" to "fake-mimo-key",
            "x-subscription-token" to "fake-brave-key",
            "X-Auth-Token" to "fake-auth-token",
            "Cookie" to "session=fake-session",
            "Set-Cookie" to "session=fake-session; HttpOnly",
            "Content-Type" to "application/json",
        ).redacted()

        assertEquals("[redacted]", redacted["api-key"])
        assertEquals("[redacted]", redacted["x-subscription-token"])
        assertEquals("[redacted]", redacted["X-Auth-Token"])
        assertEquals("[redacted]", redacted["Cookie"])
        assertEquals("[redacted]", redacted["Set-Cookie"])
        assertEquals("application/json", redacted["Content-Type"])
    }
}
