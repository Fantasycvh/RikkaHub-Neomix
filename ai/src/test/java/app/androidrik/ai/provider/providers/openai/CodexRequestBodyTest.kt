package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.MessageRole
import app.androidrik.ai.core.ReasoningLevel
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.OpenAIReasoningMetadata
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.ui.toMetadata
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
 * Codex 端点（baseUrl 指向官方 Codex 后端）的 Responses 请求体，套用 `CODEX_CAPABILITIES`
 * （对齐上游 #2186）：
 * - 不带采样参数（`temperature`/`top_p`/`max_output_tokens`）
 * - system 提示以 `developer` 消息进 `input`，不写 `instructions`
 * - 推理模型带 `reasoning.effort` + 加密推理 `include`
 * - `prompt_cache_key` 只取显式 sessionId（P5 删掉旧的 provider+model 稳定键兜底）
 */
class CodexRequestBodyTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    private fun codexSetting() = ProviderSetting.OpenAI(
        baseUrl = "https://chatgpt.com/backend-api/codex",
    )

    private fun buildBody(
        setting: ProviderSetting.OpenAI = codexSetting(),
        abilities: List<ModelAbility> = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        stream: Boolean = true,
        sessionId: String? = null,
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
            sessionId = sessionId,
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
    fun `prompt_cache_key 只取显式 sessionId`() {
        val body = buildBody(sessionId = "conv-123")
        assertEquals("conv-123", body["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `没有 sessionId 时不写 prompt_cache_key`() {
        val body = buildBody()
        assertNull(body["prompt_cache_key"])
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

    /**
     * 回归：Codex 后端要求 reasoning item 的 `summary` **字段存在**（空数组也算）。
     *
     * 2026-11-02 真机实测：只回传 `encrypted_content`、缺 `summary` 的请求全部 400，界面表现为
     * "SSE stream failed without exception"（历史里有推理 + 工具的第二轮必炸）。
     */
    @Test
    fun `回传加密推理时仍带 summary 字段（Codex 必需）`() {
        val assistant = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "plaintext reasoning",
                    metadata = OpenAIReasoningMetadata(
                        reasoningId = "rs_1",
                        encryptedContent = "enc-blob",
                    ).toMetadata(),
                )
            ),
        )
        val body = api.buildRequestBody(
            providerSetting = codexSetting(),
            messages = listOf(UIMessage.system("sys"), UIMessage.user("q"), assistant),
            params = TextGenerationParams(
                model = Model(
                    modelId = "gpt-5.1-codex",
                    abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.HIGH,
            ),
            stream = true,
        )

        val item = (body["input"] as JsonArray).map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals("rs_1", item["id"]?.jsonPrimitive?.content)
        assertEquals("enc-blob", item["encrypted_content"]?.jsonPrimitive?.content)
        assertTrue("summary 字段必须存在（可为空数组）", item.containsKey("summary"))
        assertEquals("加密时不回放明文 summary", 0, item["summary"]!!.jsonArray.size)
        assertFalse("加密时不回放明文 content", item.containsKey("content"))
    }
}
