package app.androidrik.ai.provider.providers

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
 * Claude 的思考强度覆盖**接线**测试。
 *
 * Claude 走 adaptive thinking + `output_config.effort`（无 budget 参数），覆盖只应影响
 * effort 文本；OFF/AUTO 的开关语义不变（不出现 `output_config`）。
 * 按本仓既有做法（[ClaudeProviderPromptCacheTest]）用反射调用私有 `buildMessageRequest`，
 * 直接读请求体，验的是「哪个值真的写进了请求」而不是纯函数。
 */
class ClaudeProviderReasoningOverrideTest {

    private lateinit var provider: ClaudeProvider

    @Before
    fun setUp() {
        provider = ClaudeProvider(OkHttpClient())
    }

    private fun buildRequest(
        level: ReasoningLevel,
        modelOverride: ReasoningOverride? = null,
        providerOverride: ReasoningOverride? = null,
        reasoningCapable: Boolean = true,
    ): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        val params = TextGenerationParams(
            model = Model(
                modelId = "claude-opus-4-7",
                abilities = if (reasoningCapable) listOf(ModelAbility.REASONING) else emptyList(),
                reasoningOverride = modelOverride,
            ),
            reasoningLevel = level,
        )
        val providerSetting = ProviderSetting.Claude(reasoningOverride = providerOverride)
        return method.invoke(
            provider,
            providerSetting,
            listOf(UIMessage.user("hi")),
            params,
            false,
        ) as JsonObject
    }

    private fun JsonObject.effort(): String? =
        this["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content

    private fun JsonObject.thinkingType(): String? =
        this["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content

    @Test
    fun `无覆盖时保持内置档位映射`() {
        val body = buildRequest(ReasoningLevel.HIGH)
        assertEquals("adaptive", body.thinkingType())
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
    fun `提供商级兜底在模型无覆盖时生效`() {
        val body = buildRequest(
            ReasoningLevel.HIGH,
            providerOverride = ReasoningOverride(effort = "provider-pro"),
        )
        assertEquals("provider-pro", body.effort())
    }

    @Test
    fun `模型级覆盖优先于提供商兜底`() {
        val body = buildRequest(
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "model-pro"),
            providerOverride = ReasoningOverride(effort = "provider-pro"),
        )
        assertEquals("model-pro", body.effort())
    }

    @Test
    fun `只覆盖 budget 时 Claude 忽略它并回退内置 effort`() {
        // Claude 没有 budget 自由度：只填 budgetTokens 不应改变 effort，也不应凭空多出字段
        val body = buildRequest(
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(budgetTokens = 12_345),
        )
        assertEquals(ReasoningLevel.HIGH.effort, body.effort())
        assertEquals(setOf("effort"), body["output_config"]!!.jsonObject.keys)
    }

    @Test
    fun `OFF 不吃覆盖且不写 output_config`() {
        val body = buildRequest(
            ReasoningLevel.OFF,
            modelOverride = ReasoningOverride(effort = "pro"),
            providerOverride = ReasoningOverride(effort = "provider-pro"),
        )
        assertEquals("disabled", body.thinkingType())
        assertNull(body["output_config"])
    }

    @Test
    fun `AUTO 不吃覆盖且不写 output_config`() {
        val body = buildRequest(
            ReasoningLevel.AUTO,
            modelOverride = ReasoningOverride(effort = "pro"),
        )
        assertEquals("adaptive", body.thinkingType())
        assertNull(body["output_config"])
    }

    @Test
    fun `不具备推理能力的模型完全不写思考参数`() {
        val body = buildRequest(
            ReasoningLevel.HIGH,
            modelOverride = ReasoningOverride(effort = "pro"),
            reasoningCapable = false,
        )
        assertNull(body["thinking"])
        assertNull(body["output_config"])
    }
}
