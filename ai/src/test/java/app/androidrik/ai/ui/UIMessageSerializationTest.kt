package app.androidrik.ai.ui

import app.androidrik.ai.core.TokenUsage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessageSerializationTest {

    @Test
    fun `synthetic marker is not serialized`() {
        val message = UIMessage.user("internal").copy(isSynthetic = true)

        val encoded = Json.encodeToString(message)
        val decoded = Json.decodeFromString<UIMessage>(encoded)

        assertTrue(message.isSynthetic)
        assertFalse(encoded.contains("isSynthetic"))
        assertFalse(decoded.isSynthetic)
    }

    /**
     * #2307：lastUsage 是新增字段，旧消息的 JSON 里没有它。
     *
     * 判据两条：①旧 JSON 仍能解析且 lastUsage 为 null（默认值兜底，零迁移）；
     * ②新消息的 lastUsage 与整轮 usage 各自独立往返（后者是累计口径，不能被覆盖）。
     */
    @Test
    fun `lastUsage defaults to null for legacy json and survives roundtrip`() {
        val legacy = """{"role":"assistant","parts":[]}"""
        val legacyDecoded = Json.decodeFromString<UIMessage>(legacy)
        assertNull(legacyDecoded.lastUsage)

        val message = UIMessage.assistant("hi").copy(
            usage = TokenUsage(promptTokens = 100, completionTokens = 40, totalTokens = 140),
            lastUsage = TokenUsage(promptTokens = 30, completionTokens = 10, totalTokens = 40),
        )
        val encoded = Json.encodeToString(message)
        assertTrue(encoded.contains("lastUsage"))
        val decoded = Json.decodeFromString<UIMessage>(encoded)
        assertEquals(message.usage, decoded.usage)
        assertEquals(message.lastUsage, decoded.lastUsage)
    }
}
