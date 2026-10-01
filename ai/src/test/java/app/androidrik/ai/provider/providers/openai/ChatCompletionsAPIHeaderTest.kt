package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.CustomHeader
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.util.KeyRoulette
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自定义请求头在**真实请求链路**上的行为（起一个本地服务端收请求，直接看 wire 上的头）。
 *
 * 为什么不能只靠纯函数单测：`RequestHeadersTest` 验的是合并/覆盖语义，而「调用点有没有
 * 用上它」只有真发一次请求才能证明——本次重构恰恰把 19 个调用点的
 * `.headers(params.customHeaders.toHeaders())` 换成了统一的 `applyCustomHeaders(...)`。
 *
 * 钉住三件事：
 * 1. 自定义头能盖掉内置头（`Authorization`），且 wire 上只有一个值；
 * 2. 提供商级与请求级同名时请求级胜出（X-Dup = model 而不是 provider），同样只有一个值；
 * 3. 两边各自独有的头都照常带上（证明「合并」没有把谁吃掉）。
 */
class ChatCompletionsAPIHeaderTest {

    @Test
    fun `真实请求上同名头只有一个值且请求级覆盖提供商级`() {
        val server = MockWebServer()
        // 让请求走到「发出去」为止即可：401 的响应体是 OpenAI 兼容的错误格式
        server.enqueue(MockResponse(code = 401, body = """{"error":{"message":"stop here"}}"""))
        server.start()
        try {
            val provider = ProviderSetting.OpenAI(
                name = "header-test",
                apiKey = "sk-built-in",
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                customHeaders = listOf(
                    CustomHeader("Authorization", "Bearer custom-wins"),
                    CustomHeader("X-From-Provider", "provider"),
                    CustomHeader("X-Dup", "provider"),
                ),
            )
            val params = TextGenerationParams(
                model = Model(modelId = "gpt-4o"),
                customHeaders = listOf(
                    CustomHeader("X-From-Model", "model"),
                    CustomHeader("X-Dup", "model"),
                ),
            )

            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            // 请求必然失败（401），但这不影响我们对「发出去的请求」做断言
            runCatching {
                runBlocking { api.generateText(provider, listOf(UIMessage.user("hi")), params) }
            }

            val recorded = server.takeRequest()
            val headers = recorded.headers

            assertEquals(
                "自定义 Authorization 应当盖掉内置的 key 头，且只剩一个值",
                listOf("Bearer custom-wins"),
                headers.values("Authorization"),
            )
            assertEquals(
                "同名头（提供商级 vs 请求级）应当由请求级取胜，且只剩一个值",
                listOf("model"),
                headers.values("X-Dup"),
            )
            assertEquals(listOf("provider"), headers.values("X-From-Provider"))
            assertEquals(listOf("model"), headers.values("X-From-Model"))
        } finally {
            server.close()
        }
    }
}
