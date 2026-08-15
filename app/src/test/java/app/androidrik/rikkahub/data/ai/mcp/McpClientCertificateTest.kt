package app.androidrik.rikkahub.data.ai.mcp

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientCertificateTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serialization round trip preserves fields`() {
        val cert = McpClientCertificate(
            enabled = true,
            format = "P12",
            certData = "AAAA",
            keyData = "BBBB",
            password = "secret",
        )
        val encoded = json.encodeToString(McpClientCertificate.serializer(), cert)
        val decoded = json.decodeFromString(McpClientCertificate.serializer(), encoded)
        assertEquals(cert, decoded)
    }

    @Test
    fun `old config without clientCertificate deserializes with default`() {
        // 旧配置没有该字段 → 默认 disabled
        val decoded = json.decodeFromString(
            McpCommonOptions.serializer(),
            """{"enable":true,"name":"srv","headers":[],"tools":[]}"""
        )
        assertFalse(decoded.clientCertificate.enabled)
        assertFalse(decoded.clientCertificate.isConfigured)
    }

    @Test
    fun `isConfigured requires cert and key material`() {
        assertFalse(McpClientCertificate(enabled = true).isConfigured)
        assertFalse(McpClientCertificate(enabled = true, certData = "A", format = "PEM").isConfigured)
        assertTrue(McpClientCertificate(enabled = true, certData = "A", format = "P12").isConfigured)
        assertTrue(McpClientCertificate(enabled = true, certData = "A", keyData = "B", format = "PEM").isConfigured)
    }

    @Test
    fun `to string masks sensitive data`() {
        val s = McpClientCertificate(certData = "AAAA", keyData = "BBBB", password = "secret").toString()
        assertFalse(s.contains("AAAA"))
        assertFalse(s.contains("BBBB"))
        assertFalse(s.contains("secret"))
        assertTrue(s.contains("cert=***(4)"))
    }
}
