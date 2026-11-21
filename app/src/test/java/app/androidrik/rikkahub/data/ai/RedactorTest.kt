package app.androidrik.rikkahub.data.ai

import app.androidrik.ai.provider.ApiKeyEntry
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.common.http.CustomHeader
import app.androidrik.rikkahub.data.model.Assistant
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

@Suppress("DEPRECATION")
class RedactorTest {

    private fun credential(value: String) = setOf(value)

    // ==================== L2 白名单 ====================

    @Test
    fun `openai style key is redacted`() {
        val key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1"
        val r = Redactor.redact("my key is $key please", emptySet())
        assertEquals("my key is <redacted kind=\"api-key\"/> please", r.text)
        assertEquals(mapOf("api-key" to 1), r.counts)
    }

    @Test
    fun `google aws github and jwt shapes are redacted`() {
        val fixtures = listOf(
            "AIza" + "x".repeat(35),
            "AKIA" + "1".repeat(16),
            "ghp_" + "a".repeat(36),
            "github_pat_" + "a".repeat(20),
            "eyJ" + "a".repeat(12) + "." + "b".repeat(12) + "." + "c".repeat(12),
        )
        fixtures.forEach { fixture ->
            val r = Redactor.redact("token=$fixture end", emptySet())
            assertNotEquals("expected $fixture to be redacted", "token=$fixture end", r.text)
            assertFalse(r.text.contains(fixture))
        }
    }

    @Test
    fun `identifier lookalikes are not redacted`() {
        val r = Redactor.redact(
            "task-sk-pipeline short ids and the word mask itself stay intact",
            emptySet(),
        )
        assertTrue(r.counts.isEmpty())
        assertEquals(
            "task-sk-pipeline short ids and the word mask itself stay intact",
            r.text,
        )
    }

    // ==================== L1 已知密钥登记 ====================

    @Test
    fun `registered credential is redacted exactly`() {
        val secret = "cfg-secret-98765"
        val r = Redactor.redact("config:\napi_key = \"$secret\"\n", credential(secret))
        assertEquals("config:\napi_key = \"<redacted kind=\"credential\"/>\"\n", r.text)
        assertEquals(mapOf("credential" to 1), r.counts)
    }

    @Test
    fun `longest credential wins when one is a prefix of another`() {
        val long = "abcd1234567890ef"
        val short = "abcd1234"
        val r = Redactor.redact("value=$long", credential(long) + credential(short))
        assertEquals("value=<redacted kind=\"credential\"/>", r.text)
        assertEquals(1, r.counts.values.sum())
    }

    @Test
    fun `short values below threshold are not registered`() {
        val short = "abc123"
        val r = Redactor.redact("value=$short", credential(short))
        assertEquals("value=$short", r.text)
    }

    // ==================== L3 私钥块（含截断容忍） ====================

