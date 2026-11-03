package app.androidrik.ai.provider

import kotlinx.serialization.Serializable

/**
 * 账号登录得到的 OAuth 凭据（对齐上游 #2186）。
 *
 * 这是**明文模型**：ai 层 provider 直接拿它发请求。落盘时由 app 层的账号管理负责
 * 加密敏感字段（本仓用 `SecretCipher`，与旧 `CodexSubscriptionAccount` 一致），
 * 其它字段（email/plan）仅用于界面展示。
 */
@Serializable
data class OAuthCredential(
    val accessToken: String,
    val refreshToken: String? = null,
    // access token 过期时间（毫秒时间戳），0 表示未知
    val expiresAt: Long = 0L,
    // 厂商侧的账号/工作区 ID，ChatGPT 请求需要带上
    val accountId: String? = null,
    // 以下仅用于界面展示
    val email: String? = null,
    val plan: String? = null,
    /** Local login epoch; token renewal preserves it, new sign-in replaces it. */
    val loginSessionId: String? = null,
) {
    override fun toString(): String =
        "OAuthCredential(accessToken=${accessToken.masked()}, refreshToken=${refreshToken?.masked()}, " +
            "expiresAt=$expiresAt, accountId=$accountId, email=$email, plan=$plan)"

    private fun String.masked(): String = if (length <= 8) "***" else "${take(4)}***${takeLast(4)}"
}
