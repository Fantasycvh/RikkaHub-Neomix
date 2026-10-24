package app.androidrik.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.core.ReasoningOverride
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Responses API 下思考强度覆盖的**接线**测试（`reasoning.effort`）。
 *
 * Responses 的 effort 只在「非 AUTO」时写入；OFF 仍写内置 `none` 以保持既有行为。
 * 这里直接调用 `internal` 的 [ResponseAPI.buildRequestBody]（本仓既有做法，
 * 见 [ResponseAPIMessageTest]），断言请求体里最终的值。
 */
class ResponseAPIReasoningOverrideTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    private fun buildRequest(
        level: ReasoningLevel,
        modelOverride: ReasoningOverride? = null,
        providerOverride: ReasoningOverride? = null,
        reasoningCapable: Boolean = true,
    ): JsonObject {
        val params = TextGenerationParams(
            model = Model(
                modelId = "gpt-5",
                abilities = if (reasoningCapable) listOf(ModelAbility.REASONING) else emptyList(),
                reasoningOverride = modelOverride,
            ),
            reasoningLevel = level,
        )
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1",
            reasoningOverride = providerOverride,
        )
        return api.buildRequestBody(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user("hi")),
            params = params,
            stream = false,
        )
    }

    private fun JsonObject.effort(): String? =
        this["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content

    @Test
    fun `无覆盖时保持内置 effort`() {
        val body = buildRequest(ReasoningLevel.HIGH)
        assertEquals(ReasoningLevel.HIGH.effort, body.effort())
    }

    @Test
    fun `模型级 effort 覆盖生效`() {
        val body = buildRequest(
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertEquals("pro", body.effort())
    }

    @Test
    fun `提供商级兜底生效且模型级优先`() {
        val fallback = buildRequest(
            ReasoningLevel.HIGH,
            providerOverride = ReasoningOverride(effort = "provider-pro"),
        )
        assertEquals("provider-pro", fallback.effort())

        val modelWins = buildRequest(
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "model-pro"),
            providerOverride = ReasoningOverride(effort = "provider-pro"),
        )
        assertEquals("model-pro", modelWins.effort())
    }

    @Test
    fun `只覆盖 budget 时 effort 回退内置`() {
        val body = buildRequest(
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(budgetTokens = 12_345),
        )
        assertEquals(ReasoningLevel.HIGH.effort, body.effort())
    }

    @Test
    fun `OFF 不吃覆盖`() {
        val body = buildRequest(
            ReasoningLevel.OFF,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertEquals(ReasoningLevel.OFF.effort, body.effort())
    }

    @Test
    fun `AUTO 不吃覆盖且不写 effort`() {
        val body = buildRequest(
            ReasoningLevel.AUTO,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertNull(body.effort())
    }

    @Test
    fun `不具备推理能力的模型不写 reasoning`() {
        val body = buildRequest(
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "pro"),
            reasoningCapable = false,
        )
        assertNull(body["reasoning"])
    }
}
