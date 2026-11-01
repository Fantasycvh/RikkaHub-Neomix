package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.effectiveApiKeys
import app.androidrik.ai.util.KeyRoulette
import okhttp3.Request

/**
 * OpenAI 系列请求的统一鉴权入口：按 [KeyRoulette] 选 key（多 key 顺序/LRU 语义不变）。
 *
 * ChatGPT/Grok 订阅走账号类 provider（`providers/AccountAuth.kt` 的 `configureAccountAuth`），
 * 不再经过这里；旧的设备码订阅链路（`authType = CHATGPT_SUBSCRIPTION`）已在 P5 删除。
 */
class OpenAIRequestAuthenticator(
    private val keyRoulette: KeyRoulette,
) {
    /** 同步写入 API Key 鉴权头。 */
    fun authenticateApiKey(
        builder: Request.Builder,
        providerSetting: ProviderSetting.OpenAI,
    ) {
        builder.addHeader(
            "Authorization",
            "Bearer ${keyRoulette.next(providerSetting.effectiveApiKeys, providerSetting.id.toString())}"
        )
    }
}
