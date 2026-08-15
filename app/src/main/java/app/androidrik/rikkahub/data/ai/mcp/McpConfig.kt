package app.androidrik.rikkahub.data.ai.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import app.androidrik.ai.core.InputSchema
import kotlin.uuid.Uuid

@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList(),
    val oauth: McpOAuthState? = null,
    val clientCertificate: McpClientCertificate = McpClientCertificate(),
)

/**
 * MCP 客户端证书（mTLS 双向认证，上游 #1687）。
 *
 * - format: `P12`（PKCS#12，certData 为 .p12 文件 base64）或 `PEM`（certData 为证书，keyData 为私钥，均 base64）
 * - password: P12 文件密码或 PEM 私钥密码（无则空）
 * - 敏感字段脱敏 toString，避免证书/私钥随配置打印到日志
 */
@Serializable
data class McpClientCertificate(
    val enabled: Boolean = false,
    val format: String = "P12",
    val certData: String = "",
    val keyData: String = "",
    val password: String = "",
) {
    val isConfigured: Boolean get() = certData.isNotBlank() && (format == "P12" || keyData.isNotBlank())

    override fun toString(): String =
        "McpClientCertificate(enabled=$enabled, format=$format, cert=${certData.masked()}, " +
            "key=${keyData.masked()}, password=${password.masked()})"

    private fun String?.masked(): String = when {
        this == null || isBlank() -> "***"
        else -> "***(${length})"
    }
}

/**
 * OAuth 2.1 授权状态，遵循 MCP 授权规范 (2025-11-25)。
 *
 * 持久化了动态客户端注册结果、授权服务器端点以及令牌，用于对需要
 * OAuth 授权的 MCP Server 注入 `Authorization: Bearer` 请求头并支持刷新。
 */
@Serializable
data class McpOAuthState(
    val enabled: Boolean = false,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val registrationEndpoint: String? = null,
    val scope: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L, // epoch millis, 0 表示未知/不过期
) {
    val isAuthorized: Boolean get() = !accessToken.isNullOrBlank()

    // 脱敏 toString，避免 client_secret / token 随 config 打印到日志
    override fun toString(): String =
        "McpOAuthState(enabled=$enabled, clientId=$clientId, clientSecret=${clientSecret.masked()}, " +
            "authorizationEndpoint=$authorizationEndpoint, tokenEndpoint=$tokenEndpoint, " +
            "registrationEndpoint=$registrationEndpoint, scope=$scope, " +
            "accessToken=${accessToken.masked()}, refreshToken=${refreshToken.masked()}, expiresAt=$expiresAt)"

    private fun String?.masked(): String = when {
        this == null -> "null"
        isBlank() -> "***"
        else -> "***(${length})"
    }
}

@Serializable
data class McpTool(
    val enable: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val inputSchema: InputSchema? = null,
    val needsApproval: Boolean = false
)

@Serializable
sealed class McpServerConfig {
    abstract val id: Uuid
    abstract val commonOptions: McpCommonOptions

    abstract fun clone(
        id: Uuid = this.id,
        commonOptions: McpCommonOptions = this.commonOptions
    ): McpServerConfig

    @Serializable
    @SerialName("sse")
    data class SseTransportServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }

    @Serializable
    @SerialName("streamable_http")
    data class StreamableHTTPServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }
}

/** MCP Server 的连接地址（作为 OAuth 的 canonical resource 标识）。 */
val McpServerConfig.serverUrl: String
    get() = when (this) {
        is McpServerConfig.SseTransportServer -> url
        is McpServerConfig.StreamableHTTPServer -> url
    }
