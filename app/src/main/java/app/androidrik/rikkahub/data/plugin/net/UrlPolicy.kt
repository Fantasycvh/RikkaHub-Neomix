package app.androidrik.rikkahub.data.plugin.net

import java.net.InetAddress
import java.util.Locale

/**
 * JS 插件出站 URL 的纯策略(无网络、无 IO),单独成对象是为了能脱离网关单测。
 *
 * 两道互补的防线:
 * - [hostAllowed]:**正向**白名单(插件清单里声明的 `networkHosts`),默认拒绝;
 * - [isBlockedHost] / [isBlockedAddress]:**反向**黑名单(内网/环回/链路本地/保留地址),
 *   即使白名单写错/被绕过也不放行。
 *
 * 只做"地址长得像什么"的判断;**域名 → IP 的解析**在 [PluginHttpGateway] 里做,
 * 解析结果交给 [isBlockedAddress] 复核(DNS rebinding 基础防护)。
 */
object UrlPolicy {

    private const val WILDCARD_PREFIX = "*."

    /**
     * host 是否命中白名单。
     *
     * 规则:
     * - 精确匹配(大小写不敏感,忽略末尾的 `.`/`[]`,便于 IPv6 与 FQDN 变体);
     * - `*.example.com` 只匹配**子域**(任意层级),不匹配裸域 `example.com`;
     * - 空 host / 空白名单一律 false(默认拒绝)。
     */
    fun hostAllowed(host: String, allowedHosts: Set<String>): Boolean {
        val normalizedHost = normalizeHost(host)
        if (normalizedHost.isEmpty()) return false
        return allowedHosts.any { raw ->
            val entry = normalizeHost(raw)
            when {
                entry.isEmpty() -> false
                entry.startsWith(WILDCARD_PREFIX) -> {
                    val suffix = entry.removePrefix(WILDCARD_PREFIX)
                    suffix.isNotEmpty() && normalizedHost.endsWith(".$suffix")
                }
                else -> entry == normalizedHost
            }
        }
    }

    /**
     * 是否命中"禁止出站"的 host。
     *
     * 覆盖:localhost 及其变体(含 `localhost.`、`*.localhost`)、IPv4 的环回/私网/链路本地/
     * 运营商级 NAT/保留段、IPv6 的 `::1`/`::`/`fc00::/7`/`fe80::/10`、IPv4-mapped IPv6。
     *
     * 对**数字型 host 的歧义写法**(`127.1`、`2130706433`、`0x7f.0.0.1`、带前导零的段)
     * 采取 fail-closed:直接视为禁止,避免不同解析器/平台把同一字符串解释成不同地址。
     *
     * 普通域名(需要 DNS 才知道指向)返回 false;调用方必须再做 [isBlockedAddress] 复核。
     */
    fun isBlockedHost(host: String): Boolean {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty()) return true
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true

        val literal = parseIpLiteral(normalized)
        if (literal != null) {
            // 前导零/少段等歧义 IPv4 写法:十进制与八进制/整数解释可能不同,fail-closed
            if (!normalized.contains(':') && hasAmbiguousIpv4Notation(normalized)) return true
            return isBlockedAddress(literal)
        }
        // 看起来像 IPv4 变体却解析不出标准四段点分形式(如 `127.1`、`2130706433`、`0x7f.0.0.1`)
        return looksLikeIpv4Variant(normalized)
    }

    /**
     * 已解析出的 IP 是否属于禁止出站范围。
     *
     * 供网关在 DNS 解析后逐地址复核(解析出**任一**命中地址即拒绝)。
     */
    internal fun isBlockedAddress(address: InetAddress): Boolean {
        // 标准库的语义判断先过一遍:环回 / 任意 / 链路本地 / 站点本地 / 组播
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        return when (bytes.size) {
            4 -> isBlockedIpv4(bytes)
            16 -> isBlockedIpv6(bytes)
            // 未知格式:不认识的地址一律不放行
            else -> true
        }
    }

    // ---- 归一化与字面量解析(不触网) ----

    private fun normalizeHost(raw: String): String {
        var host = raw.trim().lowercase(Locale.ROOT)
        if (host.length >= 2 && host.startsWith('[') && host.endsWith(']')) {
            host = host.substring(1, host.length - 1)
        }
        return host.trimEnd('.')
    }

    /**
     * 把 IP 字面量解析成 [InetAddress];不是字面量时返回 null。
     *
     * 刻意避开 `InetAddress.getByName` 对**普通域名**的调用(那会触发 DNS):
     * IPv4 只认严格四段点分十进制;IPv6 只在字符串含 `:` 时交给 JDK —— JDK 对含 `:`
     * 的非法串只抛 [java.net.UnknownHostException],不会发起 DNS 查询。
     */
    private fun parseIpLiteral(host: String): InetAddress? {
        if (host.contains(':')) {
            // IPv6(含 `::ffff:a.b.c.d` 映射形式);非法串只失败、不解析域名
            return runCatching { InetAddress.getByName(host) }.getOrNull()
        }
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[index] = value.toByte()
        }
        return InetAddress.getByAddress(bytes)
    }

    private fun hasAmbiguousIpv4Notation(host: String): Boolean =
        host.split('.').any { part -> part.length > 1 && part.startsWith('0') }

    private fun looksLikeIpv4Variant(host: String): Boolean {
        if (host.isEmpty()) return false
        // 全数字/点:`127.1`、`2130706433`、`1.2.3`
        if (host.all { it in '0'..'9' || it == '.' }) return true
        // 0x 前缀十六进制:`0x7f000001`、`0x7f.0.0.1`
        if (host.startsWith("0x")) {
            val rest = host.substring(2)
            return rest.isNotEmpty() && rest.all { it in '0'..'9' || it in 'a'..'f' || it == '.' }
        }
        return false
    }

    // ---- 网段判定 ----

    private fun isBlockedIpv4(bytes: ByteArray): Boolean {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        return when {
            b0 == 0 -> true // 0.0.0.0/8 "本网络"
            b0 == 10 -> true // 10.0.0.0/8 私网
            b0 == 127 -> true // 127.0.0.0/8 环回
            b0 == 169 && b1 == 254 -> true // 169.254.0.0/16 链路本地
            b0 == 172 && b1 in 16..31 -> true // 172.16.0.0/12 私网
            b0 == 192 && b1 == 168 -> true // 192.168.0.0/16 私网
            b0 == 100 && b1 in 64..127 -> true // 100.64.0.0/10 运营商级 NAT
            b0 == 192 && b1 == 0 && b2 == 0 -> true // 192.0.0.0/24 IETF 协议保留
            b0 == 198 && b1 in 18..19 -> true // 198.18.0.0/15 基准测试
            b0 >= 224 -> true // 224.0.0.0/4 组播 + 240.0.0.0/4 保留
            else -> false
        }
    }

    private fun isBlockedIpv6(bytes: ByteArray): Boolean {
        // IPv4-mapped ::ffff:a.b.c.d:按 IPv4 规则判断(部分 JVM 直接返回 Inet4Address,这里兜底)
        if (bytes.take(10).all { it == 0.toByte() } &&
            (bytes[10].toInt() and 0xFF) == 0xFF &&
            (bytes[11].toInt() and 0xFF) == 0xFF
        ) {
            return isBlockedIpv4(bytes.copyOfRange(12, 16))
        }
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        return when {
            b0 == 0xFC || b0 == 0xFD -> true // fc00::/7 唯一本地
            b0 == 0xFE && (b1 and 0xC0) == 0x80 -> true // fe80::/10 链路本地
            else -> false
        }
    }
}
