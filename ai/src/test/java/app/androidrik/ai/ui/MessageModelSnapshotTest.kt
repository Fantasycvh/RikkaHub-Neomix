package app.androidrik.ai.ui

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.TextGenerationResult
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
 *
 * P1 收尾后 `MessageChunk`/`handleMessageChunk` 已删除，非流式入口统一为
 * [handleTextGenerationResult]（内部走 [UIMessage.mergeDelta]，与旧 `plus(MessageChunk)` 的
 * 合并语义逐行等价）；本测试改由 `TextGenerationResult` 驱动，覆写逻辑不变。
 */
class MessageModelSnapshotTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun assistantMessage() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("hello")),
    )

    /** 一条**完整**的非流式结果（旧 `MessageChunk(choice.message = ...)` 的等价物）。 */
    private fun result(role: MessageRole = MessageRole.ASSISTANT) = TextGenerationResult(
        id = "resp-1",
        model = "test-model",
        message = UIMessage(role = role, parts = listOf(UIMessagePart.Text("hi"))),
    )

    @Test
    fun `new assistant message carries model and provider snapshots`() {
        val model = Model(modelId = "gpt-4o", displayName = "GPT-4o")
        val messages = listOf(assistantMessage().copy(role = MessageRole.USER))
            .handleTextGenerationResult(result(), model = model, providerName = "OpenAI")

        // 角色不同 ⇒ 追加新消息，并写入模型/提供商快照
        assertEquals(2, messages.size)
        val created = messages.last()
        assertEquals(MessageRole.ASSISTANT, created.role)
        assertEquals(model.id, created.modelId)
        assertEquals("GPT-4o", created.modelNameSnapshot)
        assertEquals("OpenAI", created.providerNameSnapshot)
        assertEquals("hi", created.toText())
    }

    @Test
    fun `blank names are not stored as snapshots`() {
        val model = Model(modelId = "m", displayName = "   ")
        val messages = listOf(assistantMessage().copy(role = MessageRole.USER))
            .handleTextGenerationResult(result(), model = model, providerName = "")

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
    fun `existing message accumulates result without changing its snapshots`() {
        val original = assistantMessage().copy(
            role = MessageRole.ASSISTANT,
            modelNameSnapshot = "GPT-4o",
            providerNameSnapshot = "OpenAI",
        )
        val messages = listOf(original).handleTextGenerationResult(
            result(),
            model = Model(displayName = "别的模型"),
            providerName = "别的提供商",
        )

        // 同角色 ⇒ 合并进最后一条（不追加），已有快照不被覆盖
        assertEquals(1, messages.size)
        val updated = messages.last()
        assertEquals("GPT-4o", updated.modelNameSnapshot)
        assertEquals("OpenAI", updated.providerNameSnapshot)
        // 正文真的拼上了（旧 plus/handleMessageChunk 的核心语义：Text 追加到最后一段 Text）
        assertEquals("hellohi", updated.toText())
    }
}
