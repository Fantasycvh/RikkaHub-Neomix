package app.androidrik.ai.util

import app.androidrik.ai.provider.ProviderProxy
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Proxy

/**
 * 两层代理的 per-provider 语义与 JSON 兼容（见 `docs/design-proxy-two-layer.md`）。
 *
 * 纯 JVM 可测：代理类型/地址是 OkHttp 的公开属性，不需真实网络。
 */
class ProxyUtilsTest {

    private val json = Json { encodeDefaults = true }
    private val base = OkHttpClient()

    @Test
    fun `inherit returns the same client instance (global layer stays in charge)`() {
        // 关键：Inherit 必须「什么都不做」，这样 app 层共享 client 的全局 ProxySelector 才生效
        assertSame(base, base.configureClientWithProxy(ProviderProxy.Inherit))
    }

    @Test
    fun `none forces direct connection and overrides any inherited client proxy`() {
        val withProxy = base.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("10.0.0.1", 8080)))
            .build()

        val configured = withProxy.configureClientWithProxy(ProviderProxy.None)

        // 显式直连：即使原 client 有代理也要被屏蔽
        assertEquals(Proxy.NO_PROXY, configured.proxy)
    }

    @Test
    fun `http sets an HTTP proxy with the configured address and port`() {
        val configured = base.configureClientWithProxy(
            ProviderProxy.Http(address = "127.0.0.1", port = 7890)
        )

        val proxy = configured.proxy
        assertNotNull(proxy)
        assertEquals(Proxy.Type.HTTP, proxy!!.type())
        val addr = proxy.address() as java.net.InetSocketAddress
        assertEquals("127.0.0.1", addr.hostString)
        assertEquals(7890, addr.port)
    }

    @Test
    fun `socks5 sets a SOCKS proxy with the configured address and port`() {
        val configured = base.configureClientWithProxy(
            ProviderProxy.Socks5(address = "127.0.0.1", port = 1080, username = "u", password = "p")
        )

        val proxy = configured.proxy
        assertEquals(Proxy.Type.SOCKS, proxy?.type())
        val addr = proxy?.address() as java.net.InetSocketAddress
        assertEquals(1080, addr.port)
    }

    @Test
    fun `socks5 auth is not handled by proxy authenticator (JVM Authenticator does it)`() {
        // SOCKS 认证在 socket 层，proxyAuthenticator 只覆盖 HTTP CONNECT——
        // 这里断言「没装 proxyAuthenticator」，凭据由 app 层 java.net.Authenticator 提供
        val withAuth = base.configureClientWithProxy(
            ProviderProxy.Socks5("127.0.0.1", 1080, "user", "pass")
        )
        // OkHttp 的 proxyAuthenticator 永不为 null（默认 AuthenticatorNone）——
        // 断言「未被替换成自定义实现」，即 SOCKS5 不在此层做认证
        assertSame(base.proxyAuthenticator, withAuth.proxyAuthenticator)
    }

    // ---- JSON 兼容（迁移安全的关键）----

    @Test
    fun `legacy none still deserializes to None`() {
        // 旧数据显式写的 "none" 必须仍是「直连」语义，不能变成 Inherit
        val decoded = json.decodeFromString<ProviderProxy>("""{"type":"none"}""")
        assertSame(ProviderProxy.None, decoded)
    }

    @Test
    fun `inherit round-trips`() {
        val text = json.encodeToString<ProviderProxy>(ProviderProxy.Inherit)
        assertTrue("type tag should be inherit: $text", text.contains("inherit"))
        assertSame(ProviderProxy.Inherit, json.decodeFromString<ProviderProxy>(text))
    }

    @Test
    fun `socks5 round-trips with credentials`() {
        val value = ProviderProxy.Socks5("127.0.0.1", 1080, "u", "p")
        val decoded = json.decodeFromString<ProviderProxy>(json.encodeToString<ProviderProxy>(value))
        assertEquals(value, decoded)
    }
}
