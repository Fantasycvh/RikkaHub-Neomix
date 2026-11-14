package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NoticeProtocolTest {
    // 必须与生产持久化同配置（encodeDefaults=true）：裸 Json 的 encodeDefaults=false 会把
    // 等于「静态 default 实例」的 createdAt 省略掉，解码回落到新的 Clock.System.now()——
    // 默认表达式是 now() 时跨毫秒边界即偶发不等（2026-11-14 全量跑复现的 flake 根因）。
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test fun `mixed notices expand in place with stable unique identities and intact tools`() {
        val tool = UIMessagePart.Tool("call", "shell", "{}", listOf(UIMessagePart.Text("result")))
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool,
            UIMessagePart.Notice("event", "Untrusted task output"), UIMessagePart.Text("answer")))
        val expanded = listOf(original).expandNotices()
        assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.SYSTEM, MessageRole.ASSISTANT), expanded.map { it.role })
        assertEquals(tool, expanded.first().parts.single())
        assertEquals(3, expanded.map { it.id }.toSet().size)
        assertEquals(expanded, listOf(original).expandNotices())
        assertEquals(expanded, expanded.expandNotices())
        assertEquals(3, original.parts.size)
        assertEquals(2, listOf(original).messagesForAuxGeneration().size)
    }

    @Test fun `notice part round trips and contributes to visible text`() {
        val original = UIMessage(role = MessageRole.SYSTEM, parts = listOf(UIMessagePart.Notice("id", "bounded output")))
        val encoded = json.encodeToString(UIMessage.serializer(), original)
        assertEquals(original, json.decodeFromString(UIMessage.serializer(), encoded))
        assertEquals("bounded output", original.toText())
        assertTrue(original.isExternalNotice)
        assertTrue(listOf(original).messagesForAuxGeneration().isEmpty())
    }
}
