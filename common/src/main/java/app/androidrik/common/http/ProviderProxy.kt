package app.androidrik.common.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 单个提供商的代理配置。
 *
 * 语义（两层代理设计，见 `docs/design/design-proxy-two-layer.md`）：
 * - [Inherit]：跟随全局代理设置（**新 provider 的默认值**；app 层不动 client，由共享 client 的
 *   ProxySelector 决定走向）——消费模块无需知道全局设置是否存在。
 * - [None]：**显式直连**（即使 app 配了全局代理也不走）。语义由旧「无代理」收窄而来。
 * - [Http] / [Socks5]：自身配置优先，覆盖全局。
 *
 * 向后兼容：旧数据里显式序列化的 `"none"` 仍反序列化为 [None]（直连），故既有 provider 行为
 * 不变；只有新建 provider（默认 [Inherit]）或用户手动改选才继承全局。
 *
 * 原定义在 ai 模块（M3 收敛时下沉到 common，mediagen 同样消费，序列化 `@SerialName` 不变，
 * 既有存储数据无感）。
 */
@Serializable
sealed class ProviderProxy {
    /** 跟随全局代理（新默认）——app 层共享 client 的 ProxySelector 生效。 */
    @Serializable
    @SerialName("inherit")
    object Inherit : ProviderProxy()

    /** 显式直连（屏蔽全局代理）。 */
    @Serializable
    @SerialName("none")
    object None : ProviderProxy()

    @Serializable
    @SerialName("http")
    data class Http(
        val address: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) : ProviderProxy()

    /** SOCKS5 代理（认证在 socket 层，需 app 层的 JVM Authenticator 配合，见设计文档 §三）。 */
    @Serializable
    @SerialName("socks5")
    data class Socks5(
        val address: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) : ProviderProxy()
}
