package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.RetrySetting
import app.androidrik.ai.provider.effectiveApiKeys
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

    // ---------- rotateProviderKeys / splitEffectiveKeys ----------

    @Test
    fun `rotate drops first enabled key in priority order`() {
        val setting = ProviderSetting.OpenAI().copy(
            apiKeys = listOf(ApiKeyEntry("k1"), ApiKeyEntry("k2"), ApiKeyEntry("k3")),
        )
        val rotated = rotateProviderKeys(setting)!!
        // 顺序模式首个=刚失败的 k1，轮转后生效 key 应为 k2,k3
        assertEquals(listOf("k2", "k3"), splitEffectiveKeys(rotated.effectiveApiKeys))
        assertEquals("k2", splitEffectiveKeys(rotated.effectiveApiKeys).first())
        // 轮转副本的 apiKeys 与旧 apiKey 字段同步覆盖
        val openAI = rotated as ProviderSetting.OpenAI
        assertEquals(listOf(ApiKeyEntry("k2"), ApiKeyEntry("k3")), openAI.apiKeys)
        assertEquals("k2\nk3", openAI.apiKey)
        // 再转一次 → 只剩 k3
        val rotated2 = rotateProviderKeys(rotated)!!
        assertEquals(listOf("k3"), splitEffectiveKeys(rotated2.effectiveApiKeys))
        // 最后一次 → null（无可切换）
        assertNull(rotateProviderKeys(rotated2))
    }

    @Test
    fun `rotate skips disabled keys and stops at single remaining`() {
        // 首个启用的才是"刚失败的"；禁用 key 不参与轮转
        val setting = ProviderSetting.OpenAI().copy(
            apiKeys = listOf(
                ApiKeyEntry("k1", enabled = false),
                ApiKeyEntry("k2"),
                ApiKeyEntry("k3"),
            ),
        )
        val rotated = rotateProviderKeys(setting)!!
        assertEquals(listOf("k3"), splitEffectiveKeys(rotated.effectiveApiKeys))
    }

    @Test
    fun `rotate supports legacy newline separated multi key string`() {
        // 旧形态：apiKeys 为空，apiKey 字符串里塞多个 key
        val setting = ProviderSetting.OpenAI().copy(apiKey = "k1\nk2")
        val rotated = rotateProviderKeys(setting)!!
        assertEquals(listOf("k2"), splitEffectiveKeys(rotated.effectiveApiKeys))
    }

    @Test
    fun `rotate returns null for single or empty key`() {
        assertNull(rotateProviderKeys(ProviderSetting.OpenAI()))
        assertNull(rotateProviderKeys(ProviderSetting.OpenAI().copy(apiKey = "only")))
        assertNull(
            rotateProviderKeys(
                ProviderSetting.OpenAI().copy(apiKeys = listOf(ApiKeyEntry("only")))
            )
        )
    }

    @Test
    fun `rotate works for google and claude providers`() {
        val google = rotateProviderKeys(
            ProviderSetting.Google().copy(apiKeys = listOf(ApiKeyEntry("g1"), ApiKeyEntry("g2")))
        )!!
        assertEquals("g2", splitEffectiveKeys(google.effectiveApiKeys).single())
        val claude = rotateProviderKeys(
            ProviderSetting.Claude().copy(apiKeys = listOf(ApiKeyEntry("c1"), ApiKeyEntry("c2")))
        )!!
        assertEquals("c2", splitEffectiveKeys(claude.effectiveApiKeys).single())
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
