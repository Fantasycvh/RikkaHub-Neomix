package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.OPENAI_CODEX_BASE_URL
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.util.KeyRoulette
import okhttp3.Request

/**
 * OpenAI 系列请求的统一鉴权入口。
 *
 * - [ProviderSetting.OpenAI.authType] = `API_KEY`：沿用 [KeyRoulette] 选 key（多 key 顺序/LRU
 *   语义不变），与改造前的 `keyRoulette.next(...)` 完全等价。
 * - `CHATGPT_SUBSCRIPTION`：从 [OpenAICodexTokenProvider] 取订阅令牌（由 app 层负责刷新与解密），
 *   附 `Authorization`、`ChatGPT-Account-ID`、`originator` 三个头。
 *
 * 订阅模式带**端点守卫**：令牌只发往官方 Codex 端点。用户若把 baseUrl 改成第三方中转，
 * 这里直接拒绝，而不是把 ChatGPT 凭据发出去（与「不覆写 baseUrl」的存储策略配套）。
 */
class OpenAIRequestAuthenticator(
    private val keyRoulette: KeyRoulette,
    private val codexTokenProvider: OpenAICodexTokenProvider? = null,
) {
    /** API Key 模式鉴权（同步，供只能同步构建请求的调用点使用）。 */
    fun authenticateApiKey(
        builder: Request.Builder,
        providerSetting: ProviderSetting.OpenAI,
    ) {
        builder.addHeader(
            "Authorization",
            "Bearer ${keyRoulette.next(providerSetting.effectiveApiKeys, providerSetting.id.toString())}"
        )
    }

    suspend fun authenticate(
        builder: Request.Builder,
        providerSetting: ProviderSetting.OpenAI,
        url: String,
    ) {
        if (!providerSetting.isChatGPTSubscription) {
            authenticateApiKey(builder, providerSetting)
            return
        }

        if (!url.startsWith(OPENAI_CODEX_BASE_URL)) {
            error("ChatGPT 订阅凭据只允许发送到官方 Codex 端点（$OPENAI_CODEX_BASE_URL），当前请求：$url")
        }
        val tokenProvider = codexTokenProvider
            ?: error("ChatGPT 订阅登录未初始化：请重新打开设置页后再试")
        val token = tokenProvider.getToken(providerSetting)
        builder
            .header("Authorization", "Bearer ${token.accessToken}")
            .header("ChatGPT-Account-ID", token.accountId)
            .header("originator", CODEX_ORIGINATOR)
    }
}