    @Test
    fun `complete private key block is replaced`() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIBabc\n-----END RSA PRIVATE KEY-----\ntail"
        val r = Redactor.redact("head\n$pem", emptySet())
        assertEquals("head\n<redacted kind=\"private-key\"/>\ntail", r.text)
        assertEquals(mapOf("private-key" to 1), r.counts)
    }

    @Test
    fun `truncated private key block is tolerated`() {
        // OpenHands 核心教训：闭合块缺失（截断）也必须整段脱敏，绝不留块体
        val truncated = "-----BEGIN PRIVATE KEY-----\nMIIBabcdef"
        val r = Redactor.redact("head\n$truncated", emptySet())
        assertEquals("head\n<redacted kind=\"private-key\"/>", r.text)
    }

    @Test
    fun `whitelist does not scan inside a replaced private key block`() {
        // 块内 base64 体若含 sk- 形态片段，块级先替换后白名单不应再计数
        val pem = "-----BEGIN PRIVATE KEY-----\nsk-" + "a".repeat(24) + "\n-----END PRIVATE KEY-----"
        val r = Redactor.redact(pem, emptySet())
        assertEquals("<redacted kind=\"private-key\"/>", r.text)
        assertEquals(mapOf("private-key" to 1), r.counts)
    }

    // ==================== 幂等与零残留 ====================

    @Test
    fun `redaction is idempotent and markers are residue free`() {
        val key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1"
        val credentialValue = "cfg-secret-98765"
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIBabc\n-----END RSA PRIVATE KEY-----"
        val original = "key=$key cred=$credentialValue\n$pem\njwt=" +
            "eyJ" + "a".repeat(12) + "." + "b".repeat(12) + "." + "c".repeat(12)
        val once = Redactor.redact(original, credential(credentialValue))
        val twice = Redactor.redact(once.text, credential(credentialValue))
        assertEquals(once.text, twice.text)
        assertTrue(twice.counts.isEmpty())
        // 标记自身不命中任何层（零残留设计约束）
        listOf(
            Redactor.MARKER_CREDENTIAL,
            Redactor.MARKER_API_KEY,
            Redactor.MARKER_TOKEN,
            Redactor.MARKER_PRIVATE_KEY,
        ).forEach { marker ->
            val r = Redactor.redact(marker, credential(credentialValue))
            assertEquals(marker, r.text)
            assertTrue(r.counts.isEmpty())
        }
    }

    // ==================== 消息级导出脱敏 ====================

    @Test
    fun `message level export redaction covers text reasoning notice and tool output`() {
        val key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1"
        val credentials = credential("cfg-secret-98765") + credential(key)
        val messages = listOf(
            UIMessage.user("please use $key"),
            UIMessage(
                role = app.androidrik.ai.core.MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking about cfg-secret-98765"),
                    UIMessagePart.Image(url = "file:///img.png"),
                    UIMessagePart.Tool(
                        toolCallId = "t1", toolName = "shell", input = "{}",
                        output = listOf(UIMessagePart.Text("output has $key inside")),
                    ),
                ),
            ),
            UIMessage(
                role = app.androidrik.ai.core.MessageRole.USER,
                parts = listOf(UIMessagePart.Notice(eventId = "n1", text = "clean notice")),
            ),
        )
        val (redacted, hits) = messages.redactedForExport(credentials)
        assertEquals(3, hits)
        // 命中的 part 被替换
        assertTrue(redacted[0].parts.filterIsInstance<UIMessagePart.Text>().single().text.contains("<redacted"))
        val assistant = redacted[1]
        assertTrue(
            assistant.parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning.contains("<redacted")
        )
        val tool = assistant.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertTrue(tool.output.filterIsInstance<UIMessagePart.Text>().single().text.contains("<redacted"))
        // 无命中 part 共享原实例
        assertSame(messages[1].parts[1], assistant.parts[1])
        assertSame(messages[2], redacted[2])
    }

    @Test
    fun `empty credentials short circuit the message level pass`() {
        val messages = listOf(UIMessage.user("sk-" + "a1B2c3D4e5F6g7H8i9J0k1"))
        val (redacted, hits) = messages.redactedForExport(emptySet())
        assertSame(messages, redacted)
        assertEquals(0, hits)
    }

    // ==================== collectCredentials ====================

    @Test
    fun `collect credentials gathers provider keys and both header levels`() {
        val provider = ProviderSetting.OpenAI(
            id = Uuid.parse("22222222-2222-4222-8222-222222222222"),
            models = emptyList(),
            apiKeys = listOf(ApiKeyEntry("sk-real-key-12345678"), ApiKeyEntry("disabled-key-99", enabled = false)),
            customHeaders = listOf(CustomHeader(name = "X-Api-Key", value = "header-value-7777777")),
        )
        val assistant = Assistant(
            customHeaders = listOf(CustomHeader(name = "Authorization", value = "Bearer bearer-token-888888")),
        )
        val credentials = collectCredentials(listOf(provider), listOf(assistant))
        assertTrue("sk-real-key-12345678" in credentials)
        assertTrue("header-value-7777777" in credentials)
        assertTrue("Bearer bearer-token-888888" in credentials)
        assertFalse("disabled-key-99" in credentials)
    }

    // ==================== legacy part 形态（guard 用例之外的配对残留） ====================

    @Test
    fun `legacy tool result text is redacted at export`() {
        val key = "sk-" + "a1B2c3D4e5F6g7H8i9J0k1"
        val messages = listOf(
            UIMessage(
                role = app.androidrik.ai.core.MessageRole.USER,
                parts = listOf(
                    UIMessagePart.ToolResult(
                        toolCallId = "r1", toolName = "t",
                        content = JsonPrimitive("result with $key"),
                        arguments = JsonPrimitive("{}"),
                    ),
                    UIMessagePart.Text("plain $key"),
                ),
            ),
        )
        val (redacted, hits) = messages.redactedForExport(credential(key))
        // 旧版 ToolResult part 的 content 是 JsonElement，不在 v1 脱敏范围（设计稿 §3.1 非文本 part）
        assertEquals(1, hits)
        assertTrue(redacted[0].parts[1].toString().contains("<redacted"))
        assertTrue(redacted[0].parts[0].toString().contains(key))
    }
}
