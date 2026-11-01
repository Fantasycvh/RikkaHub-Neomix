package app.androidrik.ai.provider

import android.content.Context
import app.androidrik.ai.provider.providers.ClaudeProvider
import app.androidrik.ai.provider.providers.GoogleProvider
import app.androidrik.ai.provider.providers.OpenAIProvider
import app.androidrik.ai.provider.providers.chatgpt.ChatGPTProvider
import app.androidrik.ai.provider.providers.grok.GrokProvider
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(
    client: OkHttpClient,
    context: Context,
    /** P4 账号层：ChatGPT / Grok 的凭据来源（app 层 ProviderAccountManager 实现） */
    accountCredentials: AccountCredentialProvider,
) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()

    init {
        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))
        // 账号类：请求地址写死，鉴权走 OAuth 凭据 + 401 自动刷新重试
        registerProvider("chatgpt", ChatGPTProvider(client, accountCredentials))
        registerProvider("grok", GrokProvider(client, accountCredentials))
    }

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
            is ProviderSetting.ChatGPT -> getProvider("chatgpt")
            is ProviderSetting.Grok -> getProvider("grok")
        } as Provider<T>
    }
}
