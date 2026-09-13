package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息上的模型/提供商名称快照：写入时机与序列化兼容性。
 *
 * 快照用于 token 统计按名称分组——模型被删除后 id 查不到（悬空），且删除重建会得到新 UUID
 * 导致同一模型裂成两行，因此统计不能只依赖 modelId。
 */
class MessageModelSnapshotTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun assistantMessage() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("hello")),
    )

    private fun chunk(role: MessageRole = MessageRole.ASSISTANT) = MessageChunk(
        id = "chunk-1",
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = role, parts = listOf(UIMessagePart.Text("hi"))),
                message = null,
                finishReason = null,
            )
        ),
    )

    @Test
    fun `new assistant message carries model and provider snapshots`() {
        val model = Model(modelId = "gpt-4o", displayName = "GPT-4o")
        val messages = listOf(assistantMessage().copy(role = MessageRole.USER))
            .handleMessageChunk(chunk = chunk(), model = model, providerName = "OpenAI")

        val created = messages.last()
        assertEquals(model.id, created.modelId)
        assertEquals("GPT-4o", created.modelNameSnapshot)
        assertEquals("OpenAI", created.providerNameSnapshot)
    }

    @Test
    fun `blank names are not stored as snapshots`() {
        val model = Model(modelId = "m", displayName = "   ")
        val messages = listOf(assistantMessage().copy(role = MessageRole.USER))
            .handleMessageChunk(chunk = chunk(), model = model, providerName = "")

        val created = messages.last()
        assertNull(created.modelNameSnapshot)
        assertNull(created.providerNameSnapshot)
    }

    @Test
    fun `snapshots survive serialization round trip`() {
        val original = assistantMessage().copy(
            modelId = java.util.UUID.randomUUID().let { kotlin.uuid.Uuid.parse(it.toString()) },
            modelNameSnapshot = "GLM 5.3 Flash",
            providerNameSnapshot = "智谱",
        )
        val decoded = json.decodeFromString<UIMessage>(json.encodeToString(original))

        assertEquals(original.modelNameSnapshot, decoded.modelNameSnapshot)
        assertEquals(original.providerNameSnapshot, decoded.providerNameSnapshot)
    }

    @Test
    fun `legacy payload without snapshot fields decodes to null`() {
        // 旧数据（无快照字段）必须能正常反序列化——新增可选字段不触发迁移
        val legacy = """
            {
              "id": "0f9c4a3e-1b2d-4c5e-8f7a-9b0c1d2e3f40",
              "role": "assistant",
              "parts": [{"type": "text", "text": "old message"}]
            }
        """.trimIndent()
        val decoded = json.decodeFromString<UIMessage>(legacy)

        assertNull(decoded.modelNameSnapshot)
        assertNull(decoded.providerNameSnapshot)
        assertTrue(decoded.toText().contains("old message"))
    }

    @Test
    fun `existing message accumulates chunk without changing its snapshots`() {
        val original = assistantMessage().copy(
            role = MessageRole.ASSISTANT,
            modelNameSnapshot = "GPT-4o",
            providerNameSnapshot = "OpenAI",
        )
        val messages = listOf(original).handleMessageChunk(
            chunk = chunk(),
            model = Model(displayName = "别的模型"),
            providerName = "别的提供商",
        )

        val updated = messages.last()
        assertEquals("GPT-4o", updated.modelNameSnapshot)
        assertEquals("OpenAI", updated.providerNameSnapshot)
    }
}
