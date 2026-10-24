package app.androidrik.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.ReasoningOverride
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * ChatCompletions 方言下思考强度覆盖的**接线**测试。
 *
 * 同一套 `ReasoningOverride(effort, budgetTokens)` 要落到各 host 不同字段：
 * - OpenAI 官方 / deepseek / nvidia / opencode：`reasoning_effort`（effort）
 * - 阿里云百炼（dashscope）：`thinking_budget`（budget）+ `enable_thinking`
 * - OpenRouter：`reasoning.effort` / `reasoning.enabled`
 *
 * 两个关键不变量：①模型级优先、提供商兜底；②OFF/AUTO 不吃覆盖（开关语义不变）。
 * 按本仓既有做法（[ChatCompletionsAPIMoonshotTest]）反射调用私有 `buildChatCompletionRequest` 读请求体。
 */
class ChatCompletionsAPIReasoningOverrideTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    private fun buildRequest(
        baseUrl: String,
        level: ReasoningLevel,
        modelOverride: ReasoningOverride? = null,
        providerOverride: ReasoningOverride? = null,
        modelId: String = "gpt-5",
        reasoningCapable: Boolean = true,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        val params = TextGenerationParams(
            model = Model(
                modelId = modelId,
                abilities = if (reasoningCapable) listOf(ModelAbility.REASONING) else emptyList(),
                reasoningOverride = modelOverride,
            ),
            reasoningLevel = level,
        )
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = baseUrl,
            reasoningOverride = providerOverride,
        )
        return method.invoke(
            api,
            listOf(UIMessage.user("hi")),
            params,
            providerSetting,
            false,
        ) as JsonObject
    }

    private fun JsonObject.effort(): String? = this["reasoning_effort"]?.jsonPrimitive?.content

    private fun JsonObject.budget(): Int? = this["thinking_budget"]?.jsonPrimitive?.int

    // ==================== OpenAI 官方（else 分支：reasoning_effort） ====================

    @Test
    fun `openai 官方无覆盖时保持内置 effort`() {
        val body = buildRequest("https://api.openai.com/v1", ReasoningLevel.HIGH)
        assertEquals("high", body.effort())
    }

    @Test
    fun `openai 官方模型级 effort 覆盖生效`() {
        val body = buildRequest(
            "https://api.openai.com/v1",
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertEquals("pro", body.effort())
    }

    @Test
    fun `openai 官方提供商级兜底生效`() {
        val body = buildRequest(
            "https://api.openai.com/v1",
            ReasoningLevel.HIGH,
            providerOverride = ReasoningOverride(effort = "provider-pro"),
        )
        assertEquals("provider-pro", body.effort())
    }

    @Test
    fun `openai 官方模型级优先于提供商兜底`() {
        val body = buildRequest(
            "https://api.openai.com/v1",
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "model-pro"),
            providerOverride = ReasoningOverride(effort = "provider-pro"),
        )
        assertEquals("model-pro", body.effort())
    }

    @Test
    fun `openai 官方只覆盖 budget 时 effort 回退内置`() {
        // 两个自由度互相独立：effort 没被覆盖就仍走内置映射，budget 在这里无处可写
        val body = buildRequest(
            "https://api.openai.com/v1",
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(budgetTokens = 12_345),
        )
        assertEquals("high", body.effort())
        assertNull(body.budget())
    }

    @Test
    fun `openai 官方 OFF 不吃覆盖`() {
        // 既有行为：OFF 在官方 completions 上仍写 reasoning_effort，取内置兜底值 low
        val body = buildRequest(
            "https://api.openai.com/v1",
            ReasoningLevel.OFF,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertEquals("low", body.effort())
    }

    @Test
    fun `openai 官方 AUTO 不吃覆盖且不写 reasoning_effort`() {
        val body = buildRequest(
            "https://api.openai.com/v1",
            ReasoningLevel.AUTO,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertNull(body.effort())
    }

    @Test
    fun `不具备推理能力的模型不写 reasoning_effort`() {
        val body = buildRequest(
            "https://api.openai.com/v1",
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "pro"),
            reasoningCapable = false,
        )
        assertNull(body.effort())
    }

    // ==================== 阿里云百炼（dashscope：thinking_budget） ====================

    @Test
    fun `dashscope 无覆盖时用内置 budget`() {
        val body = buildRequest("https://dashscope.aliyuncs.com/compatible-mode/v1", ReasoningLevel.HIGH)
        assertEquals(true, body["enable_thinking"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(ReasoningLevel.HIGH.budgetTokens, body.budget())
    }

    @Test
    fun `dashscope budget 覆盖生效`() {
        val body = buildRequest(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(budgetTokens = 12_345),
        )
        assertEquals(12_345, body.budget())
    }

    @Test
    fun `dashscope 提供商级兜底与模型级优先`() {
        val fallback = buildRequest(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ReasoningLevel.HIGH,
            providerOverride = ReasoningOverride(budgetTokens = 99),
        )
        assertEquals(99, fallback.budget())

        val modelWins = buildRequest(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(budgetTokens = 12_345),
            providerOverride = ReasoningOverride(budgetTokens = 99),
        )
        assertEquals(12_345, modelWins.budget())
    }

    @Test
    fun `dashscope 只覆盖 effort 时 budget 回退内置`() {
        val body = buildRequest(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertEquals(ReasoningLevel.HIGH.budgetTokens, body.budget())
    }

    @Test
    fun `dashscope OFF 不吃覆盖且关闭思考`() {
        val body = buildRequest(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            ReasoningLevel.OFF,
            modelOverride = ReasoningOverride(budgetTokens = 12_345),
        )
        assertEquals(false, body["enable_thinking"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(ReasoningLevel.OFF.budgetTokens, body.budget())
    }

    // ==================== OpenRouter（reasoning.effort / reasoning.enabled） ====================

    @Test
    fun `openrouter effort 覆盖生效`() {
        val body = buildRequest(
            "https://openrouter.ai/api/v1",
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertEquals("pro", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `openrouter OFF 不吃覆盖`() {
        val body = buildRequest(
            "https://openrouter.ai/api/v1",
            ReasoningLevel.OFF,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertEquals("none", body["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `openrouter AUTO 不吃覆盖且只开 enabled`() {
        val body = buildRequest(
            "https://openrouter.ai/api/v1",
            ReasoningLevel.AUTO,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        val reasoning = body["reasoning"]?.jsonObject
        assertEquals(true, reasoning?.get("enabled")?.jsonPrimitive?.content?.toBoolean())
        assertNull(reasoning?.get("effort"))
    }

    // ==================== DeepSeek 官方（thinking + reasoning_effort） ====================

    @Test
    fun `deepseek effort 覆盖生效`() {
        val body = buildRequest(
            "https://api.deepseek.com/v1",
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("pro", body.effort())
    }

    @Test
    fun `deepseek AUTO 不吃覆盖且不写 reasoning_effort`() {
        val body = buildRequest(
            "https://api.deepseek.com/v1",
            ReasoningLevel.AUTO,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(body.effort())
    }

    // ==================== NVIDIA（deepseek-v4 特例：默认换算成 max） ====================

    @Test
    fun `nvidia deepseek-v4 无覆盖时用内置换算`() {
        val body = buildRequest(
            "https://integrate.api.nvidia.com/v1",
            ReasoningLevel.XHIGH,
            modelId = "deepseek-v4-flash",
        )
        assertEquals("max", body.effort())
    }

    @Test
    fun `nvidia deepseek-v4 effort 覆盖替换内置换算`() {
        val body = buildRequest(
            "https://integrate.api.nvidia.com/v1",
            ReasoningLevel.XHIGH,
            modelOverride = ReasoningOverride(effort = "pro"),
            modelId = "deepseek-v4-flash",
        )
        assertEquals("pro", body.effort())
    }

    @Test
    fun `nvidia deepseek-v4 OFF 不吃覆盖`() {
        val body = buildRequest(
            "https://integrate.api.nvidia.com/v1",
            ReasoningLevel.OFF,
            modelOverride = ReasoningOverride(effort = "pro"),
            modelId = "deepseek-v4-flash",
        )
        assertEquals("none", body.effort())
    }
}
