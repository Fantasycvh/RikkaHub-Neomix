package app.androidrik.ai

import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.registry.ModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun testGPT5() {
        assertTrue(ModelRegistry.GPT_5.match("gpt-5"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5-chat"))
        assertTrue(ModelRegistry.GPT_5.match("gpt-5-mini"))
        assertFalse(ModelRegistry.GPT_5.match("deepseek-v3"))
        assertFalse(ModelRegistry.GPT_5.match("gemini-2.0-flash"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.1"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-4o"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-5.0"))
        assertFalse(ModelRegistry.GPT_5.match("gpt-6"))
    }

    @Test
    fun testGemini25() {
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-flash-latest"))
        assertTrue(ModelRegistry.GEMINI_LATEST.match("gemini-pro-latest"))
        assertTrue(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-pro"))
        assertFalse(ModelRegistry.GEMINI_2_5_FLASH.match("gemini-2.5-flash-image-preview"))
        assertTrue(ModelRegistry.GEMINI_2_5_IMAGE.match("gemini-2.5-flash-image"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash-image")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("gemini-2.5-flash")
        )
    }

    @Test
    fun testClaudeSeries() {
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4.5-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4.5-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-4-20250929"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-4-sonnet"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-3.5-sonnet"))
    }

    @Test
    fun testGemini4() {
        val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
        val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)
        assertTrue(ModelRegistry.GEMINI_4.match("gemini-4-pro"))
        assertTrue(ModelRegistry.GEMINI_4.match("gemini-4-flash-preview"))
        assertTrue(ModelRegistry.GEMINI_SERIES.match("gemini-4-pro"))
        assertFalse(ModelRegistry.GEMINI_4.match("gemini-3-pro"))
        // "04" 是独立 token，不能被当成 4
        assertFalse(ModelRegistry.GEMINI_4.match("gemini-2.5-flash-preview-04-17"))
        assertFalse(ModelRegistry.GEMINI_3_SERIES.match("gemini-4-pro"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("gemini-4-pro"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("gemini-4-flash"))
    }

    @Test
    fun testClaude5Series() {
        val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
        val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-5"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-opus-5"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-sonnet-5-5"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-opus-5-5"))
        assertTrue(ModelRegistry.CLAUDE_SERIES.match("claude-opus-5.5"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("claude-sonnet-5-5"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("claude-opus-5-5"))
        // 5 代带 1M 上下文（带日期后缀的 id 也要命中）
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-5-5"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-opus-5-5"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-5"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-5-20260305"))
        assertEquals(1_000_000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("deepseek-v4-1-flash"))
        // 4 代的 id 里也含 "5"，notTokens 必须把它们挡在 1M 之外
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-4-5"))
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-opus-4-5"))
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-sonnet-4-5-20250929"))
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-4.5-sonnet"))
        // fork 保留 Claude 3（上游 3443dd45 顺手删了）：老 id 仍有视觉/工具元数据，
        // 且不能被 5 代抢走上下文（更具体的 3.5 条目胜出）
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("claude-3.5-sonnet"))
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("claude-3.5-sonnet"))
    }

    @Test
    fun testSpecificityPriority() {
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2.5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("kimi-k2")
        )
    }

    @Test
    fun testOpenAIOModels() {
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o1"))
        assertTrue(ModelRegistry.OPENAI_O_MODELS.match("o3-mini"))
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("o3-mini")
        )
    }

    @Test
    fun testGlm5AndMinimaxM25() {
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("glm-5")
        )
        assertEquals(
            listOf(Modality.TEXT),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("minimax-m2.5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("glm-5")
        )
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("minimax-m2.5")
        )
    }

    @Test
    fun testDeepseekV4() {
        val reasonerAbilities = ModelRegistry.MODEL_ABILITIES.getData("deepseek-reasoner")
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash")
        )
        assertEquals(
            reasonerAbilities,
            ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-pro")
        )
    }
}
