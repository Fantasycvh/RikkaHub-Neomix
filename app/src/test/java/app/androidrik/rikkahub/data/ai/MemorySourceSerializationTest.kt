package app.androidrik.rikkahub.data.ai

import app.androidrik.rikkahub.data.db.entity.MemoryEntity
import app.androidrik.rikkahub.data.model.AssistantMemory
import app.androidrik.rikkahub.utils.JsonInstantPretty
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 来源遥测的纯序列化/派生测试（不改数据库、不依赖 Android）。
 *
 * 1. 旧 B 快照缺少 4 个来源字段 ⇒ 解码为 0，历史读取不被硬归因为 CHAT；
 * 2. 新实体全量快照往返保留全部 4 值；
 * 3. `AssistantMemory` 的工具 JSON 不泄漏来源遥测；
 * 4. `legacyReadCount` 非负，且 consolidation 读取不改变它。
 */
class MemorySourceSerializationTest {

    @Test
    fun `legacy snapshot without source fields decodes as zero`() {
        // B-era snapshot literal: the four source fields did not exist yet.
        val legacy = """
            {"id":43,"assistantId":"__global__","content":"prior","publicId":"a1000043",
            "revision":8,"readCount":7,"lastReadAt":19}
        """.trimIndent()

        val decoded = Json.decodeFromString(MemoryEntity.serializer(), legacy)

        assertEquals(7, decoded.readCount)
        assertEquals(19L, decoded.lastReadAt)
        assertEquals(0, decoded.chatReadCount)
        assertEquals(0L, decoded.lastChatReadAt)
        assertEquals(0, decoded.consolidationReadCount)
        assertEquals(0L, decoded.lastConsolidationReadAt)
    }

    @Test
    fun `full entity snapshot round trip keeps all four source values`() {
        val entity = MemoryEntity(
            id = 43,
            assistantId = "__global__",
            content = "source fact",
            publicId = "a1000043",
            importance = 0.4f,
            updatedAt = 10L,
            description = "topic",
            readCount = 7,
            lastReadAt = 19L,
            isAnchor = true,
            revision = 3L,
            chatReadCount = 5,
            lastChatReadAt = 100L,
            consolidationReadCount = 2,
            lastConsolidationReadAt = 50L,
        )

        val encoded = JsonInstantPretty.encodeToString(MemoryEntity.serializer(), entity)
        val decoded = JsonInstantPretty.decodeFromString(MemoryEntity.serializer(), encoded)

        assertEquals(entity, decoded)
    }

    @Test
    fun `assistant memory tool JSON never exposes source telemetry`() {
        val memory = AssistantMemory(
            id = "a1000043",
            content = "source fact",
            importance = 0.4f,
            description = "topic",
            revision = 3L,
            readCount = 7,
            lastReadAt = 19L,
            chatReadCount = 5,
            lastChatReadAt = 100L,
            consolidationReadCount = 2,
            lastConsolidationReadAt = 50L,
        )

        // Same serializer shape as MemoryTools.memoryPayload.
        val json = JsonInstantPretty.encodeToString(AssistantMemory.serializer(), memory)

        listOf(
            "chatReadCount", "lastChatReadAt", "consolidationReadCount", "lastConsolidationReadAt",
            "chat_read_count", "last_chat_read_at", "consolidation_read_count", "last_consolidation_read_at",
            "legacyReadCount",
        ).forEach { key ->
            assertFalse("tool JSON must not expose $key: $json", json.contains(key))
        }
        assertTrue("business fields stay visible: $json", json.contains("\"revision\""))
        assertTrue("content stays visible: $json", json.contains("\"content\""))
    }

    @Test
    fun `legacy read count is non negative and ignores consolidation`() {
        assertEquals(0, AssistantMemory().legacyReadCount)
        assertEquals(7, AssistantMemory(readCount = 7).legacyReadCount)
        assertEquals(5, AssistantMemory(readCount = 7, chatReadCount = 2).legacyReadCount)
        assertEquals(0, AssistantMemory(readCount = 1, chatReadCount = 9).legacyReadCount)

        val knownChat = AssistantMemory(readCount = 7, chatReadCount = 2)
        val withConsolidationReads = knownChat.copy(
            consolidationReadCount = 99,
            lastConsolidationReadAt = 123L,
        )
        assertEquals(knownChat.legacyReadCount, withConsolidationReads.legacyReadCount)
        assertTrue(
            "consolidation 读取不得产生负数或聊天信用",
            withConsolidationReads.legacyReadCount >= 0,
        )
    }
}
