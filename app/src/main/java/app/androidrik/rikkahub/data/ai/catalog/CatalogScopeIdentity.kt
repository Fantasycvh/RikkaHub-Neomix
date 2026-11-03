package app.androidrik.rikkahub.data.ai.catalog

import app.androidrik.ai.provider.*
import app.androidrik.rikkahub.data.datastore.GlobalProxySetting
import app.androidrik.rikkahub.data.ai.account.withLegacyCatalogSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Hash only catalog request inputs. No key/token/header value is persisted in the resulting scope. */
internal fun catalogScopeFingerprint(provider: ProviderSetting, globalProxy: GlobalProxySetting, salt: ByteArray): String {
    // Preserve duplicates/order too: account adapters send Headers directly, while API-key
    // adapters apply last-value-wins. Hash raw inputs without validating them or losing scope.
    val headers = provider.customHeaders.filter { it.name.isNotBlank() }
    val fields = buildJsonObject {
        put("providerId", provider.id.toString())
        put("type", when (provider) {
            is ProviderSetting.OpenAI -> "openai"
            is ProviderSetting.Google -> "google"
            is ProviderSetting.Claude -> "claude"
            is ProviderSetting.ChatGPT -> "chatgpt"
            is ProviderSetting.Grok -> "grok"
        })
        put("headers", JsonArray(headers.map {
            JsonArray(listOf(JsonPrimitive(it.name.lowercase()), JsonPrimitive(it.value)))
        }))
        put("proxy", Json.encodeToJsonElement(ProviderProxy.serializer(), provider.proxy))
        if (provider.proxy == ProviderProxy.Inherit) put("globalProxy", Json.encodeToJsonElement(globalProxy))
        when (provider) {
            is ProviderSetting.ApiKey -> {
                put("baseUrl", provider.baseUrl)
                put("key", provider.effectiveApiKeys.split(Regex("[\\s,]+" )).firstOrNull { it.isNotBlank() }.orEmpty())
                if (provider is ProviderSetting.Google) {
                    put("vertex", provider.vertexAI)
                    put("serviceAccount", provider.useServiceAccount)
                    put("privateKey", provider.privateKey)
                    put("email", provider.serviceAccountEmail)
                    put("project", provider.projectId)
                    put("location", provider.location)
                }
            }
            is ProviderSetting.Account -> {
                put("session", provider.credential?.withLegacyCatalogSession()?.loginSessionId)
                put("account", provider.credential?.accountId)
            }
        }
    }
    return Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(salt, "HmacSHA256"))
        doFinal(fields.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
