package app.androidrik.rikkahub.data.network

import app.androidrik.ai.provider.ProviderProxy
import app.androidrik.rikkahub.data.datastore.GlobalProxySetting
import app.androidrik.rikkahub.data.datastore.ProxyType

/**
 * 两层代理（`docs/design-proxy-two-layer.md`）的**共享工具**——不依赖 SettingsStore，
 * 纯函数 + 数据结构，便于单测。
 */

/** 一条 `host:port → 凭据` 记录；凭据为 null = 该代理无需认证。 */
data class ProxyEndpoint(
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
) {
    /** 归一化匹配键（host 大小写不敏感；IPv6 去方括号）。 */
    val key: String get() = "${host.trim().removeSurrounding("[", "]").lowercase()}:$port"
}

/** [GlobalProxySetting] → [ProxyEndpoint]；未配置返回 null。 */
fun GlobalProxySetting.toEndpointOrNull(): ProxyEndpoint? =
    if (!isConfigured) null
    else ProxyEndpoint(
        type = type,
        host = host.trim(),
        port = port,
        username = username.trim().ifBlank { null },
        // 与 username 一致地 trim：密码里的首尾空白几乎总是输入误触，且会静默认证失败
        password = password.trim().ifBlank { null },
    )

/** [ProviderProxy.Socks5] → [ProxyEndpoint]（只处理 SOCKS5；其余返回 null）。 */
fun ProviderProxy.toSocksEndpointOrNull(): ProxyEndpoint? {
    val socks = this as? ProviderProxy.Socks5 ?: return null
    if (socks.address.isBlank() || socks.port !in 1..65535) return null
    return ProxyEndpoint(
        type = ProxyType.SOCKS5,
        host = socks.address.trim(),
        port = socks.port,
        username = socks.username?.trim()?.ifBlank { null },
        password = socks.password?.trim()?.ifBlank { null },
    )
}

/**
 * 多源 SOCKS5 凭据表：全局设置 + 所有 provider 自配的 Socks5。
 *
 * 为什么需要多源：`java.net.Authenticator` 是**全局单例**（SOCKS 认证在 socket 层，
 * OkHttp 的 `proxyAuthenticator` 覆盖不到），只能注册一个并按 `host:port` 反查。
 * 后写入者覆盖同键（provider 自配优先于全局，与「自身优先」语义一致）。
 */
class SocksCredentialRegistry {
    @Volatile
    private var table: Map<String, ProxyEndpoint> = emptyMap()

    fun update(globalProxy: GlobalProxySetting, providers: Iterable<ProviderProxy>) {
        val next = LinkedHashMap<String, ProxyEndpoint>()
        globalProxy.toEndpointOrNull()
            ?.takeIf { it.type == ProxyType.SOCKS5 }
            ?.let { next[it.key] = it }
        providers.forEach { proxy ->
            proxy.toSocksEndpointOrNull()?.let { next[it.key] = it } // provider 覆盖全局同键
        }
        table = next
    }

    /** 按 host:port 精确匹配（大小写/方括号归一）。 */
    fun lookup(host: String?, port: Int): ProxyEndpoint? {
        val normalized = "${host.orEmpty().trim().removeSurrounding("[", "]").lowercase()}:$port"
        return table[normalized]
    }
}
