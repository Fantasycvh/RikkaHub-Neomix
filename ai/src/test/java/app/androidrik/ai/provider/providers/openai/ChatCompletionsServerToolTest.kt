package app.androidrik.ai.provider.providers.openai

import app.androidrik.ai.provider.BuiltInTools
import app.androidrik.ai.provider.Model
import app.androidrik.ai.provider.ModelAbility
import app.androidrik.ai.ui.ServerToolStatus
import app.androidrik.ai.ui.UIMessagePart
import app.androidrik.ai.util.KeyRoulette
import app.androidrik.ai.core.InputSchema
import app.androidrik.ai.core.Tool
import app.androidrik.ai.provider.TextGenerationParams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 内置 web tools 走 Chat Completions 的收发契约守卫（配料 01 §8 / 03 §5.2）。
 *
 * ## 为什么这些断言值得存在
 *
 * 1. **发送是裸 `{"type":…}`**：与 function 工具混排在同一个 `tools` 数组，无 `function`
 *    包装；没有 function 工具时不下发 `tool_choice`（官方规定 tool_choice 不能指定
 *    内置工具，误下发就是 400）。
 * 2. **原始 call JSON 逐字段保留**：`signature` 官方要求原样回传，缺了下一轮 400——
 *    用「重新拼装」不如「原样带回」保险（块形态未真机确认，配料 04 探测清单 #5）。
 * 3. **解析跳过而不是抛**：解析链跑在每个 delta 上，抛一次 = 整轮中断；
 *    思考链少一个块远好于丢整个回答。
 * 4. **注解未知类型跳过**：定向放宽（本仓决策，偏离上游的响亮失败）——
 *    DeepSeek annotations 形态未确认，一次漂移不该杀掉整轮。
 */
class ChatCompletionsServerToolTest {

