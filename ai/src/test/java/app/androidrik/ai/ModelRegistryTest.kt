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
        // 泛化代际写法要能兜住 4 系列的任意命名（不必逐条加注册表）：
        // major.minor、lite 变体、带日期后缀的 id 都命中
        assertTrue(ModelRegistry.GEMINI_4.match("gemini-4.5-flash"))
        assertTrue(ModelRegistry.GEMINI_4.match("gemini-4-flash-lite"))
        assertTrue(ModelRegistry.GEMINI_4.match("gemini-4-pro-20260101"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("gemini-4.5-flash"))
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

    /**
     * 上游四条注册提交（`f167a855` deepseek-v4-flash-vision-exp / `04d6c4ea` muse-spark+glimmer /
     * `c3d6867c` step-5 / `068103dd` mimo v2.6）在 fork 同步区间**之内**，但 fork 注册表当时没跟上。
     *
     * 这不是化妆品：fork 缺条目时这些 id 会退回默认值（只 TEXT、无工具/推理）——
     * 例如 `deepseek-v4-flash-vision-exp` 明明能收图，界面却连图片入口都不给。
     */
    @Test
    fun testLateRegistryEntriesArePresent() {
        val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
        val toolReasoning = listOf(ModelAbility.TOOL, ModelAbility.REASONING)

        // 视觉版比同名非视觉版更具体 ⇒ 由它取胜，且拿到视觉输入
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash-vision-exp"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("DeepSeek-V4-Flash-Vision-Exp"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("deepseek-v4-flash-vision-exp"))
        // 非视觉版不能被顺带带上视觉
        assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("deepseek-v4-flash"))

        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-spark"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-spark-1.3-contributor"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("muse-glimmer-30b"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("muse-glimmer-30b"))

        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("step-5"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("step-5-preview"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("step-5"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("step-3"))

        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.6"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("mimo-v2.6"))
        // 2.5 代不能被 2.6 抢走（token 顺序匹配：mimo→v→2→5 命中的是 V2_5 条目）
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2.5"))
        assertEquals(toolReasoning, ModelRegistry.MODEL_ABILITIES.getData("mimo-v2.5"))
        // 更短的 V2 条目没有视觉，只作为兜底
        assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_INPUT_MODALITIES.getData("mimo-v2"))
    }

    @Test
    fun testMagnitudeNotVersion() {
        val textInput = listOf(Modality.TEXT)
        val visionInput = listOf(Modality.TEXT, Modality.IMAGE)
        // 8b 是参数量，不能当成 qwen 3.8
        assertEquals(textInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("qwen3-8b"))
        assertEquals(textInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("qwen3-30b-a3b"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("qwen3-vl-8b-instruct"))
        assertEquals(visionInput, ModelRegistry.MODEL_INPUT_MODALITIES.getData("qwen3.8-plus"))
        // 3b 不能让 qwen2.5 命中 qwen 3
        assertEquals(emptyList<ModelAbility>(), ModelRegistry.MODEL_ABILITIES.getData("qwen2.5-3b-instruct"))
        assertEquals(
            listOf(ModelAbility.TOOL, ModelAbility.REASONING),
            ModelRegistry.MODEL_ABILITIES.getData("qwen3-8b")
        )
    }
}
