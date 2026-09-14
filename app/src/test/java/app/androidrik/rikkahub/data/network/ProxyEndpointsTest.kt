package app.androidrik.rikkahub.data.network

import app.androidrik.ai.provider.ProviderProxy
import app.androidrik.rikkahub.data.datastore.GlobalProxySetting
import app.androidrik.rikkahub.data.datastore.ProxyType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 两层代理的应用层纯逻辑（`docs/design-proxy-two-layer.md`）：
 * 全局设置/ provider 自配 → 端点映射，以及多源 SOCKS5 凭据查表。
 */
class ProxyEndpointsTest {

    // ---- GlobalProxySetting → endpoint ----

    @Test
    fun `unconfigured global proxy yields null`() {
        assertNull(GlobalProxySetting().toEndpointOrNull())
        // 选了类型但没填地址/端口也视为未配置
        assertNull(GlobalProxySetting(type = ProxyType.HTTP, host = "", port = 8080).toEndpointOrNull())
        assertNull(GlobalProxySetting(type = ProxyType.HTTP, host = "h", port = 0).toEndpointOrNull())
        assertNull(GlobalProxySetting(type = ProxyType.HTTP, host = "h", port = 65536).toEndpointOrNull())
    }

    @Test
    fun `configured global proxy maps to endpoint and trims blanks`() {
        val ep = GlobalProxySetting(
            type = ProxyType.SOCKS5, host = " 127.0.0.1 ", port = 1080,
            username = " u ", password = " p ",
        ).toEndpointOrNull()!!

        assertEquals(ProxyType.SOCKS5, ep.type)
        assertEquals("127.0.0.1", ep.host)
        assertEquals(1080, ep.port)
        assertEquals("u", ep.username)
        assertEquals("p", ep.password)
    }

    @Test
    fun `blank credentials become null not empty string`() {
        val ep = GlobalProxySetting(type = ProxyType.HTTP, host = "h", port = 1, username = "  ", password = "").toEndpointOrNull()!!
        assertNull("空用户名不应产生认证", ep.username)
        assertNull(ep.password)
    }

    // ---- provider Socks5 → endpoint ----

    @Test
    fun `only socks5 provider proxies map to endpoint`() {
        assertNull(ProviderProxy.Http("h", 1).toSocksEndpointOrNull())
        assertNull(ProviderProxy.None.toSocksEndpointOrNull())
        assertNull(ProviderProxy.Inherit.toSocksEndpointOrNull())
        // SOCKS5 但地址/端口非法 → null
        assertNull(ProviderProxy.Socks5("", 1080).toSocksEndpointOrNull())
        assertNull(ProviderProxy.Socks5("h", 0).toSocksEndpointOrNull())

        val ok = ProviderProxy.Socks5("h", 1080, "u", "p").toSocksEndpointOrNull()!!
        assertEquals(ProxyType.SOCKS5, ok.type)
        assertEquals(1080, ok.port)
    }

    // ---- 多源凭据表 ----

    @Test
    fun `registry holds global socks5 credentials`() {
        val registry = SocksCredentialRegistry()
        registry.update(
            globalProxy = GlobalProxySetting(ProxyType.SOCKS5, "10.0.0.1", 1080, "gu", "gp"),
            providers = emptyList(),
        )
        assertEquals("gu", registry.lookup("10.0.0.1", 1080)?.username)
        assertNull("端口不匹配不应命中", registry.lookup("10.0.0.1", 1081))
    }

    @Test
    fun `registry holds provider socks5 credentials alongside global`() {
        val registry = SocksCredentialRegistry()
        registry.update(
            globalProxy = GlobalProxySetting(ProxyType.SOCKS5, "10.0.0.1", 1080, "gu", "gp"),
            providers = listOf(ProviderProxy.Socks5("10.0.0.2", 1081, "pu", "pp")),
        )
        assertEquals("gu", registry.lookup("10.0.0.1", 1080)?.username)
        assertEquals("pu", registry.lookup("10.0.0.2", 1081)?.username)
    }

    @Test
    fun `provider credentials win on the same host and port`() {
        val registry = SocksCredentialRegistry()
        registry.update(
            globalProxy = GlobalProxySetting(ProxyType.SOCKS5, "10.0.0.1", 1080, "global", "gp"),
            providers = listOf(ProviderProxy.Socks5("10.0.0.1", 1080, "provider", "pp")),
        )
        assertEquals("provider", registry.lookup("10.0.0.1", 1080)?.username)
    }

    @Test
    fun `registry ignores non socks5 global types`() {
        val registry = SocksCredentialRegistry()
        registry.update(
            globalProxy = GlobalProxySetting(ProxyType.HTTP, "10.0.0.1", 8080, "u", "p"),
            providers = emptyList(),
        )
        assertNull("HTTP 代理不走 SOCKS 凭据表", registry.lookup("10.0.0.1", 8080))
    }

    @Test
    fun `lookup normalizes host case and ipv6 brackets`() {
        val registry = SocksCredentialRegistry()
        registry.update(
            globalProxy = GlobalProxySetting(ProxyType.SOCKS5, "Proxy.Example.COM", 1080, "u", "p"),
            providers = emptyList(),
        )
        assertEquals("u", registry.lookup("proxy.example.com", 1080)?.username)
        assertEquals("u", registry.lookup("PROXY.EXAMPLE.COM", 1080)?.username)
    }

    @Test
    fun `update replaces previous table (removed provider no longer matches)`() {
        val registry = SocksCredentialRegistry()
        registry.update(GlobalProxySetting(), listOf(ProviderProxy.Socks5("h", 1080, "u", "p")))
        assertEquals("u", registry.lookup("h", 1080)?.username)

        // provider 被删除 / 改为其它代理后，旧凭据必须消失
        registry.update(GlobalProxySetting(), emptyList())
        assertNull(registry.lookup("h", 1080))
    }
}
