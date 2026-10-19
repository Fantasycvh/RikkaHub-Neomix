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
}
