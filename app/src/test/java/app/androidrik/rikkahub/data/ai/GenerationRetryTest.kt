package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.RetrySetting
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class GenerationRetryTest {

    // ---------- RetrySetting.parseDelays ----------

    @Test
    fun `parseDelays parses user example sequence`() {
        assertEquals(
            listOf(2, 2, 2, 2, 2, 5, 10, 20, 60, 120),
            RetrySetting.parseDelays("2,2,2,2,2,5,10,20,60,120")
        )
    }

    @Test
    fun `parseDelays tolerates whitespace and fullwidth comma`() {
        assertEquals(listOf(1, 2, 3), RetrySetting.parseDelays(" 1 , 2，3 "))
    }

    @Test
    fun `parseDelays rejects empty garbage negative and empty parts`() {
        assertNull(RetrySetting.parseDelays(""))
        assertNull(RetrySetting.parseDelays("   "))
        assertNull(RetrySetting.parseDelays("a,b"))
        assertNull(RetrySetting.parseDelays("-1,2"))
        assertNull(RetrySetting.parseDelays("1,,2"))
        assertNull(RetrySetting.parseDelays("1.5,2"))
    }

    @Test
    fun `parseDelays accepts zero for immediate retry`() {
        assertEquals(listOf(0, 0), RetrySetting.parseDelays("0,0"))
    }

    @Test
    fun `default delays match user spec`() {
        assertEquals(listOf(2, 2, 2, 2, 2, 5, 10, 20, 60, 120), RetrySetting.DEFAULT_DELAYS_SECONDS)
        assertNotNull(RetrySetting().delaysSeconds)
        assertFalse(RetrySetting().enabled)
    }

    // ---------- isKeySwitchingError / hasMultipleEnabledKeys ----------

    @Test
    fun `key switching errors are 401 403 and 429`() {
        assertTrue(isKeySwitchingError(RuntimeException("Failed to get response: 401 unauthorized")))
        assertTrue(isKeySwitchingError(RuntimeException("status: 403 Forbidden")))
        // 429 账户级限流：多账户场景换 key 可避开（流式半截 429 已被 #1812 分流拦截）
        assertTrue(isKeySwitchingError(RuntimeException("Failed to get response: 429 too many requests")))
        assertFalse(isKeySwitchingError(RuntimeException("Failed to get response: 500 internal")))
        assertFalse(isKeySwitchingError(IOException("connect timeout")))
    }

    @Test
    fun `hasMultipleEnabledKeys counts only enabled non-blank keys`() {
        // 空列表回退旧单 key 字段，不算多 key
        assertFalse(hasMultipleEnabledKeys(ProviderSetting.OpenAI()))
        // 多 key 全启用
        val multi = ProviderSetting.OpenAI().copy(
            apiKeys = listOf(ApiKeyEntry("k1"), ApiKeyEntry("k2")),
        )
        assertTrue(hasMultipleEnabledKeys(multi))
        // 禁用的 key 不计入
        val oneEnabled = multi.copy(apiKeys = listOf(ApiKeyEntry("k1", enabled = false), ApiKeyEntry("k2")))
        assertFalse(hasMultipleEnabledKeys(oneEnabled))
        // 空白 key 不计入
        val blank = multi.copy(apiKeys = listOf(ApiKeyEntry("  "), ApiKeyEntry("k2")))
        assertFalse(hasMultipleEnabledKeys(blank))
    }

    // ---------- isRetryableGenerationError ----------

    @Test
    fun `network IOExceptions are retryable`() {
        assertTrue(isRetryableGenerationError(SocketTimeoutException("timeout")))
        assertTrue(isRetryableGenerationError(UnknownHostException("api.example.com")))
        assertTrue(isRetryableGenerationError(IOException("Connection reset")))
    }

    @Test
    fun `serialization errors are retryable`() {
        assertTrue(
            isRetryableGenerationError(kotlinx.serialization.SerializationException("Unexpected JSON token"))
        )
    }

    @Test
    fun `retryable http codes in message are retried`() {
        assertTrue(isRetryableGenerationError(Exception("Failed to get response: 429 Too Many Requests")))
        assertTrue(isRetryableGenerationError(Exception("Failed to get response: 502 Bad Gateway")))
        assertTrue(isRetryableGenerationError(Exception("Failed to get response: 503 Service Unavailable")))
        assertTrue(isRetryableGenerationError(Exception("Failed to get response: 408 Request Timeout")))
    }

    @Test
    fun `auth and client errors are not retried`() {
        assertFalse(isRetryableGenerationError(Exception("Failed to get response: 401 Unauthorized")))
        assertFalse(isRetryableGenerationError(Exception("Failed to get response: 403 Forbidden")))
        assertFalse(isRetryableGenerationError(Exception("Failed to get response: 400 Bad Request")))
        assertFalse(isRetryableGenerationError(Exception("Failed to get models: 404 Not Found")))
    }

    @Test
    fun `status keyword takes precedence over body digits`() {
        // 前缀形态优先于正文里的数字（如 400 错误体里出现 "500 tokens"）
        assertFalse(
            isRetryableGenerationError(Exception("""code 400: {"usage":"about 500 tokens"}"""))
        )
    }

    @Test
    fun `fallback finds code from colon form and html error pages`() {
        assertTrue(isRetryableGenerationError(Exception(": 500 Internal Server Error")))
        assertTrue(
            isRetryableGenerationError(Exception("<html>502 Bad Gateway cloudflare</html>"))
        )
    }

    @Test
    fun `unrelated exceptions are not retried`() {
        assertFalse(isRetryableGenerationError(IllegalStateException("Provider not found")))
        assertFalse(isRetryableGenerationError(Exception(null as String?)))
    }

    @Test
    fun `cancellation must be classified non-retryable`() {
        // 引擎层在分类前显式直通 CancellationException；分类器兜底也必须返回 false，
        // 防止「停止生成」被误判为可重试错误后吞掉取消语义。
        assertFalse(isRetryableGenerationError(CancellationException("stopped by user")))
    }
}
