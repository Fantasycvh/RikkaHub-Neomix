package app.androidrik.rikkahub.data.plugin.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UrlPolicy] 的纯 JVM 单测(零网络、零 IO)。
 *
 * 锁住两件事:
 * - 白名单是"默认拒绝 + 精确/通配"语义,`*.example.com` **不能**匹配裸域;
 * - 内网/保留地址黑名单覆盖 IPv4 私网、IPv6 ULA/链路本地、localhost 变体与歧义数字写法。
 */
class UrlPolicyTest {

    // ---- hostAllowed:白名单语义 ----

    @Test
    fun `exact host matches`() {
        assertTrue(UrlPolicy.hostAllowed("api.example.com", setOf("api.example.com")))
    }

    @Test
    fun `different host does not match`() {
        assertFalse(UrlPolicy.hostAllowed("api.example.com", setOf("api.other.com")))
    }

    @Test
    fun `empty whitelist rejects everything`() {
        assertFalse(UrlPolicy.hostAllowed("api.example.com", emptySet()))
    }

    @Test
    fun `matching ignores case on both sides`() {
        assertTrue(UrlPolicy.hostAllowed("API.Example.COM", setOf("api.example.com")))
        assertTrue(UrlPolicy.hostAllowed("api.example.com", setOf("API.EXAMPLE.COM")))
    }

    @Test
    fun `trailing dot is normalized`() {
        assertTrue(UrlPolicy.hostAllowed("api.example.com.", setOf("api.example.com")))
        assertTrue(UrlPolicy.hostAllowed("api.example.com", setOf("api.example.com.")))
    }

    @Test
    fun `wildcard matches subdomain`() {
        assertTrue(UrlPolicy.hostAllowed("api.example.com", setOf("*.example.com")))
    }

    @Test
    fun `wildcard does not match the bare domain`() {
        assertFalse(UrlPolicy.hostAllowed("example.com", setOf("*.example.com")))
    }

    @Test
    fun `wildcard matches deep subdomain`() {
        assertTrue(UrlPolicy.hostAllowed("v1.api.example.com", setOf("*.example.com")))
    }

    @Test
    fun `wildcard does not match a lookalike suffix`() {
        assertFalse(UrlPolicy.hostAllowed("evil-example.com", setOf("*.example.com")))
        assertFalse(UrlPolicy.hostAllowed("example.com.evil.net", setOf("*.example.com")))
    }

    @Test
    fun `wildcard entry itself is case insensitive`() {
        assertTrue(UrlPolicy.hostAllowed("Api.Example.Com", setOf("*.Example.COM")))
    }

    @Test
    fun `empty host is rejected`() {
        assertFalse(UrlPolicy.hostAllowed("", setOf("api.example.com", "*.example.com")))
    }

    // ---- isBlockedHost:内网/保留地址 ----

    @Test
    fun `localhost and its variants are blocked`() {
        assertTrue(UrlPolicy.isBlockedHost("localhost"))
        assertTrue(UrlPolicy.isBlockedHost("LOCALHOST"))
        assertTrue(UrlPolicy.isBlockedHost("localhost."))
        assertTrue(UrlPolicy.isBlockedHost("api.localhost"))
        assertFalse(UrlPolicy.isBlockedHost("localhost.evil.com"))
    }

    @Test
    fun `ipv4 loopback and any-address are blocked`() {
        assertTrue(UrlPolicy.isBlockedHost("127.0.0.1"))
        assertTrue(UrlPolicy.isBlockedHost("127.255.255.254"))
        assertTrue(UrlPolicy.isBlockedHost("0.0.0.0"))
    }

    @Test
    fun `ipv4 private ranges are blocked`() {
        assertTrue(UrlPolicy.isBlockedHost("10.0.0.1"))
        assertTrue(UrlPolicy.isBlockedHost("10.255.255.255"))
        assertTrue(UrlPolicy.isBlockedHost("172.16.0.1"))
        assertTrue(UrlPolicy.isBlockedHost("172.31.255.255"))
        assertTrue(UrlPolicy.isBlockedHost("192.168.1.1"))
    }

    @Test
    fun `ipv4 link local cgnat and reserved ranges are blocked`() {
        assertTrue(UrlPolicy.isBlockedHost("169.254.169.254"))
        assertTrue(UrlPolicy.isBlockedHost("100.64.0.1"))
        assertTrue(UrlPolicy.isBlockedHost("192.0.0.1"))
        assertTrue(UrlPolicy.isBlockedHost("224.0.0.1"))
        assertTrue(UrlPolicy.isBlockedHost("255.255.255.255"))
    }

    @Test
    fun `ipv4 private range boundaries are respected`() {
        // 172.16/12 的两侧边界
        assertFalse(UrlPolicy.isBlockedHost("172.15.255.255"))
        assertFalse(UrlPolicy.isBlockedHost("172.32.0.1"))
        // 10/8 与 100.64/10 的两侧边界
        assertFalse(UrlPolicy.isBlockedHost("11.0.0.1"))
        assertFalse(UrlPolicy.isBlockedHost("100.128.0.1"))
        // 192.168/16 边界
        assertFalse(UrlPolicy.isBlockedHost("192.169.0.1"))
    }

    @Test
    fun `ipv6 loopback unique local and link local are blocked`() {
        assertTrue(UrlPolicy.isBlockedHost("::1"))
        assertTrue(UrlPolicy.isBlockedHost("[::1]"))
        assertTrue(UrlPolicy.isBlockedHost("::"))
        assertTrue(UrlPolicy.isBlockedHost("fc00::1"))
        assertTrue(UrlPolicy.isBlockedHost("fd12:3456:789a::1"))
        assertTrue(UrlPolicy.isBlockedHost("fe80::1"))
    }

    @Test
    fun `ipv4 mapped ipv6 addresses use ipv4 rules`() {
        assertTrue(UrlPolicy.isBlockedHost("::ffff:127.0.0.1"))
        assertTrue(UrlPolicy.isBlockedHost("::ffff:192.168.1.5"))
        assertFalse(UrlPolicy.isBlockedHost("::ffff:8.8.8.8"))
    }

    @Test
    fun `public ip and public domain names are not blocked by the hostname rule`() {
        assertFalse(UrlPolicy.isBlockedHost("8.8.8.8"))
        assertFalse(UrlPolicy.isBlockedHost("1.1.1.1"))
        assertFalse(UrlPolicy.isBlockedHost("2001:4860:4860::8888"))
        assertFalse(UrlPolicy.isBlockedHost("api.github.com"))
        assertFalse(UrlPolicy.isBlockedHost("example.com"))
    }

    @Test
    fun `ambiguous numeric ipv4 notations fail closed`() {
        // 不同解析器会把它们解释成不同地址(十进制/八进制/整数),一律拒绝
        assertTrue(UrlPolicy.isBlockedHost("127.1"))
        assertTrue(UrlPolicy.isBlockedHost("2130706433"))
        assertTrue(UrlPolicy.isBlockedHost("0x7f.0.0.1"))
        assertTrue(UrlPolicy.isBlockedHost("0x7f000001"))
        assertTrue(UrlPolicy.isBlockedHost("01.2.3.4"))
    }
}
