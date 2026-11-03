package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.provider.ProviderSetting
import app.androidrik.ai.provider.TextGenerationParams
import app.androidrik.ai.ui.UIMessage
import app.androidrik.ai.util.KeyRoulette
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * 网关兼容：有 tools 时必须显式发 `tool_choice:"auto"`。
 *
 * 省略该字段时部分中转/聚合端点会走另一条翻译路径，把 `tool_calls[].function.name`
 * 翻成空串——模型看似调了工具，实际一个都执行不了（NMoi-Agent 2026-11-03 真机定位，
 * bigeye 也无条件发送同一字段）。规范上省略等于 auto，但实现不保证。
 */
class ChatCompletionsToolChoiceTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    @Test
    fun `tools present sends explicit tool_choice auto`() {
        val body = buildRequest(listOf(tool()))
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun `no tools omits tool_choice`() {
        val body = buildRequest(emptyList())
        assertFalse("没有 tools 时不得发送 tool_choice", body.containsKey("tool_choice"))
    }

    private fun tool() = Tool(
        name = "get_current_time",
        description = "Get the current time.",
        execute = { emptyList() },
    )

    /** 通过反射调用私有的 buildChatCompletionRequest 构造一次完整请求体（同 ChatCompletionsToolSchemaTest）。 */
    private fun buildRequest(tools: List<Tool>): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true

        val params = TextGenerationParams(
            model = Model(
                modelId = "test-model",
                abilities = listOf(ModelAbility.TOOL),
            ),
            tools = tools,
        )
        return method.invoke(
            api,
            listOf(UIMessage.user("Use the get_current_time tool.")),
            params,
            ProviderSetting.OpenAI(),
            false,
        ) as JsonObject
    }
}
