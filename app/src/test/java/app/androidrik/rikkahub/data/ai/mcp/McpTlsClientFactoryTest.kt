package app.androidrik.rikkahub.data.ai.mcp

import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTlsClientFactoryTest {

    private val factory = McpTlsClientFactory()

    private fun p12Cert(): McpClientCertificate {
        val bytes = File("src/test/resources/test-client.p12").readBytes()
        return McpClientCertificate(
            enabled = true,
            format = "P12",
            certData = Base64.getEncoder().encodeToString(bytes),
            password = "changeit",
        )
    }

    @Test
    fun `buildSslContext succeeds with valid p12 and password`() {
        val ctx = factory.buildSslContext(p12Cert())
        assertNotNull(ctx)
    }

    @Test
    fun `buildSslContext returns null for wrong password`() {
        val cert = p12Cert().copy(password = "wrongpass")
        assertNull(factory.buildSslContext(cert))
    }

    @Test
    fun `buildSslContext returns null for invalid base64`() {
        val cert = McpClientCertificate(enabled = true, format = "P12", certData = "!!!not-base64!!!")
        assertNull(factory.buildSslContext(cert))
    }

    @Test
    fun `buildSslContext returns null for unsupported format`() {
        val cert = McpClientCertificate(enabled = true, format = "DER", certData = "AAAA")
        assertNull(factory.buildSslContext(cert))
    }



}
