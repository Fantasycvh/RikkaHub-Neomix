package app.androidrik.ai.provider.providers.chatgpt

import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.Modality
import app.androidrik.ai.provider.OAuthCredential
import app.androidrik.ai.provider.ProviderQuota
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.provider.UsageWindow
import app.androidrik.ai.provider.providers.openai.CODEX_CAPABILITIES
import app.androidrik.ai.provider.providers.openai.ResponseAPI
import app.androidrik.ai.provider.providers.openai.parseCodexModels
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.util.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * ChatGPT 账号登录（Codex 后端）的请求体、模型目录与订阅用量解析
 * （移植自上游 #2186 `ChatGPTProviderTest`）。
 *
 * 与上游测试的差异：
 * - `CODEX_CAPABILITIES` / `parseCodexModels` 从本仓 `providers/openai` 包复用（旧 Codex 设备码
 *   订阅链路也在用，见 `CodexSupport.kt`），因此改为显式 import；
 * - 上游 `supportsBuiltInSearch` 测试依赖上游重写后的 `ProviderSetting` 扩展，本仓没有该扩展
 *   （内置搜索支持由 app 层计算），该用例不移植。
 */
class ChatGPTProviderTest {
    private val api = ResponseAPI(OkHttpClient())

    private fun codexBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        sessionId: String? = "session-1",
    ) = api.buildRequestBody(CODEX_CAPABILITIES, messages, params.copy(sessionId = sessionId), stream = true)

    @Test
    fun `system prompt becomes leading developer message instead of instructions`() {
        val body = codexBody(
            messages = listOf(UIMessage.system("Be brief."), UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "gpt-5.5")),
        )

        assertFalse("instructions" in body)
        val input = body["input"]!!.jsonArray
        val developer = input[0].jsonObject
        assertEquals("developer", developer["role"]!!.jsonPrimitive.content)
        assertEquals("Be brief.", developer["content"]!!.jsonPrimitive.content)
        assertEquals("user", input[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no developer message without system prompt`() {
        val body = codexBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "gpt-5.5")),
        )

        val input = body["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `streams without storage and drops unsupported parameters`() {
        val body = codexBody(
            messages = listOf(UIMessage.user("hello")),
            // 非推理模型名，ResponseAPI 会带上 temperature/top_p
            params = TextGenerationParams(
                model = Model(modelId = "custom-model"),
                temperature = 0.7f,
                topP = 0.9f,
                maxTokens = 1024,
            ),
        )

        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, body["store"]!!.jsonPrimitive.content.toBoolean())
        assertFalse("max_output_tokens" in body)
        assertFalse("temperature" in body)
        assertFalse("top_p" in body)
        // 路由字段保持在最前面
        assertEquals(listOf("model", "stream", "store"), body.keys.take(3))
    }

    @Test
    fun `always includes encrypted reasoning and prompt cache key`() {
        val body = codexBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(model = Model(modelId = "gpt-5.5")),
            sessionId = "conversation-42",
        )

        assertEquals(
            listOf("reasoning.encrypted_content"),
            body["include"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("conversation-42", body["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `keeps include from reasoning models and omits missing cache key`() {
        val body = codexBody(
            messages = listOf(UIMessage.user("hello")),
            params = TextGenerationParams(
                model = Model(modelId = "gpt-5.5", abilities = listOf(ModelAbility.REASONING)),
            ),
            sessionId = null,
        )

        assertEquals(1, body["include"]!!.jsonArray.size)
        assertTrue("reasoning" in body)
        assertNull(body["prompt_cache_key"])
    }

    @Test
    fun `parses visible codex models`() {
        val models = parseCodexModels(
            """
            {
              "models": [
                {
                  "slug": "gpt-5.5",
                  "display_name": "GPT-5.5",
                  "visibility": "list",
                  "supported_reasoning_levels": [{"effort": "low", "description": "fast"}],
                  "input_modalities": ["text", "image"],
                  "context_window": 272000
                },
                {
                  "slug": "gpt-5.5-mini",
                  "display_name": "GPT-5.5 mini",
                  "visibility": "list",
                  "supported_reasoning_levels": [],
                  "input_modalities": ["text"]
                },
                {
                  "slug": "internal-model",
                  "display_name": "Internal",
                  "visibility": "hide",
                  "supported_reasoning_levels": []
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf("gpt-5.5", "gpt-5.5-mini"), models.map { it.modelId })
        val full = models[0]
        assertEquals("GPT-5.5", full.displayName)
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), full.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), full.abilities)
        assertEquals(272000, full.contextLength)
        val mini = models[1]
        assertEquals(listOf(Modality.TEXT), mini.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL), mini.abilities)
    }

    @Test
    fun `falls back to all models when none are listed`() {
        val models = parseCodexModels(
            """{"models": [{"slug": "a", "display_name": "A", "visibility": "hide"}]}"""
        )

        assertEquals(listOf("a"), models.map { it.modelId })
        // 没给 input_modalities 时按 Codex 的默认值：文本 + 图片
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), models.single().inputModalities)
    }

    @Test
    fun `chatgpt provider round trips through provider list serialization`() {
        val provider = ProviderSetting.ChatGPT(
            credential = OAuthCredential(accessToken = "access", refreshToken = "refresh", accountId = "acc"),
        )

        val encoded = json.encodeToString<List<ProviderSetting>>(listOf(provider))
        assertTrue(encoded.contains("\"type\":\"chatgpt\""))
        val decoded = json.decodeFromString<List<ProviderSetting>>(encoded).single()

        assertTrue(decoded is ProviderSetting.ChatGPT)
        assertEquals(provider.credential, (decoded as ProviderSetting.ChatGPT).credential)
    }

    @Test
    fun `usage response maps both rate limit windows`() {
        val usage = parseCodexUsage(
            """{"plan_type":"plus","rate_limit":{"allowed":true,"limit_reached":false,
                "primary_window":{"used_percent":42,"limit_window_seconds":18000,"reset_after_seconds":600,"reset_at":1760000000},
                "secondary_window":{"used_percent":7,"limit_window_seconds":604800,"reset_after_seconds":9000,"reset_at":1760500000}},
                "credits":{"has_credits":false,"unlimited":false,"balance":null}}"""
        )

        assertEquals(
            ProviderQuota.UsageLimits(
                windows = listOf(
                    UsageWindow(
                        usedPercent = 42.0,
                        windowMinutes = 300,
                        resetsAt = Instant.fromEpochSeconds(1760000000)
                    ),
                    UsageWindow(
                        usedPercent = 7.0,
                        windowMinutes = 10080,
                        resetsAt = Instant.fromEpochSeconds(1760500000)
                    ),
                ),
                limitReached = false,
            ),
            usage,
        )
    }

    @Test
    fun `usage response without rate limit has no windows`() {
        val usage = parseCodexUsage("""{"plan_type":"free","rate_limit":null}""")

        assertTrue(usage.windows.isEmpty())
        assertFalse(usage.limitReached)
    }

    @Test
    fun `usage response reports reached limit and skips unknown window fields`() {
        val usage = parseCodexUsage(
            """{"rate_limit":{"allowed":false,"limit_reached":true,
                "primary_window":{"used_percent":100,"limit_window_seconds":0,"reset_at":0},"secondary_window":null}}"""
        )

        assertTrue(usage.limitReached)
        assertEquals(listOf(UsageWindow(usedPercent = 100.0, windowMinutes = null, resetsAt = null)), usage.windows)
    }
}
