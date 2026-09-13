package app.androidrik.rikkahub.ui.pages.stats

import app.androidrik.ai.provider.Model
import app.androidrik.rikkahub.data.db.dao.ModelTokenStats
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 模型用量分组的展示名解析与合并。
 *
 * 关键行为：改过名的模型历史统计仍显示当时的名字（快照优先）；模型被删后回退为「已删除模型」；
 * 同名模型（如删除后重建、id 不同）合并为一行而非裂成两行。
 */
class ModelUsageResolveTest {

    private fun row(
        modelId: String? = null,
        modelName: String? = null,
        providerName: String? = null,
        prompt: Long = 100,
        completion: Long = 10,
    ) = ModelTokenStats(
        modelId = modelId,
        modelName = modelName,
        providerName = providerName,
        promptTokens = prompt,
        completionTokens = completion,
        cachedTokens = 0,
    )

    @Test
    fun `snapshot name wins over current model name`() {
        val model = Model(modelId = "gpt-4o", displayName = "现在的名字")
        val result = resolveModelUsage(
            rows = listOf(row(modelId = model.id.toString(), modelName = "当时的名字")),
            models = listOf(model),
        )
        assertEquals("当时的名字", result.single().label)
    }

    @Test
    fun `falls back to current name when snapshot is absent`() {
        val model = Model(modelId = "gpt-4o", displayName = "现在叫这个")
        val result = resolveModelUsage(
            rows = listOf(row(modelId = model.id.toString(), modelName = null)),
            models = listOf(model),
        )
        assertEquals("现在叫这个", result.single().label)
    }

    @Test
    fun `deleted model falls back to placeholder`() {
        val result = resolveModelUsage(
            rows = listOf(row(modelId = "11111111-1111-1111-1111-111111111111", modelName = null)),
            models = emptyList(),
        )
        assertEquals(DELETED_MODEL_LABEL, result.single().label)
    }

    @Test
    fun `same model rebuilt with new id merges into one row`() {
        // 删除后重建：id 不同但快照名相同 -> 应合并
        val result = resolveModelUsage(
            rows = listOf(
                row(modelId = "11111111-1111-1111-1111-111111111111", modelName = "GLM 5.3", prompt = 100, completion = 10),
                row(modelId = "22222222-2222-2222-2222-222222222222", modelName = "GLM 5.3", prompt = 300, completion = 30),
            ),
            models = emptyList(),
        )
        assertEquals(1, result.size)
        assertEquals(400L, result.single().promptTokens)
        assertEquals(40L, result.single().completionTokens)
    }

    @Test
    fun `different providers stay separate even with same model name`() {
        val result = resolveModelUsage(
            rows = listOf(
                row(modelName = "GPT-4o", providerName = "OpenAI", prompt = 100),
                row(modelName = "GPT-4o", providerName = "中转站", prompt = 200),
            ),
            models = emptyList(),
        )
        assertEquals(2, result.size)
        assertEquals("中转站", result.first().providerName) // 用量大者在前
    }

    @Test
    fun `rows are sorted by total tokens descending`() {
        val result = resolveModelUsage(
            rows = listOf(
                row(modelName = "小", prompt = 10, completion = 1),
                row(modelName = "大", prompt = 1000, completion = 100),
            ),
            models = emptyList(),
        )
        assertEquals(listOf("大", "小"), result.map { it.label })
    }
}
