package app.androidrik.rikkahub.data.sync.lan

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.androidrik.rikkahub.data.sync.sha256Hex
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Principal
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

internal object LanIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "rikkahub_lan_identity_v1"

    @Synchronized
    fun load(alias: String = ALIAS): LanCredentials {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
                        .setCertificateSubject(X500Principal("CN=RikkaHub LAN"))
                        .build(),
                )
            }.generateKeyPair()
        }
        val certificate = store.getCertificate(alias) as X509Certificate
        return LanCredentials(certificate, store.getKey(alias, null) as PrivateKey)
    }
}

internal class LanCredentials(
    private val certificate: X509Certificate,
    private val privateKey: PrivateKey,
) {
    val fingerprint: String = certificate.fingerprint()

    fun tlsContext(trust: X509TrustManager): SSLContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf(IdentityKeyManager()), arrayOf(trust), null)
    }

    private inner class IdentityKeyManager : X509KeyManager {
        override fun getClientAliases(keyType: String, issuers: Array<out Principal>?) = aliases(keyType)
        override fun getServerAliases(keyType: String, issuers: Array<out Principal>?) = aliases(keyType)
        override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: Socket?) =
            keyTypes.firstNotNullOfOrNull(::alias)
        override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?) = alias(keyType)
        override fun getCertificateChain(alias: String?) = if (alias == NAME) arrayOf(certificate) else null
        override fun getPrivateKey(alias: String?) = if (alias == NAME) privateKey else null
        private fun aliases(keyType: String) = alias(keyType)?.let { arrayOf(it) }
        private fun alias(keyType: String) = NAME.takeIf { keyType == "EC" || keyType == "EC_EC" }
    }

    private companion object {
        const val NAME = "lan"
    }
}

internal fun X509Certificate.fingerprint(): String = sha256Hex(encoded)

internal fun pairingTrust(expectedFingerprint: String? = null): X509TrustManager = object : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (expectedFingerprint != null) throw CertificateException("Unexpected TLS client certificate")
        val certificate = chain.singleOrNull() ?: throw CertificateException("Expected a self-signed peer certificate")
        certificate.checkValidity()
        try {
            certificate.verify(certificate.publicKey)
        } catch (failure: Exception) {
            throw CertificateException("Invalid peer certificate", failure)
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val certificate = chain.singleOrNull() ?: throw CertificateException("Expected a pinned peer certificate")
        certificate.checkValidity()
        if (expectedFingerprint == null || certificate.fingerprint() != expectedFingerprint) {
            throw CertificateException("Peer certificate fingerprint does not match")
        }
    }
}
