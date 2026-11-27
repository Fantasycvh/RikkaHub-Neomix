package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerCredentialDetectorTest {

    @Test
    fun `clean message produces no warning`() {
        val result = ComposerCredentialDetector.scanText("请帮我把这段 Kotlin 代码改成协程版本，谢谢！")
        assertFalse(result.hasFindings)
        assertEquals(0, result.totalCount)
        assertTrue(result.kinds.isEmpty())
    }

    @Test
    fun `detects openai google aws github and jwt secrets`() {
        val sample = buildString {
            appendLine("openai=sk-proj-abcdefghijklmnopqrstuvwxyz1234")
            appendLine("google=AIzaSyA1234567890abcdefghijklmnopqrstuv")
            appendLine("aws=AKIAIOSFODNN7EXAMPLE")
            appendLine("gh=ghp_1234567890abcdefghijklmnopqrstuvwxyz")
            appendLine("jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U")
        }
        val result = ComposerCredentialDetector.scanText(sample)
        assertTrue(result.hasFindings)
        assertEquals(5, result.totalCount)
        assertTrue(ComposerCredentialDetector.Kind.API_KEY in result.kinds)
        assertTrue(ComposerCredentialDetector.Kind.TOKEN in result.kinds)
    }

    @Test
    fun `detects private key block even when truncated`() {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcw"
        val result = ComposerCredentialDetector.scanText(pem)
        assertTrue(result.hasFindings)
        assertEquals(1, result.totalCount)
        assertEquals(listOf(ComposerCredentialDetector.Kind.PRIVATE_KEY), result.kinds)
    }

    @Test
    fun `detects bearer token while ignoring placeholders and avoiding double count`() {
        val placeholder = ComposerCredentialDetector.scanText("Authorization: Bearer your_api_key_here_12345")
        assertFalse(placeholder.hasFindings)

        val repeated = ComposerCredentialDetector.scanText("Authorization: Bearer xxxxxxxxxxxxxxxxxxxx")
        assertFalse(repeated.hasFindings)

        val realBearer = ComposerCredentialDetector.scanText("Authorization: Bearer tok_live_9a8b7c6d5e4f3g2h1i")
        assertTrue(realBearer.hasFindings)
        assertEquals(1, realBearer.totalCount)
        assertEquals(listOf(ComposerCredentialDetector.Kind.BEARER_TOKEN), realBearer.kinds)

        // Bearer 后接 sk- 不应被重复计两次
        val bearerWithSk = ComposerCredentialDetector.scanText("Authorization: Bearer sk-proj-abcdefghijklmnopqrstuvwxyz1234")
        assertEquals(1, bearerWithSk.totalCount)
        assertEquals(listOf(ComposerCredentialDetector.Kind.API_KEY), bearerWithSk.kinds)
    }

    @Test
    fun `detects configured provider api key via scanParts`() {
        val provider = ProviderSetting.OpenAI(
            name = "Test",
            apiKey = "custom-secret-key-987654",
            baseUrl = "https://example.com/v1",
        )
        val parts = listOf(
            UIMessagePart.Image(url = "file:///tmp/a.png"),
            UIMessagePart.Text("debug key: custom-secret-key-987654"),
        )
        val result = ComposerCredentialDetector.scanParts(parts, listOf(provider), listOf(Assistant()))
        assertTrue(result.hasFindings)
        assertEquals(1, result.totalCount)
        assertEquals(listOf(ComposerCredentialDetector.Kind.REGISTERED_CREDENTIAL), result.kinds)
    }
}
