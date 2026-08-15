package app.androidrik.rikkahub.data.ai.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * MCP mTLS 客户端证书工厂（上游 #1687）。
 *
 * 为配置了客户端证书的 MCP Server 构建独立的 OkHttpClient/Ktor HttpClient：
 * - P12：KeyStore 直接加载 .p12（password 解密）
 * - PEM：证书（X509）+ 私钥（PKCS#8）组装 KeyStore
 * 证书/私钥解密失败返回 null（调用方回退到普通连接或报错）。
 */
class McpTlsClientFactory {

    /** 构建带客户端证书的 Ktor HttpClient；证书缺失/损坏返回 null */
    fun createHttpClient(cert: McpClientCertificate): HttpClient? {
        if (!cert.enabled || !cert.isConfigured) return null
        val sslContext = buildSslContext(cert) ?: return null
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .sslSocketFactory(sslContext.socketFactory, sslContext.getTrustManagers())
            .build()
        return HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            install(ContentNegotiation) { json(Json { prettyPrint = true; isLenient = true }) }
            install(SSE)
        }
    }

    /** 纯逻辑：从 McpClientCertificate 构建 SSLContext；失败返回 null（可单测） */
    fun buildSslContext(cert: McpClientCertificate): SSLContext? {
        return try {
            val keyStore = when (cert.format.uppercase()) {
                "P12" -> loadP12KeyStore(cert)
                "PEM" -> loadPemKeyStore(cert)
                else -> return null
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, cert.password.toCharArray())
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun loadP12KeyStore(cert: McpClientCertificate): KeyStore {
        val bytes = Base64.getDecoder().decode(cert.certData)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(ByteArrayInputStream(bytes), cert.password.toCharArray())
        return ks
    }

    private fun loadPemKeyStore(cert: McpClientCertificate): KeyStore {
        val certBytes = Base64.getDecoder().decode(cert.certData)
        val keyBytes = Base64.getDecoder().decode(cert.keyData)
        val cf = CertificateFactory.getInstance("X.509")
        val certificate = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val keyFactory = java.security.KeyFactory.getInstance("RSA")
        val privateKey = try {
            keyFactory.generatePrivate(keySpec)
        } catch (e: Exception) {
            // 尝试 EC
            java.security.KeyFactory.getInstance("EC").generatePrivate(keySpec)
        }
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("client", privateKey, cert.password.toCharArray(), arrayOf(certificate))
        return ks
    }
}

private fun SSLContext.getTrustManagers(): javax.net.ssl.X509TrustManager {
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(null as KeyStore?)
    return tmf.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
}