    private val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())

    private fun parse(json: String) = api.parseMessage(Json.parseToJsonElement(json).jsonObject)

    // ---------------- 解析：server_tool_calls → ServerTool part ----------------

    @Test
    fun `server_tool_calls steps parse into server tool parts with raw json preserved`() {
        val message = parse(
            """
            {"role":"assistant","content":"答案","server_tool_calls":[
              {"index":0,"reasoning_content":"先搜索",
               "calls":[{"id":"call_1","type":"web_search","kind":"search","action":{"queries":["DeepSeek"]},
                         "status":"completed","sources":[{"title":"T","url":"https://e/x"}]},
                        {"id":"call_2","type":"web_fetch","kind":"read_link","action":{"url":"https://e/x"},
                         "status":"completed","page":{"title":"T"},"signature":"sig-abc"}]}
            ]}
            """.trimIndent(),
        )

        val tools = message.parts.filterIsInstance<UIMessagePart.ServerTool>()
        assertEquals(2, tools.size)

        val search = tools[0]
        assertEquals("search", search.toolName)
        assertEquals(ServerToolStatus.COMPLETED, search.status)
        assertEquals("call_1", search.toolCallId)
        val searchInput = search.input as? JsonObject
        assertEquals("""{"queries":["DeepSeek"]}""", searchInput?.get("action").toString())

        val fetch = tools[1]
        assertEquals("read_link", fetch.toolName)
        assertEquals("sig-abc", ((fetch.metadata?.get("deepseek_raw_call")) as? JsonObject)
            ?.get("signature")?.let { (it as JsonPrimitive).content })
        assertEquals(0, (fetch.metadata?.get("deepseek_step") as? JsonPrimitive)?.content?.toInt())
        assertEquals("先搜索", (fetch.metadata?.get("deepseek_reasoning") as? JsonPrimitive)?.content)
    }

    @Test
    fun `failed status maps to failed`() {
        val message = parse(
            """{"role":"assistant","content":"","server_tool_calls":[
               {"index":0,"calls":[{"id":"c1","type":"web_search","kind":"search","status":"failed"}]}]}""",
        )
        assertEquals(ServerToolStatus.FAILED, message.parts.filterIsInstance<UIMessagePart.ServerTool>().single().status)
    }

    @Test
    fun `malformed steps are skipped not thrown`() {
        val message = parse(
            """{"role":"assistant","content":"ok","server_tool_calls":[
               null,{"no_calls":true},"garbage",
               {"index":1,"calls":[null,{"id":"c2","kind":"search","status":"completed"}]}]}""",
        )
        val tools = message.parts.filterIsInstance<UIMessagePart.ServerTool>()
        assertEquals(1, tools.size)
        assertEquals("c2", tools.single().toolCallId)
    }

    // ---------------- 注解：未知类型定向放宽 ----------------

    @Test
    fun `unknown annotation type is skipped instead of failing the whole round`() {
        val message = parse(
            """{"role":"assistant","content":"Hi","annotations":[
               {"type":"url_citation","url_citation":{"title":"T","url":"https://e/x"}},
               {"type":"future_citation","payload":123}]}""",
        )
        assertEquals(1, message.annotations.size)
    }

    // ---------------- 回传：重建 steps 并保留原始 JSON ----------------

    @Test
    fun `rebuild round-trips raw calls with signature intact`() {
        val rawCall =
            """{"id":"call_2","type":"web_fetch","kind":"read_link","status":"completed",
                "signature":"sig-abc","page":{"title":"T"}}"""
        val message = parse(
            """{"role":"assistant","content":"","server_tool_calls":[
               {"index":0,"reasoning_content":"先看链接","calls":[$rawCall]}]}""",
        )
        val parts = message.parts.filterIsInstance<UIMessagePart.ServerTool>()
        val steps = rebuildServerToolCallSteps(parts)
        assertNotNull(steps)

        val step = (steps as kotlinx.serialization.json.JsonArray).single() as JsonObject
        assertEquals(0, ((step["index"] as JsonPrimitive).content).toInt())
        assertEquals("先看链接", (step["reasoning_content"] as JsonPrimitive).content)
        val calls = step["calls"] as kotlinx.serialization.json.JsonArray
        assertEquals(1, calls.size)
        val call = calls.single() as JsonObject
        // signature 原样带回（官方要求；丢失 = 下一轮 400）
        assertEquals("sig-abc", (call["signature"] as JsonPrimitive).content)
        assertEquals("read_link", (call["kind"] as JsonPrimitive).content)
    }

    @Test
    fun `rebuild returns null for parts without raw metadata`() {
        val orphan = UIMessagePart.ServerTool(toolCallId = "x", toolName = "search", metadata = null)
        assertEquals(null, rebuildServerToolCallSteps(listOf(orphan)))
    }

    // ---------------- 发送：裸 type 项混排 ----------------

    private val deepseekProvider = app.androidrik.ai.provider.ProviderSetting.OpenAI(
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
    )

    private fun buildBody(model: Model, tools: List<Tool>): JsonObject =
        api.buildChatCompletionRequestBodyForTest(
            messages = listOf(app.androidrik.ai.ui.UIMessage.user("hi")),
            params = TextGenerationParams(model = model, tools = tools),
            providerSetting = deepseekProvider,
        )

    @Test
    fun `request body carries bare deepseek tool types mixed with function tools`() {
        val body = buildBody(
            model = Model(
                modelId = "deepseek-flash",
                abilities = listOf(ModelAbility.TOOL),
                tools = setOf(BuiltInTools.DeepSeekWebSearch, BuiltInTools.DeepSeekReadUrl),
            ),
            tools = listOf(Tool(name = "search_web", description = "", parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) }, execute = { emptyList() })),
        )
        val toolsArray = body["tools"] as kotlinx.serialization.json.JsonArray
        assertEquals(3, toolsArray.size)
        val types = toolsArray.map { (it as JsonObject)["type"]?.let { t -> (t as JsonPrimitive).content } }
        assertTrue(types.contains("function"))
        assertTrue(types.contains("deepseek_web_search"))
        assertTrue(types.contains("deepseek_read_url"))
        // 有 function 工具才有 tool_choice；内置工具不能被指定
        assertEquals("auto", (body["tool_choice"] as? JsonPrimitive)?.content)
    }

    @Test
    fun `request with only builtin tools omits tool_choice`() {
        val body = buildBody(
            model = Model(
                modelId = "deepseek-flash",
                abilities = listOf(ModelAbility.TOOL),
                tools = setOf(BuiltInTools.DeepSeekWebSearch),
            ),
            tools = emptyList(),
        )
        val toolsArray = body["tools"] as kotlinx.serialization.json.JsonArray
        assertEquals(1, toolsArray.size)
        assertEquals("deepseek_web_search", ((toolsArray.single() as JsonObject)["type"] as JsonPrimitive).content)
        // tool_choice 不能指定内置工具：无 function 工具时不下发
        assertTrue(!body.containsKey("tool_choice"))
    }

    @Test
    fun `models without deepseek tools do not send builtin items`() {
        val body = buildBody(
            model = Model(modelId = "gpt-4o", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(Tool(name = "search_web", description = "", parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) }, execute = { emptyList() })),
        )
        val toolsArray = body["tools"] as kotlinx.serialization.json.JsonArray
        assertTrue(toolsArray.all { it is JsonObject && ((it)["type"] as JsonPrimitive).content == "function" })
    }
}
