package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.OpenAIAuthType
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.CustomHeader
import app.androidrik.ai.ui.UIMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Codex 订阅模式的 Responses 请求体（对齐上游 #2186 `CODEX_CAPABILITIES`）：
 * - 不带采样参数（`temperature`/`top_p`/`max_output_tokens`）
 * - system 提示以 `developer` 消息进 `input`，不写 `instructions`
 * - 推理模型带 `reasoning.effort` + 加密推理 `include`
 * - 带稳定 `prompt_cache_key`
 */
class CodexRequestBodyTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    private fun codexSetting(
        customHeaders: List<CustomHeader> = emptyList(),
    ) = ProviderSetting.OpenAI(
        authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
        customHeaders = customHeaders,
    )

    private fun buildBody(
        setting: ProviderSetting.OpenAI = codexSetting(),
        abilities: List<ModelAbility> = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        stream: Boolean = true,
    ): JsonObject = api.buildRequestBody(
        providerSetting = setting,
        messages = listOf(
            UIMessage.system("你是 Neomix 助手"),
            UIMessage.user("你好"),
        ),
        params = TextGenerationParams(
            model = Model(modelId = "gpt-5.1-codex", abilities = abilities),
            temperature = 0.7f,
            topP = 0.9f,
            maxTokens = 1024,
            reasoningLevel = ReasoningLevel.HIGH,
        ),
        stream = stream,
    )

    @Test
    fun `订阅模式不发送采样参数`() {
        val body = buildBody()
        assertNull(body["temperature"])
        assertNull(body["top_p"])
        assertNull(body["max_output_tokens"])
    }

    @Test
    fun `订阅模式用 developer 消息承载 system 提示`() {
        val body = buildBody()
        assertNull("Codex 不走 instructions 字段", body["instructions"])

        val input = body["input"]!!.jsonArray
        val first = input.first().jsonObject
        assertEquals("developer", first["role"]!!.jsonPrimitive.content)
        assertEquals("你是 Neomix 助手", first["content"]!!.jsonPrimitive.content)
        // 首条 system 不应重复出现在后续消息里
        val roles = input.drop(1).mapNotNull { it.jsonObject["role"]?.jsonPrimitive?.content }
        assertFalse(roles.contains("developer"))
    }

    @Test
    fun `订阅模式强制流式并带加密推理`() {
        val body = buildBody()
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("false", body["store"]!!.jsonPrimitive.content)

        val include = body["include"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(include.contains("reasoning.encrypted_content"))

        val reasoning = body["reasoning"]!!.jsonObject
        assertEquals("high", reasoning["effort"]!!.jsonPrimitive.content)
        assertEquals("auto", reasoning["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun `非推理模型也带加密推理 include`() {
        val body = buildBody(abilities = listOf(ModelAbility.TOOL))
        assertNull(body["reasoning"])
        assertTrue(
            body["include"]!!.jsonArray.map { it.jsonPrimitive.content }
                .contains("reasoning.encrypted_content")
        )
    }

    @Test
    fun `prompt_cache_key 默认用 provider 加 model 稳定键`() {
        val setting = codexSetting()
        val body = buildBody(setting = setting)
        assertEquals(
            "codex-${setting.id}-gpt-5.1-codex",
            body["prompt_cache_key"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `prompt_cache_key 优先取 session-id 自定义头`() {
        val body = buildBody(
            setting = codexSetting(
                customHeaders = listOf(CustomHeader("session-id", "conv-123")),
            )
        )
        assertEquals("conv-123", body["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `API Key 模式行为不变`() {
        val body = api.buildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            messages = listOf(UIMessage.system("sys"), UIMessage.user("hi")),
            // gpt-5/o 系列按既有规则不发 temperature；这里用 gpt-4o 验证 API Key 路径原行为
            params = TextGenerationParams(
                model = Model(modelId = "gpt-4o"),
                temperature = 0.5f,
                maxTokens = 100,
            ),
            stream = false,
        )
        assertEquals("sys", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("0.5", body["temperature"]!!.jsonPrimitive.content)
        assertEquals("100", body["max_output_tokens"]!!.jsonPrimitive.content)
        val roles = (body["input"] as JsonArray).mapNotNull {
            it.jsonObject["role"]?.jsonPrimitive?.content
        }
        assertFalse("API Key 模式不写 developer 消息", roles.contains("developer"))
        assertNull(body["prompt_cache_key"])
    }
}
