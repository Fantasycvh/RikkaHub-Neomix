package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.Modality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexModelsParseTest {

    @Test
    fun `解析 models 数组并按 visibility 过滤`() {
        val body = """
            {
              "models": [
                {
                  "slug": "gpt-5.1-codex",
                  "display_name": "GPT-5.1 Codex",
                  "visibility": "list",
                  "context_window": 272000,
                  "input_modalities": ["text", "image"],
                  "supported_reasoning_levels": [{"effort": "low"}, {"effort": "high"}]
                },
                {
                  "slug": "internal-preview",
                  "display_name": "Internal",
                  "visibility": "hide"
                }
              ]
            }
        """.trimIndent()

        val models = parseCodexModels(body)

        assertEquals(1, models.size)
        val model = models.single()
        assertEquals("gpt-5.1-codex", model.modelId)
        assertEquals("GPT-5.1 Codex", model.displayName)
        assertEquals(272000, model.contextLength)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), model.inputModalities)
        assertTrue(model.abilities.contains(ModelAbility.REASONING))
        assertTrue(model.abilities.contains(ModelAbility.TOOL))
    }

    @Test
    fun `visibility 缺失时退回全量并保留非推理模型能力`() {
        val body = """
            {"models":[{"slug":"gpt-5.1"},{"slug":"gpt-5.1-codex","supported_reasoning_levels":[]}]}
        """.trimIndent()

        val models = parseCodexModels(body)

        assertEquals(2, models.size)
        assertEquals("gpt-5.1", models[0].displayName)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), models[0].inputModalities)
        assertEquals(listOf(ModelAbility.TOOL), models[0].abilities)
        // 空数组视为非推理模型
        assertEquals(listOf(ModelAbility.TOOL), models[1].abilities)
    }

    @Test
    fun `非法响应返回空列表而不是抛错`() {
        assertTrue(parseCodexModels("not json").isEmpty())
        assertTrue(parseCodexModels("""{"models":"oops"}""").isEmpty())
        assertTrue(parseCodexModels("""{"models":[{"display_name":"no slug"}]}""").isEmpty())
    }
}
