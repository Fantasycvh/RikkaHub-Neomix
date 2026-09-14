package app.androidrik.rikkahub.data.network

import app.androidrik.rikkahub.data.datastore.ProxyType
import app.androidrik.rikkahub.data.datastore.SettingsStore
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 全局代理层（挂在 app 的**共享 OkHttpClient** 上；ai 模块完全不知道它存在——
 * provider 选 `Inherit` 时不做任何配置，本层自然生效）。
 *
 * 无全局代理时**回落系统 ProxySelector**（显式尊重 Android 系统代理设置），
 * 系统也没有则 [Proxy.NO_PROXY]。
 */
class SettingsProxySelector(
    private val settingsStore: SettingsStore,
    private val systemProxySelector: ProxySelector? = ProxySelector.getDefault(),
) : ProxySelector() {

    override fun select(uri: URI): List<Proxy> {
        globalProxyOrNull()?.let { endpoint ->
            val type = if (endpoint.type == ProxyType.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
            return listOf(Proxy(type, InetSocketAddress.createUnresolved(endpoint.host, endpoint.port)))
        }
        return runCatching { systemProxySelector?.select(uri) }
            .getOrNull()
            .orEmpty()
            .ifEmpty { listOf(Proxy.NO_PROXY) }
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, exception: IOException) {
        // 只在我们「没有接管」时把失败转给系统选择器，避免双重上报
        if (globalProxyOrNull() == null) {
            systemProxySelector?.connectFailed(uri, socketAddress, exception)
        }
    }

    private fun globalProxyOrNull(): ProxyEndpoint? =
        settingsStore.settingsFlow.value.globalProxy.toEndpointOrNull()
}

/**
 * SOCKS5 认证（JVM 全局单例）：SOCKS 的认证发生在 socket 层，OkHttp 的 `proxyAuthenticator`
 * 覆盖不到，必须走 `java.net.Authenticator`。凭据来源为 [SocksCredentialRegistry]
 * （全局 + 各 provider 自配），按 `requestingHost:requestingPort` 精确匹配。
 */
class RikkaProxyAuthenticator(
    private val registry: SocksCredentialRegistry,
) : java.net.Authenticator() {
    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val endpoint = registry.lookup(requestingHost, requestingPort) ?: return null
        val username = endpoint.username ?: return null
        return PasswordAuthentication(username, endpoint.password.orEmpty().toCharArray())
    }
}

/**
 * 全局 **HTTP** 代理认证（`Proxy-Authorization` 头）——这是标准 HTTP CONNECT 层，
 * 与 SOCKS 不同，可以走 OkHttp 的 `proxyAuthenticator`。
 *
 * 仅对「当前生效的全局 HTTP 代理」加凭据，其它路由（无代理 / SOCKS / 系统代理）不动。
 */
class SettingsProxyAuthenticator(
    private val settingsStore: SettingsStore,
) : okhttp3.Authenticator {
    override fun authenticate(route: okhttp3.Route?, response: okhttp3.Response): okhttp3.Request? {
        val endpoint = settingsStore.settingsFlow.value.globalProxy.toEndpointOrNull() ?: return null
        if (endpoint.type != ProxyType.HTTP) return null
        val username = endpoint.username ?: return null
        // 已带过凭据却仍被要求认证 → 说明凭据不对，避免无限重试
        if (response.request.header("Proxy-Authorization") != null) return null
        return response.request.newBuilder()
            .header(
                "Proxy-Authorization",
                okhttp3.Credentials.basic(username, endpoint.password.orEmpty(), Charsets.UTF_8),
            )
            .build()
    }
}
