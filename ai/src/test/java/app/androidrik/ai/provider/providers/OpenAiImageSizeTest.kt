package app.androidrik.ai.provider.providers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上游 4e4bfa62 / #1978：Grok 判断必须解析 host，`size=auto` 不写入请求体。
 *
 * 这里钉住的是「自定义分辨率不再被静默丢弃」这条用户可见行为：旧实现在整串 URL 上找
 * `x.ai`，`api.xxx-max.ai` 这类中转域名（很常见）会被误判成 Grok。
 */
class OpenAiImageSizeTest {

    @Test
    fun `x_ai 及其子域名被判定为 grok 端点`() {
        assertTrue(isXaiHost("https://api.x.ai/v1"))
        assertTrue(isXaiHost("https://x.ai/v1"))
        assertTrue(isXaiHost("https://API.X.AI/v1"))
        assertTrue(isXaiHost("http://api.x.ai"))
    }

    @Test
    fun `中转域名不再被误判为 grok`() {
        // 旧实现 baseUrl.contains("x.ai") 会把这四条全判成 Grok ⇒ 自定义分辨率被静默丢弃
        assertFalse(isXaiHost("https://api.xxx-max.ai/v1"))
        assertFalse(isXaiHost("https://relay.max.ai/v1"))
        assertFalse(isXaiHost("https://open.bigmodel.cn/api/paas/v4"))
        // 只有路径里出现 x.ai 也不算（host 检查而非全串检查）
        assertFalse(isXaiHost("https://proxy.example.com/x.ai/v1"))
    }

    @Test
    fun `相似但不同域名的后缀不算 grok`() {
        assertFalse(isXaiHost("https://notx.ai/v1"))
        assertFalse(isXaiHost("https://api.x.ai.evil.com/v1"))
        assertFalse(isXaiHost("https://x.ai.example.com/v1"))
    }

    @Test
    fun `baseUrl 无法解析时按非 grok 处理`() {
        assertFalse(isXaiHost(""))
        assertFalse(isXaiHost("api.x.ai"))
        assertFalse(isXaiHost("本地代理"))
    }

    @Test
    fun `auto 不写入请求体且大小写不敏感`() {
        assertFalse(shouldSendImageSize("auto", isGrok = false))
        assertFalse(shouldSendImageSize("AUTO", isGrok = false))
        assertFalse(shouldSendImageSize("Auto", isGrok = false))
        assertFalse(shouldSendImageSize("", isGrok = false))
    }

    @Test
    fun `具体尺寸照常写入`() {
        assertTrue(shouldSendImageSize("1024x1024", isGrok = false))
        assertTrue(shouldSendImageSize("1792x1024", isGrok = false))
    }

    @Test
    fun `grok 端点上任何尺寸都不写入`() {
        assertFalse(shouldSendImageSize("1024x1024", isGrok = true))
        assertFalse(shouldSendImageSize("auto", isGrok = true))
    }
}
