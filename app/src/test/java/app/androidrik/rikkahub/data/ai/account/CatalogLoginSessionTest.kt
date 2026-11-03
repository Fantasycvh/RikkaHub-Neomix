package app.androidrik.rikkahub.data.ai.account

import app.androidrik.ai.provider.*
import org.junit.Assert.*
import org.junit.Test

class CatalogLoginSessionTest {
    @Test fun `legacy session identity is stable and independent of provider display changes`() {
        val legacy = OAuthCredential("enc:access", "enc:refresh")
        assertEquals(legacy.withLegacyCatalogSession(), legacy.withLegacyCatalogSession())
        assertNotEquals(legacy.withLegacyCatalogSession().loginSessionId, legacy.copy(accessToken = "other").withLegacyCatalogSession().loginSessionId)
        val providers = listOf(ProviderSetting.ChatGPT(credential = legacy))
        val normalized = providers.ensureCatalogSessions(legacy = true)
        assertEquals(normalized, normalized.ensureCatalogSessions(legacy = true))
    }

    @Test fun `new and imported sessions receive separate epochs while ordinary writes preserve them`() {
        val providers = listOf(ProviderSetting.ChatGPT(credential = OAuthCredential("token")))
        val normalized = providers.ensureCatalogSessions()
        val session = (normalized.single() as ProviderSetting.Account).credential!!.loginSessionId
        assertNotNull(session)
        assertEquals(normalized, normalized.ensureCatalogSessions())
        assertNotEquals(session, (normalized.renewCatalogSessions().single() as ProviderSetting.Account).credential!!.loginSessionId)
    }

    @Test fun `credential cipher keeps the login epoch across encryption and decryption`() {
        val cipher = object : CredentialCipher {
            override fun encrypt(plaintext: String) = "v1:$plaintext"
            override fun decrypt(ciphertext: String) = ciphertext.removePrefix("v1:")
        }
        val original = OAuthCredential("access", "refresh", loginSessionId = "session")
        assertEquals(original, original.encrypted(cipher).decrypted(cipher))
    }
}
