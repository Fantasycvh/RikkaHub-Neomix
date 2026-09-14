package app.androidrik.common.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落盘日志的文本格式化（[formatLogEntry]）。
 *
 * 为什么不用 JSONL：请求体本身就是大段 JSON，再被 JSON 转义进单行会难读；
 * 排查时是「贴给人看」，故用带分隔线的文本块 + 正文原样保留。
 *
 * **敏感头脱敏**：[formatLogEntry] 在写出时把 [SENSITIVE_HEADERS] 的值替换为 `[redacted]`。
 */
class LogFormatTest {

    @Test
    fun `request log keeps full body unescaped on its own lines`() {
        val body = """{"model":"gpt","messages":[{"role":"user","content":"hi"}]}"""
        val entry = LogEntry.RequestLog(
            timestamp = 1_700_000_000_000,
            tag = "HTTP",
            url = "https://api.example.com/v1/chat/completions",
            method = "POST",
            requestHeaders = mapOf("Authorization" to "Bearer x"),
            requestBody = body,
            responseCode = 200,
            durationMs = 1234L,
        )

        val text = formatLogEntry(entry)

        // 正文原样出现（无 JSON 转义、不折行）
        assertTrue("body must be verbatim", text.contains(body))
        assertTrue(text.contains("POST https://api.example.com/v1/chat/completions"))
        assertTrue(text.contains("status=200"))
        assertTrue(text.contains("duration=1234ms"))
        // 敏感头脱敏（见下面专门测试，这里确认不会泄漏原值）
        assertTrue(text.contains("Authorization: [redacted]"))
        assertFalse(text.contains("Bearer x"))
        // 带分隔头，便于在多条日志间定位
        assertTrue(text.startsWith("===== "))
    }

    @Test
    fun `request log omits absent fields without placeholders`() {
        val entry = LogEntry.RequestLog(
            timestamp = 0,
            tag = "HTTP",
            url = "https://x",
            method = "GET",
        )
        val text = formatLogEntry(entry)

        assertTrue(text.contains("GET https://x"))
        assertTrue("no status line when null", !text.contains("status="))
        assertTrue("no duration when null", !text.contains("duration="))
        assertTrue("no headers section when empty", !text.contains("[request headers]"))
        assertTrue("no body section when null", !text.contains("[request body]"))
    }

    @Test
    fun `text log prints tag and message verbatim`() {
        val entry = LogEntry.TextLog(timestamp = 0, tag = "ChatService", message = "boom")
        val text = formatLogEntry(entry)

        assertTrue("header must carry the tag", text.contains("| ChatService ====="))
        assertTrue("message preserved", text.trimEnd().endsWith("boom"))
    }

    @Test
    fun `error is surfaced for failed requests`() {
        val entry = LogEntry.RequestLog(
            timestamp = 0,
            tag = "HTTP",
            url = "https://x",
            method = "POST",
            error = "timeout",
        )
        assertTrue(formatLogEntry(entry).contains("error: timeout"))
    }

    // ---- 敏感头脱敏 ----

    @Test
    fun `authorization header is redacted`() {
        val entry = LogEntry.RequestLog(
            timestamp = 0, tag = "HTTP", url = "https://x", method = "POST",
            requestHeaders = mapOf("Authorization" to "Bearer sk-abc123-xyz"),
        )
        val text = formatLogEntry(entry)
        assertTrue(text.contains("Authorization: [redacted]"))
        assertFalse("Bearer token must not leak", text.contains("sk-abc123-xyz"))
    }

    @Test
    fun `anthropic x-api-key header is redacted`() {
        val entry = LogEntry.RequestLog(
            timestamp = 0, tag = "HTTP", url = "https://x", method = "POST",
            requestHeaders = mapOf("x-api-key" to "sk-ant-secret"),
        )
        val text = formatLogEntry(entry)
        assertTrue(text.contains("x-api-key: [redacted]"))
        assertFalse(text.contains("sk-ant-secret"))
    }

    @Test
    fun `google x-goog-api-key header is redacted`() {
        val entry = LogEntry.RequestLog(
            timestamp = 0, tag = "HTTP", url = "https://x", method = "POST",
            requestHeaders = mapOf("x-goog-api-key" to "AIzaSyB-secret"),
        )
        val text = formatLogEntry(entry)
        assertTrue(text.contains("x-goog-api-key: [redacted]"))
        assertFalse(text.contains("AIzaSyB-secret"))
    }

    @Test
    fun `proxy-authorization header is redacted`() {
        val entry = LogEntry.RequestLog(
            timestamp = 0, tag = "HTTP", url = "https://x", method = "POST",
            requestHeaders = mapOf("Proxy-Authorization" to "Basic dXNlcjpwYXNz"),
        )
        val text = formatLogEntry(entry)
        assertTrue(text.contains("Proxy-Authorization: [redacted]"))
        assertFalse(text.contains("dXNlcjpwYXNz"))
    }

    @Test
    fun `non-sensitive headers are preserved verbatim`() {
        val entry = LogEntry.RequestLog(
            timestamp = 0, tag = "HTTP", url = "https://x", method = "POST",
            requestHeaders = mapOf(
                "Content-Type" to "application/json",
                "anthropic-version" to "2023-06-01",
                "Authorization" to "Bearer secret",
            ),
        )
        val text = formatLogEntry(entry)
        assertTrue("Content-Type preserved", text.contains("Content-Type: application/json"))
        assertTrue("anthropic-version preserved", text.contains("anthropic-version: 2023-06-01"))
        assertTrue("Authorization redacted", text.contains("Authorization: [redacted]"))
    }

    @Test
    fun `redacted map extension preserves non-sensitive values`() {
        val map = mapOf(
            "Authorization" to "Bearer secret",
            "X-Api-Key" to "key123",
            "content-type" to "application/json",
        )
        val redacted = map.redacted()
        assertTrue(redacted["Authorization"] == "[redacted]")
        assertTrue(redacted["X-Api-Key"] == "[redacted]")
        assertTrue(redacted["content-type"] == "application/json")
    }

    @Test
    fun `response headers are also redacted`() {
        val entry = LogEntry.RequestLog(
            timestamp = 0, tag = "HTTP", url = "https://x", method = "POST",
            responseCode = 200,
            responseHeaders = mapOf("Proxy-Authorization" to "sensitive"),
        )
        val text = formatLogEntry(entry)
        assertTrue(text.contains("Proxy-Authorization: [redacted]"))
        assertFalse(text.contains("sensitive"))
    }
}
