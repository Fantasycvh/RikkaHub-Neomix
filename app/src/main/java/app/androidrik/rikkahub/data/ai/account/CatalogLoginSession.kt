package app.androidrik.rikkahub.data.ai.account

import app.androidrik.ai.provider.OAuthCredential
import app.androidrik.ai.provider.ProviderSetting
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Stable fallback for legacy on-disk encrypted credentials, before their next settings write. */
internal fun OAuthCredential.withLegacyCatalogSession(): OAuthCredential = if (!loginSessionId.isNullOrBlank()) this else copy(
    loginSessionId = "legacy-" + MessageDigest.getInstance("SHA-256")
        .digest(Json.encodeToString(this).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) },
)

internal fun List<ProviderSetting>.ensureCatalogSessions(legacy: Boolean = false): List<ProviderSetting> = map { provider ->
    if (provider is ProviderSetting.Account) {
        provider.withCredential(provider.credential?.let {
            if (!it.loginSessionId.isNullOrBlank()) it
            else if (legacy) it.withLegacyCatalogSession() else it.copy(loginSessionId = Uuid.random().toString())
        })
    } else provider
}

/** Imported credentials start a new local login epoch even when their provider UUID was reused. */
internal fun List<ProviderSetting>.renewCatalogSessions(): List<ProviderSetting> = map { provider ->
    if (provider is ProviderSetting.Account) provider.withCredential(
        provider.credential?.copy(loginSessionId = Uuid.random().toString()),
    ) else provider
}
