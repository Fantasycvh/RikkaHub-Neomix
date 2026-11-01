package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.ProviderSetting

/**
 * ChatGPT 订阅访问令牌（明文）。只在内存与请求头里出现，不落盘、不打日志。
 */
data class CodexToken(
    val accessToken: String,
    /** ChatGPT 账号 id，用于请求头 `ChatGPT-Account-ID` */
    val accountId: String,
)

/**
 * 订阅令牌提供者：由 app 层实现（负责用 [app.androidrik.rikkahub.utils.SecretCipher] 解密、
 * 过期前刷新、并发去重），ai 层不感知存储格式与加密方式。
 *
 * 与上游 `AccountCredentialProvider` 同职责，但只保留本仓需要的最小面。
 */
fun interface OpenAICodexTokenProvider {
    /**
     * 返回可用令牌。实现方需保证：
     * - 已过期（或即将过期）时先刷新再返回；
     * - 刷新失败（`invalid_grant` 等）时抛出异常并清除本地凭据，由 UI 引导重新登录；
     * - [CodexToken.accessToken] 非空。
     */
    suspend fun getToken(providerSetting: ProviderSetting.OpenAI): CodexToken
}
