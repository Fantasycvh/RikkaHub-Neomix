package app.androidrik.ai.util

import android.util.Log
import app.androidrik.ai.provider.ProviderProxy
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy

private const val TAG = "ProxyUtils"

/**
 * 按 [ProviderProxy] 语义给 client 配置代理（两层代理设计，见 `docs/design-proxy-two-layer.md` §二）。
 *
 * | 配置 | 行为 |
 * |---|---|
 * | [ProviderProxy.Inherit] | **返回原 client**（不配置）→ app 层共享 client 的全局 ProxySelector 自然生效 |
 * | [ProviderProxy.None] | 显式直连（`Proxy.NO_PROXY`，屏蔽全局） |
 * | [ProviderProxy.Http] | 自身 HTTP 代理优先 |
 * | [ProviderProxy.Socks5] | 自身 SOCKS 代理优先（认证由 app 层 JVM Authenticator 处理） |
 */
fun OkHttpClient.configureClientWithProxy(proxyConfig: ProviderProxy): OkHttpClient {
    return when (proxyConfig) {
        // 跟随全局：什么都不做。全局层只装在 app 的共享 client 上，ai 模块无需感知其存在。
        is ProviderProxy.Inherit -> this

        is ProviderProxy.None -> this.newBuilder().proxy(Proxy.NO_PROXY).build()

        is ProviderProxy.Http -> {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyConfig.address, proxyConfig.port))
            val clientBuilder = this.newBuilder().proxy(proxy)
            Log.d(TAG, "configureClientWithProxy: HTTP $proxy")

            // 认证启用条件：用户名非空即可（与上游一致；旧实现要求用户名密码都非空，
            // 会把「有用户名、空密码」的代理静默退化成匿名连接而 407）
            val username = proxyConfig.username
            val password = proxyConfig.password
            if (!username.isNullOrEmpty()) {
                clientBuilder.proxyAuthenticator { _: Route?, response: Response ->
                    val credential = Credentials.basic(username, password.orEmpty())
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }

            clientBuilder.build()
        }

        is ProviderProxy.Socks5 -> {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyConfig.address, proxyConfig.port))
            Log.d(TAG, "configureClientWithProxy: SOCKS5 $proxy")
            // SOCKS 认证不走 proxyAuthenticator（那只是 HTTP CONNECT 层），
            // 由 app 层注册的 java.net.Authenticator 按 host:port 提供凭据。
            this.newBuilder().proxy(proxy).build()
        }
    }
}
