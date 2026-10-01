package app.androidrik.ai.util

import app.androidrik.ai.provider.CustomHeader
import app.androidrik.ai.provider.ProviderSetting
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义请求头的合并与**去重**语义（上游 `654a5e1f`）。
 *
 * 为什么值得单测：旧实现是「请求级头 `.headers()` 铺一遍 + 提供商级头逐条 `addHeader()`」，
 * 同名头在请求上会出现两个值；而链路上同名头出现两次时，服务端取哪个是不确定的
 * （不少中转站直接判非法）。这里钉住新旧两种失效形态都不再出现。
 */
class RequestHeadersTest {

    private fun provider(vararg headers: Pair<String, String>) = ProviderSetting.OpenAI(
        customHeaders = headers.map { CustomHeader(it.first, it.second) }
    )

    @Test
    fun `没有请求级头时提供商级头照常生效`() {
        val headers = provider("User-Agent" to "provider-agent", "X-Title" to "provider-title")
            .mergeCustomHeaders()

        assertEquals(listOf("provider-agent"), headers.values("User-Agent"))
        assertEquals(listOf("provider-title"), headers.values("X-Title"))
    }

    @Test
    fun `请求级头覆盖同名的提供商级头（大小写不敏感），其余提供商级头保留`() {
        val headers = provider("User-Agent" to "provider-agent", "X-Title" to "provider-title")
            .mergeCustomHeaders(listOf(CustomHeader("user-agent", "model-agent")))

        assertEquals(listOf("model-agent"), headers.values("User-Agent"))
        assertEquals(listOf("provider-title"), headers.values("X-Title"))
    }

    @Test
    fun `命名为空或空白的头被忽略`() {
        assertEquals(0, provider("" to "value").mergeCustomHeaders().size)
        assertEquals(0, provider("   " to "value").mergeCustomHeaders().size)
        assertEquals(
            0,
            ProviderSetting.OpenAI().mergeCustomHeaders(listOf(CustomHeader("", "x"))).size
        )
    }

    @Test
    fun `应用后每个名字只有一个值——不会与内置头重复`() {
        val request = Request.Builder()
            .url("https://example.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer built-in-key")
            .addHeader("Content-Type", "application/json")
            .applyCustomHeaders(
                provider("Authorization" to "Bearer custom-key", "X-Extra" to "1"),
                listOf(CustomHeader("content-type", "text/plain")),
            )
            .build()

        // 自定义头盖掉内置头：只有一个值，且是用户写的那个
        assertEquals("Bearer custom-key", request.header("Authorization"))
        assertEquals(listOf("Bearer custom-key"), request.headers.values("Authorization"))
        assertEquals("text/plain", request.header("Content-Type"))
        assertEquals(listOf("text/plain"), request.headers.values("Content-Type"))
        // 没冲突的头照常带上
        assertEquals("1", request.header("X-Extra"))
    }

    @Test
    fun `同一份列表里重复写同一个名字时，最后一个生效`() {
        // 「同名只保留一个值」在链路上是硬要求；单份列表里写重复名字是用户笔误，
        // 这里取后者（与「后写的覆盖先写的」一致），而不是把两个值都发出去
        val request = Request.Builder()
            .url("https://example.com")
            .applyCustomHeaders(provider("X-A" to "first", "X-A" to "second"))
            .build()

        assertEquals(listOf("second"), request.headers.values("X-A"))
    }
}
