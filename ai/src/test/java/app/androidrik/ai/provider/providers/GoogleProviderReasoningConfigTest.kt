package app.androidrik.ai.provider.providers

import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.ReasoningOverride
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Gemini 推理参数的**接线**测试（上游 `3a9ae690`：Gemini 4 也用 `thinkingLevel`）。
 *
 * 判定逻辑在 ModelRegistry，但「哪个分支真的写进了请求体」只能从请求体上验——
 * 这里按本仓既有做法（`GoogleProviderMessageTest`）用反射调用私有
 * `buildCompletionRequestBody`，直接读 `generationConfig.thinkingConfig`。
 */
class GoogleProviderReasoningConfigTest {

    private val provider = GoogleProvider(OkHttpClient())

    private fun thinkingConfig(
        modelId: String,
        level: ReasoningLevel,
        override: ReasoningOverride? = null,
        providerOverride: ReasoningOverride? = null,
    ): JsonObject? {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            ProviderSetting.Google::class.java,
            List::class.java,
            TextGenerationParams::class.java
        )
        method.isAccessible = true
        val params = TextGenerationParams(
            model = Model(
                modelId = modelId,
                abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                reasoningOverride = override
            ),
            reasoningLevel = level
        )
        val providerSetting = ProviderSetting.Google(reasoningOverride = providerOverride)
        val body = method.invoke(provider, providerSetting, emptyList<Any>(), params) as JsonObject
        return body["generationConfig"]?.jsonObject?.get("thinkingConfig")?.jsonObject
    }

    private fun assertThinkingLevel(modelId: String, level: ReasoningLevel, expected: String) {
        val config = thinkingConfig(modelId, level)
        assertEquals(expected, config?.get("thinkingLevel")?.jsonPrimitive?.content)
        assertNull("thinkingLevel 与 thinkingBudget 不能同时出现", config?.get("thinkingBudget"))
    }

    @Test
    fun `gemini 4 走 thinkingLevel 而不是 thinkingBudget`() {
        assertThinkingLevel("gemini-4-pro", ReasoningLevel.OFF, "minimal")
        assertThinkingLevel("gemini-4-flash", ReasoningLevel.LOW, "low")
        assertThinkingLevel("gemini-4-flash", ReasoningLevel.HIGH, "high")
        assertThinkingLevel("gemini-4-pro-preview-20260101", ReasoningLevel.XHIGH, "high")
    }

    @Test
    fun `gemini 3 系列行为不变`() {
        assertThinkingLevel("gemini-3-pro", ReasoningLevel.OFF, "minimal")
        assertThinkingLevel("gemini-3-flash", ReasoningLevel.MEDIUM, "medium")
    }

    @Test
    fun `2_5 代仍走 thinkingBudget`() {
        val off = thinkingConfig("gemini-2.5-flash", ReasoningLevel.OFF)
        assertEquals(0, off?.get("thinkingBudget")?.jsonPrimitive?.int)
        assertNull(off?.get("thinkingLevel"))

        val low = thinkingConfig("gemini-2.5-flash", ReasoningLevel.LOW)
        assertEquals(ReasoningLevel.LOW.budgetTokens, low?.get("thinkingBudget")?.jsonPrimitive?.int)
        assertNull(low?.get("thinkingLevel"))
    }

    @Test
    fun `2_5 pro 的 OFF 不写任何预算参数`() {
        // 既有特例：2.5 pro 关不掉思考（写 budget 会被 API 拒），保持原行为
        val off = thinkingConfig("gemini-2.5-pro", ReasoningLevel.OFF)
        assertEquals(true, off?.get("includeThoughts")?.jsonPrimitive?.content?.toBoolean())
        assertNull(off?.get("thinkingBudget"))
        assertNull(off?.get("thinkingLevel"))
    }

    @Test
    fun `模型级覆盖优先于提供商兜底`() {
        // Gemini4 系走 thinkingLevel,effort 覆盖直接作为 thinkingLevel
        val config = thinkingConfig(
            modelId = "gemini-4-flash",
            level = ReasoningLevel.HIGH,
            override = ReasoningOverride(effort = "pro", budgetTokens = 12_345),
            providerOverride = ReasoningOverride(effort = "provider-text", budgetTokens = 99),
        )
        assertEquals("pro", config?.get("thinkingLevel")?.jsonPrimitive?.content)
        assertNull(config?.get("thinkingBudget"))
        // 2.5 系走 thinkingBudget,budget 覆盖生效
        val budget = thinkingConfig(
            modelId = "gemini-2.5-flash",
            level = ReasoningLevel.HIGH,
            override = ReasoningOverride(budgetTokens = 12_345),
        )
        assertEquals(12_345, budget?.get("thinkingBudget")?.jsonPrimitive?.int)
    }

    @Test
    fun `提供商级兜底在模型无覆盖时生效`() {
        val config = thinkingConfig(
            modelId = "gemini-4-flash",
            level = ReasoningLevel.HIGH,
            providerOverride = ReasoningOverride(effort = "provider-text", budgetTokens = 99),
        )
        assertEquals("provider-text", config?.get("thinkingLevel")?.jsonPrimitive?.content)
        val budget = thinkingConfig(
            modelId = "gemini-2.5-flash",
            level = ReasoningLevel.HIGH,
            providerOverride = ReasoningOverride(budgetTokens = 99),
        )
        assertEquals(99, budget?.get("thinkingBudget")?.jsonPrimitive?.int)
    }

    @Test
    fun `OFF 与 AUTO 不吃覆盖`() {
        val off = thinkingConfig(
            modelId = "gemini-2.5-flash",
            level = ReasoningLevel.OFF,
            override = ReasoningOverride(effort = "x", budgetTokens = 77),
            providerOverride = ReasoningOverride(effort = "y", budgetTokens = 88),
        )
        assertEquals(0, off?.get("thinkingBudget")?.jsonPrimitive?.int)

        // AUTO:不设任何思考参数
        val auto = thinkingConfig(
            modelId = "gemini-3-flash",
            level = ReasoningLevel.AUTO,
            override = ReasoningOverride(effort = "x"),
        )
        assertNull(auto?.get("thinkingLevel"))
    }
}
